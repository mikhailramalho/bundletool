/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.android.ddmlib.IDevice;
import com.android.ddmlib.IShellOutputReceiver;
import com.android.ddmlib.ServiceInfo;
import com.android.ddmlib.SyncException;
import com.android.ddmlib.SyncException.SyncError;
import com.android.sdklib.AndroidVersion;
import com.android.sdklib.AndroidVersion.VersionCodes;
import com.android.tools.build.bundletool.device.DdmlibDevice.RemoteCommandExecutor;
import com.android.tools.build.bundletool.device.Device.FilePullParams;
import com.android.tools.build.bundletool.device.Device.InstallOptions;
import com.android.tools.build.bundletool.device.Device.PushOptions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

@RunWith(JUnit4.class)
public final class DdmlibDeviceTest {

  private static final Path APK_PATH = Paths.get("/tmp/app.apk");
  private static final Path APK_PATH_2 = Paths.get("/tmp/app2.apk");

  @Mock private IDevice mockDevice;

  @Captor ArgumentCaptor<List<String>> extraArgsCaptor;

  @Before
  public void setUp() {
    MockitoAnnotations.initMocks(this);
  }

  @Test
  public void doesNotAllowDowngrade() throws Exception {
    when(mockDevice.getVersion()).thenReturn(new AndroidVersion(VersionCodes.KITKAT));
    DdmlibDevice ddmlibDevice = new DdmlibDevice(mockDevice);

    ddmlibDevice.installApks(
        ImmutableList.of(APK_PATH), InstallOptions.builder().setAllowDowngrade(false).build());

    // "-d" should *not* be passed as extra arg.
    verify(mockDevice).installPackage(eq(APK_PATH.toString()), anyBoolean() /*, no extra args */);
  }

  @Test
  public void allowDowngrade_preL() throws Exception {
    when(mockDevice.getVersion()).thenReturn(new AndroidVersion(VersionCodes.KITKAT));
    DdmlibDevice ddmlibDevice = new DdmlibDevice(mockDevice);

    ddmlibDevice.installApks(
        ImmutableList.of(APK_PATH), InstallOptions.builder().setAllowDowngrade(true).build());

    verify(mockDevice).installPackage(eq(APK_PATH.toString()), anyBoolean(), eq("-d"));
  }

  @Test
  public void allowDowngrade_postL() throws Exception {
    when(mockDevice.getVersion()).thenReturn(new AndroidVersion(VersionCodes.LOLLIPOP));
    DdmlibDevice ddmlibDevice = new DdmlibDevice(mockDevice);

    ddmlibDevice.installApks(
        ImmutableList.of(APK_PATH), InstallOptions.builder().setAllowDowngrade(true).build());

    verify(mockDevice)
        .installPackages(
            eq(ImmutableList.of(APK_PATH.toFile())),
            anyBoolean(),
            extraArgsCaptor.capture(),
            anyLong(),
            any(TimeUnit.class));

    assertThat(extraArgsCaptor.getValue()).contains("-d");
  }

  @Test
  public void allowTestOnly() throws Exception {
    when(mockDevice.getVersion()).thenReturn(new AndroidVersion(VersionCodes.KITKAT));
    DdmlibDevice ddmlibDevice = new DdmlibDevice(mockDevice);

    ddmlibDevice.installApks(
        ImmutableList.of(APK_PATH), InstallOptions.builder().setAllowTestOnly(true).build());

    verify(mockDevice).installPackage(eq(APK_PATH.toString()), anyBoolean(), eq("-t"));
  }

  @Test
  public void grantRuntimePermissions_postM() throws Exception {
    when(mockDevice.getVersion()).thenReturn(new AndroidVersion(VersionCodes.M));
    DdmlibDevice ddmlibDevice = new DdmlibDevice(mockDevice);

    ddmlibDevice.installApks(
        ImmutableList.of(APK_PATH),
        InstallOptions.builder().setGrantRuntimePermissions(true).build());

    verify(mockDevice)
        .installPackages(
            eq(ImmutableList.of(APK_PATH.toFile())),
            anyBoolean(),
            extraArgsCaptor.capture(),
            anyLong(),
            any(TimeUnit.class));
    assertThat(extraArgsCaptor.getValue()).contains("-g");
  }

