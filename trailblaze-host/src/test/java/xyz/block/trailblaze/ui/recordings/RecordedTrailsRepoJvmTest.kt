package xyz.block.trailblaze.ui.recordings

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionInfo
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.recordings.TrailRecordings
import xyz.block.trailblaze.yaml.DirectionStep
import xyz.block.trailblaze.yaml.ToolRecording
import xyz.block.trailblaze.yaml.TrailConfig
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.TrailblazeToolYamlWrapper
import xyz.block.trailblaze.yaml.TrailheadDefinition
import xyz.block.trailblaze.yaml.createTrailblazeYaml

/**
 * Contract tests for the desktop recording tab's save path. Every destination holds unified YAML —
 * the routing choice is only which FILE: a directory that already uses per-classifier siblings gets
 * the device's own `<classifier>.trail.yaml`, everything else merges the classifier slot into the
 * shared `trail.yaml`. Runs against a temp directory; no daemon or persisted config needed.
 */
class RecordedTrailsRepoJvmTest {

  @get:Rule val tempFolder = TemporaryFolder()

  private val trailsRoot: File get() = tempFolder.root

  @Test
  fun `a directory that already holds per-classifier siblings gets this device's own file`() {
    val trailDir = File(trailsRoot, "flows/login").apply { mkdirs() }
    File(trailDir, "ios.trail.yaml").writeText("config:\n  id: flows/login\ntrail:\n  - step: Open the cart\n")
    val repo = RecordedTrailsRepoJvm(trailsDirectory = trailsRoot)

    val result = repo.saveRecording(recordingItems("tapCart"), sessionInfo("flows/login", listOf("android")))

    assertTrue(result.isSuccess, "save failed: ${result.exceptionOrNull()?.message}")
    val sibling = File(trailDir, "android.trail.yaml")
    assertTrue(sibling.isFile, "expected the device's own sibling")
    assertFalse(File(trailDir, TrailRecordings.UNIFIED_TRAIL_FILENAME).exists(), "no shared trail.yaml forked")
    // The sibling is itself a unified document holding just this device's slot.
    val step = createTrailblazeYaml().decodeUnifiedTrail(sibling.readText()).trail.single()
    assertEquals(listOf("tapCart"), step.recordings["android"]?.map { it.name })
  }

  @Test
  fun `merges the classifier slot preserving other classifiers`() {
    val repo = RecordedTrailsRepoJvm(trailsDirectory = trailsRoot)
    // First device seeds the unified file; second device merges into the same step.
    assertTrue(repo.saveRecording(recordingItems("iosCart"), sessionInfo("flows/login", listOf("ios"))).isSuccess)

    val result = repo.saveRecording(recordingItems("androidCart"), sessionInfo("flows/login", listOf("android")))

    assertTrue(result.isSuccess, "merge save failed: ${result.exceptionOrNull()?.message}")
    val unifiedFile = File(trailsRoot, "flows/login/${TrailRecordings.UNIFIED_TRAIL_FILENAME}")
    assertTrue(unifiedFile.isFile, "the classifier slot must merge into the unified trail.yaml")
    assertFalse(File(trailsRoot, "flows/login/android.trail.yaml").exists(), "no sibling when routing unified")
    val step = createTrailblazeYaml().decodeUnifiedTrail(unifiedFile.readText()).trail.single()
    assertEquals(listOf("iosCart"), step.recordings["ios"]?.map { it.name }, "ios slot preserved")
    assertEquals(listOf("androidCart"), step.recordings["android"]?.map { it.name }, "android slot merged in")
  }

  @Test
  fun `refuses a corrupt existing unified trail untouched`() {
    val trailDir = File(trailsRoot, "flows/login").apply { mkdirs() }
    val corrupt = File(trailDir, TrailRecordings.UNIFIED_TRAIL_FILENAME).apply { writeText("foo: not a unified trail\n") }
    val repo = RecordedTrailsRepoJvm(trailsDirectory = trailsRoot)

    val result = repo.saveRecording(recordingItems("tapCart"), sessionInfo("flows/login", listOf("android")))

    assertTrue(result.isFailure, "a corrupt unified trail must not be clobbered by a merge")
    assertEquals("foo: not a unified trail\n", corrupt.readText(), "the corrupt file must be left untouched")
  }

