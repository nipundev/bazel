// Copyright 2015 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.actions;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue.RunfileSymlinksMode;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.vfs.PathFragment;
import java.util.Map;
import javax.annotation.Nullable;
import net.starlark.java.eval.StarlarkValue;

/** Convenience wrapper around runfiles allowing lazy expansion. */
// TODO(bazel-team): Ideally we could refer to Runfiles objects directly here, but current package
// structure makes this difficult. Consider moving things around to make this possible.
//
// RunfilesSuppliers appear to be Starlark values;
// they are exposed through ctx.resolve_tools[2], for example.
public interface RunfilesSupplier extends StarlarkValue {

  /**
   * Returns artifacts the runfiles tree contain symlinks to.
   *
   * <p>This includes artifacts that the symlinks and root symlinks point to, not just artifacts at
   * their canonical location.
   */
  NestedSet<Artifact> getAllArtifacts();

  /** Returns the runfiles' root directories. */
  ImmutableSet<PathFragment> getRunfilesDirs();

  /** Returns mappings from runfiles directories to artifact mappings in that directory. */
  ImmutableMap<PathFragment, Map<PathFragment, Artifact>> getMappings();

  /**
   * Returns the {@link RunfileSymlinksMode} for the given {@code runfilesDir}, or {@code null} if
   * the {@link RunfilesSupplier} doesn't know about the directory.
   *
   * @param runfilesDir runfiles directory relative to the exec root
   */
  @Nullable
  RunfileSymlinksMode getRunfileSymlinksMode(PathFragment runfilesDir);

  /**
   * Returns whether the runfile symlinks should be materialized during the build for the given
   * {@code runfilesDir}, or {@code false} if the {@link RunfilesSupplier} doesn't know about the
   * directory.
   *
   * @param runfilesDir runfiles directory relative to the exec root
   */
  boolean isBuildRunfileLinks(PathFragment runfilesDir);

  /**
   * Returns a {@link RunfilesSupplier} identical to this one, but with the given runfiles
   * directory.
   *
   * <p>Must only be called on suppliers with a single runfiles directory, i.e. {@link
   * #getRunfilesDirs} returns a set of size 1.
   */
  RunfilesSupplier withOverriddenRunfilesDir(PathFragment newRunfilesDir);
}
