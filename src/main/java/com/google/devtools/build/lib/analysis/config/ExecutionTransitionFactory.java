// Copyright 2019 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.analysis.config;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.devtools.build.lib.packages.ExecGroup.DEFAULT_EXEC_GROUP_NAME;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.analysis.PlatformOptions;
import com.google.devtools.build.lib.analysis.config.transitions.ComparingTransition;
import com.google.devtools.build.lib.analysis.config.transitions.ConfigurationTransition;
import com.google.devtools.build.lib.analysis.config.transitions.PatchTransition;
import com.google.devtools.build.lib.analysis.config.transitions.TransitionFactory;
import com.google.devtools.build.lib.analysis.starlark.FunctionTransitionUtil;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.packages.AttributeTransitionData;
import com.google.devtools.build.lib.rules.config.FeatureFlagValue;
import com.google.devtools.build.lib.starlarkbuildapi.StarlarkConfigApi.ExecTransitionFactoryApi;
import com.google.devtools.build.lib.util.Pair;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * {@link TransitionFactory} implementation which creates a {@link PatchTransition} which will
 * transition to a configuration suitable for building dependencies for the execution platform of
 * the depending target.
 *
 * <p>Note that execGroup is not directly consumed by the involved transition but instead stored
 * here. Instead, the rule definition stores it in this factory. Then, toolchain resolution extracts
 * and consumes it to store an execution platform in attrs. Finally, the execution platform is read
 * by the factory to create the transition.
 */
