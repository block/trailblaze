package xyz.block.trailblaze.trailrunner

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.toolcalls.commands.MaestroTrailblazeTool
import xyz.block.trailblaze.yaml.TrailblazeToolYamlWrapper
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import xyz.block.trailblaze.yaml.unified.UnifiedTrail
import xyz.block.trailblaze.yaml.unified.UnifiedTrailConfig
import xyz.block.trailblaze.yaml.unified.UnifiedTrailStep

/**
 * A step-range recording rewrites a trail a human is free to edit, so [saveBackRefusal] is the gate
 * between "the run finished" and "the file may be rewritten". What the recording itself can get
 * wrong is the writer's business; these are the facts only the session knows.
 */
class SaveBackRefusalTest {

  private fun withTrailFile(block: (File) -> Unit) {
    val dir = createTempDirectory("tb-save-back").toFile()
    try {
      block(File(dir, "checkout.trail.yaml").apply { writeText("config:\n  title: Checkout\ntrail:\n  - step: Pay\n") })
    } finally {
      dir.deleteRecursively()
    }
  }

  @Test
  fun `a session that succeeded saves back`() = withTrailFile { file ->
    assertNull(saveBackRefusal(SessionStatus.Ended.Succeeded(durationMs = 1_000), file))
  }

  @Test
  fun `a session that needed self-heal to pass still saves back`() = withTrailFile { file ->
    // Self-heal is how a recording gets repaired, so refusing it would refuse the case that most
    // needs re-recording.
    assertNull(saveBackRefusal(SessionStatus.Ended.SucceededWithSelfHeal(durationMs = 1_000), file))
  }

  @Test
  fun `a failed session does not overwrite the recording it was re-recording`() = withTrailFile { file ->
    val refusal = saveBackRefusal(
      SessionStatus.Ended.Failed(durationMs = 1_000, exceptionMessage = "step 3 timed out"),
      file,
    )
    assertNotNull(refusal)
    assertTrue(refusal.contains("failed"), refusal)
  }

  @Test
  fun `a cancelled session does not overwrite it either`() = withTrailFile { file ->
    val refusal = saveBackRefusal(
      SessionStatus.Ended.Cancelled(durationMs = 1_000, cancellationMessage = null),
      file,
    )
    assertNotNull(refusal)
    assertTrue(refusal.contains("cancelled"), refusal)
  }

  @Test
  fun `a run still reporting Started is not a recording yet`() = withTrailFile { file ->
    // The on-device runner's completion callback fires the moment its RPC returns, which is why the
    // session's own status is what this gate reads.
    val refusal = saveBackRefusal(SessionStatus.Unknown, file)
    assertNotNull(refusal)
    assertTrue(refusal.contains("never reported a finished status"), refusal)
  }

  @Test
  fun `a trail renamed mid-run is not recreated as a fresh file`() = withTrailFile { file ->
    // Left to the writer, an absent named file resolves to the folder's shared trail.yaml.
    file.delete()
    val refusal = saveBackRefusal(SessionStatus.Ended.Succeeded(durationMs = 1_000), file)
    assertNotNull(refusal)
    assertTrue(refusal.contains("renamed or deleted"), refusal)
  }

  @Test
  fun `a whole-trail recording keeps a trailhead's raw Maestro step`() = withRawMaestroTrailhead { file, trail ->
    // The whole trail replays its trailhead, and the raw step it replays is never recorded.
    val refusal = rawMaestroTrailheadRefusal(file, trail, listOf("android", "phone"))
    assertNotNull(refusal)
    assertTrue(refusal.contains("mobile_maestro"), refusal)
  }

  @Test
  fun `a narrowed range runs no trailhead, so it may save back`() = withRawMaestroTrailhead { file, trail ->
    assertNull(rawMaestroTrailheadRefusal(file, trail.copy(trailhead = null), listOf("android")))
  }

  @Test
  fun `another device's raw Maestro trailhead does not block this one`() = withRawMaestroTrailhead { file, trail ->
    assertNull(rawMaestroTrailheadRefusal(file, trail, listOf("ios")))
  }

  /** A unified trail whose `android` trailhead is a raw Maestro step and whose `ios` one is typed. */
  private fun withRawMaestroTrailhead(block: (File, UnifiedTrail) -> Unit) {
    val trail = UnifiedTrail(
      config = UnifiedTrailConfig(id = "x", target = "y"),
      trailhead = UnifiedTrailStep(
        step = "Sign in",
        recordings = mapOf(
          "android" to listOf(TrailblazeToolYamlWrapper("mobile_maestro", MaestroTrailblazeTool(yaml = "- back\n"))),
          "ios" to listOf(typed("myapp_signIn")),
        ),
      ),
      trail = listOf(UnifiedTrailStep(step = "Pay", recordings = mapOf("android" to listOf(typed("myapp_pay"))))),
    )
    withTrailFile { file ->
      file.writeText(createTrailblazeYaml().encodeUnifiedTrailToString(trail))
      block(file, trail)
    }
  }

  private fun typed(name: String) =
    TrailblazeToolYamlWrapper(name, OtherTrailblazeTool(toolName = name, raw = JsonObject(emptyMap())))
}
