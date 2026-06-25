/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.tools.build.bundletool.device;

import static com.android.tools.build.bundletool.device.DdmlibDevice.joinUnixPaths;

import com.android.tools.build.bundletool.model.exceptions.CommandExecutionException;
import java.util.Optional;

/** Resolves a local testing directory to a path on the device in the apps external storage. */
public class LocalTestingPathResolver {

  private LocalTestingPathResolver() {}

  public static String resolveLocalTestingPath(
      String localTestPath, Optional<String> packageName, int userId) {
    if (localTestPath.startsWith("/")) {
      return userId == 0 ? localTestPath : rewriteUserPath(localTestPath, userId);
    }
    String packageNameStr =
        packageName.orElseThrow(
            () ->
                CommandExecutionException.builder()
                    .withInternalMessage("packageName must be set for relative paths.")
                    .build());
    return joinUnixPaths("/sdcard/Android/data/", packageNameStr, "files", localTestPath);
  }

  public static String getLocalTestingWorkingDir(String packageName, int userId) {
    String base = userId == 0 ? "/data/data" : "/data/user/" + userId;
    return joinUnixPaths(base, packageName, "files/splitcompat");
  }

  private static String rewriteUserPath(String absolutePath, int userId) {
    String externalStoragePath = "/sdcard";
    return rewritePrefix(
        rewritePrefix(
            absolutePath, "/storage/emulated/0", externalStoragePath),
        "/data/data",
        "/data/user/" + userId);
  }

  private static String rewritePrefix(String absolutePath, String oldPrefix, String newPrefix) {
    if (absolutePath.equals(oldPrefix)) {
      return newPrefix;
    }
    if (absolutePath.startsWith(oldPrefix + "/")) {
      return newPrefix + absolutePath.substring(oldPrefix.length());
    }
    return absolutePath;
  }
}
