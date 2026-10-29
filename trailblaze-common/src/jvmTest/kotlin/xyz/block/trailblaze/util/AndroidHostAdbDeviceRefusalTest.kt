package xyz.block.trailblaze.util

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import dadb.AdbStream
import dadb.Dadb
import dadb.InstallResult
import dadb.SyncResult
import dadb.UninstallResult
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Test
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform

/**
 * dadb reports a device refusing an install, uninstall, push or pull as a returned result, not an
 * exception. A call site that ignores the result reports success for an APK that never installed.
 * These run the real [AndroidHostAdbUtils] entry points against a fake client that refuses, and pin
 * that each refusal still surfaces as a failure — once, without the eviction-and-retry reserved for
 * a broken connection.
 */
class AndroidHostAdbDeviceRefusalTest {

  private val serial = "fake-dadb-${UUID.randomUUID()}"
  private val deviceId = TrailblazeDeviceId(serial, TrailblazeDevicePlatform.ANDROID)
  private val apk = File.createTempFile("refusal", ".apk").apply { deleteOnExit() }

  @After
  fun tearDown() {
    AndroidHostAdbUtils.dadbClients.remove(serial)
    apk.delete()
  }

  private fun serve(client: FakeDadb): FakeDadb = client.also { AndroidHostAdbUtils.dadbClients[serial] = it }

  @Test
  fun aRefusedInstallFailsWithoutRetrying() {
    val client = serve(FakeDadb(installs = listOf(InstallResult.Failure("Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]"))))

    assertThat(AndroidHostAdbUtils.installApkFile(apk, deviceId)).isFalse()
    assertThat(client.installCalls).isEqualTo(1)
    assertThat(AndroidHostAdbUtils.dadbClients[serial]).isSameInstanceAs(client)
  }

  @Test
  fun anAcceptedInstallSucceeds() {
    val client = serve(FakeDadb(installs = listOf(InstallResult.Success)))

    assertThat(AndroidHostAdbUtils.installApkFile(apk, deviceId)).isTrue()
    assertThat(client.installCalls).isEqualTo(1)
  }

  @Test
  fun aSigningKeyMismatchUninstallsTheStalePackageAndReinstalls() {
    val client = serve(
      FakeDadb(
        installs = listOf(
          InstallResult.Failure(
            "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: " +
              "Existing package com.example.app signatures do not match newer version; ignoring!]",
          ),
          InstallResult.Success,
        ),
      ),
    )

    assertThat(AndroidHostAdbUtils.installApkFile(apk, deviceId)).isTrue()
    assertThat(client.uninstalled).containsExactly("com.example.app")
    assertThat(client.installCalls).isEqualTo(2)
  }

  @Test
  fun aReinstallRefusedAfterTheSigningKeyRecoveryStillFails() {
    val mismatch = InstallResult.Failure(
      "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: " +
        "Existing package com.example.app signatures do not match newer version; ignoring!]",
    )
    val client = serve(FakeDadb(installs = listOf(mismatch, mismatch)))

    assertThat(AndroidHostAdbUtils.installApkFile(apk, deviceId)).isFalse()
    assertThat(client.installCalls).isEqualTo(2)
  }

  @Test
  fun aRefusedUninstallIsNonFatalAndNotRetried() {
    val client = serve(FakeDadb(uninstall = UninstallResult.Failure("Failure [DELETE_FAILED_INTERNAL_ERROR]", 1)))

    assertThat(AndroidHostAdbUtils.tryUninstallApp(deviceId, "com.example.app")).isFalse()
    assertThat(client.uninstalled).containsExactly("com.example.app")
  }

  @Test
  fun anAcceptedUninstallSucceeds() {
    serve(FakeDadb(uninstall = UninstallResult.Success))

    assertThat(AndroidHostAdbUtils.tryUninstallApp(deviceId, "com.example.app")).isTrue()
  }

  @Test
  fun aRefusedPushFailsWithoutRetrying() {
    val client = serve(FakeDadb(push = SyncResult.Failure("secure_mkdirs failed: Permission denied")))

    assertThat(AndroidHostAdbUtils.pushFile(deviceId, apk, "/system/denied.apk")).isFalse()
    assertThat(client.pushCalls).isEqualTo(1)
    assertThat(AndroidHostAdbUtils.dadbClients[serial]).isSameInstanceAs(client)
  }

  @Test
  fun anAcceptedPushSucceeds() {
    serve(FakeDadb(push = SyncResult.Success))

    assertThat(AndroidHostAdbUtils.pushFile(deviceId, apk, "/data/local/tmp/ok.apk")).isTrue()
  }

  @Test
  fun aRefusedPullFailsWithoutRetrying() {
    val client = serve(FakeDadb(pull = SyncResult.Failure("open failed: Permission denied")))

    assertThat(AndroidHostAdbUtils.pullFile(deviceId, "/system/priv-app/denied.apk", apk)).isFalse()
    assertThat(client.pullCalls).isEqualTo(1)
    assertThat(AndroidHostAdbUtils.dadbClients[serial]).isSameInstanceAs(client)
  }

  @Test
  fun anAcceptedPullSucceeds() {
    serve(FakeDadb(pull = SyncResult.Success))

    assertThat(AndroidHostAdbUtils.pullFile(deviceId, "/data/local/tmp/ok.apk", apk)).isTrue()
  }

  /** Answers each operation with a canned result; any other traffic fails the test loudly. */
  private class FakeDadb(
    installs: List<InstallResult> = emptyList(),
    private val uninstall: UninstallResult = UninstallResult.Success,
    private val push: SyncResult = SyncResult.Success,
    private val pull: SyncResult = SyncResult.Success,
  ) : Dadb {
    private val installQueue = ArrayDeque(installs)
    var installCalls = 0
    var pushCalls = 0
    var pullCalls = 0
    val uninstalled = mutableListOf<String>()

    override fun install(file: File, vararg options: String): InstallResult {
      installCalls++
      return installQueue.removeFirst()
    }

    override fun uninstall(packageName: String): UninstallResult {
      uninstalled += packageName
      return uninstall
    }

    override fun push(src: File, remotePath: String, mode: Int, lastModifiedMs: Long): SyncResult {
      pushCalls++
      return push
    }

    override fun pull(dst: File, remotePath: String): SyncResult {
      pullCalls++
      return pull
    }

    override fun open(destination: String): AdbStream = error("unexpected stream: $destination")

    override fun supportsFeature(feature: String): Boolean = true

    override fun close() = Unit
  }
}
