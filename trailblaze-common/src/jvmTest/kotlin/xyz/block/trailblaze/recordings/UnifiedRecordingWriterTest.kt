package xyz.block.trailblaze.recordings

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
import xyz.block.trailblaze.yaml.createTrailblazeYaml

/**
 * Contract tests for the shared save-back writer used by the CLI, MCP, and desktop recording
 * surfaces. Exercises routing (gate on/off, greenfield vs half-migrated vs migrated), the gate-off
 * refusal guard, and the unified read-merge-write (fresh create, second-classifier merge, corrupt
 * refusal, multi-tool trailhead fallback) directly against a temp directory — no device, daemon, or
 * CLI needed. The pure merge itself is covered by the `:trailblaze-models` adapter tests.
 */
class UnifiedRecordingWriterTest {

  @get:Rule val tempFolder = TemporaryFolder()

  // ---------------------------------------------------------------------------
  // resolveGate — env > persisted, flag on top (CLI-only)
  // ---------------------------------------------------------------------------

  // The env tier can be set in a developer/CI shell; when it is, it outranks persisted config and
  // the default. Compute the parsed env value the same way resolveGate does so the assertions below
  // stay deterministic in any environment (Copilot flagged the naive "assume unset" form as flaky).
  private val envGate: Boolean? = UnifiedRecordingWriter.parseBooleanGate(
    System.getenv(UnifiedRecordingWriter.ENV_UNIFIED_RECORDINGS),
  )

  @Test
  fun `resolveGate an explicit flag wins over env and persisted config`() {
    // The flag is the highest tier, so this holds regardless of the ambient env var.
    assertTrue(UnifiedRecordingWriter.resolveGate(flagOverride = true, persistedConfig = false))
    assertFalse(UnifiedRecordingWriter.resolveGate(flagOverride = false, persistedConfig = true))
  }

  @Test
  fun `resolveGate falls back to persisted config when no flag or env`() {
    if (envGate != null) return // env tier outranks persisted; skip in an env that sets it
    assertTrue(UnifiedRecordingWriter.resolveGate(flagOverride = null, persistedConfig = true))
    assertFalse(UnifiedRecordingWriter.resolveGate(flagOverride = null, persistedConfig = false))
  }

  @Test
  fun `resolveGate defaults to off when nothing is set`() {
    if (envGate != null) return // env tier decides when set; skip in an env that sets it
    assertFalse(UnifiedRecordingWriter.resolveGate(flagOverride = null, persistedConfig = null))
  }

  @Test
  fun `parseBooleanGate accepts the documented 1 and true forms`() {
    assertEquals(true, UnifiedRecordingWriter.parseBooleanGate("1"))
    assertEquals(true, UnifiedRecordingWriter.parseBooleanGate("true"))
    assertEquals(true, UnifiedRecordingWriter.parseBooleanGate("TRUE"))
    assertEquals(true, UnifiedRecordingWriter.parseBooleanGate(" 1 "))
    assertEquals(false, UnifiedRecordingWriter.parseBooleanGate("0"))
    assertEquals(false, UnifiedRecordingWriter.parseBooleanGate("false"))
    assertNull(UnifiedRecordingWriter.parseBooleanGate(null))
    assertNull(UnifiedRecordingWriter.parseBooleanGate("yes"))
    assertNull(UnifiedRecordingWriter.parseBooleanGate(""))
  }

  // ---------------------------------------------------------------------------
  // shouldRouteUnified — routing decision
  // ---------------------------------------------------------------------------

  @Test
  fun `shouldRouteUnified is false when the gate is off`() {
    val dir = tempFolder.newFolder()
    assertFalse(UnifiedRecordingWriter.shouldRouteUnified(dir, "android", unifiedEnabled = false))
  }

  @Test
  fun `shouldRouteUnified is false for a blank classifier`() {
    val dir = tempFolder.newFolder()
    assertFalse(UnifiedRecordingWriter.shouldRouteUnified(dir, "", unifiedEnabled = true))
  }

  @Test
  fun `shouldRouteUnified is true for a greenfield directory`() {
    val dir = tempFolder.newFolder()
    File(dir, "blaze.yaml").writeText("- prompts:\n  - step: do it\n")
    assertTrue(UnifiedRecordingWriter.shouldRouteUnified(dir, "android", unifiedEnabled = true))
  }

