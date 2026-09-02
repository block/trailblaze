package xyz.block.trailblaze.trailrunner

import io.ktor.http.HttpStatusCode
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import xyz.block.trailblaze.yaml.unified.UnifiedTrailAdapter

/**
 * [buildRecordTrailRangeResponse] is the only path that can hand a run write authority over a trail
 * file (`RunRequest.recordTrailFile`), so what it refuses matters as much as what it runs: the trail
 * id it accepts must resolve inside the trails roots, and the step range must exist in the file.
 *
 * These cases all refuse before dispatching, which is why they can run without a device manager.
 */
class RecordTrailRangeTest {

  private val deviceId = TrailblazeDeviceId(
    instanceId = "emulator-5554",
    trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
  )

  private val threeStepTrail = """
    config:
      title: Checkout
    trail:
      - step: Open the app
      - step: Add an item
      - step: Pay
  """.trimIndent()

  private fun withTrailsRoot(block: suspend (TrailRunnerDeps, File) -> Unit) {
    val root = createTempDirectory("tb-record-range").toFile()
    try {
      val trailsDir = File(root, "trails").apply { mkdirs() }
      val deps = TrailRunnerDeps(
        trailsRootProvider = { trailsDir },
        logsRepo = LogsRepo(logsDir = File(root, "logs").apply { mkdirs() }, watchFileSystem = false),
        settingsRepo = null,
        deviceManager = null,
        integrationsProvider = null,
        integrationActionHandler = null,
        analyticsProvider = null,
        analyticsCaptureStarter = null,
        eventCaptureController = null,
        toolExecutor = null,
      )
      runBlocking { block(deps, trailsDir) }
    } finally {
      root.deleteRecursively()
    }
  }

  // A trail id names the file WITHOUT its `.trail.yaml` suffix, the same shape the index hands the
  // web UI and resolveTrailFile appends.
  private fun request(id: String, from: Int, to: Int, devices: List<TrailblazeDeviceId> = listOf(deviceId)) =
    RecordTrailRangeRequest(id = id, deviceIds = devices, from = from, to = to)

  @Test
  fun `an id that climbs out of the trails root is refused`() {
    withTrailsRoot { deps, trailsDir ->
      // A real trail outside the root, named by a traversal id: the containment check has to refuse
      // it rather than hand a run write authority over a file no trails root contains.
      File(trailsDir.parentFile, "outside.trail.yaml").writeText(threeStepTrail)

      val outcome = buildRecordTrailRangeResponse(deps, request("../outside", 0, 0))

      assertTrue(outcome.body.sessionIds.isEmpty(), "nothing should be dispatched")
      assertEquals(HttpStatusCode.BadRequest, outcome.status)
      assertNotNull(outcome.body.error)
      assertTrue(outcome.body.error!!.contains("not found"), "unexpected error: ${outcome.body.error}")
    }
  }

  @Test
  fun `an unknown trail id is refused`() {
    withTrailsRoot { deps, _ ->
      val outcome = buildRecordTrailRangeResponse(deps, request("nope/missing", 0, 0))

      assertEquals(HttpStatusCode.BadRequest, outcome.status)
      assertTrue(outcome.body.sessionIds.isEmpty())
      assertTrue(outcome.body.error!!.contains("not found"), "unexpected error: ${outcome.body.error}")
    }
  }

  @Test
  fun `an empty device list is refused`() {
    withTrailsRoot { deps, trailsDir ->
      File(trailsDir, "checkout.trail.yaml").writeText(threeStepTrail)

      val outcome = buildRecordTrailRangeResponse(deps, request("checkout", 0, 2, devices = emptyList()))

      assertEquals(HttpStatusCode.BadRequest, outcome.status)
      assertTrue(outcome.body.sessionIds.isEmpty())
      assertTrue(outcome.body.error!!.contains("device"), "unexpected error: ${outcome.body.error}")
    }
  }

  @Test
  fun `a step range outside the trail is refused`() {
    withTrailsRoot { deps, trailsDir ->
      File(trailsDir, "checkout.trail.yaml").writeText(threeStepTrail)

      val outcome = buildRecordTrailRangeResponse(deps, request("checkout", 1, 5))

      assertTrue(outcome.body.sessionIds.isEmpty())
      // The message names the trail's real length: a range the file can't satisfy is the caller's
      // bug, and "outside this trail's 3 step(s)" is what tells them so.
      assertTrue(outcome.body.error!!.contains("3 step"), "unexpected error: ${outcome.body.error}")
    }
  }