  @Test
  public void grantRuntimePermissions_isNoopPreM() throws Exception {
    when(mockDevice.getVersion()).thenReturn(new AndroidVersion(VersionCodes.LOLLIPOP));
    DdmlibDevice ddmlibDevice = new DdmlibDevice(mockDevice);

    ddmlibDevice.installApks(
        ImmutableList.of(APK_PATH),
        InstallOptions.builder().setGrantRuntimePermissions(true).build());

    verify(mockDevice)
        .installPackages(
            eq(ImmutableList.of(APK_PATH.toFile())),
            anyBoolean(),
            extraArgsCaptor.capture(),
            anyLong(),
            any(TimeUnit.class));
    assertThat(extraArgsCaptor.getValue()).doesNotContain("-g");
  }

  @Test
  public void pushFiles_targetLocation() throws Exception {
    String destinationPath = "/destination/path";
    when(mockDevice.getVersion()).thenReturn(new AndroidVersion(VersionCodes.KITKAT));
    DdmlibDevice ddmlibDevice = new DdmlibDevice(mockDevice);

    mockAdbShellCommand(String.format("rm -rf '%s' && echo OK", destinationPath), "OK\n");
    mockAdbShellCommand(
        String.format(
            "mkdir -p '%1$s' && rmdir '%1$s' && mkdir -p '%1$s' && echo OK", destinationPath),
        "OK\n");

    ddmlibDevice.push(
        ImmutableList.of(APK_PATH, APK_PATH_2),
        PushOptions.builder().setDestinationPath(destinationPath).build());

    verify(mockDevice)
        .pushFile(
            APK_PATH.toFile().getAbsolutePath(), destinationPath + "/" + APK_PATH.getFileName());
    verify(mockDevice)
        .pushFile(
            APK_PATH_2.toFile().getAbsolutePath(),
            destinationPath + "/" + APK_PATH_2.getFileName());
  }

  @Test
  public void pushFiles_sdk31_additionalPermissions() throws Exception {
    String destinationPath = "/destination/path";
    when(mockDevice.getVersion()).thenReturn(new AndroidVersion(31));
    DdmlibDevice ddmlibDevice = new DdmlibDevice(mockDevice);

    mockAdbShellCommand(String.format("rm -rf '%s' && echo OK", destinationPath), "OK\n");
    mockAdbShellCommand(
        String.format(
            "mkdir -p '%1$s' && rmdir '%1$s' && mkdir -p '%1$s' && echo OK", destinationPath),
        "OK\n");
    mockAdbShellCommand(String.format("chmod 775 '%s' && echo OK", destinationPath), "OK\n");

    ddmlibDevice.push(
        ImmutableList.of(APK_PATH, APK_PATH_2),
        PushOptions.builder().setDestinationPath(destinationPath).build());

    verify(mockDevice)
        .pushFile(
            APK_PATH.toFile().getAbsolutePath(), destinationPath + "/" + APK_PATH.getFileName());
    verify(mockDevice)
        .pushFile(
            APK_PATH_2.toFile().getAbsolutePath(),
            destinationPath + "/" + APK_PATH_2.getFileName());
    verify(mockDevice)
        .executeShellCommand(
            eq(String.format("chmod 775 '%s' && echo OK", destinationPath)),
            any(),
            anyLong(),
            any());
  }

  @Test
  public void pushFiles_tempLocation() throws Exception {
    String destinationPath = "/destination/path";
    Clock fixedClock = Clock.fixed(Instant.now(), ZoneId.systemDefault());
    when(mockDevice.getVersion()).thenReturn(new AndroidVersion(VersionCodes.KITKAT));
    DdmlibDevice ddmlibDevice = new DdmlibDevice(mockDevice, fixedClock);
    String tempPath = "/data/local/tmp/splits-" + fixedClock.millis();

    // Emulate permission error when pushing files to target location which happens on some
    // Android 11 devices.
    doThrow(new SyncException(SyncError.CANCELED, "fchown failed: Operation not permitted"))
        .when(mockDevice)
        .pushFile(
            APK_PATH.toFile().getAbsolutePath(), destinationPath + "/" + APK_PATH.getFileName());

    mockAdbShellCommand(String.format("rm -rf '%s' && echo OK", destinationPath), "OK\n");
    mockAdbShellCommand(
        String.format(
            "mkdir -p '%1$s' && rmdir '%1$s' && mkdir -p '%1$s' && echo OK", destinationPath),
        "OK\n");
    mockAdbShellCommand(
        String.format(
            "rm -rf '%s' && mv '%s' '%s' && echo OK", destinationPath, tempPath, destinationPath),
        "OK\n");

    ddmlibDevice.push(
        ImmutableList.of(APK_PATH, APK_PATH_2),
        PushOptions.builder().setDestinationPath(destinationPath).build());

    verify(mockDevice)
        .pushFile(APK_PATH.toFile().getAbsolutePath(), tempPath + "/" + APK_PATH.getFileName());
    verify(mockDevice)
        .pushFile(APK_PATH_2.toFile().getAbsolutePath(), tempPath + "/" + APK_PATH_2.getFileName());
  }

