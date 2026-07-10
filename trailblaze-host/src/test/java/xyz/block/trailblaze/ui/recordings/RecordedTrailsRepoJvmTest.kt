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
 * Contract tests for the desktop recording tab's save path under the unified-recordings rollout
 * gate. Gate off keeps the legacy `<classifier>.trail.yaml` write byte-identical for a plain
 * directory and refuses to shadow a migrated one; gate on merges the classifier slot into the
 * unified `trail.yaml`. The gate is injected, so these need no daemon or persisted config.
 */
class RecordedTrailsRepoJvmTest {

  @get:Rule val tempFolder = TemporaryFolder()

  private val trailsRoot: File get() = tempFolder.root

  @Test
  fun `gate off writes a legacy classifier sibling in a plain trail directory`() {
    val repo = RecordedTrailsRepoJvm(trailsDirectory = trailsRoot, unifiedRecordingsEnabledProvider = { false })

    val result = repo.saveRecording(v1RecordingYaml("tapCart"), sessionInfo("flows/login", listOf("android")))

    assertTrue(result.isSuccess, "save failed: ${result.exceptionOrNull()?.message}")
    assertTrue(File(trailsRoot, "flows/login/android.trail.yaml").isFile, "expected the legacy sibling")
    assertFalse(File(trailsRoot, "flows/login/${TrailRecordings.UNIFIED_TRAIL_FILENAME}").exists())
  }

  @Test
  fun `gate off refuses to write a legacy sibling next to a unified trail`() {
    val trailDir = File(trailsRoot, "flows/login").apply { mkdirs() }
    val unified = File(trailDir, TrailRecordings.UNIFIED_TRAIL_FILENAME)
      .apply { writeText("config:\n  id: flows/login\ntrail:\n  - step: Open the cart\n") }
    val bytesBefore = unified.readBytes()
    val repo = RecordedTrailsRepoJvm(trailsDirectory = trailsRoot, unifiedRecordingsEnabledProvider = { false })

    val result = repo.saveRecording(v1RecordingYaml("tapCart"), sessionInfo("flows/login", listOf("android")))

    assertTrue(result.isFailure, "gate-off save must be refused next to a unified trail")
    assertFalse(File(trailDir, "android.trail.yaml").exists(), "no legacy sibling dropped beside the unified trail")
    assertEquals(bytesBefore.toList(), unified.readBytes().toList(), "the unified trail must be left untouched")
  }

  @Test
  fun `gate on merges the classifier slot preserving other classifiers`() {
    val repo = RecordedTrailsRepoJvm(trailsDirectory = trailsRoot, unifiedRecordingsEnabledProvider = { true })
    // First device seeds the unified file; second device merges into the same step.
    assertTrue(repo.saveRecording(v1RecordingYaml("iosCart"), sessionInfo("flows/login", listOf("ios"))).isSuccess)

    val result = repo.saveRecording(v1RecordingYaml("androidCart"), sessionInfo("flows/login", listOf("android")))

    assertTrue(result.isSuccess, "merge save failed: ${result.exceptionOrNull()?.message}")
    val unifiedFile = File(trailsRoot, "flows/login/${TrailRecordings.UNIFIED_TRAIL_FILENAME}")
    assertTrue(unifiedFile.isFile, "the classifier slot must merge into the unified trail.yaml")
    assertFalse(File(trailsRoot, "flows/login/android.trail.yaml").exists(), "no legacy sibling when routing unified")
    val step = createTrailblazeYaml().decodeUnifiedTrail(unifiedFile.readText()).trail.single()
    assertEquals(listOf("iosCart"), step.recordings["ios"]?.map { it.name }, "ios slot preserved")
    assertEquals(listOf("androidCart"), step.recordings["android"]?.map { it.name }, "android slot merged in")
  }

  @Test
  fun `gate on refuses a corrupt existing unified trail untouched`() {
    val trailDir = File(trailsRoot, "flows/login").apply { mkdirs() }
    val corrupt = File(trailDir, TrailRecordings.UNIFIED_TRAIL_FILENAME).apply { writeText("foo: not a unified trail\n") }
    val repo = RecordedTrailsRepoJvm(trailsDirectory = trailsRoot, unifiedRecordingsEnabledProvider = { true })

    val result = repo.saveRecording(v1RecordingYaml("tapCart"), sessionInfo("flows/login", listOf("android")))

    assertTrue(result.isFailure, "a corrupt unified trail must not be clobbered by a merge")
    assertEquals("foo: not a unified trail\n", corrupt.readText(), "the corrupt file must be left untouched")
  }

  @Test
  fun `gate on refuses a multi-tool-trailhead recording that would shadow a unified trail`() {
    // A recording whose trailhead has >1 tool can't be represented in the unified format. When a
    // unified trail already exists here, dropping a legacy sibling would shadow it — refuse instead.
    val trailDir = File(trailsRoot, "flows/login").apply { mkdirs() }
    File(trailDir, TrailRecordings.UNIFIED_TRAIL_FILENAME)
      .writeText("config:\n  id: flows/login\ntrail:\n  - step: Open the cart\n")
    val repo = RecordedTrailsRepoJvm(trailsDirectory = trailsRoot, unifiedRecordingsEnabledProvider = { true })

    val result = repo.saveRecording(
      v1RecordingYamlWithMultiToolTrailhead(listOf("clearBootstrap", "openBootstrap")),
      sessionInfo("flows/login", listOf("android")),
    )

    assertTrue(result.isFailure, "a multi-tool trailhead must not drop a legacy sibling next to a unified trail")
    assertFalse(File(trailDir, "android.trail.yaml").exists(), "no shadowing legacy sibling")
  }

  @Test
  fun `null trail id writes a session-scoped file without routing`() {
    // No trail identity → the session-scoped fallback (byte-identical to pre-unified), never routed
    // and never occupying a per-test unified trail.yaml.
    val repo = RecordedTrailsRepoJvm(trailsDirectory = trailsRoot, unifiedRecordingsEnabledProvider = { true })

    val result = repo.saveRecording(v1RecordingYaml("tapCart"), sessionInfo(trailId = null, classifiers = listOf("android")))

    assertTrue(result.isSuccess, "fallback save failed: ${result.exceptionOrNull()?.message}")
    val saved = File(result.getOrThrow())
    assertEquals("android.trail.yaml", saved.name)
    assertTrue(saved.isFile)
    assertTrue(saved.absolutePath.contains("test-session"), "should land under the session-scoped directory")
  }

  // --- fixtures ---

  private fun v1RecordingYaml(toolName: String): String =
    createTrailblazeYaml().encodeToString(
      listOf(
        TrailYamlItem.ConfigTrailItem(TrailConfig(id = "flows/login", target = "app", driver = "D")),
        TrailYamlItem.PromptsTrailItem(
          listOf(DirectionStep(step = "Open the cart", recording = ToolRecording(tools = listOf(tool(toolName))))),
        ),
      ),
    )

  /** A v1 recording whose trailhead carries [toolNames] (>1 has no unified representation). */
  private fun v1RecordingYamlWithMultiToolTrailhead(toolNames: List<String>): String =
    createTrailblazeYaml().encodeToString(
      listOf(
        TrailYamlItem.ConfigTrailItem(TrailConfig(id = "flows/login", target = "app", driver = "D")),
        TrailYamlItem.TrailheadTrailItem(TrailheadDefinition(step = "Bootstrap", tools = toolNames.map { tool(it) })),
        TrailYamlItem.PromptsTrailItem(
          listOf(DirectionStep(step = "Open the cart", recording = ToolRecording(tools = listOf(tool("tapCart"))))),
        ),
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
