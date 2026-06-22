package xyz.block.trailblaze.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.After
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.api.EffectiveScreenshotScalingConfig
import xyz.block.trailblaze.api.TrailblazeImageFormat

/**
 * Behavior of the three `screenshot-*` entries in [CONFIG_KEYS]:
 * `screenshot-format`, `screenshot-max-dimensions`, `screenshot-quality`.
 *
 * Each field is nullable on [xyz.block.trailblaze.ui.models.TrailblazeServerState.SavedTrailblazeAppConfig]
 * — null means "inherit the framework default" (WebP @ 80% scaled to 1536x768). These
 * tests double as living usage docs for the CLI.
 */
class CliConfigHelperScreenshotKeysTest {

  @get:Rule val tempFolder = TemporaryFolder()

  private val priorAppDataDir = System.getProperty("trailblaze.appdata.dir")

  @After
  fun restoreAppDataDirProperty() {
    if (priorAppDataDir == null) {
      System.clearProperty("trailblaze.appdata.dir")
    } else {
      System.setProperty("trailblaze.appdata.dir", priorAppDataDir)
    }
    // `CliConfigHelper.readConfig()` mutates the JVM-wide `EffectiveScreenshotScalingConfig`
    // singleton as part of hydration. Reset it here so subsequent tests in this Gradle test
    // JVM (Gradle reuses test JVMs across classes) don't observe whatever override the prior
    // round-trip test happened to write.
    EffectiveScreenshotScalingConfig.clearForTests()
  }

  private fun isolateAppDataDir() {
    val appDataDir = tempFolder.newFolder("runtime", "appdata")
    System.setProperty("trailblaze.appdata.dir", appDataDir.absolutePath)
  }

  // --- screenshot-format -----------------------------------------------------

  @Test
  fun `screenshot-format accepts png, jpeg, webp (case-insensitive)`() {
    isolateAppDataDir()
    val key = CONFIG_KEYS.getValue("screenshot-format")
    val base = CliConfigHelper.defaultConfig()
    assertEquals(TrailblazeImageFormat.PNG, key.set(base, "png")?.screenshotImageFormat)
    assertEquals(TrailblazeImageFormat.JPEG, key.set(base, "JPEG")?.screenshotImageFormat)
    assertEquals(TrailblazeImageFormat.WEBP, key.set(base, "WeBp")?.screenshotImageFormat)
  }

  @Test
  fun `screenshot-format rejects unknown formats`() {
    isolateAppDataDir()
    val key = CONFIG_KEYS.getValue("screenshot-format")
    assertNull(key.set(CliConfigHelper.defaultConfig(), "gif"))
  }

  @Test
  fun `screenshot-format 'unset' clears the override so the framework default is used`() {
    isolateAppDataDir()
    val key = CONFIG_KEYS.getValue("screenshot-format")
    val withOverride = key.set(CliConfigHelper.defaultConfig(), "png")
    val cleared = key.set(withOverride!!, "unset")
    assertNull(cleared?.screenshotImageFormat)
  }

  @Test
  fun `screenshot-format get reads '(framework default)' on a default config`() {
    isolateAppDataDir()
    val key = CONFIG_KEYS.getValue("screenshot-format")
    assertEquals("(framework default)", key.get(CliConfigHelper.defaultConfig()))
  }

  // --- screenshot-max-dimensions --------------------------------------------

  @Test
  fun `screenshot-max-dimensions parses WIDTHxHEIGHT into longer-side and shorter-side`() {
    isolateAppDataDir()
    val key = CONFIG_KEYS.getValue("screenshot-max-dimensions")
    val updated = key.set(CliConfigHelper.defaultConfig(), "2048x1024")
    assertEquals(2048, updated?.screenshotMaxLongerSide)
    assertEquals(1024, updated?.screenshotMaxShorterSide)
  }

  @Test
  fun `screenshot-max-dimensions rejects malformed input`() {
    isolateAppDataDir()
    val key = CONFIG_KEYS.getValue("screenshot-max-dimensions")
    assertNull(key.set(CliConfigHelper.defaultConfig(), "1536"))
    assertNull(key.set(CliConfigHelper.defaultConfig(), "a x b"))
    assertNull(key.set(CliConfigHelper.defaultConfig(), "0x768"))
    assertNull(key.set(CliConfigHelper.defaultConfig(), "1536x-1"))
  }

  @Test
  fun `screenshot-max-dimensions 'unset' clears both stored sides`() {
    isolateAppDataDir()
    val key = CONFIG_KEYS.getValue("screenshot-max-dimensions")
    val withOverride = key.set(CliConfigHelper.defaultConfig(), "2048x1024")
    val cleared = key.set(withOverride!!, "unset")
    assertNull(cleared?.screenshotMaxLongerSide)
    assertNull(cleared?.screenshotMaxShorterSide)
  }

