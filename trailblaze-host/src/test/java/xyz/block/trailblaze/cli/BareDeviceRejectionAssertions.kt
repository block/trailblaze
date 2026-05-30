package xyz.block.trailblaze.cli

import kotlinx.coroutines.runBlocking
import kotlin.test.assertTrue

/**
 * Test harness for the bare-device-invocation rejection contract.
 *
 * **The contract:** a CLI invocation with no `--device` flag and no
 * `TRAILBLAZE_DEVICE` env must NOT silently proceed when the resolver cannot
 * disambiguate a target device. The acceptable rejection codes are:
 *  - [TrailblazeExitCode.MISUSE] — daemon reachable but reported 0 or 2+ devices.
 *  - [TrailblazeExitCode.INFRA_FAILED] — daemon unreachable (per exit-code policy
 *    that daemon failures must NOT be classified as user error — see the
 *    `DeviceResolution` comment block in `CliInfrastructure.kt`).
 *
 * **Why an env probe:** post-#3456 the resolver consults the daemon for a
 * single-connected-device shortcut. On a developer machine with the daemon up
 * and exactly one device connected, autodetect resolves and the action
 * proceeds — the rejection contract isn't being exercised. We probe once per
 * JVM and skip the bare-device tests in that environment; integration runs
 * cover the autodetect-success path. CI (no daemon) and dev machines with
 * 0 or 2+ devices still exercise the rejection contract.
 */
internal fun assertRejectsBareDeviceInvocation(exit: Int) {
  val rejectCodes = setOf(TrailblazeExitCode.MISUSE.code, TrailblazeExitCode.INFRA_FAILED.code)
  assertTrue(
    exit in rejectCodes,
    "bare invocation must exit MISUSE (0/multiple devices) or INFRA_FAILED " +
      "(daemon unreachable); got $exit",
  )
}

/**
 * One-time probe (per JVM) that returns true when the test environment would
 * have `resolveDeviceWithAutodetect` succeed via the single-connected-device
 * shortcut. Tests that pin the rejection contract use this as a skip guard so
 * their assertion is environment-deterministic.
 *
 * `lazy` so the daemon probe runs once across the whole suite, not once per
 * test method. The probe itself can take a few seconds when the launcher
 * auto-starts a daemon — amortizing across all 9 affected tests keeps the
 * suite fast.
 */
internal val canAutoresolveSingleDevice: Boolean by lazy {
  val port = CliConfigHelper.resolveEffectiveHttpPort()
  runBlocking {
    autodetectSingleConnectedDevice(port) is DeviceAutodetectResult.Resolved
  }
}