  @Test
  fun `a file that is not a unified trail is refused`() {
    withTrailsRoot { deps, trailsDir ->
      // Only a unified single-file trail has per-classifier slots for a partial recording to merge
      // into. A legacy list-root document has none, so this refuses instead of writing a new shape.
      File(trailsDir, "legacy.trail.yaml").writeText("- prompts:\n    - step: Open the app\n")

      val outcome = buildRecordTrailRangeResponse(deps, request("legacy", 0, 0))

      assertEquals(HttpStatusCode.BadRequest, outcome.status)
      assertTrue(outcome.body.sessionIds.isEmpty())
      assertTrue(outcome.body.error!!.contains("isn't a unified trail file"), "unexpected error: ${outcome.body.error}")
    }
  }

  @Test
  fun `a valid range reaches dispatch and reports the device that could not start`() {
    withTrailsRoot { deps, trailsDir ->
      File(trailsDir, "checkout.trail.yaml").writeText(threeStepTrail)

      // Range and file are both fine, so this gets as far as dispatching. With no device manager the
      // dispatch itself fails, which is what proves the refusals above are about the request rather
      // than an early bail-out: the per-device failure is reported against the device.
      val outcome = buildRecordTrailRangeResponse(deps, request("checkout", 1, 2))

      assertTrue(outcome.body.sessionIds.isEmpty())
      assertNotNull(outcome.body.error)
      assertTrue(outcome.body.error!!.contains("emulator-5554"), "unexpected error: ${outcome.body.error}")
      // A well-formed request the daemon couldn't dispatch is 502, not 400: the caller asked for
      // something this trail can satisfy, and the devices are what didn't answer.
      assertEquals(HttpStatusCode.BadGateway, outcome.status)
    }
  }

  @Test
  fun `a window covering every step runs the trail as authored, trailhead included`() {
    val unified = createTrailblazeYaml().decodeUnifiedTrail(
      """
      config:
        title: Checkout
      trailhead:
        step: Launch the app
      trail:
        - step: Add an item
        - step: Pay
      """.trimIndent(),
    )

    val runnable = runnableForStepWindow(unified, 0, 1)

    // Recording the whole trail must not go through the slice: a slice drops the trailhead, so every
    // step would be recorded against an app that was never launched.
    assertNotNull(runnable)
    assertNotNull(runnable.runnable.trailhead, "the whole trail keeps its trailhead")
    assertEquals("Checkout", runnable.runnable.config.title)
    assertEquals(2, runnable.runnable.trail.size)
    // And it merges back with no window: the trailhead ran, so it has to be held to the same
    // drift check as the steps, which a window would exempt it from.
    assertEquals(null, runnable.mergeWindow, "a whole-trail recording names no window")
  }

  @Test
  fun `a narrowed window is sliced, which is what drops the trailhead`() {
    val unified = createTrailblazeYaml().decodeUnifiedTrail(
      """
      config:
        title: Checkout
      trailhead:
        step: Launch the app
      trail:
        - step: Add an item
        - step: Pay
      """.trimIndent(),
    )

    val runnable = runnableForStepWindow(unified, 1, 1)

    assertNotNull(runnable)
    // A partial run picks up from whatever is on the device's screen, so the trailhead is skipped.
    assertEquals(null, runnable.runnable.trailhead)
    assertEquals(1, runnable.runnable.trail.size)
    assertEquals(1..1, runnable.mergeWindow, "a narrowed recording merges under its own window")
  }

  @Test
  fun `a window the trail cannot satisfy has no runnable trail`() {
    val unified = createTrailblazeYaml().decodeUnifiedTrail(threeStepTrail)

    assertEquals(null, runnableForStepWindow(unified, 1, 5), "past the last step")
    assertEquals(null, runnableForStepWindow(unified, -1, 2), "before the first step")
    assertEquals(null, runnableForStepWindow(unified, 2, 1), "inverted")
  }

  @Test
  fun `the sliced trail carries only the requested steps`() {
    val unified = createTrailblazeYaml().decodeUnifiedTrail(threeStepTrail)

    val slice = UnifiedTrailAdapter.sliceTrail(unified, 1, 2)

    assertNotNull(slice)
    assertEquals(2, slice.trail.size)
    // The title says which steps ran, so a session recorded from a range is identifiable in Runs.
    assertEquals("Partial: Checkout (2-3)", slice.config.title)
  }
}
