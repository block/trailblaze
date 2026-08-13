package xyz.block.trailblaze.scripting.fetch

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The Android half of the [EMULATOR_HOST_ALIASES] split. `10.0.2.2` reaches the machine hosting the
 * emulator — where the daemon's self-signed HTTPS server listens when a run isn't using
 * `adb reverse` — so on-device it belongs in the set whose certificates `fetch` accepts
 * unvalidated. Its JVM counterpart asserts the opposite, since the host launchers install this same
 * extension and there the address is an ordinary routable one.
 */
class AndroidEmulatorHostAliasTest {

  @Test
  fun theEmulatorHostAliasIsDeviceLocalOnAndroid() {
    assertTrue(OkHttpFetchExtension().isDeviceLocalHost("10.0.2.2"))
  }

  @Test
  fun loopbackIsStillDeviceLocalOnAndroid() {
    assertTrue(OkHttpFetchExtension().isDeviceLocalHost("localhost"))
    assertTrue(!OkHttpFetchExtension().isDeviceLocalHost("example.com"))
  }
}