  @Test
  public void installApks_userIdAddsUserArg() throws Exception {
    when(mockDevice.getVersion()).thenReturn(new AndroidVersion(VersionCodes.LOLLIPOP));
    DdmlibDevice ddmlibDevice = new DdmlibDevice(mockDevice);

    ddmlibDevice.installApks(
        ImmutableList.of(APK_PATH), InstallOptions.builder().setUserId(10).build());

    verify(mockDevice)
        .installPackages(
            eq(ImmutableList.of(APK_PATH.toFile())),
            anyBoolean(),
            extraArgsCaptor.capture(),
            anyLong(),
            any(TimeUnit.class));
    assertThat(extraArgsCaptor.getValue()).containsAtLeast("--user", "10").inOrder();
  }

  @Test
  public void installApks_noUserIdOmitsUserArg() throws Exception {
    when(mockDevice.getVersion()).thenReturn(new AndroidVersion(VersionCodes.LOLLIPOP));
    DdmlibDevice ddmlibDevice = new DdmlibDevice(mockDevice);

    ddmlibDevice.installApks(ImmutableList.of(APK_PATH), InstallOptions.builder().build());

    verify(mockDevice)
        .installPackages(
            eq(ImmutableList.of(APK_PATH.toFile())),
            anyBoolean(),
            extraArgsCaptor.capture(),
            anyLong(),
            any(TimeUnit.class));
    assertThat(extraArgsCaptor.getValue()).doesNotContain("--user");
  }

  @Test
  public void installApks_preL_userIdOmitsUserArg() throws Exception {
    // The legacy pre-L installPackage path does not accept --user; we must not inject it even
    // when the caller asked for a specific userId.
    when(mockDevice.getVersion()).thenReturn(new AndroidVersion(VersionCodes.KITKAT));
    DdmlibDevice ddmlibDevice = new DdmlibDevice(mockDevice);

    ddmlibDevice.installApks(
        ImmutableList.of(APK_PATH), InstallOptions.builder().setUserId(10).build());

    // "--user" / "10" must NOT be passed as extra args on the pre-L path.
    verify(mockDevice).installPackage(eq(APK_PATH.toString()), anyBoolean() /*, no extra args */);
  }

  @Test
  public void getCurrentUser_parsesActiveUser() throws Exception {
    mockAdbShellCommand("am get-current-user", "10\n");

    int userId = new DdmlibDevice(mockDevice).getCurrentUser();

    assertThat(userId).isEqualTo(10);
  }

  @Test
  public void getCurrentUser_leadingWhitespaceTolerated() throws Exception {
    mockAdbShellCommand("am get-current-user", "  10  \n");

    int userId = new DdmlibDevice(mockDevice).getCurrentUser();

    assertThat(userId).isEqualTo(10);
  }

  @Test
  public void getCurrentUser_picksFirstNumericLine() throws Exception {
    // Real-world shells sometimes emit prelude lines (warnings, banner output) before the
    // command's actual stdout. The parser must skip non-numeric lines and pick the first one
    // that parses as a non-negative integer.
    mockAdbShellCommand("am get-current-user", "warning: something\n10\n");

    int userId = new DdmlibDevice(mockDevice).getCurrentUser();

    assertThat(userId).isEqualTo(10);
  }

  @Test
  public void getCurrentUser_negativeValueRejected() throws Exception {
    // Android user ids are non-negative; treat a negative integer as garbage and fall back.
    mockAdbShellCommand("am get-current-user", "-1\n");

    int userId = new DdmlibDevice(mockDevice).getCurrentUser();

    assertThat(userId).isEqualTo(0);
  }

