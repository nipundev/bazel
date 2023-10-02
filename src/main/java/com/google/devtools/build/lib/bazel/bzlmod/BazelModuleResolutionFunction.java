// Copyright 2022 The Bazel Authors. All rights reserved.
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
//

package com.google.devtools.build.lib.bazel.bzlmod;

import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.analysis.BlazeVersionInfo;
import com.google.devtools.build.lib.bazel.BazelVersion;
import com.google.devtools.build.lib.bazel.bzlmod.InterimModule.DepSpec;
import com.google.devtools.build.lib.bazel.bzlmod.ModuleFileValue.RootModuleFileValue;
import com.google.devtools.build.lib.bazel.repository.RepositoryOptions.BazelCompatibilityMode;
import com.google.devtools.build.lib.bazel.repository.RepositoryOptions.CheckDirectDepsMode;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.profiler.Profiler;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.profiler.SilentCloseable;
import com.google.devtools.build.lib.server.FailureDetails.ExternalDeps.Code;
import com.google.devtools.build.lib.skyframe.PrecomputedValue.Precomputed;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyFunctionException.Transience;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.SkyframeLookupResult;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * Discovers the whole dependency graph and runs selection algorithm on it to produce the pruned
 * dependency graph and runs checks on it.
 */
public class BazelModuleResolutionFunction implements SkyFunction {

  public static final Precomputed<CheckDirectDepsMode> CHECK_DIRECT_DEPENDENCIES =
      new Precomputed<>("check_direct_dependency");
  public static final Precomputed<BazelCompatibilityMode> BAZEL_COMPATIBILITY_MODE =
      new Precomputed<>("bazel_compatibility_mode");

  @Override
  @Nullable
  public SkyValue compute(SkyKey skyKey, Environment env)
      throws BazelModuleResolutionFunctionException, InterruptedException {
    RootModuleFileValue root =
        (RootModuleFileValue) env.getValue(ModuleFileValue.KEY_FOR_ROOT_MODULE);
    if (root == null) {
      return null;
    }

    var state = env.getState(ModuleResolutionComputeState::new);
    if (state.selectionResult == null) {
      state.selectionResult = discoverAndSelect(env, root);
      if (state.selectionResult == null) {
        return null;
      }
    }

    ImmutableSet<RepoSpecKey> repoSpecKeys =
        state.selectionResult.getResolvedDepGraph().values().stream()
            // Modules with a null registry have a non-registry override. We don't need to
            // fetch or store the repo spec in this case.
            .filter(module -> module.getRegistry() != null)
            .map(RepoSpecKey::of)
            .collect(toImmutableSet());
    SkyframeLookupResult repoSpecResults = env.getValuesAndExceptions(repoSpecKeys);
    ImmutableMap.Builder<ModuleKey, RepoSpec> remoteRepoSpecs = ImmutableMap.builder();
    for (RepoSpecKey repoSpecKey : repoSpecKeys) {
      RepoSpec repoSpec = (RepoSpec) repoSpecResults.get(repoSpecKey);
      if (repoSpec == null) {
        return null;
      }
      remoteRepoSpecs.put(repoSpecKey.getModuleKey(), repoSpec);
    }

    ImmutableMap<ModuleKey, Module> finalDepGraph;
    try (SilentCloseable c =
        Profiler.instance().profile(ProfilerTask.BZLMOD, "compute final dep graph")) {
      finalDepGraph =
          computeFinalDepGraph(
              state.selectionResult.getResolvedDepGraph(),
              root.getOverrides(),
              remoteRepoSpecs.buildOrThrow());
    }

    return BazelModuleResolutionValue.create(
        finalDepGraph, state.selectionResult.getUnprunedDepGraph());
  }

