# Copyright 2023 The Bazel Authors. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Module extensions for loading dependencies we need to build Bazel.

"""

load("//:distdir.bzl", "dist_http_archive", "repo_cache_tar")
load("//:distdir_deps.bzl", "DIST_ARCHIVE_REPOS")
load("//:repositories.bzl", "embedded_jdk_repositories")
load("//src/main/res:winsdk_configure.bzl", "winsdk_configure")
load("//src/test/shell/bazel:list_source_repository.bzl", "list_source_repository")
load("//src/tools/bzlmod:utils.bzl", "parse_bazel_module_repos")
load("//tools/distributions/debian:deps.bzl", "debian_deps")

### Dependencies for building Bazel
def _bazel_build_deps(_ctx):
    embedded_jdk_repositories()
    debian_deps()
    repo_cache_tar(name = "bootstrap_repo_cache", repos = DIST_ARCHIVE_REPOS, dirname = "derived/repository_cache")
    BAZEL_TOOLS_DEPS_REPOS = parse_bazel_module_repos(_ctx, _ctx.path(Label("//src/test/tools/bzlmod:MODULE.bazel.lock")))
    repo_cache_tar(name = "bazel_tools_repo_cache", repos = BAZEL_TOOLS_DEPS_REPOS, lockfile = "//src/test/tools/bzlmod:MODULE.bazel.lock")

bazel_build_deps = module_extension(implementation = _bazel_build_deps)

### Dependencies for testing Bazel
def _bazel_test_deps(_ctx):
    list_source_repository(name = "local_bazel_source_list")
    dist_http_archive(name = "bazelci_rules")
    winsdk_configure(name = "local_config_winsdk")

bazel_test_deps = module_extension(implementation = _bazel_test_deps)

### Dependencies for Bazel Android tools
def _bazel_android_deps(_ctx):
    dist_http_archive(name = "desugar_jdk_libs")

bazel_android_deps = module_extension(implementation = _bazel_android_deps)