public class ExecutionTransitionFactory
    implements TransitionFactory<AttributeTransitionData>, ExecTransitionFactoryApi {

  /**
   * Returns a new {@link ExecutionTransitionFactory} for the default {@link
   * com.google.devtools.build.lib.packages.ExecGroup}.
   */
  public static ExecutionTransitionFactory createFactory() {
    return new ExecutionTransitionFactory(DEFAULT_EXEC_GROUP_NAME);
  }

  /**
   * Returns a new {@link ExecutionTransitionFactory} for the given {@link
   * com.google.devtools.build.lib.packages.ExecGroup}.
   */
  public static ExecutionTransitionFactory createFactory(String execGroup) {
    return new ExecutionTransitionFactory(execGroup);
  }

  /**
   * Guarantees we don't duplicate instances of the same transition.
   *
   * <p>Bazel already does a lot of the work for us: there's one global native exec transition
   * instance in the code base: {@link NativeExecTransition#INSTANCE}. Bazel's Starlark logic also
   * maintains a distinct instance for each Starlark transition.
   *
   * <p>While those make this cache seem unnecessary, it still serves two purposes:
   *
   * <ol>
   *   <li>This file creates its own transitions that wrap the original transitions. We have to make
   *       sure those transitions don't duplicate. TODO(b/292619013): once we remove the native
   *       transition this probably isn't necessary: remove this rationale
   *   <li>The exec transition uniquely takes an extra parameter: the execution platform label. This
   *       is provided by toolchain resolution - the transition can't read it from input build
   *       options. So we need to cache on {@code label, originalTransition} pairs.
   * </ol>
   */
  private static final Cache<Pair<Label, Integer>, PatchTransition> transitionInstanceCache =
      Caffeine.newBuilder().weakValues().build();

  @Override
  public PatchTransition create(AttributeTransitionData dataWithTargetAttributes) {
    // Delete AttributeTransitionData.attributes() so the exec transition doesn't try to read the
    // attributes of the target it's attached to. This is for two reasons:
    //
    //   1) While per-target exec transitions may be interesting, we're not ready to expose that
    //      level of API flexibility
    //   2) No need for StarlarkTransitionCache misses due to different StarlarkTransition instances
    //       bound to different attributes that shouldn't affect output.
    AttributeTransitionData data =
        AttributeTransitionData.builder()
            .analysisData(dataWithTargetAttributes.analysisData())
            .executionPlatform(dataWithTargetAttributes.executionPlatform())
            .build();

    // Always get the native transition.
    PatchTransition nativeTransition =
        transitionInstanceCache.get(
            Pair.of(
                data.executionPlatform(), System.identityHashCode(NativeExecTransition.INSTANCE)),
            (p) ->
                new ExecTransitionFinalizer(
                    data.executionPlatform(), NativeExecTransition.INSTANCE));

    @SuppressWarnings("unchecked")
    TransitionFactory<AttributeTransitionData> starlarkExecTransitionProvider =
        (TransitionFactory<AttributeTransitionData>) data.analysisData();
    if (starlarkExecTransitionProvider == null) {
      return nativeTransition;
    }

    return transitionInstanceCache.get(
        // A Starlark transition keeps the same instance unless we modify its .bzl file.
        Pair.of(data.executionPlatform(), starlarkExecTransitionProvider.hashCode()),
        (p) -> {
          PatchTransition starlarkTransition =
              new ExecTransitionFinalizer(
                  data.executionPlatform(), starlarkExecTransitionProvider.create(data));

          // We don't yet know if --experimental_exec_config_diff is set because this method
          // doesn't have access to BuildOptions. Universally create a ComparingTransition and
          // let that figure out if it should run both transitions or just the Starlark one.
          return new ComparingTransition(
              /* activeTransition= */ starlarkTransition,
              /* activeTransitionDesc= */ "starlark",
              /* altTransition= */ nativeTransition,
              /* altTransitionDesc= */ "native",
              /* runBoth= */ b -> b.get(CoreOptions.class).execConfigDiff);
        });
  }

  private final String execGroup;

  private ExecutionTransitionFactory(String execGroup) {
    this.execGroup = execGroup;
  }

  @Override
  public TransitionType transitionType() {
    return TransitionType.ATTRIBUTE;
  }

  public String getExecGroup() {
    return execGroup;
  }

  @Override
  public boolean isTool() {
    return true;
  }

  /**
   * Complete exec transition.
   *
   * <p>Takes as input the execution platform and the "main" transition used for this build: either
   * a native or Starlark transition. Calls the main transitino, the runs finalizer logic that's
   * common to both transition modes.
   */
  private static class ExecTransitionFinalizer implements PatchTransition {
    private static final ImmutableSet<Class<? extends FragmentOptions>> FRAGMENTS =
        ImmutableSet.of(CoreOptions.class, PlatformOptions.class);

    // We added this cache after observing an O(100,000)-node build graph that applied multiple exec
    // transitions on every node via an aspect. Before this cache, this produced O(500,000)
    // BuildOptions instances that consumed over 3 gigabytes of memory.
    private static final BuildOptionsCache<Pair<Label, ConfigurationTransition>>
        nativeApplicationCache = new BuildOptionsCache<>(ExecTransitionFinalizer::transitionImpl);

    @Nullable private final Label executionPlatform;

    private final ConfigurationTransition mainTransition;

    ExecTransitionFinalizer(
        @Nullable Label executionPlatform, ConfigurationTransition mainTransition) {
      this.executionPlatform = executionPlatform;
      this.mainTransition = mainTransition;
    }

    @Override
    public String getName() {
      return "exec";
    }

    /**
     * Implement {@link ConfigurationTransition#visit}} so {@link
     * com.google.devtools.build.lib.analysis.config.StarlarkTransitionCache} caches application if
     * this is a Starlark transition.
     */
    @Override
    public <E extends Exception> void visit(Visitor<E> visitor) throws E {
      this.mainTransition.visit(visitor);
    }

    @Override
    public ImmutableSet<Class<? extends FragmentOptions>> requiresOptionFragments() {
      // This is technically a lie since the call to underlying().createExecOptions is transitively
      // reading and potentially modifying all fragments. There is currently no way for the
      // transition to actually list all fragments like this and thus only lists the ones that are
      // directly being read here. Note that this transition is exceptional in its implementation.
      return FRAGMENTS;
    }

    @Override
    public BuildOptions patch(BuildOptionsView options, EventHandler eventHandler)
        throws InterruptedException {
      if (executionPlatform == null) {
        // No execution platform is known, so don't change anything.
        return options.underlying();
      }

      // If this is the Starlark exec transition, StarlarkTransitionCache caches application. If
      // this is the native exec transition, we need to directly cache application here.
      //
      // That means we technically don't need to call this cache if this is a Starlark transition
      // (we could instead call transitionImpl() directly, trusting StarlarkTransitionCache to
      // control when that's called). But it's simpler to universally call it here and causes no
      // harm. And once we remove the native transition we can eliminate this cache outright.
      // TODO(b/292619013): remove this cache when we remove the native exec transition.
      return nativeApplicationCache.applyTransition(
          options,
          // The execution platform impacts the output's --platform_suffix and --platforms flags.
          Pair.of(executionPlatform, mainTransition),
          eventHandler);
    }

    private static BuildOptions transitionImpl(
        BuildOptionsView options,
        Pair<Label, ConfigurationTransition> data,
        @Nullable EventHandler eventHandler)
        throws InterruptedException {
      Label executionPlatform = data.first;
      ConfigurationTransition mainTransition = data.second;

      Map.Entry<String, BuildOptions> splitOptions =
          Iterables.getOnlyElement(mainTransition.apply(options, eventHandler).entrySet());
      BuildOptions execOptions = splitOptions.getValue();

      // Set the target to the saved execution platform if there is one.
      PlatformOptions platformOptions = execOptions.get(PlatformOptions.class);
      if (platformOptions != null) {
        platformOptions.platforms = ImmutableList.of(executionPlatform);
      }

      // Remove any FeatureFlags that were set.
      ImmutableList<Label> featureFlags =
          execOptions.getStarlarkOptions().entrySet().stream()
              .filter(entry -> entry.getValue() instanceof FeatureFlagValue)
              .map(Map.Entry::getKey)
              .collect(toImmutableList());

      BuildOptions result = execOptions;
      if (!featureFlags.isEmpty()) {
        BuildOptions.Builder resultBuilder = result.toBuilder();
        featureFlags.forEach(resultBuilder::removeStarlarkOption);
        result = resultBuilder.build();
      }

      // The conditional use of a Builder above may have replaced result and underlying options
      // with a clone so must refresh it.
      CoreOptions coreOptions = result.get(CoreOptions.class);
      // TODO(blaze-configurability-team): These updates probably requires a bit too much knowledge
      //   of exactly how the immutable state and mutable state of BuildOptions is interacting.
      //   Might be good to have an option to wipeout that state rather than cloning so much.
      switch (coreOptions.execConfigurationDistinguisherScheme) {
        case LEGACY:
          coreOptions.platformSuffix =
              String.format("exec-%X", executionPlatform.getCanonicalForm().hashCode());
          break;
        case FULL_HASH:
          coreOptions.platformSuffix = "";
          // execOptions creation above made a clone, which will have a fresh hashCode
          int fullHash = result.hashCode();
          coreOptions.platformSuffix = String.format("exec-%X", fullHash);
          // Previous call to hashCode irreparably locked in state so must clone to refresh since
          // options mutated after that
          result = result.clone();
          break;
        case DIFF_TO_AFFECTED:
          // Setting platform_suffix here should not be necessary for correctness but
          // done for user clarity.
          coreOptions.platformSuffix = "exec";
          ImmutableSet<String> diff =
              FunctionTransitionUtil.getAffectedByStarlarkTransitionViaDiff(
                  result, options.underlying());
          FunctionTransitionUtil.updateAffectedByStarlarkTransition(coreOptions, diff);
          // Previous call to diff irreparably locked in state so must clone to refresh.
          result = result.clone();
          break;
        default:
          // else if OFF just mark that we are now in an exec transition
          coreOptions.platformSuffix = "exec";
      }
      coreOptions.affectedByStarlarkTransition =
          options.underlying().get(CoreOptions.class).affectedByStarlarkTransition;
      coreOptions.executionInfoModifier =
          options.underlying().get(CoreOptions.class).executionInfoModifier;
      return result;
    }
  }

  /** Logic unique to the native exec transition. */
  private static class NativeExecTransition implements PatchTransition {
    private static final NativeExecTransition INSTANCE = new NativeExecTransition();

    private static final ImmutableSet<Class<? extends FragmentOptions>> FRAGMENTS =
        ImmutableSet.of(CoreOptions.class, PlatformOptions.class);

    @Override
    public BuildOptions patch(BuildOptionsView options, EventHandler eventHandler) {
      // Start by converting to exec options.
      BuildOptionsView execOptions =
          new BuildOptionsView(options.underlying().createExecOptions(), FRAGMENTS);

      CoreOptions coreOptions = checkNotNull(execOptions.get(CoreOptions.class));
      coreOptions.isExec = true;
      // Disable extra actions
      coreOptions.actionListeners = ImmutableList.of();

      return execOptions.underlying();
    }
  }
}