  @Nullable
  private static Selection.Result discoverAndSelect(Environment env, RootModuleFileValue root)
      throws BazelModuleResolutionFunctionException, InterruptedException {
    ImmutableMap<ModuleKey, InterimModule> initialDepGraph;
    try (SilentCloseable c = Profiler.instance().profile(ProfilerTask.BZLMOD, "discovery")) {
      initialDepGraph = Discovery.run(env, root);
    } catch (ExternalDepsException e) {
      throw new BazelModuleResolutionFunctionException(e, Transience.PERSISTENT);
    }
    if (initialDepGraph == null) {
      return null;
    }

    Selection.Result selectionResult;
    try (SilentCloseable c = Profiler.instance().profile(ProfilerTask.BZLMOD, "selection")) {
      selectionResult = Selection.run(initialDepGraph, root.getOverrides());
    } catch (ExternalDepsException e) {
      throw new BazelModuleResolutionFunctionException(e, Transience.PERSISTENT);
    }
    ImmutableMap<ModuleKey, InterimModule> resolvedDepGraph = selectionResult.getResolvedDepGraph();

    try (SilentCloseable c =
        Profiler.instance().profile(ProfilerTask.BZLMOD, "verify root module direct deps")) {
      verifyRootModuleDirectDepsAreAccurate(
          initialDepGraph.get(ModuleKey.ROOT),
          resolvedDepGraph.get(ModuleKey.ROOT),
          Objects.requireNonNull(CHECK_DIRECT_DEPENDENCIES.get(env)),
          env.getListener());
    }

    try (SilentCloseable c =
        Profiler.instance().profile(ProfilerTask.BZLMOD, "check bazel compatibility")) {
      checkBazelCompatibility(
          resolvedDepGraph.values(),
          Objects.requireNonNull(BAZEL_COMPATIBILITY_MODE.get(env)),
          env.getListener());
    }

    try (SilentCloseable c =
        Profiler.instance().profile(ProfilerTask.BZLMOD, "check no yanked versions")) {
      checkNoYankedVersions(resolvedDepGraph);
    }

    return selectionResult;
  }

  private static void verifyRootModuleDirectDepsAreAccurate(
      InterimModule discoveredRootModule,
      InterimModule resolvedRootModule,
      CheckDirectDepsMode mode,
      EventHandler eventHandler)
      throws BazelModuleResolutionFunctionException {
    if (mode == CheckDirectDepsMode.OFF) {
      return;
    }

    boolean failure = false;
    for (Map.Entry<String, DepSpec> dep : discoveredRootModule.getDeps().entrySet()) {
      ModuleKey resolved = resolvedRootModule.getDeps().get(dep.getKey()).toModuleKey();
      if (!dep.getValue().toModuleKey().equals(resolved)) {
        String message =
            String.format(
                "For repository '%s', the root module requires module version %s, but got %s in the"
                    + " resolved dependency graph.",
                dep.getKey(), dep.getValue().toModuleKey(), resolved);
        if (mode == CheckDirectDepsMode.WARNING) {
          eventHandler.handle(Event.warn(message));
        } else {
          eventHandler.handle(Event.error(message));
          failure = true;
        }
      }
    }

    if (failure) {
      throw new BazelModuleResolutionFunctionException(
          ExternalDepsException.withMessage(
              Code.VERSION_RESOLUTION_ERROR, "Direct dependency check failed."),
          Transience.PERSISTENT);
    }
  }

  public static void checkBazelCompatibility(
      ImmutableCollection<InterimModule> modules,
      BazelCompatibilityMode mode,
      EventHandler eventHandler)
      throws BazelModuleResolutionFunctionException {
    if (mode == BazelCompatibilityMode.OFF) {
      return;
    }

    String currentBazelVersion = BlazeVersionInfo.instance().getVersion();
    if (Strings.isNullOrEmpty(currentBazelVersion)) {
      return;
    }

    BazelVersion curVersion = BazelVersion.parse(currentBazelVersion);
    for (InterimModule module : modules) {
      for (String compatVersion : module.getBazelCompatibility()) {
        if (!curVersion.satisfiesCompatibility(compatVersion)) {
          String message =
              String.format(
                  "Bazel version %s is not compatible with module \"%s\" (bazel_compatibility: %s)",
                  curVersion.getOriginal(), module.getKey(), module.getBazelCompatibility());

          if (mode == BazelCompatibilityMode.WARNING) {
            eventHandler.handle(Event.warn(message));
          } else {
            eventHandler.handle(Event.error(message));
            throw new BazelModuleResolutionFunctionException(
                ExternalDepsException.withMessage(
                    Code.VERSION_RESOLUTION_ERROR, "Bazel compatibility check failed"),
                Transience.PERSISTENT);
          }
        }
      }
    }
  }

