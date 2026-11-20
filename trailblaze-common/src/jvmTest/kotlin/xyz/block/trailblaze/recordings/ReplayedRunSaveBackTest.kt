package xyz.block.trailblaze.recordings

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.yaml.DirectionStep
import xyz.block.trailblaze.yaml.ToolRecording
import xyz.block.trailblaze.yaml.TrailConfig
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.TrailblazeToolYamlWrapper
import xyz.block.trailblaze.yaml.TrailheadDefinition
import xyz.block.trailblaze.yaml.VerificationStep
import xyz.block.trailblaze.yaml.createTrailblazeYaml

/**
 * Save-back of a run that replayed most of a trail from a broader leg and left a few steps to the
 * AI: a `device-a` device reading the shared `device` recordings, on a trail whose author pinned the
 * device's driver. The save must add this device's tools to the steps the AI drove and leave every
 * other byte of the file alone.
 *
 * What the recorder captures for a replayed step is not the recording it replayed: calls that can't
 * be recorded are missing, secrets are masked, and arguments come back retyped. Writing that capture
 * as a device-specific leg forks the trail, and re-encoding the whole document to add a leg strips
 * comments and retypes the values it rewrites.
 */
class ReplayedRunSaveBackTest {

  @get:Rule val tempFolder = TemporaryFolder()

  private val yaml = createTrailblazeYaml()

  private val original =
    """
    |config:
    |  id: demo/flag-trail
    |  target: demo
    |  devices:
    |    device-a:
    |      driver: ANDROID_ONDEVICE_ACCESSIBILITY
    |    device-b:
    |      driver: ANDROID_ONDEVICE_ACCESSIBILITY
    |  context: |-
    |    Background the steps share.
    |trailhead:
    |  step: Launch signed in
    |  recording:
    |    device:
    |      demo_launchSignedIn:
    |        passcode: "1234"
    |
    |# Setup runs before the cart is built.
    |trail:
    |  - step: Enable the flag and restart
    |    recording:
    |      device:
    |        # Static setup, not a UI recording.
    |        - demo_setFlag:
    |            value: 'true'
    |        - android_adbShell:
    |            reason: Stop the app so the flag is read on the next launch.
    |            command: [am, force-stop, com.example]
    |  - step: Open the menu
    |  - verify: The menu is visible
    |
    """.trimMargin()

  /** What the recorder lowers this run to: every step, replayed ones as captured. */
  private fun recordedItems(): List<TrailYamlItem> = listOf(
    TrailYamlItem.ConfigTrailItem(
      TrailConfig(id = "demo/flag-trail", target = "demo", driver = "ANDROID_ONDEVICE_ACCESSIBILITY"),
    ),
    TrailYamlItem.TrailheadTrailItem(
      TrailheadDefinition(
        step = "Launch signed in",
        tools = listOf(tool("demo_launchSignedIn", "passcode" to JsonPrimitive("<redacted>"))),
      ),
    ),
    TrailYamlItem.PromptsTrailItem(
      listOf(
        // Replayed: the adb call isn't recordable, and the flag value comes back as a boolean.
        DirectionStep(
          step = "Enable the flag and restart",
          recording = ToolRecording(listOf(tool("demo_setFlag", "value" to JsonPrimitive(true)))),
        ),
        // AI-driven: no leg resolves for this device.
        DirectionStep(
          step = "Open the menu",
          recording = ToolRecording(listOf(tool("demo_tapMenu", "text" to JsonPrimitive("Menu")))),
        ),
        VerificationStep(
          verify = "The menu is visible",
          recording = ToolRecording(listOf(tool("demo_assertMenu", "text" to JsonPrimitive("Menu")))),
        ),
      ),
    ),
  )

  @Test
  fun `a replayed run saves only the AI-driven steps and leaves every other byte alone`() {
    val dir = tempFolder.newFolder()
    val file = File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).apply { writeText(original) }

    val outcome = UnifiedRecordingWriter.mergeIntoUnified(
      trailFileOrDir = dir,
      recordedItems = recordedItems(),
      classifier = "device-a",
      replayedRun = UnifiedRecordingWriter.ReplayedRun(deviceClassifiers = listOf("device", "a")),
    )

