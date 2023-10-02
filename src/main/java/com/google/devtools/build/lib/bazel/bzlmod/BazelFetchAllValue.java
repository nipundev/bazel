// Copyright 2023 The Bazel Authors. All rights reserved.
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

import com.google.auto.value.AutoValue;
import com.google.devtools.build.lib.skyframe.SkyFunctions;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec;
import com.google.devtools.build.skyframe.AbstractSkyKey;
import com.google.devtools.build.skyframe.SkyFunctionName;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;

/**
 * Empty result of running Bazel fetch all dependencies, to indicate that all repos have been
 * fetched successfully.
 */
@AutoValue
public abstract class BazelFetchAllValue implements SkyValue {

  /** Creates a key from the given repository name. */
  public static BazelFetchAllValue.Key key(Boolean configureEnabled) {
    return BazelFetchAllValue.Key.create(configureEnabled);
  }

  public static BazelFetchAllValue create() {
    return new AutoValue_BazelFetchAllValue();
  }

  /** Key type for BazelFetchAllValue. */
  @AutoCodec.VisibleForSerialization
  @AutoCodec
  public static class Key extends AbstractSkyKey<Boolean> {
    private static final SkyKeyInterner<BazelFetchAllValue.Key> interner = SkyKey.newInterner();

    private Key(Boolean arg) {
      super(arg);
    }

    @AutoCodec.VisibleForSerialization
    @AutoCodec.Instantiator
    static BazelFetchAllValue.Key create(Boolean arg) {
      return interner.intern(new BazelFetchAllValue.Key(arg));
    }

    @Override
    public SkyFunctionName functionName() {
      return SkyFunctions.BAZEL_FETCH_ALL;
    }

    @Override
    public SkyKeyInterner<BazelFetchAllValue.Key> getSkyKeyInterner() {
      return interner;
    }
  }
}
