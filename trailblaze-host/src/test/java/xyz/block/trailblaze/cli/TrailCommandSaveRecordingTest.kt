package xyz.block.trailblaze.cli

import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import picocli.CommandLine
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
}
