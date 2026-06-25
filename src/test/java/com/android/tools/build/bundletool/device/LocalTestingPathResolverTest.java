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

import static com.android.tools.build.bundletool.device.LocalTestingPathResolver.getLocalTestingWorkingDir;
import static com.android.tools.build.bundletool.device.LocalTestingPathResolver.resolveLocalTestingPath;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.android.tools.build.bundletool.model.exceptions.CommandExecutionException;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class LocalTestingPathResolverTest {

  @Test
  public void resolveLocalTestingPath_relativePathAndPackageName_userZero_resolves() {
    String actual = resolveLocalTestingPath("foo", Optional.of("com.acme.anvil"), /* userId= */ 0);

    assertThat(actual).isEqualTo("/sdcard/Android/data/com.acme.anvil/files/foo");
  }

  @Test
  public void resolveLocalTestingPath_relativePathAndPackageName_userTen_resolves() {
    String actual = resolveLocalTestingPath("foo", Optional.of("com.acme.anvil"), /* userId= */ 10);

    assertThat(actual).isEqualTo("/sdcard/Android/data/com.acme.anvil/files/foo");
  }

  @Test
  public void resolveLocalTestingPath_relativePathWithoutPackageName_throws() {
    assertThrows(
        CommandExecutionException.class,
        () -> resolveLocalTestingPath("foo", Optional.empty(), /* userId= */ 0));
  }

  @Test
  public void resolveLocalTestingPath_absolutePathWithoutLegacyPrefix_returnsUnchanged() {
    String actual =
        resolveLocalTestingPath("/foo/bar", Optional.of("com.acme.anvil"), /* userId= */ 10);

    assertThat(actual).isEqualTo("/foo/bar");
  }

  @Test
  public void resolveLocalTestingPath_absoluteLegacyPath_userZero_preserved() {
    assertThat(
            resolveLocalTestingPath(
                "/sdcard/Download/splits", Optional.of("com.acme.anvil"), /* userId= */ 0))
        .isEqualTo("/sdcard/Download/splits");
    assertThat(
            resolveLocalTestingPath(
                "/storage/emulated/0/Download/splits",
                Optional.of("com.acme.anvil"),
                /* userId= */ 0))
        .isEqualTo("/storage/emulated/0/Download/splits");
    assertThat(
            resolveLocalTestingPath(
                "/data/data/com.acme.anvil/files/x",
                Optional.of("com.acme.anvil"),
                /* userId= */ 0))
        .isEqualTo("/data/data/com.acme.anvil/files/x");
  }

  @Test
  public void resolveLocalTestingPath_sdcardPrefix_preserved() {
    String actual =
        resolveLocalTestingPath(
            "/sdcard/Download/splits", Optional.of("com.acme.anvil"), /* userId= */ 10);

    assertThat(actual).isEqualTo("/sdcard/Download/splits");
  }

  @Test
  public void resolveLocalTestingPath_storageEmulatedZeroPrefix_rewrittenToCurrentUser() {
    String actual =
        resolveLocalTestingPath(
            "/storage/emulated/0/Download/splits", Optional.of("com.acme.anvil"), /* userId= */ 10);

    assertThat(actual).isEqualTo("/sdcard/Download/splits");
  }

  @Test
  public void resolveLocalTestingPath_dataDataPrefix_rewrittenToCurrentUser() {
    String actual =
        resolveLocalTestingPath(
            "/data/data/com.acme.anvil/files/x", Optional.of("com.acme.anvil"), /* userId= */ 10);

    assertThat(actual).isEqualTo("/data/user/10/com.acme.anvil/files/x");
  }

  @Test
  public void resolveLocalTestingPath_barePrefix_rewrittenToCurrentUser() {
    assertThat(resolveLocalTestingPath("/sdcard", Optional.of("com.acme.anvil"), /* userId= */ 10))
        .isEqualTo("/sdcard");
    assertThat(
            resolveLocalTestingPath("/data/data", Optional.of("com.acme.anvil"), /* userId= */ 10))
        .isEqualTo("/data/user/10");
  }

  @Test
  public void resolveLocalTestingPath_lookalikePrefix_notRewritten() {
    assertThat(
            resolveLocalTestingPath(
                "/sdcard0/foo", Optional.of("com.acme.anvil"), /* userId= */ 10))
        .isEqualTo("/sdcard0/foo");
    assertThat(
            resolveLocalTestingPath(
                "/data/dataFoo/x", Optional.of("com.acme.anvil"), /* userId= */ 10))
        .isEqualTo("/data/dataFoo/x");
  }

  @Test
  public void getLocalTestingWorkingDir_userZero_resolves() {
    assertThat(getLocalTestingWorkingDir("com.acme.anvil", /* userId= */ 0))
        .isEqualTo("/data/data/com.acme.anvil/files/splitcompat");
  }

  @Test
  public void getLocalTestingWorkingDir_userTen_resolves() {
    assertThat(getLocalTestingWorkingDir("com.acme.anvil", /* userId= */ 10))
        .isEqualTo("/data/user/10/com.acme.anvil/files/splitcompat");
  }
}
