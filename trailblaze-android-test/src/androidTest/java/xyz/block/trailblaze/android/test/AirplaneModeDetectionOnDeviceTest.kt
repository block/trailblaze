package xyz.block.trailblaze.android.test

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import xyz.block.trailblaze.AdbCommandUtil
import xyz.block.trailblaze.android.maestro.MaestroAndroidUiAutomatorDriver
import xyz.block.trailblaze.toolcalls.commands.NetworkConnectionTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.NetworkConnectionTrailblazeTool.AndroidRadio

/**
 * What the Android drivers answer when asked whether airplane mode is on, checked against a device
 * that is genuinely in airplane mode.
 *
 * Both drivers used to answer this by looking at the radios — wifi off AND mobile data off AND
 * bluetooth off. That is not airplane mode. `TelephonyManager.isDataEnabled` and the `mobile_data`
 * setting behind it are the user's PREFERENCE for mobile data, which Android never rewrites when
 * airplane mode goes on, and wifi routinely stays up through airplane mode — so a device genuinely
 * in airplane mode answered "off", and a `toggleAirplaneMode` on it took the radios down instead of
 * reversing anything.
 *
 * The state asserted below is the one that makes that concrete and is impossible to pass with a
 * radios-based read: airplane mode ON with wifi genuinely UP. Wifi is on
 * `airplane_mode_toggleable_radios`, so it can be brought back up through airplane mode and stay
 * up, which the `networkConnection` tool already relies on.
 *
 * Airplane mode is set and cleared through [NetworkConnectionTrailblazeTool]'s shared commands, and
 * read back through the framework's own `Settings.Global` rather than through the shell the driver
 * reads with, so a driver echoing its own write cannot pass.
 *
 * The accessibility driver's read is the same one — both drivers delegate to
 * [AdbCommandUtil.isAirplaneModeEnabled], which is asserted directly here. Only the Maestro
 * driver's public override is exercised through the driver itself; standing up
 * `AccessibilityDeviceManager` needs the accessibility service running, and its own read is
 * private and reachable only by a toggle that mutates the device.
 *
 * A device left in airplane mode strands every build behind it, so the restore is unconditional
 * and waited on. It is a `finally` block, though, so it cannot cover the process being killed
 * mid-test. Recovery from that is whatever restores the device between runs, outside this test — on
 * a shared device it is worth knowing that a crash here can leave one offline.
 */
class AirplaneModeDetectionOnDeviceTest {

  @Test
  fun theDriversReportAirplaneModeOnWhileItIsGenuinelyOnAndWifiIsUp() {
    // Both modules declare minSdk 26, but setting real airplane mode from a shell needs API 28 —
    // the tool refuses below that rather than pretending. Skipping is right on an older device:
    // this test cannot put it in the state it exists to assert, and nothing here is broken.
    assumeTrue(
      "real airplane mode needs API ${NetworkConnectionTrailblazeTool.ANDROID_MIN_SDK_FOR_AIRPLANE_MODE}",
      Build.VERSION.SDK_INT >= NetworkConnectionTrailblazeTool.ANDROID_MIN_SDK_FOR_AIRPLANE_MODE,
    )
    val wifi = InstrumentationRegistry.getInstrumentation()
      .targetContext
      .applicationContext
      .getSystemService(Context.WIFI_SERVICE) as WifiManager
    try {
      setAirplaneMode(true)
      awaitAirplaneModeFlag(true)
      assertEquals(true, readAirplaneModeFlag(), "airplane mode must actually be on to test the read")

      // Wifi back up, through airplane mode. This is the state a radios-based read gets wrong.
      //
      // Re-issued inside the poll rather than asked for once, because entering airplane mode is
      // asynchronous in TWO stages: the platform writes the flag, THEN broadcasts to the receivers
      // that move the radios. `awaitAirplaneModeFlag` returns on the flag alone, so a single
      // `svc wifi enable` here can land before that broadcast and simply be undone by it. The tool
      // documents the same race for a step that asks for airplane mode and a radio at once.
      awaitWifiHeldUp(wifi)
      assertEquals(true, wifi.isWifiEnabled, "wifi must be genuinely up for this assertion to bite")
      assertEquals(
        true,
        readAirplaneModeFlag(),
        "bringing wifi up must not have cleared airplane mode — otherwise the read below proves nothing",
      )

      assertEquals(
        true,
        AdbCommandUtil.isAirplaneModeEnabled(),
        "the shared driver read must report airplane mode ON while the flag is set, even with wifi up",
      )
      assertEquals(
        true,
        MaestroAndroidUiAutomatorDriver.isAirplaneModeEnabled(),
        "the Maestro driver override must report airplane mode ON while the flag is set, even with wifi up",
      )

      setAirplaneMode(false)
      awaitAirplaneModeFlag(false)
      assertEquals(
        false,
        AdbCommandUtil.isAirplaneModeEnabled(),
        "clearing airplane mode must be reported as off",
      )
      assertEquals(
        false,
        MaestroAndroidUiAutomatorDriver.isAirplaneModeEnabled(),
        "clearing airplane mode must be reported as off by the driver override too",
      )

      // The documented direction for an unreadable flag, on a device that really has one: deleting
      // the row makes `settings get` print the literal `null`, which the mapping has no meaning
      // for. Asserted last, and the restore below re-materializes the row either way.
      AdbCommandUtil.execShellCommand(
        "settings delete global ${NetworkConnectionTrailblazeTool.ANDROID_AIRPLANE_MODE_SETTING}",
      )
      // Pin the BRANCH, not just the outcome. Airplane mode is off at this point, so the read
      // answers `false` either way — if the delete were rejected or silently repopulated, the
      // assertion below would pass on the `"0"` branch and prove nothing about the unreadable one.
      // This is the assertion that fails in that case.
      val rawAfterDelete = AdbCommandUtil.execShellCommand(
        NetworkConnectionTrailblazeTool.androidSettingReadCommand(
          NetworkConnectionTrailblazeTool.ANDROID_AIRPLANE_MODE_SETTING,
        ),
      )
      assertNull(
        NetworkConnectionTrailblazeTool.androidAirplaneModeSettingMeansOn(rawAfterDelete),
        "the delete must actually leave the flag unreadable, or the next assertion proves " +
          "nothing — got '${rawAfterDelete.trim()}'",
      )
      assertEquals(
        false,
        AdbCommandUtil.isAirplaneModeEnabled(),
        "an unreadable airplane-mode flag must read as off, not as on",
      )
    } finally {
      // Nothing here asserts, and every step is individually guarded. `execShellCommand` THROWS on
      // a wedged UiAutomation connection, so an unguarded restore could both replace whichever
      // assertion above sent us here AND abort the steps after it — stranding the device in the
      // exact state this block exists to undo.
      //
      // Airplane mode first: leaving it moves the radios itself, so radios brought up before it
      // clears can be taken straight back down.
      restoreStep("clear airplane mode") { setAirplaneMode(false) }
      restoreStep("re-materialize the airplane-mode flag") {
        // Explicit, because the assertion above DELETED the row. `Settings.Global.getInt` falls
        // back to its default for a missing row, so `awaitAirplaneModeFlag(false)` cannot tell a
        // cleared flag from an absent one — and a device left with no row reads as unreadable to
        // every later build, turning an `airplaneMode: false` step into a failure.
        AdbCommandUtil.execShellCommand(
          "settings put global ${NetworkConnectionTrailblazeTool.ANDROID_AIRPLANE_MODE_SETTING} 0",
        )
      }
      restoreStep("wait for the airplane-mode flag to clear") { awaitAirplaneModeFlag(false) }
      AndroidRadio.entries.forEach { radio ->
        restoreStep("re-enable ${radio.svcName}") {
          AdbCommandUtil.execShellCommand(
            NetworkConnectionTrailblazeTool.androidRadioShellCommand(radio, on = true),
          )
        }
      }
      restoreStep("wait for wifi") { awaitWifiSettle(true, wifi) }
    }
  }

