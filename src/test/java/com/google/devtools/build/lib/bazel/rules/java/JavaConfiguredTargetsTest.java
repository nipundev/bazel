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

package com.google.devtools.build.lib.bazel.rules.java;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.MoreCollectors.onlyElement;
import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.testutil.TestConstants.TOOLS_REPOSITORY;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.MoreFiles;
import com.google.devtools.build.lib.actions.Action;
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.actions.TemplateExpansionAction;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.rules.java.JavaCompileAction;
import com.google.devtools.build.lib.util.OS;
import java.util.Arrays;
import java.util.Objects;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests of bazel java rules. */
@RunWith(JUnit4.class)
public final class JavaConfiguredTargetsTest extends BuildViewTestCase {

  @Test
  public void testResourceStripPrefix() throws Exception {
    scratch.file(
        "a/BUILD",
        "java_binary(",
        "   name = 'bin',",
        "   srcs = ['Foo.java'],",
        "   resources = ['path/to/strip/bar.props'],",
        "   main_class = 'Foo',",
        "   resource_strip_prefix = 'a/path/to/strip'",
        ")");

    ConfiguredTarget target = getConfiguredTarget("//a:bin");

    assertThat(target).isNotNull();
    String resourceJarArgs =
        Joiner.on(" ").join(getGeneratingSpawnActionArgs(getBinArtifact("bin.jar", target)));
    assertThat(resourceJarArgs).contains("--resources a/path/to/strip/bar.props:bar.props");
  }

  @Test
  public void javaTestSetsSecurityManagerPropertyOnVersion17() throws Exception {
    scratch.file(
        "a/BUILD",
        "java_runtime(",
        "    name = 'jvm',",
        "    java = 'java_home/bin/java',",
        "    version = 17,",
        ")",
        "toolchain(",
        "    name = 'java_runtime_toolchain',",
        "    toolchain = ':jvm',",
        "    toolchain_type = '" + TOOLS_REPOSITORY + "//tools/jdk:runtime_toolchain_type',",
        ")",
        "java_test(",
        "    name = 'test',",
        "    srcs = ['FooTest.java'],",
        "    test_class = 'FooTest',",
        ")");
    useConfiguration("--extra_toolchains=//a:java_runtime_toolchain");
    var ct = getConfiguredTarget("//a:test");
    var executable = getExecutable(ct);
    if (OS.getCurrent() == OS.WINDOWS) {
      var jvmFlags =
          getGeneratingSpawnActionArgs(executable).stream()
              .filter(a -> a.startsWith("jvm_flags="))
              .flatMap(a -> Arrays.stream(a.substring("jvm_flags=".length()).split("\t")))
              .collect(toImmutableList());
      assertThat(jvmFlags).contains("-Djava.security.manager=allow");
    } else {
      var jvmFlags =
          ((TemplateExpansionAction) getGeneratingAction(executable))
              .getSubstitutions().stream()
                  .filter(s -> Objects.equals(s.getKey(), "%jvm_flags%"))
                  .collect(onlyElement())
                  .getValue();
      assertThat(jvmFlags).contains("-Djava.security.manager=allow");
    }
  }

  @Test
  public void experimentalShardedJavaLibrary_succeeds() throws Exception {
    setBuildLanguageOptions("--experimental_java_library_export");
    scratch.file(
        "foo/rule.bzl",
        //
        "java_library = experimental_java_library_export_do_not_use.sharded_java_library(",
        "  default_shard_size = 10",
        ")");
    scratch.file(
        "foo/BUILD",
        "load(':rule.bzl', 'java_library')",
        "",
        "java_library(",
        "  name = 'lib1',",
        "  srcs = ['1.java', '2.java', '3.java'],",
        "  experimental_javac_shard_size = 1,",
        ")",
        "java_library(",
        "  name = 'lib2',",
        "  srcs = ['1.java', '2.java', '3.java'],",
        "  experimental_javac_shard_size = 2,",
        ")");

    ImmutableList<Action> compileActionsWithShardSize1 =
        getActions("//foo:lib1", JavaCompileAction.class);
    ImmutableList<Action> compileActionsWithShardSize2 =
        getActions("//foo:lib2", JavaCompileAction.class);

    assertThat(compileActionsWithShardSize1).hasSize(3);
    assertThat(compileActionsWithShardSize2).hasSize(2);
  }

  // regression test for b/297356812#comment31
  @Test
  public void experimentalShardedJavaLibrary_allOutputsHaveUniqueNames() throws Exception {
    setBuildLanguageOptions("--experimental_java_library_export");
    scratch.file(
        "foo/rule.bzl",
        //
        "java_library = experimental_java_library_export_do_not_use.sharded_java_library(",
        "  default_shard_size = 1",
        ")");
    scratch.file(
        "foo/BUILD",
        "load(':rule.bzl', 'java_library')",
        "",
        "java_library(",
        "  name = 'lib',",
        "  srcs = ['1.java', '2.java', '3.java'],",
        ")");

    ImmutableList<Artifact> outputs =
        getActions("//foo:lib", JavaCompileAction.class).stream()
            .map(ActionAnalysisMetadata::getPrimaryOutput)
            .collect(toImmutableList());
    ImmutableSet<String> uniqueFilenamesWithoutExtension =
        outputs.stream()
            .map(file -> MoreFiles.getNameWithoutExtension(file.getPath().getPathFile().toPath()))
            .collect(toImmutableSet());

    assertThat(outputs).hasSize(3);
    assertThat(uniqueFilenamesWithoutExtension).hasSize(3);
  }
}
