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
import xyz.block.trailblaze.yaml.unified.UnifiedTrailAdapter
import xyz.block.trailblaze.yaml.unified.UnifiedTrailConfig
import xyz.block.trailblaze.yaml.unified.UnifiedTrailStep
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the `--[no-]save-recording` flag, its hidden deprecated alias `--no-record`, the
 * [TrailCommand.shouldSaveRecording] decision predicate, and where a save-back lands. None of the
 * cases need a running daemon, device, or LLM — they exercise picocli parsing and the pure-function
 * helpers directly.
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
  // recordingSaveTarget — shared-trail merge vs per-classifier sibling
  // ---------------------------------------------------------------------------

  @Test
  fun `recordingSaveTarget is UNIFIED_MERGE for a greenfield directory`() {
    // A brand-new trail authored from an NL definition (no *.trail.yaml on disk yet) → the
    // recording merges into the directory's shared trail.yaml.
    val cmd = command()
    val dir = tempFolder.newFolder()
    File(dir, "blaze.yaml").writeText("config:\n  id: x\ntrail:\n  - step: do it\n")
    assertEquals(
      TrailCommand.RecordingSaveTarget.UNIFIED_MERGE,
      cmd.recordingSaveTarget(dir, listOf("android")),
    )
  }

  @Test
  fun `recordingSaveTarget is UNIFIED_MERGE when a shared trail file already exists`() {
    val cmd = command()
    val dir = tempFolder.newFolder()
    File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).writeText("trail:\n  - step: s\n")
    assertEquals(
      TrailCommand.RecordingSaveTarget.UNIFIED_MERGE,
      cmd.recordingSaveTarget(dir, listOf("android")),
    )
  }

  @Test
  fun `recordingSaveTarget is UNIFIED_MERGE when the executed file IS the shared trail`() {
    val cmd = command()
    val dir = tempFolder.newFolder()
    val unified = File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).apply { writeText("trail:\n  - step: s\n") }
    assertEquals(
      TrailCommand.RecordingSaveTarget.UNIFIED_MERGE,
      cmd.recordingSaveTarget(unified, listOf("android")),
    )
  }

  @Test
  fun `recordingSaveTarget is CLASSIFIER_SIBLING when the directory already holds per-device siblings`() {
    // This directory keeps one file per device. Recording a new device updates that device's own
    // file rather than forking a second, shared copy beside them — consolidating is a separate,
    // deliberate step.
    val cmd = command()
    val dir = tempFolder.newFolder()
    writeUnifiedWithSlot(File(dir, "ios.trail.yaml"), "ios")
    assertEquals(
      TrailCommand.RecordingSaveTarget.CLASSIFIER_SIBLING,
      cmd.recordingSaveTarget(dir, listOf("android")),
    )
  }

  @Test
  fun `recordingSaveTarget is CLASSIFIER_SIBLING when there are no device classifiers`() {
    // No classifier → no key for a unified slot → fall back to the classifier-agnostic sibling.
    val cmd = command()
    val dir = tempFolder.newFolder()
    assertEquals(
      TrailCommand.RecordingSaveTarget.CLASSIFIER_SIBLING,
      cmd.recordingSaveTarget(dir, emptyList()),
    )
  }

  @Test
  fun `recordingSaveTarget is CLASSIFIER_SIBLING when the trail file has no parent`() {
    // A bare filename → File.parentFile is null → no directory to inspect → sibling.
    val cmd = command()
    assertEquals(
      TrailCommand.RecordingSaveTarget.CLASSIFIER_SIBLING,
      cmd.recordingSaveTarget(File("orphan.trail.yaml"), listOf("android")),
    )
  }

  // ---------------------------------------------------------------------------
  // shouldSaveRecording — unified slot semantics
  // ---------------------------------------------------------------------------

  @Test
  fun `shouldSaveRecording is true for a greenfield unified recording`() {
    val cmd = command()
    val dir = tempFolder.newFolder()
    File(dir, "blaze.yaml").writeText("- prompts:\n  - step: s\n")
    assertTrue(cmd.shouldSaveRecording(dir, listOf("android")))
  }

  @Test
  fun `shouldSaveRecording is false when this classifier slot is already recorded and self-heal off`() {
    val cmd = command()
    val dir = tempFolder.newFolder()
    writeUnifiedWithAndroidSlot(dir)
    assertFalse(cmd.shouldSaveRecording(dir, listOf("android")))
  }

  @Test
  fun `shouldSaveRecording is true when a different classifier is missing from the unified file`() {
    // The android slot is recorded; recording ios for the first time must still save (add its slot).
    val cmd = command()
    val dir = tempFolder.newFolder()
    writeUnifiedWithAndroidSlot(dir)
    assertTrue(cmd.shouldSaveRecording(dir, listOf("ios")))
  }

  @Test
  fun `shouldSaveRecording is true for an already-recorded classifier when self-heal is on`() {
    val cmd = command(selfHeal = true)
    val dir = tempFolder.newFolder()
    writeUnifiedWithAndroidSlot(dir)
    assertTrue(cmd.shouldSaveRecording(dir, listOf("android")))
  }

  @Test
  fun `shouldSaveRecording is false when this classifier is recorded only in the trailhead`() {
    // The classifier's sole recording living in the trailhead (no step slot) still counts as
    // "already recorded" — guards the trailheadHit branch of unifiedClassifierAlreadyRecorded.
    val cmd = command()
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
    val cmd = command()
    val dir = tempFolder.newFolder()
    val recording = File(dir, "recording.trail.yaml").apply {
      writeText(unifiedRecordingYaml(driver = "D", toolName = "tapCart", classifier = "android-phone"))
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
      writeText(unifiedRecordingYaml(driver = "D", toolName = "tapCart", classifier = "android"))
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
      writeText(unifiedRecordingYaml(driver = "D", toolName = "tapCart", classifier = "android"))
    }

    cmd.saveRecordingAsUnified(named, recording, listOf("android"))

    assertEquals(templated, named.readText(), "the template-bearing source must be left byte-identical")
    assertFalse(File(dir, "android.trail.yaml").exists(), "no legacy sibling either")
    assertTrue(recording.isFile, "the recording is preserved for a retry")
  }

  @Test
  fun `saveRecordingAsUnified refuses an undecodable recording without touching the trail directory`() {
    // The intermediate the run produced isn't readable as a trail, so there is nothing to merge.
    // The guarantee is that the refusal costs the user nothing: no file appears in the trail
    // directory, and their run's recording is left intact for a retry.
    val cmd = TrailCommand()
    val dir = tempFolder.newFolder()
    val recording = File(dir, "recording.trail.yaml").apply { writeText("not: [a, trail\n") }

    cmd.saveRecordingAsUnified(dir, recording, listOf("android"))

    assertFalse(File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).exists(), "no shared trail written")
    assertFalse(File(dir, "android.trail.yaml").exists(), "no per-device sibling written either")
    assertEquals("not: [a, trail\n", recording.readText(), "the run's recording is preserved intact")
  }

  @Test
  fun `saveRecordingAsSibling writes only this device's slot`() {
    // The intermediate is seeded from the run's source trail, so it can carry other devices' slots.
    // A per-device file must not republish them — it would fork a stale copy of another device's
    // recording that nothing updates.
    val cmd = TrailCommand()
    val dir = tempFolder.newFolder()
    val intermediate = File(dir, "recording.trail.yaml")
    writeUnifiedWithSlot(intermediate, "ios")
    val withBoth = createTrailblazeYaml().decodeUnifiedTrail(intermediate.readText())
    intermediate.writeText(
      createTrailblazeYaml().encodeUnifiedTrailToString(
        withBoth.copy(
          trail = withBoth.trail.map {
            it.copy(recordings = it.recordings + ("android" to listOf(tool("androidTap"))))
          },
        ),
      ),
    )
    val target = File(dir, "android.trail.yaml")

    cmd.saveRecordingAsSibling(dir, intermediate, target, listOf("android"))

    val sibling = createTrailblazeYaml().decodeUnifiedTrail(target.readText())
    assertEquals(listOf("androidTap"), sibling.trail.single().recordings["android"]?.map { it.name })
    assertNull(sibling.trail.single().recordings["ios"], "the other device's slot must not be republished")
  }

  @Test
  fun `saveRecordingAsSibling refuses an undecodable recording without writing`() {
    val cmd = TrailCommand()
    val dir = tempFolder.newFolder()
    val intermediate = File(dir, "recording.trail.yaml").apply { writeText("not: [a, trail\n") }
    val target = File(dir, "android.trail.yaml")

    cmd.saveRecordingAsSibling(dir, intermediate, target, listOf("android"))

    assertFalse(target.exists(), "no sibling written from an unreadable intermediate")
    assertEquals("not: [a, trail\n", intermediate.readText(), "the run's recording is preserved intact")
  }

  // ---------------------------------------------------------------------------
  // saveRecordingAsUnified — the merge-write contract
  // ---------------------------------------------------------------------------

  @Test
  fun `saveRecordingAsUnified creates a fresh unified trail from a first recording`() {
    val cmd = TrailCommand()
    val dir = tempFolder.newFolder()
    val recording = File(dir, "recording.trail.yaml").apply {
      writeText(unifiedRecordingYaml(driver = "ANDROID_ONDEVICE_INSTRUMENTATION", toolName = "tapCart", classifier = "android"))
    }

    cmd.saveRecordingAsUnified(dir, recording, listOf("android"))

    val unifiedFile = File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME)
    assertTrue(unifiedFile.isFile, "a fresh unified trail.yaml must be written")
    val unified = createTrailblazeYaml().decodeUnifiedTrail(unifiedFile.readText())
    assertEquals("ANDROID_ONDEVICE_INSTRUMENTATION", unified.config.devices?.get("android"))
    assertEquals(listOf("tapCart"), unified.trail.single().recordings["android"]?.map { it.name })
  }

  @Test
  fun `saveRecordingAsUnified extracts this device's slot from a unified intermediate`() {
    // The recording intermediate is now written in the unified shape. Seeded from the run's source
    // trail, it can carry multiple device slots; the consumer must decode only the classifier it ran
    // (keyed by the passed device classifiers) and merge that one slot — never leak a sibling slot.
    val cmd = TrailCommand()
    val dir = tempFolder.newFolder()
    val yaml = createTrailblazeYaml()
    val androidDoc = UnifiedTrailAdapter.mergeRecordedClassifier(
      existing = null,
      recordedItems = listOf(
        TrailYamlItem.ConfigTrailItem(TrailConfig(id = "flow", target = "app", driver = "ANDROID_ONDEVICE_INSTRUMENTATION")),
        TrailYamlItem.PromptsTrailItem(
          listOf(DirectionStep(step = "Open the cart", recording = ToolRecording(tools = listOf(tool("androidTap"))))),
        ),
      ),
      classifier = "android",
    )
    val bothDevices = UnifiedTrailAdapter.mergeRecordedClassifier(
      existing = androidDoc,
      recordedItems = listOf(
        TrailYamlItem.ConfigTrailItem(TrailConfig(id = "flow", target = "app", driver = "IOS_HOST")),
        TrailYamlItem.PromptsTrailItem(
          listOf(DirectionStep(step = "Open the cart", recording = ToolRecording(tools = listOf(tool("iosTap"))))),
        ),
      ),
      classifier = "ios",
    )
    val recording = File(dir, "recording.trail.yaml").apply { writeText(yaml.encodeUnifiedTrailToString(bothDevices)) }

    cmd.saveRecordingAsUnified(dir, recording, listOf("android"))

    val unified = yaml.decodeUnifiedTrail(File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).readText())
    val step = unified.trail.single()
    assertEquals(listOf("androidTap"), step.recordings["android"]?.map { it.name }, "android slot merged from the unified intermediate")
    assertNull(step.recordings["ios"], "the ios slot from the intermediate must not merge under the android classifier")
  }

  @Test
  fun `saveRecordingAsUnified merges a second device without disturbing the first`() {
    val cmd = TrailCommand()
    val dir = tempFolder.newFolder()
    // First device.
    File(dir, "recording.trail.yaml").apply {
      writeText(unifiedRecordingYaml(driver = "ANDROID_ONDEVICE_INSTRUMENTATION", toolName = "androidCart", classifier = "android"))
    }.also { cmd.saveRecordingAsUnified(dir, it, listOf("android")) }
    // Second device, same NL step, different recording.
    val iosRecording = File(dir, "recording.trail.yaml").apply {
      writeText(unifiedRecordingYaml(driver = "IOS_HOST", toolName = "iosCart", classifier = "ios"))
    }

    cmd.saveRecordingAsUnified(dir, iosRecording, listOf("ios"))

    val unified = createTrailblazeYaml().decodeUnifiedTrail(File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).readText())
    val step = unified.trail.single()
    assertEquals(listOf("androidCart"), step.recordings["android"]?.map { it.name }, "android slot preserved")
    assertEquals(listOf("iosCart"), step.recordings["ios"]?.map { it.name }, "ios slot added")
    assertEquals("ANDROID_ONDEVICE_INSTRUMENTATION", unified.config.devices?.get("android"))
    assertEquals("IOS_HOST", unified.config.devices?.get("ios"))
  }

  // ---------------------------------------------------------------------------
  // Named unified files — content-aware routing (the shared-directory corpus)
  // ---------------------------------------------------------------------------

  @Test
  fun `recordingSaveTarget is UNIFIED_MERGE for a named file with unified content`() {
    // The corpus is mostly NAMED files (login.trail.yaml) sharing a directory with other tests.
    // Keying on the filename alone would misroute them to a per-device sibling that shadows
    // resolution and doesn't identify which test it recorded — the content decides.
    val cmd = command()
    val dir = tempFolder.newFolder()
    val named = File(dir, "login.trail.yaml").apply { writeText("trail:\n  - step: s\n") }
    File(dir, "payment.trail.yaml").writeText("trail:\n  - step: p\n")
    assertEquals(
      TrailCommand.RecordingSaveTarget.UNIFIED_MERGE,
      cmd.recordingSaveTarget(named, listOf("android")),
    )
  }

  @Test
  fun `recordingSaveTarget is UNIFIED_MERGE for a named unified file whose template breaks raw YAML`() {
    // The run path resolves {{var}} templates before parsing (TrailYamlTemplateResolver), so a
    // unified file with an unquoted template — invalid as raw YAML — still executes. Detection
    // must resolve the same way, or the file misroutes to a legacy sibling: the exact damage
    // this routing exists to prevent. {{CWD}} is a built-in that always resolves.
    val cmd = command()
    val dir = tempFolder.newFolder()
    val named = File(dir, "login.trail.yaml").apply {
      writeText("config:\n  target: {{CWD}}\ntrail:\n  - step: s\n")
    }
    assertEquals(
      TrailCommand.RecordingSaveTarget.UNIFIED_MERGE,
      cmd.recordingSaveTarget(named, listOf("android")),
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
      writeText(unifiedRecordingYaml(driver = "ANDROID_ONDEVICE_INSTRUMENTATION", toolName = "tapCart", classifier = "android"))
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
    val cmd = command()
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
      writeText(unifiedRecordingYamlWithTrailhead(trailheadToolName = "openBootstrap", classifier = "android"))
    }

    cmd.saveRecordingAsUnified(dir, recording, listOf("android"))

    val unifiedFile = File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME)
    assertTrue(unifiedFile.isFile, "a single-tool trailhead stays unified")
    assertFalse(File(dir, "android.trail.yaml").exists(), "no legacy sibling for the representable case")
    val unified = createTrailblazeYaml().decodeUnifiedTrail(unifiedFile.readText())
    assertEquals(listOf("openBootstrap"), unified.trailhead?.recordings?.get("android")?.map { it.name })
  }

  // --- fixtures ---

  /** A command with self-heal pinned, so routing tests don't read ambient env/config. */
  private fun command(selfHeal: Boolean = false) = TrailCommand().apply {
    this.selfHeal = selfHeal
  }

  /** Writes a unified `trail.yaml` in [dir] whose single step already carries an `android` slot. */
  private fun writeUnifiedWithAndroidSlot(dir: File) =
    writeUnifiedWithSlot(File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME), "android")

  /**
   * Writes a unified trail at [target] (any filename — bare or named) whose single step already
   * carries a [classifier] slot. The step NL matches [unifiedRecordingYaml] so a follow-up save
   * merges into the same step.
   */
  private fun writeUnifiedWithSlot(target: File, classifier: String) {
    val yaml = createTrailblazeYaml()
    val recordingItems = listOf<TrailYamlItem>(
      TrailYamlItem.ConfigTrailItem(TrailConfig(id = "x", target = "y", driver = "D")),
      TrailYamlItem.PromptsTrailItem(
        listOf(DirectionStep(step = "Open the cart", recording = ToolRecording(tools = listOf(tool("a"))))),
      ),
    )
    val merged = UnifiedTrailAdapter.mergeRecordedClassifier(
      existing = null,
      recordedItems = recordingItems,
      classifier = classifier,
    )
    target.writeText(yaml.encodeUnifiedTrailToString(merged))
  }

  /**
   * A minimal UNIFIED `recording.trail.yaml` body: one config + one recorded step whose tool lives in
   * [classifier]'s slot. This is the shape a recording intermediate now takes — [saveRecordingAsUnified]
   * decodes it as a unified doc, lowers it to the run's classifier, and merges that one slot. The step
   * NL matches [writeUnifiedWithSlot] so a follow-up save merges into the same step.
   */
  private fun unifiedRecordingYaml(driver: String, toolName: String, classifier: String): String =
    createTrailblazeYaml().encodeUnifiedTrailToString(
      UnifiedTrailAdapter.mergeRecordedClassifier(
        existing = null,
        recordedItems = listOf(
          TrailYamlItem.ConfigTrailItem(TrailConfig(id = "app/x", target = "app", driver = driver)),
          TrailYamlItem.PromptsTrailItem(
            listOf(DirectionStep(step = "Open the cart", recording = ToolRecording(tools = listOf(tool(toolName))))),
          ),
        ),
        classifier = classifier,
      ),
    )

  /**
   * A UNIFIED `recording.trail.yaml` whose single-tool trailhead + one recorded step live in
   * [classifier]'s slot — the representable trailhead case (one tool per classifier).
   */
  private fun unifiedRecordingYamlWithTrailhead(trailheadToolName: String, classifier: String): String =
    createTrailblazeYaml().encodeUnifiedTrailToString(
      UnifiedTrailAdapter.mergeRecordedClassifier(
        existing = null,
        recordedItems = listOf(
          TrailYamlItem.ConfigTrailItem(TrailConfig(id = "app/x", target = "app", driver = "D")),
          TrailYamlItem.TrailheadTrailItem(
            TrailheadDefinition(step = "Bootstrap", tools = listOf(tool(trailheadToolName))),
          ),
          TrailYamlItem.PromptsTrailItem(
            listOf(DirectionStep(step = "Open the cart", recording = ToolRecording(tools = listOf(tool("tapCart"))))),
          ),
        ),
        classifier = classifier,
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
