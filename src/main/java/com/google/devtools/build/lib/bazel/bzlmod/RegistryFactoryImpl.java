// Copyright 2021 The Bazel Authors. All rights reserved.
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

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.devtools.build.lib.bazel.repository.downloader.DownloadManager;
import com.google.devtools.build.lib.vfs.Path;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.function.Supplier;

/** Prod implementation of {@link RegistryFactory}. */
public class RegistryFactoryImpl implements RegistryFactory {
  private final Path workspacePath;
  private final DownloadManager downloadManager;
  private final Supplier<Map<String, String>> clientEnvironmentSupplier;
  private final Cache<String, Registry> registries = Caffeine.newBuilder().build();

  public RegistryFactoryImpl(
      Path workspacePath,
      DownloadManager downloadManager,
      Supplier<Map<String, String>> clientEnvironmentSupplier) {
    this.workspacePath = workspacePath;
    this.downloadManager = downloadManager;
    this.clientEnvironmentSupplier = clientEnvironmentSupplier;
  }

  @Override
  public Registry getRegistryWithUrl(String unresolvedUrl) throws URISyntaxException {
    URI uri = new URI(unresolvedUrl.replace("%workspace%", workspacePath.getPathString()));
    if (uri.getScheme() == null) {
      throw new URISyntaxException(
          uri.toString(),
          "Registry URL has no scheme -- supported schemes are: "
              + "http://, https:// and file://");
    }
    if (uri.getPath() == null) {
      throw new URISyntaxException(
          uri.toString(),
          "Registry URL path is not valid -- did you mean to use file:///foo/bar "
              + "or file:///c:/foo/bar for Windows?");
    }
    switch (uri.getScheme()) {
      case "http":
      case "https":
      case "file":
        return registries.get(
            unresolvedUrl,
            unused ->
                new IndexRegistry(
                    uri, unresolvedUrl, downloadManager, clientEnvironmentSupplier.get()));
      default:
        throw new URISyntaxException(uri.toString(), "Unrecognized registry URL protocol");
    }
  }
}
