package xyz.block.trailblaze.cli

import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import picocli.CommandLine
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.agent.model.AgentTaskStatusData
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.logs.server.endpoints.CliRunResponse
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.logs.model.TaskId
import xyz.block.trailblaze.toolcalls.toLogPayload
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

    assertFalse(cmd.shouldSaveRecording(trail, listOf("android-phone"), selectedDeviceConfiguration = null))
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

    assertTrue(cmd.shouldSaveRecording(trail, listOf("android-phone"), selectedDeviceConfiguration = null))
  }

  @Test
  fun `shouldSaveRecording is false when target already exists and self-heal is off`() {
    // The deterministic-re-run case the PR exists to protect: the same file we'd save TO
    // already exists, so we skip rather than clobber the (potentially hand-edited) source.
    val cmd = TrailCommand().apply { selfHeal = false }
    val trailDir = tempFolder.newFolder()
    val trail = File(trailDir, "android-phone.trail.yaml").apply { writeText("") }
    assertTrue(trail.exists())

    assertFalse(cmd.shouldSaveRecording(trail, listOf("android-phone"), selectedDeviceConfiguration = null))
  }

  @Test
  fun `shouldSaveRecording is true when target exists and self-heal is on`() {
    // Self-heal short-circuits the existence check — the AI may have produced a
    // genuinely-different tool sequence worth committing over the stale source.
    val cmd = TrailCommand().apply { selfHeal = true }
    val trailDir = tempFolder.newFolder()
    val trail = File(trailDir, "android-phone.trail.yaml").apply { writeText("") }

    assertTrue(cmd.shouldSaveRecording(trail, listOf("android-phone"), selectedDeviceConfiguration = null))
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
      cmd.recordingSaveTarget(dir, listOf("android"), selectedDeviceConfiguration = null),
    )
  }

  @Test
  fun `recordingSaveTarget is UNIFIED_MERGE when a shared trail file already exists`() {
    val cmd = command()
    val dir = tempFolder.newFolder()
    File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).writeText("trail:\n  - step: s\n")
    assertEquals(
      TrailCommand.RecordingSaveTarget.UNIFIED_MERGE,
      cmd.recordingSaveTarget(dir, listOf("android"), selectedDeviceConfiguration = null),
    )
  }

  @Test
  fun `recordingSaveTarget is UNIFIED_MERGE when the executed file IS the shared trail`() {
    val cmd = command()
    val dir = tempFolder.newFolder()
    val unified = File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).apply { writeText("trail:\n  - step: s\n") }
    assertEquals(
      TrailCommand.RecordingSaveTarget.UNIFIED_MERGE,
      cmd.recordingSaveTarget(unified, listOf("android"), selectedDeviceConfiguration = null),
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
      cmd.recordingSaveTarget(dir, listOf("android"), selectedDeviceConfiguration = null),
    )
  }

  @Test
  fun `recordingSaveTarget is UNIFIED_MERGE for a configuration session in a sibling-layout directory`() {
    // The layout that would otherwise route to a sibling: per-device files, no shared trail.yaml.
    // A sibling names its file after the device classifiers and renders the leg under them, so a
    // configuration session routed there would write the classifier-keyed leg this keying prevents.
    val cmd = command()
    val dir = tempFolder.newFolder()
    writeUnifiedWithSlot(File(dir, "lab-a.trail.yaml"), "lab-a")
    assertEquals(
      TrailCommand.RecordingSaveTarget.CLASSIFIER_SIBLING,
      cmd.recordingSaveTarget(dir, listOf("lab-a"), selectedDeviceConfiguration = null),
      "a single-device run in this layout still updates its own per-device file",
    )
    assertEquals(
      TrailCommand.RecordingSaveTarget.UNIFIED_MERGE,
      cmd.recordingSaveTarget(dir, listOf("lab-a"), selectedDeviceConfiguration = "pos-pair"),
    )
  }

  @Test
  fun `recordingSaveTarget is CLASSIFIER_SIBLING when there are no device classifiers`() {
    // No classifier → no key for a unified slot → fall back to the classifier-agnostic sibling.
    val cmd = command()
    val dir = tempFolder.newFolder()
    assertEquals(
      TrailCommand.RecordingSaveTarget.CLASSIFIER_SIBLING,
      cmd.recordingSaveTarget(dir, emptyList(), selectedDeviceConfiguration = null),
    )
  }

  @Test
  fun `recordingSaveTarget is CLASSIFIER_SIBLING when the trail file has no parent`() {
    // A bare filename → File.parentFile is null → no directory to inspect → sibling.
    val cmd = command()
    assertEquals(
      TrailCommand.RecordingSaveTarget.CLASSIFIER_SIBLING,
      cmd.recordingSaveTarget(File("orphan.trail.yaml"), listOf("android"), selectedDeviceConfiguration = null),
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
    assertTrue(cmd.shouldSaveRecording(dir, listOf("android"), selectedDeviceConfiguration = null))
  }

  @Test
  fun `shouldSaveRecording is false when this classifier slot is already recorded and self-heal off`() {
    val cmd = command()
    val dir = tempFolder.newFolder()
    writeUnifiedWithAndroidSlot(dir)
    assertFalse(cmd.shouldSaveRecording(dir, listOf("android"), selectedDeviceConfiguration = null))
  }

  @Test
  fun `shouldSaveRecording is true when a different classifier is missing from the unified file`() {
    // The android slot is recorded; recording ios for the first time must still save (add its slot).
    val cmd = command()
    val dir = tempFolder.newFolder()
    writeUnifiedWithAndroidSlot(dir)
    assertTrue(cmd.shouldSaveRecording(dir, listOf("ios"), selectedDeviceConfiguration = null))
  }

  @Test
  fun `shouldSaveRecording is true for an already-recorded classifier when self-heal is on`() {
    val cmd = command(selfHeal = true)
    val dir = tempFolder.newFolder()
    writeUnifiedWithAndroidSlot(dir)
    assertTrue(cmd.shouldSaveRecording(dir, listOf("android"), selectedDeviceConfiguration = null))
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
    assertFalse(cmd.shouldSaveRecording(dir, listOf("android"), selectedDeviceConfiguration = null))
  }

  @Test
  fun `a multi-segment classifier round-trips through save then skip`() {
    // The joined key (e.g. "android-phone") must be written AND detected by the re-run guard.
    val cmd = command()
    val dir = tempFolder.newFolder()
    val recording = File(dir, "recording.trail.yaml").apply {
      writeText(unifiedRecordingYaml(driver = "ANDROID_ONDEVICE_INSTRUMENTATION", toolName = "tapCart", classifier = "android-phone"))
    }

    cmd.saveRecordingAsUnified(dir, recording, listOf("android", "phone"), selectedDeviceConfiguration = null)

    val unified = createTrailblazeYaml().decodeUnifiedTrail(File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).readText())
    assertEquals(listOf("tapCart"), unified.trail.single().recordings["android-phone"]?.map { it.name })
    assertFalse(
      cmd.shouldSaveRecording(dir, listOf("android", "phone"), selectedDeviceConfiguration = null),
      "the same multi-segment device is now recorded, so a plain re-run skips",
    )
  }

  @Test
  fun `saveRecordingAsUnified refuses to overwrite an unreadable existing trail file`() {
    val cmd = TrailCommand()
    val dir = tempFolder.newFolder()
    val corrupt = File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).apply { writeText("foo: not a unified trail\n") }
    val recording = File(dir, "recording.trail.yaml").apply {
      writeText(unifiedRecordingYaml(driver = "ANDROID_ONDEVICE_INSTRUMENTATION", toolName = "tapCart", classifier = "android"))
    }

    cmd.saveRecordingAsUnified(dir, recording, listOf("android"), selectedDeviceConfiguration = null)

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
      writeText(unifiedRecordingYaml(driver = "ANDROID_ONDEVICE_INSTRUMENTATION", toolName = "tapCart", classifier = "android"))
    }

    cmd.saveRecordingAsUnified(named, recording, listOf("android"), selectedDeviceConfiguration = null)

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

    cmd.saveRecordingAsUnified(dir, recording, listOf("android"), selectedDeviceConfiguration = null)

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

    cmd.saveRecordingAsUnified(dir, recording, listOf("android"), selectedDeviceConfiguration = null)

    val unifiedFile = File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME)
    assertTrue(unifiedFile.isFile, "a fresh unified trail.yaml must be written")
    val unified = createTrailblazeYaml().decodeUnifiedTrail(unifiedFile.readText())
    assertEquals("ANDROID_ONDEVICE_INSTRUMENTATION", unified.config.devices?.get("android")?.driver?.name)
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

    cmd.saveRecordingAsUnified(dir, recording, listOf("android"), selectedDeviceConfiguration = null)

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
    }.also { cmd.saveRecordingAsUnified(dir, it, listOf("android"), selectedDeviceConfiguration = null) }
    // Second device, same NL step, different recording.
    val iosRecording = File(dir, "recording.trail.yaml").apply {
      writeText(unifiedRecordingYaml(driver = "IOS_HOST", toolName = "iosCart", classifier = "ios"))
    }

    cmd.saveRecordingAsUnified(dir, iosRecording, listOf("ios"), selectedDeviceConfiguration = null)

    val unified = createTrailblazeYaml().decodeUnifiedTrail(File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).readText())
    val step = unified.trail.single()
    assertEquals(listOf("androidCart"), step.recordings["android"]?.map { it.name }, "android slot preserved")
    assertEquals(listOf("iosCart"), step.recordings["ios"]?.map { it.name }, "ios slot added")
    assertEquals("ANDROID_ONDEVICE_INSTRUMENTATION", unified.config.devices?.get("android")?.driver?.name)
    assertEquals("IOS_HOST", unified.config.devices?.get("ios")?.driver?.name)
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
      cmd.recordingSaveTarget(named, listOf("android"), selectedDeviceConfiguration = null),
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
      cmd.recordingSaveTarget(named, listOf("android"), selectedDeviceConfiguration = null),
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

    cmd.saveRecordingAsUnified(named, recording, listOf("android"), selectedDeviceConfiguration = null)

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
      cmd.shouldSaveRecording(named, listOf("android"), selectedDeviceConfiguration = null),
      "this classifier is already recorded in the named file, so a plain re-run skips",
    )
    assertTrue(
      cmd.shouldSaveRecording(named, listOf("ios"), selectedDeviceConfiguration = null),
      "a classifier without a slot in the named file still saves",
    )
  }

  @Test
  fun `saveRecordingAsUnified keeps a single-tool trailhead in the unified trail`() {
    // The one-tool trailhead needs no mapping at all — it lands in the trailhead slot as recorded,
    // and nothing spills into the first step.
    val cmd = TrailCommand()
    val dir = tempFolder.newFolder()
    val recording = File(dir, "recording.trail.yaml").apply {
      writeText(unifiedRecordingYamlWithTrailhead(trailheadToolName = "openBootstrap", classifier = "android"))
    }

    cmd.saveRecordingAsUnified(dir, recording, listOf("android"), selectedDeviceConfiguration = null)

    val unifiedFile = File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME)
    assertTrue(unifiedFile.isFile, "a single-tool trailhead stays unified")
    assertFalse(File(dir, "android.trail.yaml").exists(), "no legacy sibling for the representable case")
    val unified = createTrailblazeYaml().decodeUnifiedTrail(unifiedFile.readText())
    assertEquals(listOf("openBootstrap"), unified.trailhead?.recordings?.get("android")?.map { it.name })
  }

  // ---------------------------------------------------------------------------
  // Multi-device configuration sessions — legs keyed by the configuration NAME
  // ---------------------------------------------------------------------------

  @Test
  fun `a configuration session saves under the configuration name and leaves the cast untouched`() {
    // The live dual-display repro, fixed: the session ran the `pos-pair` configuration, so its recording merges
    // under the `pos-pair` slot — never under the start device's classifier chain — and the authored
    // cast in config.devices is not stripped, re-keyed, or given a driver pin.
    val cmd = TrailCommand()
    val dir = tempFolder.newFolder()
    val trailFile = File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME)
    val declaredDevices = configurationDevices()
    val yaml = createTrailblazeYaml()
    trailFile.writeText(
      yaml.encodeUnifiedTrailToString(
        UnifiedTrail(
          config = UnifiedTrailConfig(id = "pos/tip", target = "pos", devices = declaredDevices),
          trail = listOf(UnifiedTrailStep(step = "The buyer chooses a tip")),
        ),
      ),
    )
    // The intermediate the run produces: seeded from the executed trail (so it carries the cast),
    // with this session's leg keyed by the configuration name.
    val recording = File(dir, "recording.trail.yaml").apply {
      writeText(
        yaml.encodeUnifiedTrailToString(
          UnifiedTrailAdapter.mergeRecordedClassifier(
            existing = yaml.decodeUnifiedTrail(trailFile.readText()),
            recordedItems = listOf(
              TrailYamlItem.ConfigTrailItem(TrailConfig(id = "pos/tip", target = "pos", driver = "ANDROID_ONDEVICE_ACCESSIBILITY")),
              TrailYamlItem.PromptsTrailItem(
                listOf(DirectionStep(step = "The buyer chooses a tip", recording = ToolRecording(tools = listOf(tool("tapTip"))))),
              ),
            ),
            classifier = "pos-pair",
            selectedDeviceConfiguration = "pos-pair",
          ),
        ),
      )
    }

    cmd.saveRecordingAsUnified(dir, recording, listOf("lab-a"), selectedDeviceConfiguration = "pos-pair")

    val unified = yaml.decodeUnifiedTrail(trailFile.readText())
    val step = unified.trail.single()
    assertEquals(listOf("tapTip"), step.recordings["pos-pair"]?.map { it.name }, "leg keyed by the configuration name")
    assertNull(step.recordings["lab-a"], "no leg keyed by the start device's classifier chain")
    assertEquals(declaredDevices, unified.config.devices, "cast preserved byte-identical, no driver pin added")
  }

  @Test
  fun `shouldSaveRecording checks the configuration slot for a configuration session`() {
    val cmd = command()
    val dir = tempFolder.newFolder()
    val yaml = createTrailblazeYaml()
    File(dir, TrailRecordings.UNIFIED_TRAIL_FILENAME).writeText(
      yaml.encodeUnifiedTrailToString(
        UnifiedTrail(
          config = UnifiedTrailConfig(id = "pos/tip", target = "pos", devices = configurationDevices()),
          trail = listOf(
            UnifiedTrailStep(
              step = "The buyer chooses a tip",
              recordings = mapOf("pos-pair" to listOf(tool("tapTip"))),
            ),
          ),
        ),
      ),
    )

    assertFalse(
      cmd.shouldSaveRecording(dir, listOf("lab-a"), selectedDeviceConfiguration = "pos-pair"),
      "the pos-pair slot is already recorded, so a plain replay of the configuration skips the save",
    )
    assertTrue(
      cmd.shouldSaveRecording(dir, listOf("lab-a"), selectedDeviceConfiguration = null),
      "the launch device's own chain has no leg — the guard reads the configuration slot, not this",
    )

    // First authoring of the same configuration: same cast, no leg recorded yet.
    val unrecorded = tempFolder.newFolder()
    File(unrecorded, TrailRecordings.UNIFIED_TRAIL_FILENAME).writeText(
      yaml.encodeUnifiedTrailToString(
        UnifiedTrail(
          config = UnifiedTrailConfig(id = "pos/tip", target = "pos", devices = configurationDevices()),
          trail = listOf(UnifiedTrailStep(step = "The buyer chooses a tip")),
        ),
      ),
    )
    assertTrue(
      cmd.shouldSaveRecording(unrecorded, listOf("lab-a"), selectedDeviceConfiguration = "pos-pair"),
      "the configuration has no leg yet, so first authoring saves",
    )
  }

  /** An authored `config.devices:` cast: the `pos-pair` configuration plus an unrelated single entry. */
  private fun configurationDevices(): Map<String, xyz.block.trailblaze.yaml.unified.TrailblazeDeviceDefinition> =
    linkedMapOf(
      "pos-pair" to xyz.block.trailblaze.yaml.unified.TrailblazeDeviceDefinition(
        devices = linkedMapOf(
          "seller" to xyz.block.trailblaze.yaml.unified.TrailblazeDeviceDefinition(classifier = "lab-a"),
          "buyer" to xyz.block.trailblaze.yaml.unified.TrailblazeDeviceDefinition(classifier = "lab-b"),
        ),
      ),
      "android-tablet" to xyz.block.trailblaze.yaml.unified.TrailblazeDeviceDefinition(
        driver = xyz.block.trailblaze.devices.TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
      ),
    )

  // ---------------------------------------------------------------------------
  // Recording generation and save-back read the configured logs directory
  // ---------------------------------------------------------------------------

  @Test
  fun `saveRecordingToTrailDirectory reads the recording from the given logs directory`() {
    // `logsDirectory` is a persisted setting that can point anywhere, including outside the
    // checkout. The save-back must read the session's recording from THAT directory — this test
    // runs inside the repo, so a regression back to the old hard-coded `<git root>/logs` path
    // finds no recording for this session and silently skips the save.
    val cmd = command()
    val logsDir = tempFolder.newFolder("custom-logs-root")
    val sessionId = SessionId("session-under-custom-logs-dir")
    File(logsDir, sessionId.value).apply { mkdirs() }
      .resolve("recording.trail.yaml")
      .writeText(unifiedRecordingYaml(driver = "ANDROID_ONDEVICE_INSTRUMENTATION", toolName = "tapCart", classifier = "android-phone"))

    val trailDir = tempFolder.newFolder("trail-src")
    // An existing per-device sibling routes the save to CLASSIFIER_SIBLING, so the recording
    // lands as `android-phone.trail.yaml` beside it.
    writeUnifiedWithSlot(File(trailDir, "ios.trail.yaml"), "ios")
    val trailFile = File(trailDir, "android-phone.trail.yaml")

    cmd.saveRecordingToTrailDirectory(
      trailFile, sessionId, listOf("android-phone"), selectedDeviceConfiguration = null, logsDir = logsDir,
    )

    val saved = File(trailDir, "android-phone.trail.yaml")
    assertTrue(saved.exists(), "the recording read from the custom logs directory must be saved beside the trail")
    assertTrue(saved.readText().contains("tapCart"), "the saved trail must carry the recorded tool")
  }

  @Test
  fun `generateRecordingForSession writes the recording into the given logs directory`() {
    // The producer half of the pair above: the on-device/delegated path generates
    // `recording.trail.yaml` from the session's logs on disk. Both the read of the logs and the
    // write of the recording must happen under the configured logs directory, or the save-back
    // (which reads the same directory) finds nothing.
    val cmd = command()
    val logsDir = tempFolder.newFolder("producer-logs-root")
    val sessionId = SessionId("producer-session")
    val sessionDir = File(logsDir, sessionId.value).apply { mkdirs() }
    val step = DirectionStep(step = "Open the cart")
    // The Started log supplies the device classifier the recording's slot is keyed by.
    writeLog(
      sessionDir,
      "000.json",
      TrailblazeLog.TrailblazeSessionStatusChangeLog(
        sessionStatus = SessionStatus.Started(
          trailConfig = null,
          trailFilePath = null,
          hasRecordedSteps = false,
          testMethodName = "test",
          testClassName = "Test",
          trailblazeDeviceInfo = TrailblazeDeviceInfo(
            trailblazeDeviceId = TrailblazeDeviceId(
              instanceId = "pixel-7",
              trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
            ),
            trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
            widthPixels = 1080,
            heightPixels = 1920,
            classifiers = listOf(TrailblazeDeviceClassifier("android-phone")),
          ),
          rawYaml = null,
        ),
        session = sessionId,
        timestamp = FIXED_NOW,
      ),
    )
    writeLog(sessionDir, "001.json", TrailblazeLog.ObjectiveStartLog(promptStep = step, session = sessionId, timestamp = FIXED_NOW))
    writeLog(
      sessionDir,
      "002.json",
      TrailblazeLog.TrailblazeToolLog(
        trailblazeTool = tool("tapCart").trailblazeTool.toLogPayload(),
        rawTrailblazeTool = null,
        toolName = "tapCart",
        successful = true,
        traceId = null,
        durationMs = 100,
        session = sessionId,
        timestamp = FIXED_NOW,
        isRecordable = true,
        isTopLevelToolCall = false,
        isVerification = false,
      ),
    )
    writeLog(
      sessionDir,
      "003.json",
      TrailblazeLog.ObjectiveCompleteLog(
        promptStep = step,
        objectiveResult = AgentTaskStatus.Success.ObjectiveComplete(
          llmExplanation = "Done",
          statusData = AgentTaskStatusData(
            taskId = TaskId.generate(),
            prompt = step.prompt,
            callCount = 1,
            taskStartTime = FIXED_NOW,
            totalDurationMs = 100,
          ),
        ),
        session = sessionId,
        timestamp = FIXED_NOW,
      ),
    )

    cmd.generateRecordingForSession(sessionId, logsDir)

    val recording = File(sessionDir, "recording.trail.yaml")
    assertTrue(recording.exists(), "the recording must be generated into the configured logs directory")
    assertTrue(recording.readText().contains("tapCart"), "the recording must carry the session's recorded tool")
  }

  @Test
  fun `generateRecordingForSession writes a recording for an AI-blazed multi-tool trailhead`() {
    // The reported symptom: a green run whose natural-language trailhead was satisfied by several
    // tools (four here, five live) produced NO recording.trail.yaml at all — the render threw, was
    // caught, and the CLI logged "No recording data". Every tool must reach the file, split across
    // the trailhead slot (which holds one) and the first step. Tool names are stand-ins, as
    // elsewhere in this file: what matters is how many there are and the order they replay in.
    val cmd = command()
    val logsDir = tempFolder.newFolder("blazed-trailhead-logs")
    val sessionId = SessionId("blazed-trailhead-session")
    val sessionDir = File(logsDir, sessionId.value).apply { mkdirs() }
    val trailhead = DirectionStep(step = "Launch the sample app", isTrailhead = true)
    val firstStep = DirectionStep(step = "Open the cart")
    // Distinct, increasing timestamps: the generator orders the session by timestamp, so equal
    // stamps would let the directory's listing order decide which window each tool fell in.
    listOf(
      startedLog(sessionId, classifier = "android-phone"),
      objectiveStartLog(sessionId, trailhead),
      recordableToolLog(sessionId, "bootstrapProbe", atSecond(1)),
      recordableToolLog(sessionId, "bootstrapLaunch", atSecond(2)),
      recordableToolLog(sessionId, "bootstrapSettle", atSecond(3)),
      recordableToolLog(sessionId, "bootstrapCheck", atSecond(4)),
      objectiveCompleteLog(sessionId, trailhead, atSecond(5)),
      objectiveStartLog(sessionId, firstStep, atSecond(6)),
      recordableToolLog(sessionId, "tapCart", atSecond(7)),
      objectiveCompleteLog(sessionId, firstStep, atSecond(8)),
    ).forEachIndexed { i, log -> writeLog(sessionDir, "%03d.json".format(i), log) }

    cmd.generateRecordingForSession(sessionId, logsDir)

    val recording = File(sessionDir, "recording.trail.yaml")
    assertTrue(recording.exists(), "a green AI-driven run must leave a recording behind")
    // Decoded, not grepped: the file has to be one the reader accepts, since that is what the
    // save-back and any replay will do with it.
    val unified = createTrailblazeYaml().decodeUnifiedTrail(recording.readText())
    assertEquals(
      listOf("bootstrapProbe"),
      unified.trailhead?.recordings?.get("android-phone")?.map { it.name },
    )
    assertEquals(
      listOf("bootstrapLaunch", "bootstrapSettle", "bootstrapCheck", "tapCart"),
      unified.trail.single().recordings["android-phone"]?.map { it.name },
      "the trailhead's remaining tools replay at the start of step 1, in the order they ran",
    )
  }

  @Test
  fun `the daemon's reported logs dir outranks the client's own resolution`() {
    // The daemon pins its logs repo at boot, so a client attached to a daemon started from another
    // checkout resolves a different directory from the same settings. The run's own response is
    // the only authority on where its session actually landed.
    val cmd = command()
    val daemonDir = tempFolder.newFolder("daemon-logs")
    val clientDir = tempFolder.newFolder("client-logs")

    assertEquals(
      daemonDir,
      cmd.sessionLogsDir(CliRunResponse(success = true, logsDir = daemonDir.absolutePath), fallback = clientDir),
    )
  }

  @Test
  fun `an older daemon that sends no logs dir falls back to the client's resolution`() {
    // Absent must degrade rather than fail: the field is new, and a mixed client/daemon pair is
    // the normal state right after an upgrade.
    val cmd = command()
    val clientDir = tempFolder.newFolder("fallback-logs")

    assertEquals(clientDir, cmd.sessionLogsDir(CliRunResponse(success = true), fallback = clientDir))
    assertEquals(
      clientDir,
      cmd.sessionLogsDir(CliRunResponse(success = true, logsDir = "  "), fallback = clientDir),
    )
  }

  @Test
  fun `a device-clock skew must not pull a step's first tool into the trailhead window`() {
    // The live repro: a replayed run whose ObjectiveStart/Complete logs are stamped by the HOST
    // runner while each tool's TrailblazeToolLog is stamped ON DEVICE, with the device clock ~0.7s
    // behind. Sorted by timestamp, step 1's first tool then lands before the trailhead's complete
    // log — the trailhead collects 2 tools, the unified emitter's one-tool-per-platform rule
    // throws, and the CLI writes NO recording for a passing run. Window membership must follow the
    // tool's execution-span overlap, not its sorted position.
    val cmd = command()
    val logsDir = tempFolder.newFolder("skewed-logs-root")
    val sessionId = SessionId("skewed-clock-session")
    val sessionDir = File(logsDir, sessionId.value).apply { mkdirs() }
    // The authored trail the run replayed: its one-tool trailhead is declared under the `android:`
    // family leg, while the run records under the device's own `android-phone` slot. Tool names are
    // synthetic stand-ins (not registered tools) so the YAML round-trips via the generic decoder.
    val authoredYaml = createTrailblazeYaml().encodeUnifiedTrailToString(
      UnifiedTrail(
        config = UnifiedTrailConfig(id = "app/skew", target = "app"),
        trailhead = UnifiedTrailStep(
          step = "Launch the app",
          recordings = mapOf("android" to listOf(tool("bootstrapLaunch"))),
        ),
        trail = listOf(
          UnifiedTrailStep(
            step = "Open the menu",
            recordings = mapOf("android" to listOf(tool("tapMenu"), tool("scrollList"))),
          ),
        ),
      ),
    )
    val trailheadStep = DirectionStep(step = "Launch the app", isTrailhead = true)
    val menuStep = DirectionStep(step = "Open the menu")
    fun at(ms: Long): Instant = Instant.fromEpochMilliseconds(FIXED_NOW.toEpochMilliseconds() + ms)
    writeLog(
      sessionDir,
      "000.json",
      TrailblazeLog.TrailblazeSessionStatusChangeLog(
        sessionStatus = SessionStatus.Started(
          trailConfig = null,
          trailFilePath = null,
          hasRecordedSteps = true,
          testMethodName = "test",
          testClassName = "Test",
          trailblazeDeviceInfo = TrailblazeDeviceInfo(
            trailblazeDeviceId = TrailblazeDeviceId(
              instanceId = "pixel-7",
              trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
            ),
            trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
            widthPixels = 1080,
            heightPixels = 1920,
            classifiers = listOf(TrailblazeDeviceClassifier("android-phone")),
          ),
          rawYaml = authoredYaml,
        ),
        session = sessionId,
        timestamp = at(0),
      ),
    )
    // Host clock: trailhead window [100, 3800], menu window [3810, 10400].
    writeLog(sessionDir, "001.json", TrailblazeLog.ObjectiveStartLog(promptStep = trailheadStep, session = sessionId, timestamp = at(100)))
    // Device clock (~0.7s behind the host): the trailhead's own tool sits inside its window…
    writeLog(sessionDir, "002.json", skewedToolLog("bootstrapLaunch", sessionId, timestamp = at(1_900), durationMs = 500))
    writeLog(sessionDir, "003.json", objectiveCompleteLog(trailheadStep, sessionId, timestamp = at(3_800)))
    writeLog(sessionDir, "004.json", TrailblazeLog.ObjectiveStartLog(promptStep = menuStep, session = sessionId, timestamp = at(3_810)))
    // …but the menu step's first tool's device stamp (3200) sorts BEFORE the trailhead's complete
    // (3800). Its span [3200, 6600] overlaps the menu window far more than the trailhead's.
    writeLog(sessionDir, "005.json", skewedToolLog("tapMenu", sessionId, timestamp = at(3_200), durationMs = 3_400))
    writeLog(sessionDir, "006.json", skewedToolLog("scrollList", sessionId, timestamp = at(6_900), durationMs = 1_000))
    writeLog(sessionDir, "007.json", objectiveCompleteLog(menuStep, sessionId, timestamp = at(10_400)))

    cmd.generateRecordingForSession(sessionId, logsDir)

    val recording = File(sessionDir, "recording.trail.yaml")
    assertTrue(recording.exists(), "a passing skewed-clock run must still render its recording")
    val unified = createTrailblazeYaml().decodeUnifiedTrail(recording.readText())
    assertEquals(
      listOf("bootstrapLaunch"),
      unified.trailhead?.recordings?.get("android-phone")?.map { it.name },
      "the trailhead records exactly its own tool — not the next step's skew-shifted first tool",
    )
    assertEquals(
      listOf("bootstrapLaunch"),
      unified.trailhead?.recordings?.get("android")?.map { it.name },
      "the authored android family leg is preserved untouched",
    )
    assertEquals(
      listOf("tapMenu", "scrollList"),
      unified.trail.single().recordings["android-phone"]?.map { it.name },
      "the step keeps both of its own tools",
    )
  }

  /** A device-stamped recordable tool log, as the on-device runner path emits (no top-level mark). */
  private fun skewedToolLog(
    toolName: String,
    sessionId: SessionId,
    timestamp: Instant,
    durationMs: Long,
  ) = TrailblazeLog.TrailblazeToolLog(
    trailblazeTool = tool(toolName).trailblazeTool.toLogPayload(),
    rawTrailblazeTool = null,
    toolName = toolName,
    successful = true,
    traceId = null,
    durationMs = durationMs,
    session = sessionId,
    timestamp = timestamp,
    isRecordable = true,
    isTopLevelToolCall = false,
    isVerification = false,
  )

  private fun objectiveCompleteLog(step: DirectionStep, sessionId: SessionId, timestamp: Instant) =
    TrailblazeLog.ObjectiveCompleteLog(
      promptStep = step,
      objectiveResult = AgentTaskStatus.Success.ObjectiveComplete(
        llmExplanation = "Done",
        statusData = AgentTaskStatusData(
          taskId = TaskId.generate(),
          prompt = step.prompt,
          callCount = 1,
          taskStartTime = timestamp,
          totalDurationMs = 100,
        ),
      ),
      session = sessionId,
      timestamp = timestamp,
    )

  private fun writeLog(sessionDir: File, fileName: String, log: TrailblazeLog) {
    File(sessionDir, fileName).writeText(TrailblazeJsonInstance.encodeToString<TrailblazeLog>(log))
  }

  /** The `Started` log supplying the device [classifier] a recording's slot is keyed by. */
  private fun startedLog(sessionId: SessionId, classifier: String) =
    TrailblazeLog.TrailblazeSessionStatusChangeLog(
      sessionStatus = SessionStatus.Started(
        trailConfig = null,
        trailFilePath = null,
        hasRecordedSteps = false,
        testMethodName = "test",
        testClassName = "Test",
        trailblazeDeviceInfo = TrailblazeDeviceInfo(
          trailblazeDeviceId = TrailblazeDeviceId(
            instanceId = "pixel-7",
            trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
          ),
          trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
          widthPixels = 1080,
          heightPixels = 1920,
          classifiers = listOf(TrailblazeDeviceClassifier(classifier)),
        ),
        rawYaml = null,
      ),
      session = sessionId,
      timestamp = FIXED_NOW,
    )

  /** [FIXED_NOW] plus [seconds], for fixtures that need the session's log order to be unambiguous. */
  private fun atSecond(seconds: Int): Instant =
    Instant.fromEpochMilliseconds(FIXED_NOW.toEpochMilliseconds() + seconds * 1_000L)

  private fun objectiveStartLog(sessionId: SessionId, step: DirectionStep, timestamp: Instant = FIXED_NOW) =
    TrailblazeLog.ObjectiveStartLog(promptStep = step, session = sessionId, timestamp = timestamp)

  private fun objectiveCompleteLog(sessionId: SessionId, step: DirectionStep, timestamp: Instant = FIXED_NOW) =
    TrailblazeLog.ObjectiveCompleteLog(
      promptStep = step,
      objectiveResult = AgentTaskStatus.Success.ObjectiveComplete(
        llmExplanation = "Done",
        statusData = AgentTaskStatusData(
          taskId = TaskId.generate(),
          prompt = step.prompt,
          callCount = 1,
          taskStartTime = FIXED_NOW,
          totalDurationMs = 100,
        ),
      ),
      session = sessionId,
      timestamp = timestamp,
    )

  private fun recordableToolLog(sessionId: SessionId, toolName: String, timestamp: Instant = FIXED_NOW) =
    TrailblazeLog.TrailblazeToolLog(
      trailblazeTool = tool(toolName).trailblazeTool.toLogPayload(),
      rawTrailblazeTool = null,
      toolName = toolName,
      successful = true,
      traceId = null,
      durationMs = 100,
      session = sessionId,
      timestamp = timestamp,
      isRecordable = true,
      isTopLevelToolCall = false,
      isVerification = false,
    )

  private companion object {
    /** Any fixed instant — the generator only sorts by timestamp. */
    val FIXED_NOW: Instant = Instant.parse("2026-01-01T00:00:00Z")
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
      TrailYamlItem.ConfigTrailItem(TrailConfig(id = "x", target = "y", driver = "ANDROID_ONDEVICE_INSTRUMENTATION")),
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
          TrailYamlItem.ConfigTrailItem(TrailConfig(id = "app/x", target = "app", driver = "ANDROID_ONDEVICE_INSTRUMENTATION")),
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
