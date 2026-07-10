package xyz.block.trailblaze.cli

import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import picocli.CommandLine
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.recordings.TrailRecordings
import xyz.block.trailblaze.yaml.DirectionStep
import xyz.block.trailblaze.yaml.ToolRecording
import xyz.block.trailblaze.yaml.TrailConfig
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.TrailheadDefinition
import xyz.block.trailblaze.yaml.TrailblazeToolYamlWrapper
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import xyz.block.trailblaze.yaml.unified.UnifiedTrail
import xyz.block.trailblaze.yaml.unified.UnifiedTrailConfig
import xyz.block.trailblaze.yaml.unified.UnifiedTrailStep
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the new `--[no-]save-recording` flag, its hidden deprecated alias
 * `--no-record`, and the [TrailCommand.shouldSaveRecording] decision predicate. None of
 * the cases need a running daemon, device, or LLM — they exercise picocli parsing and the
 * pure-function helpers directly.
 *
 * Companion to [CliCommandValidationTest], kept in its own file because the
 * save-recording behaviour is the single largest surface added by this PR and the test
 * count is going to grow as additional edge cases are uncovered.
 */
class TrailCommandSaveRecordingTest {

  @get:Rule val tempFolder = TemporaryFolder()

  // ---------------------------------------------------------------------------
  // Flag parsing
  // ---------------------------------------------------------------------------

  @Test
  fun `saveRecording defaults to null and resolves to true when no flag is passed`() {
    // Tri-state: null (user didn't say) resolves to "save" so the default behaviour is
    // recordings-on. Explicit true/false from the flag take precedence over the default.
    val cmd = TrailCommand()
    CommandLine(cmd).parseArgs("any.trail.yaml")

    assertNull(cmd.saveRecording)
    assertTrue(cmd.resolveEffectiveSaveRecording())
  }

  @Test
  fun `trail parses --save-recording as true`() {
    val cmd = TrailCommand()
    CommandLine(cmd).parseArgs("--save-recording", "any.trail.yaml")

    assertEquals(true, cmd.saveRecording)
    assertTrue(cmd.resolveEffectiveSaveRecording())
  }

  @Test
  fun `trail parses --no-save-recording as false`() {
    val cmd = TrailCommand()
    CommandLine(cmd).parseArgs("--no-save-recording", "any.trail.yaml")

    assertEquals(false, cmd.saveRecording)
    assertFalse(cmd.resolveEffectiveSaveRecording())
  }

  @Test
  fun `trail parses deprecated --no-record alias as false`() {
    // Guard the deprecation window: if picocli ever fails to bind the setter-style @Option,
    // this test catches it before any external caller (cli_smoke_tests_common.sh, skill
    // docs) silently regresses to the destructive default.
    val cmd = TrailCommand()
    CommandLine(cmd).parseArgs("--no-record", "any.trail.yaml")

    assertEquals(false, cmd.saveRecording)
    assertFalse(cmd.resolveEffectiveSaveRecording())
  }

  // ---------------------------------------------------------------------------
  // shouldSaveRecording predicate
  // ---------------------------------------------------------------------------

  @Test
  fun `shouldSaveRecording is false when saveRecording is off`() {
    val cmd = TrailCommand().apply {
      saveRecording = false
      selfHeal = false // pin to known state — resolveEffectiveSelfHeal otherwise reads env+config
    }
    val trail = tempFolder.newFile("foo.trail.yaml")

    assertFalse(cmd.shouldSaveRecording(trail, listOf("android-phone")))
  }

  @Test
  fun `shouldSaveRecording is true when target does not yet exist`() {
    // Source filename differs from the classifier-derived target so the existence check
    // genuinely tests "no prior recording on disk." If the source and target collide
    // (e.g. running an already-recorded android-phone.trail.yaml on android-phone), the
    // existence check correctly fires and we skip to protect the source.
    val cmd = TrailCommand().apply { selfHeal = false }
    val trailDir = tempFolder.newFolder()
    val trail = File(trailDir, "source.trail.yaml").apply { writeText("") }

    assertTrue(cmd.shouldSaveRecording(trail, listOf("android-phone")))
  }