  @Test
  fun `shouldRouteUnified is true when a unified trail file already exists`() {
    val dir = tempFolder.newFolder()
    File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).writeText("trail:\n  - step: s\n")
    assertTrue(UnifiedRecordingWriter.shouldRouteUnified(dir, "android", unifiedEnabled = true))
  }

  @Test
  fun `shouldRouteUnified is false for a half-migrated directory with legacy siblings`() {
    val dir = tempFolder.newFolder()
    File(dir, "ios.trail.yaml").writeText(v1RecordingYaml(driver = "D", toolName = "t"))
    assertFalse(UnifiedRecordingWriter.shouldRouteUnified(dir, "android", unifiedEnabled = true))
  }

  @Test
  fun `shouldRouteUnified is true for a named file whose content is unified`() {
    val dir = tempFolder.newFolder()
    val named = File(dir, "login.trail.yaml").apply { writeText("trail:\n  - step: s\n") }
    File(dir, "payment.trail.yaml").writeText("trail:\n  - step: p\n")
    assertTrue(UnifiedRecordingWriter.shouldRouteUnified(named, "android", unifiedEnabled = true))
  }

  // ---------------------------------------------------------------------------
  // unifiedTrailPresent — the gate-off refusal guard
  // ---------------------------------------------------------------------------

  @Test
  fun `unifiedTrailPresent is true when the directory holds a trail file`() {
    val dir = tempFolder.newFolder()
    File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).writeText("trail:\n  - step: s\n")
    assertTrue(UnifiedRecordingWriter.unifiedTrailPresent(dir))
  }

  @Test
  fun `unifiedTrailPresent is true for a named unified-content file`() {
    val dir = tempFolder.newFolder()
    val named = File(dir, "login.trail.yaml").apply { writeText("trail:\n  - step: s\n") }
    assertTrue(UnifiedRecordingWriter.unifiedTrailPresent(named))
  }

  @Test
  fun `unifiedTrailPresent is false for a legacy-only directory`() {
    val dir = tempFolder.newFolder()
    File(dir, "android.trail.yaml").writeText(v1RecordingYaml(driver = "D", toolName = "t"))
    File(dir, "blaze.yaml").writeText("- prompts:\n  - step: s\n")
    assertFalse(UnifiedRecordingWriter.unifiedTrailPresent(dir))
  }

  // ---------------------------------------------------------------------------
  // mergeIntoUnified — the read-merge-write contract
  // ---------------------------------------------------------------------------

  @Test
  fun `mergeIntoUnified creates a fresh unified trail from a first recording`() {
    val dir = tempFolder.newFolder()
    val items = createTrailblazeYaml().decodeTrail(v1RecordingYaml(driver = "ANDROID_ONDEVICE_INSTRUMENTATION", toolName = "tapCart"))

    val outcome = UnifiedRecordingWriter.mergeIntoUnified(dir, items, "android")

    assertTrue(outcome is UnifiedRecordingWriter.MergeOutcome.Merged)
    val unified = createTrailblazeYaml().decodeUnifiedTrail(File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).readText())
    assertEquals("ANDROID_ONDEVICE_INSTRUMENTATION", unified.config.devices?.get("android"))
    assertEquals(listOf("tapCart"), unified.trail.single().recordings["android"]?.map { it.name })
  }

  @Test
  fun `mergeIntoUnified merges a second classifier without disturbing the first`() {
    val dir = tempFolder.newFolder()
    val yaml = createTrailblazeYaml()
    UnifiedRecordingWriter.mergeIntoUnified(
      dir,
      yaml.decodeTrail(v1RecordingYaml(driver = "ANDROID_ONDEVICE_INSTRUMENTATION", toolName = "androidCart")),
      "android",
    )

    val outcome = UnifiedRecordingWriter.mergeIntoUnified(
      dir,
      yaml.decodeTrail(v1RecordingYaml(driver = "IOS_HOST", toolName = "iosCart")),
      "ios",
    )

    assertTrue(outcome is UnifiedRecordingWriter.MergeOutcome.Merged)
    val unified = yaml.decodeUnifiedTrail(File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).readText())
    val step = unified.trail.single()
    assertEquals(listOf("androidCart"), step.recordings["android"]?.map { it.name }, "android slot preserved")
    assertEquals(listOf("iosCart"), step.recordings["ios"]?.map { it.name }, "ios slot added")
  }

  @Test
  fun `mergeIntoUnified refuses to overwrite an unreadable existing trail file`() {
    val dir = tempFolder.newFolder()
    val corrupt = File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).apply { writeText("foo: not a unified trail\n") }
    val items = createTrailblazeYaml().decodeTrail(v1RecordingYaml(driver = "D", toolName = "tapCart"))

    val outcome = UnifiedRecordingWriter.mergeIntoUnified(dir, items, "android")

    assertTrue(outcome is UnifiedRecordingWriter.MergeOutcome.RefusedCorrupt)
    assertEquals(
      "foo: not a unified trail\n",
      corrupt.readText(),
      "an unreadable trail.yaml must be left untouched, not clobbered by the merge",
    )
  }

  @Test
  fun `mergeIntoUnified reports a multi-tool trailhead as unsupported and writes nothing`() {
    val dir = tempFolder.newFolder()
    val items = createTrailblazeYaml().decodeTrail(
      v1RecordingYamlWithMultiToolTrailhead(toolNames = listOf("clearBootstrap", "openBootstrap")),
    )

    val outcome = UnifiedRecordingWriter.mergeIntoUnified(dir, items, "android")

    assertTrue(outcome is UnifiedRecordingWriter.MergeOutcome.MultiToolTrailheadUnsupported)
    assertEquals(2, (outcome as UnifiedRecordingWriter.MergeOutcome.MultiToolTrailheadUnsupported).toolCount)
    assertFalse(
      File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).exists(),
      "an un-representable trailhead must not produce a unified trail.yaml — the caller writes a legacy sibling",
    )
  }

  @Test
  fun `mergeIntoUnified keeps a single-tool trailhead in the unified trail`() {
    val dir = tempFolder.newFolder()
    val items = createTrailblazeYaml().decodeTrail(v1RecordingYamlWithMultiToolTrailhead(toolNames = listOf("openBootstrap")))

    val outcome = UnifiedRecordingWriter.mergeIntoUnified(dir, items, "android")

    assertTrue(outcome is UnifiedRecordingWriter.MergeOutcome.Merged)
    val unified = createTrailblazeYaml().decodeUnifiedTrail(File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).readText())
    assertEquals(listOf("openBootstrap"), unified.trailhead?.recordings?.get("android")?.map { it.name })
  }

  @Test
  fun `mergeIntoUnified skips and writes nothing when the recording has no steps`() {
    // A degenerate recording (config only, no prompt steps) merges to an empty trail — an empty
    // `trail:` is unparseable, so the write is skipped rather than producing a corrupt file.
    val dir = tempFolder.newFolder()
    val items = listOf<TrailYamlItem>(TrailYamlItem.ConfigTrailItem(TrailConfig(id = "x", target = "y")))

    val outcome = UnifiedRecordingWriter.mergeIntoUnified(dir, items, "android")

    assertTrue(outcome is UnifiedRecordingWriter.MergeOutcome.SkippedEmpty)
    assertFalse(File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).exists(), "no file written for an empty merge")
  }

  @Test
  fun `mergeIntoUnified returns NoTarget for a parentless orphan path`() {
    // An orphan file with no parent directory resolves to no unified target. Routers never send
    // such a path to UNIFIED, so this is defensive — assert it neither writes nor throws.
    val items = createTrailblazeYaml().decodeTrail(v1RecordingYaml(driver = "D", toolName = "tapCart"))

    val outcome = UnifiedRecordingWriter.mergeIntoUnified(File("orphan.trail.yaml"), items, "android")

    assertTrue(outcome is UnifiedRecordingWriter.MergeOutcome.NoTarget)
  }

  // --- fixtures ---

  /** A minimal v1 `recording.trail.yaml` body with one config + one recorded step. */
  private fun v1RecordingYaml(driver: String, toolName: String): String =
    createTrailblazeYaml().encodeToString(
      listOf(
        TrailYamlItem.ConfigTrailItem(TrailConfig(id = "app/x", target = "app", driver = driver)),
        TrailYamlItem.PromptsTrailItem(
          listOf(DirectionStep(step = "Open the cart", recording = ToolRecording(tools = listOf(tool(toolName))))),
        ),
      ),
    )

  /**
   * A v1 `recording.trail.yaml` whose trailhead (step 0) carries [toolNames] as its `tools:` list,
   * plus one ordinary recorded step. A trailhead with more than one tool has no unified representation.
   */
  private fun v1RecordingYamlWithMultiToolTrailhead(toolNames: List<String>): String =
    createTrailblazeYaml().encodeToString(
      listOf(
        TrailYamlItem.ConfigTrailItem(TrailConfig(id = "app/x", target = "app", driver = "D")),
        TrailYamlItem.TrailheadTrailItem(
          TrailheadDefinition(step = "Bootstrap", tools = toolNames.map { tool(it) }),
        ),
        TrailYamlItem.PromptsTrailItem(
          listOf(DirectionStep(step = "Open the cart", recording = ToolRecording(tools = listOf(tool("tapCart"))))),
        ),
      ),
    )

  private fun tool(name: String) = TrailblazeToolYamlWrapper(
    name = name,
    trailblazeTool = OtherTrailblazeTool(toolName = name, raw = JsonObject(mapOf("marker" to JsonPrimitive(name)))),
  )
}