  // --- screenshot-quality ----------------------------------------------------

  @Test
  fun `screenshot-quality accepts floats in 0_05 to 1_0 range`() {
    isolateAppDataDir()
    val key = CONFIG_KEYS.getValue("screenshot-quality")
    val base = CliConfigHelper.defaultConfig()
    assertEquals(0.05f, key.set(base, "0.05")?.screenshotCompressionQuality)
    assertEquals(0.95f, key.set(base, "0.95")?.screenshotCompressionQuality)
    assertEquals(1.0f, key.set(base, "1.0")?.screenshotCompressionQuality)
  }

  @Test
  fun `screenshot-quality rejects zero - quality 0 produces unreadable images on lossy formats`() {
    isolateAppDataDir()
    val key = CONFIG_KEYS.getValue("screenshot-quality")
    assertNull(key.set(CliConfigHelper.defaultConfig(), "0"))
    assertNull(key.set(CliConfigHelper.defaultConfig(), "0.0"))
    assertNull(key.set(CliConfigHelper.defaultConfig(), "0.04"))
  }

  @Test
  fun `screenshot-quality rejects out-of-range floats and non-numeric input`() {
    isolateAppDataDir()
    val key = CONFIG_KEYS.getValue("screenshot-quality")
    assertNull(key.set(CliConfigHelper.defaultConfig(), "-0.1"))
    assertNull(key.set(CliConfigHelper.defaultConfig(), "1.5"))
    assertNull(key.set(CliConfigHelper.defaultConfig(), "abc"))
  }

  // --- round-trip ------------------------------------------------------------

  @Test
  fun `all three keys survive a write-then-read round trip via CliConfigHelper`() {
    isolateAppDataDir()
    CliConfigHelper.updateConfig {
      it.copy(
        screenshotImageFormat = TrailblazeImageFormat.PNG,
        screenshotMaxLongerSide = 2048,
        screenshotMaxShorterSide = 1024,
        screenshotCompressionQuality = 0.95f,
      )
    }
    val reread = CliConfigHelper.readConfig()
    assertEquals(TrailblazeImageFormat.PNG, reread?.screenshotImageFormat)
    assertEquals(2048, reread?.screenshotMaxLongerSide)
    assertEquals(1024, reread?.screenshotMaxShorterSide)
    assertEquals(0.95f, reread?.screenshotCompressionQuality)
  }

  // --- screenshotScalingConfig() helper -------------------------------------

  @Test
  fun `screenshotScalingConfig fills unset fields from the framework default`() {
    val partial = CliConfigHelper.defaultConfig().copy(
      screenshotImageFormat = TrailblazeImageFormat.PNG,
    )
    val effective = partial.screenshotScalingConfig()
    assertEquals(TrailblazeImageFormat.PNG, effective.imageFormat)
    // Other fields fall back to ScreenshotScalingConfig.DEFAULT (1536x768, 0.80f).
    assertEquals(1536, effective.maxDimension1)
    assertEquals(768, effective.maxDimension2)
    assertEquals(0.80f, effective.compressionQuality)
  }

  @Test
  fun `screenshotScalingConfigOrNull is null when nothing is overridden, materialized otherwise`() {
    // No overrides → null, so the web path can fall back to its own default.
    assertNull(CliConfigHelper.defaultConfig().screenshotScalingConfigOrNull())
    // Any single override → a fully materialized config (no longer null).
    val partial = CliConfigHelper.defaultConfig().copy(
      screenshotImageFormat = TrailblazeImageFormat.PNG,
    )
    assertEquals(partial.screenshotScalingConfig(), partial.screenshotScalingConfigOrNull())
  }

  // --- on-device RPC forwarding ----------------------------------------------

  @Test
  fun `GetScreenStateRequest withScreenshotScalingConfig copies all four screenshot fields`() {
    // Host-side path: when the host forwards the user's effective config to the on-device
    // agent, every scaling field must round-trip through the request so on-device renders
    // the screenshot the way the user asked.
    val userConfig = xyz.block.trailblaze.api.ScreenshotScalingConfig(
      maxDimension1 = 2048,
      maxDimension2 = 1024,
      imageFormat = TrailblazeImageFormat.PNG,
      compressionQuality = 1.0f,
    )
    val request = xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateRequest()
      .withScreenshotScalingConfig(userConfig)
    assertEquals(2048, request.screenshotMaxDimension1)
    assertEquals(1024, request.screenshotMaxDimension2)
    assertEquals(TrailblazeImageFormat.PNG, request.screenshotImageFormat)
    assertEquals(1.0f, request.screenshotCompressionQuality)
    // Non-scaling fields are preserved by `copy`.
    assertEquals(true, request.includeScreenshot)
    assertEquals(true, request.includeAnnotatedScreenshot)
  }
}
