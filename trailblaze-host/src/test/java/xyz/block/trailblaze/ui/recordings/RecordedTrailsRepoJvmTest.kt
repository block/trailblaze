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
  fun `merges a multi-tool-trailhead recording into the unified trail without a sibling`() {
    // A trailhead slot holds one tool, so a recording with more is mapped onto that shape — the
    // extras replay in the first step. It merges into the unified trail like any other recording,
    // rather than being refused (which used to cost the whole recording).
    val trailDir = File(trailsRoot, "flows/login").apply { mkdirs() }
    File(trailDir, TrailRecordings.UNIFIED_TRAIL_FILENAME)
      .writeText("config:\n  id: flows/login\ntrail:\n  - step: Open the cart\n")
    val repo = RecordedTrailsRepoJvm(trailsDirectory = trailsRoot)

    val result = repo.saveRecording(
      recordingItemsWithMultiToolTrailhead(listOf("clearBootstrap", "openBootstrap")),
      sessionInfo("flows/login", listOf("android")),
    )

    assertTrue(result.isSuccess, "merge save failed: ${result.exceptionOrNull()?.message}")
    assertFalse(File(trailDir, "android.trail.yaml").exists(), "no shadowing sibling")
    val unified = createTrailblazeYaml()
      .decodeUnifiedTrail(File(trailDir, TrailRecordings.UNIFIED_TRAIL_FILENAME).readText())
    assertEquals(listOf("clearBootstrap"), unified.trailhead?.recordings?.get("android")?.map { it.name })
    assertEquals(
      listOf("openBootstrap", "tapCart"),
      unified.trail.single().recordings["android"]?.map { it.name },
    )
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

  @Test
  fun `a configuration session merges its leg under the configuration name`() {
    val trailDir = File(trailsRoot, "flows/login").apply { mkdirs() }
    File(trailDir, TrailRecordings.UNIFIED_TRAIL_FILENAME).writeText(multiDeviceTrailYaml)
    val repo = RecordedTrailsRepoJvm(trailsDirectory = trailsRoot)

    val result = repo.saveRecording(
      recordingItems("tapCart"),
      sessionInfo("flows/login", listOf("android"), selectedDeviceConfiguration = "pos-pair"),
    )

    assertTrue(result.isSuccess, "configuration save failed: ${result.exceptionOrNull()?.message}")
    val unified = createTrailblazeYaml()
      .decodeUnifiedTrail(File(trailDir, TrailRecordings.UNIFIED_TRAIL_FILENAME).readText())
    assertEquals(
      listOf("tapCart"),
      unified.trail.single().recordings["pos-pair"]?.map { it.name },
      "the leg must land under the configuration name, not the launch device's classifier",
    )
    assertEquals(null, unified.trail.single().recordings["android"], "no classifier-keyed duplicate leg")
    // The authored cast survives, and the launch device's driver is not pinned onto it — a
    // configuration entry can't carry one.
    val configurationEntry = unified.config.devices?.get("pos-pair")
    assertTrue(configurationEntry?.isConfiguration == true, "the authored cast must survive the merge")
    assertEquals(null, configurationEntry?.driver, "a configuration entry must never carry a driver pin")
  }

  @Test
  fun `a single-device session is still refused on a multi-device trail`() {
    val trailDir = File(trailsRoot, "flows/login").apply { mkdirs() }
    val existing = File(trailDir, TrailRecordings.UNIFIED_TRAIL_FILENAME).apply { writeText(multiDeviceTrailYaml) }
    val repo = RecordedTrailsRepoJvm(trailsDirectory = trailsRoot)

    val result = repo.saveRecording(recordingItems("tapCart"), sessionInfo("flows/login", listOf("android")))

    assertTrue(result.isFailure, "a classifier leg would duplicate the configuration's steps")
    assertEquals(multiDeviceTrailYaml, existing.readText(), "the refused save must leave the trail untouched")
  }

  @Test
  fun `a configuration session is refused in a directory holding only per-classifier siblings`() {
    // Merging is not the same as writing. `shouldMergeIntoSharedTrail` says no here (a sibling
    // layout, no trail.yaml), and forking a fresh unified trail beside the sibling would leave both
    // layouts on disk with the sibling permanently stale — the mirror image of the sibling-shadow
    // refusal the single-device path already makes.
    val trailDir = File(trailsRoot, "flows/login").apply { mkdirs() }
    val sibling = File(trailDir, "android.trail.yaml").apply { writeText("config:\n  id: flows/login\ntrail: []\n") }
    val repo = RecordedTrailsRepoJvm(trailsDirectory = trailsRoot)

    val result = repo.saveRecording(
      recordingItems("tapCart"),
      sessionInfo("flows/login", listOf("android"), selectedDeviceConfiguration = "pos-pair"),
    )

    assertTrue(result.isFailure, "a configuration has no cast to key against in a sibling-only directory")
    assertFalse(
      File(trailDir, TrailRecordings.UNIFIED_TRAIL_FILENAME).exists(),
      "no forked unified trail beside the sibling",
    )
    assertTrue(sibling.isFile, "the existing sibling must be left untouched")
  }

  @Test
  fun `a configuration session is refused on a trail that declares a different cast`() {
    // Saving back to the wrong destination: the legs would be keyed by a name this trail's replay
    // resolves to nothing, so the file would be unreachable while the save reported success.
    val trailDir = File(trailsRoot, "flows/login").apply { mkdirs() }
    val existing = File(trailDir, TrailRecordings.UNIFIED_TRAIL_FILENAME).apply { writeText(multiDeviceTrailYaml) }
    val repo = RecordedTrailsRepoJvm(trailsDirectory = trailsRoot)

    val result = repo.saveRecording(
      recordingItems("tapCart"),
      sessionInfo("flows/login", listOf("android"), selectedDeviceConfiguration = "web-phone"),
    )

    assertTrue(result.isFailure, "`web-phone` is not declared by this trail")
    assertEquals(multiDeviceTrailYaml, existing.readText(), "the refused save must leave the trail untouched")
  }

  @Test
  fun `a configuration name that is not one path segment is refused`() {
    // The name is BOTH the recording slot key and a filename component on the session-scoped
    // fallback, and unlike a classifier chain it is author-written. Without this it escapes the
    // session directory.
    val repo = RecordedTrailsRepoJvm(trailsDirectory = trailsRoot)

    val result = repo.saveRecording(
      recordingItems("tapCart"),
      sessionInfo(trailId = null, classifiers = listOf("android"), selectedDeviceConfiguration = "../../victim"),
    )

    assertTrue(result.isFailure, "a configuration name with path separators must not reach the filesystem")
    assertFalse(File(trailsRoot.parentFile, "victim.trail.yaml").exists(), "nothing written outside the trails root")
  }

  // --- fixtures ---

  /** A trail whose only recording legs are keyed by the `pos-pair` configuration it declares. */
  private val multiDeviceTrailYaml =
    """
    config:
      id: flows/login
      devices:
        pos-pair:
          devices:
            seller:
              classifier: lab-a
            buyer:
              classifier: lab-b
    trail:
      - step: Open the cart
        recording:
          pos-pair:
            - tapTip: {}
    """.trimIndent() + "\n"


  private fun recordingItems(toolName: String): List<TrailYamlItem> =
    listOf(
      TrailYamlItem.ConfigTrailItem(TrailConfig(id = "flows/login", target = "app", driver = "ANDROID_ONDEVICE_INSTRUMENTATION")),
      TrailYamlItem.PromptsTrailItem(
        listOf(DirectionStep(step = "Open the cart", recording = ToolRecording(tools = listOf(tool(toolName))))),
      ),
    )

  /** A recording whose trailhead carries [toolNames] (>1 spills into the first step on merge). */
  private fun recordingItemsWithMultiToolTrailhead(toolNames: List<String>): List<TrailYamlItem> =
    listOf(
      TrailYamlItem.ConfigTrailItem(TrailConfig(id = "flows/login", target = "app", driver = "ANDROID_ONDEVICE_INSTRUMENTATION")),
      TrailYamlItem.TrailheadTrailItem(TrailheadDefinition(step = "Bootstrap", tools = toolNames.map { tool(it) })),
      TrailYamlItem.PromptsTrailItem(
        listOf(DirectionStep(step = "Open the cart", recording = ToolRecording(tools = listOf(tool("tapCart"))))),
      ),
    )

  private fun tool(name: String) = TrailblazeToolYamlWrapper(
    name = name,
    trailblazeTool = OtherTrailblazeTool(toolName = name, raw = JsonObject(mapOf("marker" to JsonPrimitive(name)))),
  )

  private fun sessionInfo(
    trailId: String?,
    classifiers: List<String>,
    selectedDeviceConfiguration: String? = null,
  ): SessionInfo = SessionInfo(
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
    selectedDeviceConfiguration = selectedDeviceConfiguration,
  )
}
