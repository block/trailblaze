package xyz.block.trailblaze.playwright

import org.junit.Test
import kotlin.test.assertEquals

/**
 * Pins the precedence [PlaywrightBrowserManager.resolveDeviceScaleFactor] applies. Every case here
 * is a capture that silently changes resolution — and therefore artifact size — if the order moves,
 * which is not something a failing screenshot makes obvious.
 */
class PlaywrightBrowserManagerDeviceScaleFactorTest {

  @Test
  fun `a viewport preset's own density outranks a caller override`() {
    // Emulating a named device means matching its pixel density. A caller pinning 1x for artifact
    // size must not turn an emulated 3x phone into a 1x one — that is no longer that device.
    assertEquals(
      3.0,
      PlaywrightBrowserManager.resolveDeviceScaleFactor(
        presetScaleFactor = 3.0,
        override = 1.0,
        isCI = false,
      ),
    )
  }

  @Test
  fun `an override replaces the developer-machine default`() {
    // The report export's reason for existing: off CI the default is 2x, which quadruples the bytes
    // in every frame of a several-hundred-frame animation.
    assertEquals(
      1.0,
      PlaywrightBrowserManager.resolveDeviceScaleFactor(
        presetScaleFactor = null,
        override = 1.0,
        isCI = false,
      ),
    )
  }

  @Test
  fun `no preset and no override keeps the CI-versus-developer heuristic`() {
    // Existing callers pass neither, so their screenshots must come out byte-identical.
    assertEquals(
      2.0,
      PlaywrightBrowserManager.resolveDeviceScaleFactor(
        presetScaleFactor = null,
        override = null,
        isCI = false,
      ),
    )
    assertEquals(
      1.0,
      PlaywrightBrowserManager.resolveDeviceScaleFactor(
        presetScaleFactor = null,
        override = null,
        isCI = true,
      ),
    )
  }

  @Test
  fun `an override makes a capture identical on CI and on a laptop`() {
    // The point of pinning: the same command produces the same artifact either side, so a size
    // regression reported from a laptop is reproducible in CI.
    assertEquals(
      PlaywrightBrowserManager.resolveDeviceScaleFactor(null, override = 1.0, isCI = true),
      PlaywrightBrowserManager.resolveDeviceScaleFactor(null, override = 1.0, isCI = false),
    )
  }
}
