package xyz.block.trailblaze.host.recording

import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.recording.DeviceConnectionService.Companion.resolveElectronCdpUrl
import xyz.block.trailblaze.host.recording.DeviceConnectionService.Companion.webConnectStrategy
import xyz.block.trailblaze.host.recording.DeviceConnectionService.WebConnectStrategy
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the two pure decisions the WEB connect fork relies on, so the `/devices` viewer's
 * "electron tile → attach over CDP, web tile → launch a browser" contract can't silently
 * regress. Both are driven with injected env values — no process environment, no device.
 */
class DeviceConnectionServiceElectronTest {

  @Test
  fun `electron driver attaches, every other web driver launches`() {
    assertEquals(
      WebConnectStrategy.ATTACH_ELECTRON,
      webConnectStrategy(TrailblazeDriverType.PLAYWRIGHT_ELECTRON),
      "the Electron device must attach to a running app, never launch a fresh Chromium",
    )
    // The rest of the WEB-platform drivers keep the launch-a-browser behavior.
    TrailblazeDriverType.entries
      .filter { it.platform == xyz.block.trailblaze.devices.TrailblazeDevicePlatform.WEB }
      .filter { it != TrailblazeDriverType.PLAYWRIGHT_ELECTRON }
      .forEach { driver ->
        assertEquals(
          WebConnectStrategy.LAUNCH_CHROMIUM,
          webConnectStrategy(driver),
          "$driver is a launch-a-browser web driver",
        )
      }
  }

  @Test
  fun `an explicit CDP url wins verbatim`() {
    val url = "http://127.0.0.1:9333"
    assertEquals(url, resolveElectronCdpUrl(cdpUrlEnv = url, cdpPortEnv = "9222"))
  }

  @Test
  fun `a whitespace-padded CDP url is trimmed`() {
    assertEquals(
      "http://localhost:9222",
      resolveElectronCdpUrl(cdpUrlEnv = "  http://localhost:9222  ", cdpPortEnv = null),
    )
  }

  @Test
  fun `no url falls back to localhost on the given port`() {
    assertEquals(
      "http://localhost:9250",
      resolveElectronCdpUrl(cdpUrlEnv = null, cdpPortEnv = "9250"),
    )
  }

  @Test
  fun `no url and no port falls back to the default port`() {
    assertEquals(
      "http://localhost:9222",
      resolveElectronCdpUrl(cdpUrlEnv = null, cdpPortEnv = null),
    )
  }

  @Test
  fun `a blank url and malformed port fall back to the default`() {
    assertEquals(
      "http://localhost:9222",
      resolveElectronCdpUrl(cdpUrlEnv = "   ", cdpPortEnv = "not-a-number"),
    )
  }

  @Test
  fun `an out-of-range port falls back to the default`() {
    // 0, negatives, and anything above the valid TCP port range would build an unusable URL
    // like http://localhost:0 — treat them as malformed and use the default instead.
    listOf("0", "-1", "65536", "99999").forEach { port ->
      assertEquals(
        "http://localhost:9222",
        resolveElectronCdpUrl(cdpUrlEnv = null, cdpPortEnv = port),
        "port $port is out of the valid 1..65535 range and must fall back to the default",
      )
    }
  }

  @Test
  fun `the boundary ports 1 and 65535 are honored`() {
    assertEquals("http://localhost:1", resolveElectronCdpUrl(cdpUrlEnv = null, cdpPortEnv = "1"))
    assertEquals(
      "http://localhost:65535",
      resolveElectronCdpUrl(cdpUrlEnv = null, cdpPortEnv = "65535"),
    )
  }
}