  /**
   * Runs one restore step, swallowing and reporting any failure.
   *
   * A restore that fails must not become the failure this test reports, and must not stop the
   * remaining steps — but it must not vanish either, or a device stranded offline looks like the
   * next build's problem.
   */
  private fun restoreStep(what: String, step: () -> Unit) {
    runCatching(step).onFailure { failure ->
      println("[AirplaneModeDetectionOnDeviceTest] restore step '$what' failed: $failure")
    }
  }

  private fun setAirplaneMode(enabled: Boolean) {
    AdbCommandUtil.execShellCommand(
      NetworkConnectionTrailblazeTool.androidAirplaneModeShellCommand(enabled),
    )
  }

  private fun setWifi(on: Boolean) {
    AdbCommandUtil.execShellCommand(
      NetworkConnectionTrailblazeTool.androidRadioShellCommand(AndroidRadio.WIFI, on = on),
    )
  }

  /** The platform's own view of the flag, read independently of the shell the driver reads with. */
  private fun readAirplaneModeFlag(): Boolean = Settings.Global.getInt(
    InstrumentationRegistry.getInstrumentation().targetContext.contentResolver,
    Settings.Global.AIRPLANE_MODE_ON,
    0,
  ) == 1

  private fun awaitAirplaneModeFlag(expected: Boolean) {
    val deadline = SystemClock.uptimeMillis() + SETTLE_TIMEOUT_MS
    while (readAirplaneModeFlag() != expected && SystemClock.uptimeMillis() < deadline) {
      SystemClock.sleep(250)
    }
  }

  /**
   * Switching a radio is asynchronous on every Android device — the framework accepts the request
   * and reports the new state once the supplicant has moved — so reading it on the next line tests
   * the timing of the emulator.
   */
  private fun awaitWifiSettle(expected: Boolean, wifi: WifiManager) {
    val deadline = SystemClock.uptimeMillis() + SETTLE_TIMEOUT_MS
    while (wifi.isWifiEnabled != expected && SystemClock.uptimeMillis() < deadline) {
      SystemClock.sleep(250)
    }
  }

  /**
   * Brings wifi up and keeps asking until it STAYS up, against airplane mode's late broadcast to
   * the radios.
   *
   * Requiring it to hold across consecutive polls is the point: a single reading of `true` can be
   * the moment before the broadcast arrives and takes it back down.
   */
  private fun awaitWifiHeldUp(wifi: WifiManager) {
    val deadline = SystemClock.uptimeMillis() + SETTLE_TIMEOUT_MS
    var consecutiveUp = 0
    while (consecutiveUp < WIFI_HELD_UP_POLLS && SystemClock.uptimeMillis() < deadline) {
      if (wifi.isWifiEnabled) {
        consecutiveUp++
      } else {
        consecutiveUp = 0
        setWifi(true)
      }
      SystemClock.sleep(250)
    }
  }

  private companion object {
    /** Generous: an emulator brings its Wi-Fi back up in a second or two, a loaded one slower. */
    const val SETTLE_TIMEOUT_MS = 20_000L

    /**
     * How many consecutive polls wifi has to read up before it counts as held.
     *
     * Two, not one, so the reading cannot be the gap before airplane mode's broadcast reaches the
     * radios. Not a timing budget — the deadline above is what bounds the wait.
     */
    const val WIFI_HELD_UP_POLLS = 2
  }
}