  @Test
  fun `shouldSaveRecording is false when target already exists and self-heal is off`() {
    // The deterministic-re-run case the PR exists to protect: the same file we'd save TO
    // already exists, so we skip rather than clobber the (potentially hand-edited) source.
    val cmd = TrailCommand().apply { selfHeal = false }
    val trailDir = tempFolder.newFolder()
    val trail = File(trailDir, "android-phone.trail.yaml").apply { writeText("") }
    assertTrue(trail.exists())

    assertFalse(cmd.shouldSaveRecording(trail, listOf("android-phone")))
  }

  @Test
  fun `shouldSaveRecording is true when target exists and self-heal is on`() {
    // Self-heal short-circuits the existence check — the AI may have produced a
    // genuinely-different tool sequence worth committing over the stale source.
    val cmd = TrailCommand().apply { selfHeal = true }
    val trailDir = tempFolder.newFolder()
    val trail = File(trailDir, "android-phone.trail.yaml").apply { writeText("") }

    assertTrue(cmd.shouldSaveRecording(trail, listOf("android-phone")))
  }

  // ---------------------------------------------------------------------------
  // computeRecordingTargetFile branches
  // ---------------------------------------------------------------------------

  @Test
  fun `computeRecordingTargetFile joins classifiers with hyphen`() {
    val cmd = TrailCommand()
    val trailDir = tempFolder.newFolder()
    val trail = File(trailDir, "foo.trail.yaml").apply { writeText("") }

    val target = cmd.computeRecordingTargetFile(trail, listOf("android-phone", "small"))

    assertNotNull(target)
    assertEquals("android-phone-small.trail.yaml", target.name)
    assertEquals(trailDir, target.parentFile)
  }

  @Test
  fun `computeRecordingTargetFile falls back to recording-trail-yaml when no classifiers`() {
    val cmd = TrailCommand()
    val trailDir = tempFolder.newFolder()
    val trail = File(trailDir, "foo.trail.yaml").apply { writeText("") }

    val target = cmd.computeRecordingTargetFile(trail, emptyList())

    assertNotNull(target)
    assertEquals("recording.trail.yaml", target.name)
  }

  @Test
  fun `computeRecordingTargetFile uses directory itself when trailFile is a directory`() {
    val cmd = TrailCommand()
    val trailDir = tempFolder.newFolder("flow-dir")

    val target = cmd.computeRecordingTargetFile(trailDir, listOf("android-phone"))

    assertNotNull(target)
    assertEquals(trailDir, target.parentFile)
    assertEquals("android-phone.trail.yaml", target.name)
  }

  @Test
  fun `computeRecordingTargetFile returns null when trailFile has no parent`() {
    val cmd = TrailCommand()
    // A bare filename with no parent path — File.parentFile returns null. This is the
    // edge case where the existence check can't run and shouldSaveRecording must rely
    // on self-heal alone.
    val target = cmd.computeRecordingTargetFile(File("orphan.trail.yaml"), listOf("x"))

    assertNull(target)
  }

  // ---------------------------------------------------------------------------
  // recordingSaveFormat — unified vs legacy routing
  // ---------------------------------------------------------------------------

  @Test
  fun `recordingSaveFormat is UNIFIED for a greenfield directory`() {
    // A brand-new trail authored from an NL definition (no *.trail.yaml on disk yet) → new
    // recordings use the unified format once the rollout gate is on.
    val cmd = unifiedEnabledCommand()
    val dir = tempFolder.newFolder()
    File(dir, "blaze.yaml").writeText("- prompts:\n  - step: do it\n")
    assertEquals(
      TrailCommand.RecordingSaveFormat.UNIFIED,
      cmd.recordingSaveFormat(dir, listOf("android")),
    )
  }