  @Test
  fun `refuses a multi-tool-trailhead recording rather than writing a shadowing sibling`() {
    // A recording whose trailhead has >1 tool can't be represented in the unified format, and a
    // sibling dropped here would shadow the existing unified trail — refuse instead.
    val trailDir = File(trailsRoot, "flows/login").apply { mkdirs() }
    File(trailDir, TrailRecordings.UNIFIED_TRAIL_FILENAME)
      .writeText("config:\n  id: flows/login\ntrail:\n  - step: Open the cart\n")
    val repo = RecordedTrailsRepoJvm(trailsDirectory = trailsRoot)

    val result = repo.saveRecording(
      recordingItemsWithMultiToolTrailhead(listOf("clearBootstrap", "openBootstrap")),
      sessionInfo("flows/login", listOf("android")),
    )

    assertTrue(result.isFailure, "a multi-tool trailhead must not drop a sibling next to a unified trail")
    assertFalse(File(trailDir, "android.trail.yaml").exists(), "no shadowing sibling")
  }

  @Test
  fun `null trail id writes a session-scoped file without routing`() {
    // No trail identity → the session-scoped fallback: never routed, never occupying a per-test
    // unified trail.yaml.
    val repo = RecordedTrailsRepoJvm(trailsDirectory = trailsRoot)

    val result = repo.saveRecording(recordingItems("tapCart"), sessionInfo(trailId = null, classifiers = listOf("android")))

    assertTrue(result.isSuccess, "fallback save failed: ${result.exceptionOrNull()?.message}")
    val saved = File(result.getOrThrow())
    assertEquals("android.trail.yaml", saved.name)
    assertTrue(saved.isFile)
    assertTrue(saved.absolutePath.contains("test-session"), "should land under the session-scoped directory")
  }

  // --- fixtures ---

  private fun recordingItems(toolName: String): List<TrailYamlItem> =
    listOf(
      TrailYamlItem.ConfigTrailItem(TrailConfig(id = "flows/login", target = "app", driver = "D")),
      TrailYamlItem.PromptsTrailItem(
        listOf(DirectionStep(step = "Open the cart", recording = ToolRecording(tools = listOf(tool(toolName))))),
      ),
    )

  /** A recording whose trailhead carries [toolNames] (>1 has no unified representation). */
  private fun recordingItemsWithMultiToolTrailhead(toolNames: List<String>): List<TrailYamlItem> =
    listOf(
      TrailYamlItem.ConfigTrailItem(TrailConfig(id = "flows/login", target = "app", driver = "D")),
      TrailYamlItem.TrailheadTrailItem(TrailheadDefinition(step = "Bootstrap", tools = toolNames.map { tool(it) })),
      TrailYamlItem.PromptsTrailItem(
        listOf(DirectionStep(step = "Open the cart", recording = ToolRecording(tools = listOf(tool("tapCart"))))),
      ),
    )

  private fun tool(name: String) = TrailblazeToolYamlWrapper(
    name = name,
    trailblazeTool = OtherTrailblazeTool(toolName = name, raw = JsonObject(mapOf("marker" to JsonPrimitive(name)))),
  )

  private fun sessionInfo(trailId: String?, classifiers: List<String>): SessionInfo = SessionInfo(
    sessionId = SessionId("test-session"),
    latestStatus = SessionStatus.Unknown,
    timestamp = Instant.fromEpochMilliseconds(0),
    durationMs = 0L,
    trailFilePath = null,
    hasRecordedSteps = true,
    trailblazeDeviceInfo = TrailblazeDeviceInfo(
      trailblazeDeviceId = TrailblazeDeviceId(
        instanceId = "test-device",
        trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
      ),
      trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
      widthPixels = 100,
      heightPixels = 200,
      classifiers = classifiers.map { TrailblazeDeviceClassifier(it) },
    ),
    trailConfig = trailId?.let { TrailConfig(id = it) },
  )
}
