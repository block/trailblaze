package xyz.block.trailblaze.capture

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CaptureOptionsTest {

  @Test
  fun `hasAnyCaptureEnabled is false when video, logcat, iosLogs, and memory all off`() {
    val options = CaptureOptions(
      captureVideo = false,
      captureLogcat = false,
      captureIosLogs = false,
      captureMemory = false,
    )
    assertFalse(options.hasAnyCaptureEnabled)
  }

  @Test
  fun `hasAnyCaptureEnabled is true when only video is on`() {
    val options = CaptureOptions(
      captureVideo = true,
      captureLogcat = false,
      captureIosLogs = false,
      captureMemory = false,
    )
    assertTrue(options.hasAnyCaptureEnabled)
  }

  @Test
  fun `hasAnyCaptureEnabled is true when only logcat is on`() {
    val options = CaptureOptions(
      captureVideo = false,
      captureLogcat = true,
      captureIosLogs = false,
      captureMemory = false,
    )
    assertTrue(options.hasAnyCaptureEnabled)
  }

  @Test
  fun `hasAnyCaptureEnabled is true when only iosLogs is on`() {
    val options = CaptureOptions(
      captureVideo = false,
      captureLogcat = false,
      captureIosLogs = true,
      captureMemory = false,
    )
    assertTrue(options.hasAnyCaptureEnabled)
  }

  @Test
  fun `hasAnyCaptureEnabled is true when only memory is on`() {
    val options = CaptureOptions(
      captureVideo = false,
      captureLogcat = false,
      captureIosLogs = false,
      captureMemory = true,
    )
    assertTrue(options.hasAnyCaptureEnabled)
  }

  @Test
  fun `default options enable both device-log streams but not video`() {
    // Log capture is always-on by default (per-platform gating in CaptureSession.fromOptions
    // means logcat only acts on Android and iOS logs only on iOS). Video is opt-in — it writes
    // large files, so a run must ask for it explicitly.
    val options = CaptureOptions()
    assertFalse(options.captureVideo)
    assertTrue(options.captureLogcat)
    assertTrue(options.captureIosLogs)
    assertTrue(options.captureMemory)
    assertTrue(options.hasAnyCaptureEnabled)
  }

  @Test
  fun `NONE has all capture flags off`() {
    // NONE is explicitly all-off (the default constructor still enables the log streams), so it
    // genuinely means "no capture" — distinct from CaptureOptions().
    assertFalse(CaptureOptions.NONE.captureVideo)
    assertFalse(CaptureOptions.NONE.captureLogcat)
    assertFalse(CaptureOptions.NONE.captureIosLogs)
    assertFalse(CaptureOptions.NONE.captureMemory)
    assertFalse(CaptureOptions.NONE.hasAnyCaptureEnabled)
    assertNotEquals(CaptureOptions(), CaptureOptions.NONE)
  }

  @Test
  fun `hostCaptureOptions leaves video off when nothing asks for it`() {
    val options = CaptureOptions.hostCaptureOptions(env = { null })
    assertFalse(options.captureVideo)
    assertTrue(options.captureLogcat)
    assertTrue(options.captureIosLogs)
  }

  @Test
  fun `hostCaptureOptions honors an explicit per-run opt-in`() {
    // Video defaults off, so this can only be true if the caller's value is honored.
    assertTrue(CaptureOptions.hostCaptureOptions(captureVideo = true, env = { null }).captureVideo)
  }

  @Test
  fun `TRAILBLAZE_CAPTURE_VIDEO turns video on for a caller that did not ask for it`() {
    // The lever CI reaches for: a pipeline can't pass --capture-video (its trails are launched by
    // scripts it doesn't own), so the env var has to be able to override the off default alone.
    for (truthy in listOf("1", "true", "TRUE", "True")) {
      val env = mapOf(CaptureOptions.ENV_CAPTURE_VIDEO to truthy)
      assertTrue(
        CaptureOptions.hostCaptureOptions(captureVideo = null, env = env::get).captureVideo,
        "'$truthy' should read as an opt-in",
      )
    }
  }

  @Test
  fun `an absent, blank, or falsey TRAILBLAZE_CAPTURE_VIDEO leaves video off`() {
    // A malformed value must never silently switch a large-artifact stream on.
    for (falsey in listOf(null, "", "  ", "0", "false", "no", "yes", "on")) {
      val env = mapOf(CaptureOptions.ENV_CAPTURE_VIDEO to falsey)
      assertFalse(
        CaptureOptions.hostCaptureOptions(captureVideo = null, env = env::get).captureVideo,
        "'$falsey' should not read as an opt-in",
      )
    }
  }

  @Test
  fun `TRAILBLAZE_CAPTURE_VIDEO does not disturb a caller that already opted in`() {
    val env = mapOf(CaptureOptions.ENV_CAPTURE_VIDEO to "0")
    assertTrue(CaptureOptions.hostCaptureOptions(captureVideo = true, env = env::get).captureVideo)
  }

  @Test
  fun `an explicit no-video beats TRAILBLAZE_CAPTURE_VIDEO and the saved config`() {
    // `--no-capture-video` is how a developer opts out of a lane that exports the env var, or of
    // their own `trailblaze config capture-video true`. If the lower tiers could override it, the
    // documented CLI-over-environment precedence would be a lie and the flag would do nothing.
    val env = mapOf(CaptureOptions.ENV_CAPTURE_VIDEO to "1")
    assertFalse(
      CaptureOptions.hostCaptureOptions(
        captureVideo = false,
        persistedCaptureVideo = true,
        env = env::get,
      ).captureVideo,
    )
  }

  @Test
  fun `the saved config turns video on when nothing higher has an opinion`() {
    // `trailblaze config capture-video true` is the only opt-in reachable from interactive
    // `session start` and MCP, which have no per-run flag to pass.
    assertTrue(
      CaptureOptions.hostCaptureOptions(
        captureVideo = null,
        persistedCaptureVideo = true,
        env = { null },
      ).captureVideo,
    )
  }

  @Test
  fun `TRAILBLAZE_CAPTURE_VIDEO turns video on over a saved config that leaves it off`() {
    // The tier between the flag and the config: a CI lane exports the env var without touching
    // the developer's persisted settings.
    val env = mapOf(CaptureOptions.ENV_CAPTURE_VIDEO to "1")
    assertTrue(
      CaptureOptions.hostCaptureOptions(
        captureVideo = null,
        persistedCaptureVideo = false,
        env = env::get,
      ).captureVideo,
    )
  }

  @Test
  fun `memory diagnostics are off unless TRAILBLAZE_MEMORY_DIAGNOSTICS opts in`() {
    assertFalse(CaptureOptions().memoryDiagnostics)
    assertFalse(CaptureOptions.hostCaptureOptions(env = { null }).memoryDiagnostics)
    for (falsey in listOf("", " ", "0", "false", "yes")) {
      val env = mapOf(CaptureOptions.ENV_MEMORY_DIAGNOSTICS to falsey)
      assertFalse(CaptureOptions.hostCaptureOptions(env = env::get).memoryDiagnostics, "value '$falsey'")
    }
    for (truthy in listOf("1", "true", "TRUE", " true ")) {
      val env = mapOf(CaptureOptions.ENV_MEMORY_DIAGNOSTICS to truthy)
      assertTrue(CaptureOptions.hostCaptureOptions(env = env::get).memoryDiagnostics, "value '$truthy'")
    }
  }

  @Test
  fun `an explicit diagnostics choice beats TRAILBLAZE_MEMORY_DIAGNOSTICS`() {
    val on = mapOf(CaptureOptions.ENV_MEMORY_DIAGNOSTICS to "true")
    assertFalse(CaptureOptions.hostCaptureOptions(memoryDiagnostics = false, env = on::get).memoryDiagnostics)
    assertTrue(CaptureOptions.hostCaptureOptions(memoryDiagnostics = true, env = { null }).memoryDiagnostics)
  }
}