  @Test
  fun `recordingSaveFormat is UNIFIED when a unified trail file already exists`() {
    val cmd = unifiedEnabledCommand()
    val dir = tempFolder.newFolder()
    File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).writeText("trail:\n  - step: s\n")
    assertEquals(
      TrailCommand.RecordingSaveFormat.UNIFIED,
      cmd.recordingSaveFormat(dir, listOf("android")),
    )
  }

  @Test
  fun `recordingSaveFormat is UNIFIED when the executed file IS the unified trail`() {
    val cmd = unifiedEnabledCommand()
    val dir = tempFolder.newFolder()
    val unified = File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).apply { writeText("trail:\n  - step: s\n") }
    assertEquals(
      TrailCommand.RecordingSaveFormat.UNIFIED,
      cmd.recordingSaveFormat(unified, listOf("android")),
    )
  }

  @Test
  fun `recordingSaveFormat is LEGACY when legacy siblings exist without a unified file`() {
    // A half-migrated / v1 directory: keep updating siblings rather than forking a `trail.yaml`
    // beside them — even with the rollout gate on. Migration to unified is a separate,
    // deliberate step.
    val cmd = unifiedEnabledCommand()
    val dir = tempFolder.newFolder()
    File(dir, "ios.trail.yaml").writeText("- prompts:\n  - step: s\n")
    assertEquals(
      TrailCommand.RecordingSaveFormat.LEGACY,
      cmd.recordingSaveFormat(File(dir, "ios.trail.yaml"), listOf("android")),
    )
  }

  @Test
  fun `recordingSaveFormat is LEGACY when there are no device classifiers`() {
    // No classifier → no key for a unified slot → fall back to the classifier-agnostic sibling.
    val cmd = unifiedEnabledCommand()
    val dir = tempFolder.newFolder()
    assertEquals(
      TrailCommand.RecordingSaveFormat.LEGACY,
      cmd.recordingSaveFormat(dir, emptyList()),
    )
  }

  @Test
  fun `recordingSaveFormat is LEGACY when the trail file has no parent`() {
    // A bare filename → File.parentFile is null → can't inspect a directory → fall back to LEGACY.
    val cmd = unifiedEnabledCommand()
    assertEquals(
      TrailCommand.RecordingSaveFormat.LEGACY,
      cmd.recordingSaveFormat(File("orphan.trail.yaml"), listOf("android")),
    )
  }

  // ---------------------------------------------------------------------------
  // Unified-recordings rollout gate (off by default)
  // ---------------------------------------------------------------------------

  @Test
  fun `trail parses --unified-recordings and --no-unified-recordings`() {
    val on = TrailCommand()
    CommandLine(on).parseArgs("--unified-recordings", "any.trail.yaml")
    assertEquals(true, on.unifiedRecordings)

    val off = TrailCommand()
    CommandLine(off).parseArgs("--no-unified-recordings", "any.trail.yaml")
    assertEquals(false, off.unifiedRecordings)

    val unset = TrailCommand()
    CommandLine(unset).parseArgs("any.trail.yaml")
    assertNull(unset.unifiedRecordings, "tri-state: unset flag defers to env var / persisted config")
  }

  @Test
  fun `gate off - a greenfield recording saves as a legacy sibling`() {
    // Pre-unified behavior must be byte-identical while the gate is off: greenfield recordings
    // keep landing as <classifier>.trail.yaml, never as a unified trail.yaml.
    val cmd = TrailCommand().apply { unifiedRecordings = false }
    val dir = tempFolder.newFolder()
    File(dir, "blaze.yaml").writeText("- prompts:\n  - step: do it\n")
    assertEquals(
      TrailCommand.RecordingSaveFormat.LEGACY,
      cmd.recordingSaveFormat(dir, listOf("android")),
    )
  }

  @Test
  fun `gate off - nothing is saved next to a unified trail file even with self-heal on`() {
    // The historical guard: the legacy save-back can't update a unified trail.yaml, so it must
    // never drop a divergent sibling beside one — self-heal doesn't override that.
    val cmd = TrailCommand().apply {
      unifiedRecordings = false
      selfHeal = true
    }
    val dir = tempFolder.newFolder()
    File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).writeText("trail:\n  - step: s\n")
    assertFalse(cmd.shouldSaveRecording(dir, listOf("android")))
    assertFalse(cmd.shouldSaveRecording(File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME), listOf("android")))
  }

  @Test
  fun `gate off - nothing is saved for an executed named unified file`() {
    // Deliberate deviation from byte-identical pre-gate behavior: a NAMED unified file
    // (login.trail.yaml in a shared multi-test directory) used to get a v1
    // <classifier>.trail.yaml sibling raw-copied beside it — data damage, not preservation.
    // Gate off now refuses (no new write path), even with self-heal on.
    val cmd = TrailCommand().apply {
      unifiedRecordings = false
      selfHeal = true
    }
    val dir = tempFolder.newFolder()
    val named = File(dir, "login.trail.yaml")
    writeUnifiedWithSlot(named, "ios")

    assertFalse(cmd.shouldSaveRecording(named, listOf("android")))
  }

  // ---------------------------------------------------------------------------
  // shouldSaveRecording — unified slot semantics
  // ---------------------------------------------------------------------------

  @Test
  fun `shouldSaveRecording is true for a greenfield unified recording`() {
    val cmd = unifiedEnabledCommand()
    val dir = tempFolder.newFolder()
    File(dir, "blaze.yaml").writeText("- prompts:\n  - step: s\n")
    assertTrue(cmd.shouldSaveRecording(dir, listOf("android")))
  }

  @Test
  fun `shouldSaveRecording is false when this classifier slot is already recorded and self-heal off`() {
    val cmd = unifiedEnabledCommand()
    val dir = tempFolder.newFolder()
    writeUnifiedWithAndroidSlot(dir)
    assertFalse(cmd.shouldSaveRecording(dir, listOf("android")))
  }

  @Test
  fun `shouldSaveRecording is true when a different classifier is missing from the unified file`() {
    // The android slot is recorded; recording ios for the first time must still save (add its slot).
    val cmd = unifiedEnabledCommand()
    val dir = tempFolder.newFolder()
    writeUnifiedWithAndroidSlot(dir)
    assertTrue(cmd.shouldSaveRecording(dir, listOf("ios")))
  }

  @Test
  fun `shouldSaveRecording is true for an already-recorded classifier when self-heal is on`() {
    val cmd = unifiedEnabledCommand(selfHeal = true)
    val dir = tempFolder.newFolder()
    writeUnifiedWithAndroidSlot(dir)
    assertTrue(cmd.shouldSaveRecording(dir, listOf("android")))
  }

  @Test
  fun `shouldSaveRecording is false when this classifier is recorded only in the trailhead`() {
    // The classifier's sole recording living in the trailhead (no step slot) still counts as
    // "already recorded" — guards the trailheadHit branch of unifiedClassifierAlreadyRecorded.
    val cmd = unifiedEnabledCommand()
    val dir = tempFolder.newFolder()
    val unified = UnifiedTrail(
      config = UnifiedTrailConfig(id = "x", target = "y"),
      trailhead = UnifiedTrailStep(step = "Sign in", recordings = mapOf("android" to listOf(tool("launch")))),
      trail = listOf(UnifiedTrailStep(step = "Step 1")),
    )
    File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).writeText(createTrailblazeYaml().encodeUnifiedTrailToString(unified))
    assertFalse(cmd.shouldSaveRecording(dir, listOf("android")))
  }

  @Test
  fun `a multi-segment classifier round-trips through save then skip`() {
    // The joined key (e.g. "android-phone") must be written AND detected by the re-run guard.
    val cmd = unifiedEnabledCommand()
    val dir = tempFolder.newFolder()
    val recording = File(dir, "recording.trail.yaml").apply {
      writeText(v1RecordingYaml(driver = "D", toolName = "tapCart"))
    }

    cmd.saveRecordingAsUnified(dir, recording, listOf("android", "phone"))

    val unified = createTrailblazeYaml().decodeUnifiedTrail(File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).readText())
    assertEquals(listOf("tapCart"), unified.trail.single().recordings["android-phone"]?.map { it.name })
    assertFalse(
      cmd.shouldSaveRecording(dir, listOf("android", "phone")),
      "the same multi-segment device is now recorded, so a plain re-run skips",
    )
  }

  @Test
  fun `saveRecordingAsUnified refuses to overwrite an unreadable existing trail file`() {
    val cmd = TrailCommand()
    val dir = tempFolder.newFolder()
    val corrupt = File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).apply { writeText("foo: not a unified trail\n") }
    val recording = File(dir, "recording.trail.yaml").apply {
      writeText(v1RecordingYaml(driver = "D", toolName = "tapCart"))
    }

    cmd.saveRecordingAsUnified(dir, recording, listOf("android"))

    assertEquals(
      "foo: not a unified trail\n",
      corrupt.readText(),
      "an unreadable trail.yaml must be left untouched, not clobbered by the merge",
    )
    assertTrue(recording.isFile, "the recording is preserved for a retry")
  }

  @Test
  fun `saveRecordingAsUnified refuses to merge into a named unified file whose template breaks raw YAML`() {
    // Detection resolves templates (so the file routes UNIFIED, not to a legacy sibling), but the
    // writer reads the target raw and must refuse rather than merge: merging resolved text would
    // bake resolved values (e.g. an absolute CWD path) into the source and destroy the template.
    val cmd = TrailCommand()
    val dir = tempFolder.newFolder()
    val templated = "config:\n  target: {{CWD}}\ntrail:\n  - step: s\n"
    val named = File(dir, "login.trail.yaml").apply { writeText(templated) }
    val recording = File(dir, "recording.trail.yaml").apply {
      writeText(v1RecordingYaml(driver = "D", toolName = "tapCart"))
    }

    cmd.saveRecordingAsUnified(named, recording, listOf("android"))

    assertEquals(templated, named.readText(), "the template-bearing source must be left byte-identical")
    assertFalse(File(dir, "android.trail.yaml").exists(), "no legacy sibling either")
    assertTrue(recording.isFile, "the recording is preserved for a retry")
  }

  // ---------------------------------------------------------------------------
  // saveRecordingAsUnified — the merge-write contract
  // ---------------------------------------------------------------------------

  @Test
  fun `saveRecordingAsUnified creates a fresh unified trail from a first recording`() {
    val cmd = TrailCommand()
    val dir = tempFolder.newFolder()
    val recording = File(dir, "recording.trail.yaml").apply {
      writeText(v1RecordingYaml(driver = "ANDROID_ONDEVICE_INSTRUMENTATION", toolName = "tapCart"))
    }

    cmd.saveRecordingAsUnified(dir, recording, listOf("android"))

    val unifiedFile = File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME)
    assertTrue(unifiedFile.isFile, "a fresh unified trail.yaml must be written")
    val unified = createTrailblazeYaml().decodeUnifiedTrail(unifiedFile.readText())
    assertEquals("ANDROID_ONDEVICE_INSTRUMENTATION", unified.config.devices?.get("android"))
    assertEquals(listOf("tapCart"), unified.trail.single().recordings["android"]?.map { it.name })
  }

  @Test
  fun `saveRecordingAsUnified merges a second device without disturbing the first`() {
    val cmd = TrailCommand()
    val dir = tempFolder.newFolder()
    // First device.
    File(dir, "recording.trail.yaml").apply {
      writeText(v1RecordingYaml(driver = "ANDROID_ONDEVICE_INSTRUMENTATION", toolName = "androidCart"))
    }.also { cmd.saveRecordingAsUnified(dir, it, listOf("android")) }
    // Second device, same NL step, different recording.
    val iosRecording = File(dir, "recording.trail.yaml").apply {
      writeText(v1RecordingYaml(driver = "IOS_HOST", toolName = "iosCart"))
    }

    cmd.saveRecordingAsUnified(dir, iosRecording, listOf("ios"))

    val unified = createTrailblazeYaml().decodeUnifiedTrail(File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).readText())
    val step = unified.trail.single()
    assertEquals(listOf("androidCart"), step.recordings["android"]?.map { it.name }, "android slot preserved")
    assertEquals(listOf("iosCart"), step.recordings["ios"]?.map { it.name }, "ios slot added")
    assertEquals("ANDROID_ONDEVICE_INSTRUMENTATION", unified.config.devices?.get("android"))
    assertEquals("IOS_HOST", unified.config.devices?.get("ios"))
  }

  @Test
  fun `saveRecordingAsUnified falls back to a legacy sibling when the recorded trailhead has multiple tools`() {
    // The unified trailhead is one tool per classifier; a v1 recording whose trailhead captured more
    // than one tool can't be lowered into that shape (encoding would throw and drop the recording).
    // The recording must be preserved as a legacy `<classifier>.trail.yaml` sibling instead of lost.
    val cmd = TrailCommand()
    val dir = tempFolder.newFolder()
    val recording = File(dir, "recording.trail.yaml").apply {
      writeText(v1RecordingYamlWithMultiToolTrailhead(toolNames = listOf("clearBootstrap", "openBootstrap")))
    }

    cmd.saveRecordingAsUnified(dir, recording, listOf("android"))

    assertFalse(
      File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).exists(),
      "a multi-tool trailhead must not produce an un-encodable unified trail.yaml",
    )
    val legacy = File(dir, "android.trail.yaml")
    assertTrue(legacy.isFile, "the recording is preserved as a legacy classifier sibling")
    val savedItems = createTrailblazeYaml().decodeTrail(legacy.readText())
    val savedTrailhead = savedItems.filterIsInstance<TrailYamlItem.TrailheadTrailItem>().single().trailhead
    assertEquals(listOf("clearBootstrap", "openBootstrap"), savedTrailhead.tools?.map { it.name })
  }

  // ---------------------------------------------------------------------------
  // Named unified files — content-aware routing (the shared-directory corpus)
  // ---------------------------------------------------------------------------

  @Test
  fun `recordingSaveFormat is UNIFIED for a named file with unified content`() {
    // The real unified corpus is NAMED files (login.trail.yaml) sharing a directory with other
    // tests. Filename-only routing counted every named sibling as "legacy" and raw-copied a v1
    // <classifier>.trail.yaml into the shared directory.
    val cmd = unifiedEnabledCommand()
    val dir = tempFolder.newFolder()
    val named = File(dir, "login.trail.yaml").apply { writeText("trail:\n  - step: s\n") }
    File(dir, "payment.trail.yaml").writeText("trail:\n  - step: p\n")
    assertEquals(
      TrailCommand.RecordingSaveFormat.UNIFIED,
      cmd.recordingSaveFormat(named, listOf("android")),
    )
  }

  @Test
  fun `recordingSaveFormat is UNIFIED for a named unified file whose template breaks raw YAML`() {
    // The run path resolves {{var}} templates before parsing (TrailYamlTemplateResolver), so a
    // unified file with an unquoted template — invalid as raw YAML — still executes. Detection
    // must resolve the same way, or the file misroutes to a legacy sibling: the exact damage
    // this routing exists to prevent. {{CWD}} is a built-in that always resolves.
    val cmd = unifiedEnabledCommand()
    val dir = tempFolder.newFolder()
    val named = File(dir, "login.trail.yaml").apply {
      writeText("config:\n  target: {{CWD}}\ntrail:\n  - step: s\n")
    }
    assertEquals(
      TrailCommand.RecordingSaveFormat.UNIFIED,
      cmd.recordingSaveFormat(named, listOf("android")),
    )
  }

  @Test
  fun `recordingSaveFormat stays LEGACY for a v1-content file named like a trail`() {
    // Content detection must not flip half-migrated directories: a v1 file is its own legacy
    // sibling and keeps writing <classifier>.trail.yaml siblings (even with the gate on).
    val cmd = unifiedEnabledCommand()
    val dir = tempFolder.newFolder()
    val v1 = File(dir, "android.trail.yaml").apply { writeText(v1RecordingYaml(driver = "D", toolName = "t")) }
    assertEquals(
      TrailCommand.RecordingSaveFormat.LEGACY,
      cmd.recordingSaveFormat(v1, listOf("ios")),
    )
  }

  @Test
  fun `saveRecordingAsUnified merges into the executed named unified file`() {
    val cmd = TrailCommand()
    val dir = tempFolder.newFolder()
    val named = File(dir, "login.trail.yaml")
    writeUnifiedWithSlot(named, "ios")
    File(dir, "payment.trail.yaml").writeText("trail:\n  - step: p\n") // a different test in the same dir
    val recording = File(dir, "recording.trail.yaml").apply {
      writeText(v1RecordingYaml(driver = "ANDROID_ONDEVICE_INSTRUMENTATION", toolName = "tapCart"))
    }

    cmd.saveRecordingAsUnified(named, recording, listOf("android"))

    assertFalse(
      File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).exists(),
      "no bare trail.yaml forked beside the named file",
    )
    assertFalse(File(dir, "android.trail.yaml").exists(), "no legacy sibling in the shared directory")
    val unified = createTrailblazeYaml().decodeUnifiedTrail(named.readText())
    val step = unified.trail.single()
    assertEquals(listOf("a"), step.recordings["ios"]?.map { it.name }, "existing ios slot preserved")
    assertEquals(
      listOf("tapCart"),
      step.recordings["android"]?.map { it.name },
      "android slot merged into the executed file itself",
    )
  }

  @Test
  fun `shouldSaveRecording skip guard reads the executed named unified file`() {
    val cmd = unifiedEnabledCommand()
    val dir = tempFolder.newFolder()
    val named = File(dir, "login.trail.yaml")
    writeUnifiedWithSlot(named, "android")

    assertFalse(
      cmd.shouldSaveRecording(named, listOf("android")),
      "this classifier is already recorded in the named file, so a plain re-run skips",
    )
    assertTrue(
      cmd.shouldSaveRecording(named, listOf("ios")),
      "a classifier without a slot in the named file still saves",
    )
  }

  @Test
  fun `saveRecordingAsUnified keeps a single-tool trailhead in the unified trail`() {
    // The one-tool trailhead is the representable case: it stays in the unified format (guards the
    // boundary of the multi-tool fallback above).
    val cmd = TrailCommand()
    val dir = tempFolder.newFolder()
    val recording = File(dir, "recording.trail.yaml").apply {
      writeText(v1RecordingYamlWithMultiToolTrailhead(toolNames = listOf("openBootstrap")))
    }

    cmd.saveRecordingAsUnified(dir, recording, listOf("android"))

    val unifiedFile = File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME)
    assertTrue(unifiedFile.isFile, "a single-tool trailhead stays unified")
    assertFalse(File(dir, "android.trail.yaml").exists(), "no legacy sibling for the representable case")
    val unified = createTrailblazeYaml().decodeUnifiedTrail(unifiedFile.readText())
    assertEquals(listOf("openBootstrap"), unified.trailhead?.recordings?.get("android")?.map { it.name })
  }

  // --- fixtures ---

  /**
   * A command with the unified-recordings rollout gate explicitly ON (and self-heal pinned), so
   * unified-path tests exercise the enabled behavior regardless of ambient env/config.
   */
  private fun unifiedEnabledCommand(selfHeal: Boolean = false) = TrailCommand().apply {
    this.selfHeal = selfHeal
    unifiedRecordings = true
  }

  /** Writes a unified `trail.yaml` in [dir] whose single step already carries an `android` slot. */
  private fun writeUnifiedWithAndroidSlot(dir: File) =
    writeUnifiedWithSlot(File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME), "android")

  /**
   * Writes a unified trail at [target] (any filename — bare or named) whose single step already
   * carries a [classifier] slot. The step NL matches [v1RecordingYaml] so a follow-up save merges
   * into the same step.
   */
  private fun writeUnifiedWithSlot(target: File, classifier: String) {
    val yaml = createTrailblazeYaml()
    val recordingItems = listOf<TrailYamlItem>(
      TrailYamlItem.ConfigTrailItem(TrailConfig(id = "x", target = "y", driver = "D")),
      TrailYamlItem.PromptsTrailItem(
        listOf(DirectionStep(step = "Open the cart", recording = ToolRecording(tools = listOf(tool("a"))))),
      ),
    )
    val merged = xyz.block.trailblaze.yaml.unified.UnifiedTrailAdapter.mergeRecordedClassifier(
      existing = null,
      recordedItems = recordingItems,
      classifier = classifier,
    )
    target.writeText(yaml.encodeUnifiedTrailToString(merged))
  }

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
    trailblazeTool = OtherTrailblazeTool(
      toolName = name,
      raw = JsonObject(mapOf("marker" to JsonPrimitive(name))),
    ),
  )
}