  @Test
  public void getCurrentUser_unparseableOutputDefaultsToZero() throws Exception {
    mockAdbShellCommand("am get-current-user", "no such command\n");

    int userId = new DdmlibDevice(mockDevice).getCurrentUser();

    assertThat(userId).isEqualTo(0);
  }

  @Test
  public void getCurrentUser_emptyOutputDefaultsToZero() throws Exception {
    mockAdbShellCommand("am get-current-user", "");

    int userId = new DdmlibDevice(mockDevice).getCurrentUser();

    assertThat(userId).isEqualTo(0);
  }

  @Test
  public void getCurrentUser_shellExceptionDefaultsToZero() throws Exception {
    Mockito.doThrow(new IOException("adb gone"))
        .when(mockDevice)
        .executeShellCommand(eq("am get-current-user"), any(), anyLong(), any());

    int userId = new DdmlibDevice(mockDevice).getCurrentUser();

    assertThat(userId).isEqualTo(0);
  }

  @Test
  public void pullFiles() throws Exception {
    Path destinationPath = Paths.get("/destination/path", APK_PATH.getFileName().toString());
    DdmlibDevice ddmlibDevice = new DdmlibDevice(mockDevice);

    ddmlibDevice.pull(
        ImmutableList.of(
            FilePullParams.builder()
                .setPathOnDevice(APK_PATH.toFile().getAbsolutePath())
                .setDestinationPath(destinationPath)
                .build()));

    verify(mockDevice).pullFile(APK_PATH.toFile().getAbsolutePath(), destinationPath.toString());
  }

  @Test
  public void getDensity_densityIsAvailableViaDdmlib() throws Exception {
    when(mockDevice.getDensity()).thenReturn(540);
    mockAdbShellCommand("wm density", "Physical density: 420");

    DdmlibDevice ddmlibDevice = new DdmlibDevice(mockDevice);
    assertThat(ddmlibDevice.getDensity()).isEqualTo(540);
  }

  @Test
  public void getDensity_densityIsNotAvailableViaDdmlib_requestViaAdb() throws Exception {
    when(mockDevice.getDensity()).thenReturn(-1);
    mockAdbShellCommand("wm density", "Physical density: 420");

    DdmlibDevice ddmlibDevice = new DdmlibDevice(mockDevice);
    assertThat(ddmlibDevice.getDensity()).isEqualTo(420);
  }

  @Test
  public void getDensity_densityIsNotAvailableViaDdmlibAndAdb() throws Exception {
    when(mockDevice.getDensity()).thenReturn(-1);
    mockAdbShellCommand("wm density", "Test output");

    DdmlibDevice ddmlibDevice = new DdmlibDevice(mockDevice);
    assertThat(ddmlibDevice.getDensity()).isEqualTo(-1);
  }

  @Test
  public void removeRemotePath() throws Exception {
    String pathToRemove = "/path/to/remove";
    DdmlibDevice ddmlibDevice = new DdmlibDevice(mockDevice);

    mockAdbShellCommand(String.format("rm -rf '%s' && echo OK", pathToRemove), "OK\n");
    ddmlibDevice.removeRemotePath(
        pathToRemove, /* runAsPackageName= */ Optional.empty(), Duration.ofMillis(10));
  }

  @Test
  public void removeRemotePath_runAs() throws Exception {
    String packageName = "com.test";
    String pathToRemove = "/path/to/remove";
    DdmlibDevice ddmlibDevice = new DdmlibDevice(mockDevice);

    mockAdbShellCommand(
        String.format("run-as '%s' rm -rf '%s' && echo OK", packageName, pathToRemove), "OK\n");
    ddmlibDevice.removeRemotePath(pathToRemove, Optional.of(packageName), Duration.ofMillis(10));
  }

  @Test
  public void supportsPrivacySandbox_withServiceAndApi34Level_returnsTrue() throws Exception {
    when(mockDevice.services()).thenReturn(ImmutableMap.of("sdk_sandbox", new ServiceInfo("", "")));
    when(mockDevice.getVersion()).thenReturn(new AndroidVersion(VersionCodes.UPSIDE_DOWN_CAKE));
    DdmlibDevice ddmlibDevice = new DdmlibDevice(mockDevice);

    assertThat(ddmlibDevice.supportsPrivacySandbox()).isTrue();
  }