  private static void checkNoYankedVersions(ImmutableMap<ModuleKey, InterimModule> depGraph)
      throws BazelModuleResolutionFunctionException {
    for (InterimModule m : depGraph.values()) {
      if (m.getYankedInfo().isPresent()) {
        throw new BazelModuleResolutionFunctionException(
            ExternalDepsException.withMessage(
                Code.VERSION_RESOLUTION_ERROR,
                "Yanked version detected in your resolved dependency graph: %s, for the reason: "
                    + "%s.\nYanked versions may contain serious vulnerabilities and should not be "
                    + "used. To fix this, use a bazel_dep on a newer version of this module. To "
                    + "continue using this version, allow it using the --allow_yanked_versions "
                    + "flag or the BZLMOD_ALLOW_YANKED_VERSIONS env variable.",
                m.getKey(),
                m.getYankedInfo().get()),
            Transience.PERSISTENT);
      }
    }
  }

  private static RepoSpec maybeAppendAdditionalPatches(
      @Nullable RepoSpec repoSpec, @Nullable ModuleOverride override) {
    if (!(override instanceof SingleVersionOverride)) {
      return repoSpec;
    }
    SingleVersionOverride singleVersion = (SingleVersionOverride) override;
    if (singleVersion.getPatches().isEmpty()) {
      return repoSpec;
    }
    ImmutableMap.Builder<String, Object> attrBuilder = ImmutableMap.builder();
    attrBuilder.putAll(repoSpec.attributes().attributes());
    attrBuilder.put("patches", singleVersion.getPatches());
    attrBuilder.put("patch_cmds", singleVersion.getPatchCmds());
    attrBuilder.put("patch_args", ImmutableList.of("-p" + singleVersion.getPatchStrip()));
    return RepoSpec.builder()
        .setBzlFile(repoSpec.bzlFile())
        .setRuleClassName(repoSpec.ruleClassName())
        .setAttributes(AttributeValues.create(attrBuilder.buildOrThrow()))
        .build();
  }

  /**
   * Builds a {@link Module} from an {@link InterimModule}, discarding unnecessary fields and adding
   * extra necessary ones (such as the repo spec).
   *
   * @param remoteRepoSpec the {@link RepoSpec} for the module obtained from a registry or null if
   *     the module has a non-registry override
   */
  static Module moduleFromInterimModule(
      InterimModule interim, @Nullable ModuleOverride override, @Nullable RepoSpec remoteRepoSpec) {
    return Module.builder()
        .setName(interim.getName())
        .setVersion(interim.getVersion())
        .setKey(interim.getKey())
        .setRepoName(interim.getRepoName())
        .setExecutionPlatformsToRegister(interim.getExecutionPlatformsToRegister())
        .setToolchainsToRegister(interim.getToolchainsToRegister())
        .setDeps(ImmutableMap.copyOf(Maps.transformValues(interim.getDeps(), DepSpec::toModuleKey)))
        .setRepoSpec(maybeAppendAdditionalPatches(remoteRepoSpec, override))
        .setExtensionUsages(interim.getExtensionUsages())
        .build();
  }

  private static ImmutableMap<ModuleKey, Module> computeFinalDepGraph(
      ImmutableMap<ModuleKey, InterimModule> resolvedDepGraph,
      ImmutableMap<String, ModuleOverride> overrides,
      ImmutableMap<ModuleKey, RepoSpec> remoteRepoSpecs) {
    ImmutableMap.Builder<ModuleKey, Module> finalDepGraph = ImmutableMap.builder();
    for (Map.Entry<ModuleKey, InterimModule> entry : resolvedDepGraph.entrySet()) {
      finalDepGraph.put(
          entry.getKey(),
          moduleFromInterimModule(
              entry.getValue(),
              overrides.get(entry.getKey().getName()),
              remoteRepoSpecs.get(entry.getKey())));
    }
    return finalDepGraph.buildOrThrow();
  }

  private static class ModuleResolutionComputeState implements Environment.SkyKeyComputeState {
    Selection.Result selectionResult;
  }

  static class BazelModuleResolutionFunctionException extends SkyFunctionException {
    BazelModuleResolutionFunctionException(ExternalDepsException e, Transience transience) {
      super(e, transience);
    }
  }
}