    assertTrue(outcome is UnifiedRecordingWriter.MergeOutcome.Merged, "got $outcome")
    val saved = file.readText()
    assertEquals(
      original
        .replace(
          "  - step: Open the menu\n",
          "  - step: Open the menu\n    recording:\n      device-a:\n        - demo_tapMenu:\n            text: Menu\n",
        )
        .replace(
          "  - verify: The menu is visible\n",
          "  - verify: The menu is visible\n    recording:\n      device-a:\n        - demo_assertMenu:\n            text: Menu\n",
        ),
      saved,
      "only this device's legs on the two AI-driven steps are added",
    )
    val trail = yaml.decodeUnifiedTrail(saved)
    assertEquals(setOf("device"), trail.trailhead?.recordings?.keys, "the replayed trailhead gains no leg")
    assertEquals(setOf("device"), trail.trail[0].recordings.keys, "the replayed setup step gains no leg")
    assertEquals(listOf("demo_tapMenu"), trail.trail[1].recordings["device-a"]?.map { it.name })
    assertEquals(listOf("demo_assertMenu"), trail.trail[2].recordings["device-a"]?.map { it.name })
  }

  @Test
  fun `a run that re-recorded every step saves them all but keeps the trailhead it replayed`() {
    // --no-use-recorded-steps drives every trail: step with the AI, but the trailhead still replays
    // its `device` leg, so its masked capture must not become this device's leg.
    val dir = tempFolder.newFolder()
    val file = File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).apply { writeText(original) }

    val outcome = UnifiedRecordingWriter.mergeIntoUnified(
      trailFileOrDir = dir,
      recordedItems = recordedItems(),
      classifier = "device-a",
      replayedRun = UnifiedRecordingWriter.ReplayedRun(deviceClassifiers = listOf("device", "a"), stepsReRecorded = true),
    )

    assertTrue(outcome is UnifiedRecordingWriter.MergeOutcome.Merged, "got $outcome")
    val trail = yaml.decodeUnifiedTrail(file.readText())
    assertEquals(setOf("device"), trail.trailhead?.recordings?.keys, "the replayed trailhead gains no leg")
    assertTrue("passcode: \"1234\"" in file.readText(), "the trailhead's secret is not replaced by its masked capture")
    assertEquals(listOf("demo_setFlag"), trail.trail[0].recordings["device-a"]?.map { it.name }, "the re-driven setup step is saved")
    assertEquals(listOf("demo_tapMenu"), trail.trail[1].recordings["device-a"]?.map { it.name })
    assertEquals(listOf("demo_assertMenu"), trail.trail[2].recordings["device-a"]?.map { it.name })
  }

  @Test
  fun `a self-healed step is rewritten and the steps that replayed cleanly are not`() {
    val dir = tempFolder.newFolder()
    val file = File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).apply { writeText(original) }

    val outcome = UnifiedRecordingWriter.mergeIntoUnified(
      trailFileOrDir = dir,
      recordedItems = recordedItems(),
      classifier = "device-a",
      replayedRun = UnifiedRecordingWriter.ReplayedRun(
        deviceClassifiers = listOf("device", "a"),
        healedSteps = setOf(0),
      ),
    )

    assertTrue(outcome is UnifiedRecordingWriter.MergeOutcome.Merged, "got $outcome")
    assertOriginalLinesKept(file.readText())
    val trail = yaml.decodeUnifiedTrail(file.readText())
    assertEquals(setOf("device"), trail.trailhead?.recordings?.keys)
    assertEquals(
      listOf("demo_setFlag"),
      trail.trail[0].recordings["device-a"]?.map { it.name },
      "the healed step takes this device's fresh recording",
    )
    assertEquals(
      listOf("demo_setFlag", "android_adbShell"),
      trail.trail[0].recordings["device"]?.map { it.name },
      "the shared leg it healed beside is untouched",
    )
  }

  @Test
  fun `a trailhead the AI drove keeps its moved tools ahead of the leg step 1 replayed`() {
    val dir = tempFolder.newFolder()
    val unrecordedTrailhead = original.replace(
      "  recording:\n    device:\n      demo_launchSignedIn:\n        passcode: \"1234\"\n",
      "",
    )
    val file = File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).apply { writeText(unrecordedTrailhead) }

    UnifiedRecordingWriter.mergeIntoUnified(
      trailFileOrDir = dir,
      recordedItems = withTrailheadToolInFirstStep(recordedItems()),
      classifier = "device-a",
      replayedRun = UnifiedRecordingWriter.ReplayedRun(listOf("device", "a"), trailheadToolsInFirstStep = 1),
    )

    val trail = yaml.decodeUnifiedTrail(file.readText())
    assertEquals(listOf("demo_launchSignedIn"), trail.trailhead?.recordings?.get("device-a")?.map { it.name })
    val leg = trail.trail[0].recordings["device-a"]
    assertEquals(
      listOf("demo_dismissPromo", "demo_setFlag", "android_adbShell"),
      leg?.map { it.name },
      "the moved tool, then the leg step 1 replayed rather than its lossy capture",
    )
    assertEquals(trail.trail[0].recordings["device"]?.get(0), leg?.get(1), "the replayed tool keeps its arguments")
  }

  @Test
  fun `a replayed trailhead's moved tools are left out of a healed first step`() {
    val dir = tempFolder.newFolder()
    val file = File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).apply { writeText(original) }

    UnifiedRecordingWriter.mergeIntoUnified(
      trailFileOrDir = dir,
      recordedItems = withTrailheadToolInFirstStep(recordedItems()),
      classifier = "device-a",
      replayedRun = UnifiedRecordingWriter.ReplayedRun(
        deviceClassifiers = listOf("device", "a"),
        healedSteps = setOf(0),
        trailheadToolsInFirstStep = 1,
      ),
    )

    val trail = yaml.decodeUnifiedTrail(file.readText())
    assertEquals(setOf("device"), trail.trailhead?.recordings?.keys)
    assertEquals(listOf("demo_setFlag"), trail.trail[0].recordings["device-a"]?.map { it.name })
  }

  /** [original] declaring only `device-a`, so nothing else reads its `device` legs. */
  private val soleDevice = original.replace(
    "    device-b:\n      driver: ANDROID_ONDEVICE_ACCESSIBILITY\n",
    "",
  )

  /** [original] with no `config.devices`, so its `device` legs are for any device of that kind. */
  private val legsOnly = original.replace(
    "  devices:\n    device-a:\n      driver: ANDROID_ONDEVICE_ACCESSIBILITY\n" +
      "    device-b:\n      driver: ANDROID_ONDEVICE_ACCESSIBILITY\n",
    "",
  )

  @Test
  fun `a heal on the only declared device rewrites the leg it replayed instead of adding its own`() {
    val dir = tempFolder.newFolder()
    val file = File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).apply { writeText(soleDevice) }

    UnifiedRecordingWriter.mergeIntoUnified(
      trailFileOrDir = dir,
      recordedItems = recordedItems(),
      classifier = "device-a",
      replayedRun = UnifiedRecordingWriter.ReplayedRun(listOf("device", "a"), healedSteps = setOf(0)),
    )

    val trail = yaml.decodeUnifiedTrail(file.readText())
    assertEquals(setOf("device"), trail.trail[0].recordings.keys, "no device-specific copy beside a leg no one else reads")
    assertEquals(listOf("demo_setFlag"), trail.trail[0].recordings["device"]?.map { it.name }, "the healed tools replace it")
    assertEquals(setOf("device"), trail.trailhead?.recordings?.keys, "the replayed trailhead is untouched")
    assertTrue("passcode: \"1234\"" in file.readText(), "the replayed trailhead keeps its bytes")
    assertEquals(
      setOf("device-a"),
      trail.trail[1].recordings.keys,
      "a step that had no leg to replay still gets this device's own",
    )
  }

  @Test
  fun `a heal forks the leg it replayed when another declared device still reads it`() {
    val dir = tempFolder.newFolder()
    val file = File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).apply { writeText(original) }

    UnifiedRecordingWriter.mergeIntoUnified(
      trailFileOrDir = dir,
      recordedItems = recordedItems(),
      classifier = "device-a",
      replayedRun = UnifiedRecordingWriter.ReplayedRun(listOf("device", "a"), healedSteps = setOf(0)),
    )

    val trail = yaml.decodeUnifiedTrail(file.readText())
    assertEquals(listOf("demo_setFlag"), trail.trail[0].recordings["device-a"]?.map { it.name })
    assertEquals(
      listOf("demo_setFlag", "android_adbShell"),
      trail.trail[0].recordings["device"]?.map { it.name },
      "device-b still replays the shared leg, so it stays as it was",
    )
  }

  @Test
  fun `a heal forks the leg it replayed when the trail declares no devices`() {
    val dir = tempFolder.newFolder()
    val file = File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).apply { writeText(legsOnly) }

    UnifiedRecordingWriter.mergeIntoUnified(
      trailFileOrDir = dir,
      recordedItems = recordedItems(),
      classifier = "device-a",
      replayedRun = UnifiedRecordingWriter.ReplayedRun(listOf("device", "a"), healedSteps = setOf(0)),
    )

    val trail = yaml.decodeUnifiedTrail(file.readText())
    assertEquals(listOf("demo_setFlag"), trail.trail[0].recordings["device-a"]?.map { it.name })
    assertEquals(
      listOf("demo_setFlag", "android_adbShell"),
      trail.trail[0].recordings["device"]?.map { it.name },
      "a broad leg on a trail that names no devices is for any device of that kind",
    )
  }

  @Test
  fun `a heal never rewrites an all leg, which every platform reads`() {
    val dir = tempFolder.newFolder()
    val file = File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME)
      .apply { writeText(soleDevice.replace("    recording:\n      device:\n        # Static", "    recording:\n      all:\n        # Static")) }

    UnifiedRecordingWriter.mergeIntoUnified(
      trailFileOrDir = dir,
      recordedItems = recordedItems(),
      classifier = "device-a",
      replayedRun = UnifiedRecordingWriter.ReplayedRun(listOf("device", "a"), healedSteps = setOf(0)),
    )

    val trail = yaml.decodeUnifiedTrail(file.readText())
    assertEquals(listOf("demo_setFlag"), trail.trail[0].recordings["device-a"]?.map { it.name })
    assertEquals(listOf("demo_setFlag", "android_adbShell"), trail.trail[0].recordings["all"]?.map { it.name })
  }

  @Test
  fun `a heal forks the leg it replayed when the trail also declares a configuration`() {
    // A configuration's start device falls back to classifier legs, so it may read `device` too.
    val dir = tempFolder.newFolder()
    val withPair = soleDevice.replace(
      "  context: |-",
      "    pair:\n      devices:\n        seller:\n          classifier: device-c\n  context: |-",
    )
    val file = File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).apply { writeText(withPair) }

    UnifiedRecordingWriter.mergeIntoUnified(
      trailFileOrDir = dir,
      recordedItems = recordedItems(),
      classifier = "device-a",
      replayedRun = UnifiedRecordingWriter.ReplayedRun(listOf("device", "a"), healedSteps = setOf(0)),
    )

    val trail = yaml.decodeUnifiedTrail(file.readText())
    assertEquals(listOf("demo_setFlag"), trail.trail[0].recordings["device-a"]?.map { it.name })
    assertEquals(listOf("demo_setFlag", "android_adbShell"), trail.trail[0].recordings["device"]?.map { it.name })
  }

  @Test
  fun `a heal forks a leg keyed by a segment spanning a hyphen that another declared device reports`() {
    // Both devices report `[<platform>, cloud-x]` and read the shared `cloud-x` leg, which neither key
    // resolves to on its own.
    val dir = tempFolder.newFolder()
    val shared = original
      .replace("    device-a:", "    plat1-cloud-x:")
      .replace("    device-b:", "    plat2-cloud-x:")
      .replace("    recording:\n      device:\n        # Static", "    recording:\n      cloud-x:\n        # Static")
    val file = File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).apply { writeText(shared) }

    UnifiedRecordingWriter.mergeIntoUnified(
      trailFileOrDir = dir,
      recordedItems = recordedItems(),
      classifier = "plat1-cloud-x",
      replayedRun = UnifiedRecordingWriter.ReplayedRun(listOf("plat1", "cloud-x"), healedSteps = setOf(0)),
    )

    val trail = yaml.decodeUnifiedTrail(file.readText())
    assertEquals(listOf("demo_setFlag"), trail.trail[0].recordings["plat1-cloud-x"]?.map { it.name })
    assertEquals(listOf("demo_setFlag", "android_adbShell"), trail.trail[0].recordings["cloud-x"]?.map { it.name })
  }

  @Test
  fun `a declared device under this one does not keep the leg it replayed`() {
    // device-a-x reads device-a's own leg first, so once the fresh tools are written it reads them too.
    val dir = tempFolder.newFolder()
    val file = File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME)
      .apply { writeText(original.replace("    device-b:", "    device-a-x:")) }

    UnifiedRecordingWriter.mergeIntoUnified(
      trailFileOrDir = dir,
      recordedItems = recordedItems(),
      classifier = "device-a",
      replayedRun = UnifiedRecordingWriter.ReplayedRun(listOf("device", "a"), healedSteps = setOf(0)),
    )

    val trail = yaml.decodeUnifiedTrail(file.readText())
    assertEquals(setOf("device"), trail.trail[0].recordings.keys)
    assertEquals(listOf("demo_setFlag"), trail.trail[0].recordings["device"]?.map { it.name })
  }

  @Test
  fun `a re-recorded run on the only declared device rewrites the legs it would have replayed`() {
    val dir = tempFolder.newFolder()
    val file = File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).apply { writeText(soleDevice) }

    UnifiedRecordingWriter.mergeIntoUnified(
      trailFileOrDir = dir,
      recordedItems = recordedItems(),
      classifier = "device-a",
      replayedRun = UnifiedRecordingWriter.ReplayedRun(listOf("device", "a"), stepsReRecorded = true),
    )

    val trail = yaml.decodeUnifiedTrail(file.readText())
    assertEquals(listOf("demo_setFlag"), trail.trail[0].recordings["device"]?.map { it.name })
    assertEquals(setOf("device"), trail.trail[0].recordings.keys)
    assertEquals(setOf("device"), trail.trailhead?.recordings?.keys, "the trailhead replayed, so it is untouched")
  }

  @Test
  fun `a trailhead the AI drove on the only declared device puts its moved tools on the leg step 1 replayed`() {
    val dir = tempFolder.newFolder()
    val unrecordedTrailhead = soleDevice.replace(
      "  recording:\n    device:\n      demo_launchSignedIn:\n        passcode: \"1234\"\n",
      "",
    )
    val file = File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).apply { writeText(unrecordedTrailhead) }

    UnifiedRecordingWriter.mergeIntoUnified(
      trailFileOrDir = dir,
      recordedItems = withTrailheadToolInFirstStep(recordedItems()),
      classifier = "device-a",
      replayedRun = UnifiedRecordingWriter.ReplayedRun(listOf("device", "a"), trailheadToolsInFirstStep = 1),
    )

    val trail = yaml.decodeUnifiedTrail(file.readText())
    assertEquals(listOf("demo_launchSignedIn"), trail.trailhead?.recordings?.get("device-a")?.map { it.name })
    assertEquals(setOf("device"), trail.trail[0].recordings.keys, "step 1 gains no device-specific copy")
    assertEquals(
      listOf("demo_dismissPromo", "demo_setFlag", "android_adbShell"),
      trail.trail[0].recordings["device"]?.map { it.name },
    )
  }

  /** [items] as the generator leaves them when the trailhead recorded a second tool: at step 1's front. */
  private fun withTrailheadToolInFirstStep(items: List<TrailYamlItem>): List<TrailYamlItem> = items.map { item ->
    if (item !is TrailYamlItem.PromptsTrailItem) return@map item
    val first = item.promptSteps.first() as DirectionStep
    val tools = listOf(tool("demo_dismissPromo")) + first.recording!!.tools
    TrailYamlItem.PromptsTrailItem(listOf(first.copy(recording = ToolRecording(tools))) + item.promptSteps.drop(1))
  }

  @Test
  fun `a full re-record with no replay still writes every step`() {
    val dir = tempFolder.newFolder()
    val file = File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).apply { writeText(original) }

    val outcome = UnifiedRecordingWriter.mergeIntoUnified(dir, recordedItems(), "device-a")

    assertTrue(outcome is UnifiedRecordingWriter.MergeOutcome.Merged, "got $outcome")
    val trail = yaml.decodeUnifiedTrail(file.readText())
    assertEquals(listOf("demo_setFlag"), trail.trail[0].recordings["device-a"]?.map { it.name })
    assertEquals(listOf("demo_tapMenu"), trail.trail[1].recordings["device-a"]?.map { it.name })
  }

  /** Every line of [original], in order: the save only inserted lines. */
  private fun assertOriginalLinesKept(saved: String) {
    val savedLines = saved.lines()
    var cursor = 0
    for (line in original.lines()) {
      val found = (cursor until savedLines.size).firstOrNull { savedLines[it] == line }
      assertTrue(found != null, "line `$line` of the original is missing or out of order in:\n$saved")
      cursor = found + 1
    }
  }

  private fun tool(name: String, vararg args: Pair<String, kotlinx.serialization.json.JsonElement>) =
    TrailblazeToolYamlWrapper(
      name = name,
      trailblazeTool = OtherTrailblazeTool(toolName = name, raw = JsonObject(mapOf(*args))),
    )
}