  @Test
  public void supportsPrivacySandbox_withServiceAndApi33Level_returnsFalse() throws Exception {
    when(mockDevice.services()).thenReturn(ImmutableMap.of("sdk_sandbox", new ServiceInfo("", "")));
    when(mockDevice.getVersion()).thenReturn(new AndroidVersion(VersionCodes.TIRAMISU));
    DdmlibDevice ddmlibDevice = new DdmlibDevice(mockDevice);

    assertThat(ddmlibDevice.supportsPrivacySandbox()).isFalse();
  }

  @Test
  public void supportsPrivacySandbox_withoutServiceAndApi34Level_returnsFalse() throws Exception {
    when(mockDevice.getVersion()).thenReturn(new AndroidVersion(VersionCodes.UPSIDE_DOWN_CAKE));
    DdmlibDevice ddmlibDevice = new DdmlibDevice(mockDevice);

    assertThat(ddmlibDevice.supportsPrivacySandbox()).isFalse();
  }

  private void mockAdbShellCommand(String command, String response) throws Exception {
    Mockito.doAnswer(
            invocation -> {
              IShellOutputReceiver shellOutputReceiver =
                  (IShellOutputReceiver) invocation.getArguments()[1];
              byte[] bytes = response.getBytes(UTF_8);
              shellOutputReceiver.addOutput(bytes, 0, bytes.length);
              shellOutputReceiver.flush();
              return null;
            })
        .when(mockDevice)
        .executeShellCommand(eq(command), any(), anyLong(), any());
  }

  @Test
  public void joinUnixPathsTest() {
    assertThat(DdmlibDevice.joinUnixPaths("/", "splits", "mysplits")).isEqualTo("/splits/mysplits");
    assertThat(DdmlibDevice.joinUnixPaths("splits", "mysplits")).isEqualTo("splits/mysplits");
    assertThat(DdmlibDevice.joinUnixPaths("/", "splits/", "mysplits"))
        .isEqualTo("/splits/mysplits");
    assertThat(DdmlibDevice.joinUnixPaths("/", "splits", "mysplits/"))
        .isEqualTo("/splits/mysplits/");
  }

  @Test
  public void escapeAndSingleQuoteTest() {
    assertThat(RemoteCommandExecutor.escapeAndSingleQuote("abc")).isEqualTo("'abc'");
    assertThat(RemoteCommandExecutor.escapeAndSingleQuote("ab'c")).isEqualTo("'ab'\\''c'");
  }

  @Test
  public void formatCommandWithArgsTest() {
    assertThat(RemoteCommandExecutor.formatCommandWithArgs("cat %s %s", "abc", "def"))
        .isEqualTo("cat 'abc' 'def'");
    assertThat(RemoteCommandExecutor.formatCommandWithArgs("cat %s %s", "ab'c", "de'f"))
        .isEqualTo("cat 'ab'\\''c' 'de'\\''f'");
  }

  @Test
  public void getGlExtensions_supportedDevice_runsDumpsys() throws Exception {
    when(mockDevice.getVersion()).thenReturn(new AndroidVersion(VersionCodes.O));
    when(mockDevice.getProperty("ro.build.characteristics")).thenReturn("watch");
    mockAdbShellCommand("dumpsys SurfaceFlinger", "");

    ImmutableList<String> glExtensions = new DdmlibDevice(mockDevice).getGlExtensions();

    assertThat(glExtensions).isEmpty();
    verify(mockDevice).executeShellCommand(eq("dumpsys SurfaceFlinger"), any(), anyLong(), any());
  }

  @Test
  public void getGlExtensions_unsupportedDevice_returnsEmpty() throws Exception {
    when(mockDevice.getVersion()).thenReturn(new AndroidVersion(VersionCodes.N_MR1));
    when(mockDevice.getProperty("ro.build.characteristics")).thenReturn("watch");

    ImmutableList<String> glExtensions = new DdmlibDevice(mockDevice).getGlExtensions();

    assertThat(glExtensions).isEmpty();
    verify(mockDevice, never())
        .executeShellCommand(eq("dumpsys SurfaceFlinger"), any(), anyLong(), any());
  }
}
