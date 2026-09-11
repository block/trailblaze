package xyz.block.trailblaze.cli

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Contract for the trail-target half of [CheckCommand.runTrailLintPhase]: it REPORTS an unresolvable
 * `config.target:` without failing the build.
 *
 * Advisory on purpose, and the only one of the phase's three lints that is. No CLI command writes a
 * `trailmap.yaml`, so a fatal finding hands a first-time user a broken build and nothing to run;
 * targets are the recommended path, not a precondition for using the framework. The fatal version
 * applies to this repo's own trails via `TrailTargetCorpusTest`.
 *
 * Asserted through captured stderr rather than a return value because the warning IS the deliverable
 * here — a silent phase and a phase that reported nothing useful both return [CheckCommand.EXIT_OK].
 */
class CheckCommandTrailTargetPhaseTest {

  private val command = CheckCommand()

  /** Runs [block] with stderr captured. `Console.error` resolves `System.err` per call, so this works. */
  private fun capturingStderr(block: () -> Unit): String {
    val captured = ByteArrayOutputStream()
    val original = System.err
    System.setErr(PrintStream(captured, true))
    try {
      block()
    } finally {
      System.setErr(original)
    }
    return captured.toString()
  }

  private fun trailNaming(target: String): String = """
    config:
      target: $target
    trail:
    - step: Open the app.
  """.trimIndent()

  /** A workspace whose only registered trailmap is `demo`, holding one trail that names [target]. */
  private fun workspaceNaming(target: String): File {
    val workspace = Files.createTempDirectory("trailblaze-check-target").toFile()
    File(workspace, "trails/config/trailmaps/demo").mkdirs()
    File(workspace, "trails/config/trailmaps/demo/trailmap.yaml")
      .writeText("id: demo\ntarget:\n  display_name: Demo\n")
    File(workspace, "trails/flows").mkdirs()
    File(workspace, "trails/flows/example.trail.yaml").writeText(trailNaming(target))
    return workspace
  }

  @Test
  fun `phase says nothing when the trail names a trailmap the workspace registers`() {
    val workspace = workspaceNaming("demo")
    try {
      var exit = -1
      val stderr = capturingStderr { exit = command.runTrailLintPhase(workspace) }
      assertEquals(CheckCommand.EXIT_OK, exit)
      assertFalse(stderr.contains("trail-target"), "a resolvable target must produce no output")
    } finally {
      workspace.deleteRecursively()
    }
  }

  @Test
  fun `an unregistered target warns without failing the build`() {
    val workspace = workspaceNaming("nosuchtarget")
    try {
      var exit = -1
      val stderr = capturingStderr { exit = command.runTrailLintPhase(workspace) }
      // The whole point: reported, but `trailblaze check` still succeeds. A first-time user with no
      // trailmap gets told what is wrong and is not blocked.
      assertEquals(CheckCommand.EXIT_OK, exit)
      assertContains(stderr, "trail-target check (warning)")
      assertContains(stderr, "WARN flows/example.trail.yaml: config.target: nosuchtarget")
      assertFalse(stderr.contains("FATAL"), "the CLI half must not present itself as fatal")
    } finally {
      workspace.deleteRecursively()
    }
  }

  @Test
  fun `the warning tells the reader how to fix it, including doing nothing`() {
    val workspace = workspaceNaming("calculator")
    try {
      val stderr = capturingStderr { command.runTrailLintPhase(workspace) }
      // There is no `trailblaze init`, so the message has to carry the instructions itself.
      assertContains(stderr, "Drop `config.target:` from the trail")
      assertContains(stderr, "targets are recommended, not required")
      assertContains(stderr, "trails/config/trailmaps/calculator/trailmap.yaml")
      assertContains(stderr, "id: calculator")
      assertTrue(
        stderr.contains("app_ids:"),
        "the snippet must show where the launch package goes, not just the id",
      )
    } finally {
      workspace.deleteRecursively()
    }
  }

  @Test
  fun `a target declared by a bare targets yaml resolves, with no trailmap of its own`() {
    // `<configDir>/targets/*.yaml` is a documented way to declare a target WITHOUT a trailmap, and
    // `AppTargetDiscovery` loads it through the same workspace-layered resource source as everything
    // else. Judging trails against trailmaps alone would warn on a target that resolves perfectly.
    val workspace = workspaceNaming("standalone")
    try {
      File(workspace, "trails/config/targets").mkdirs()
      File(workspace, "trails/config/targets/standalone.yaml")
        .writeText("id: standalone\ndisplay_name: Standalone\n")
      val stderr = capturingStderr { command.runTrailLintPhase(workspace) }
      assertFalse(stderr.contains("trail-target"), "a bare targets/*.yaml registers a real target")
    } finally {
      workspace.deleteRecursively()
    }
  }

  @Test
  fun `an explicit target id REPLACES the manifest id rather than adding to it`() {
    // `toAppTargetYamlConfig` resolves `id ?: defaultId`, so a manifest carrying `target.id:` registers
    // under that name ONLY. Counting the directory name too would go quiet on the trail that is
    // actually broken.
    val workspace = workspaceNaming("renamed")
    try {
      File(workspace, "trails/config/trailmaps/demo/trailmap.yaml")
        .writeText("id: demo\ntarget:\n  id: renamed\n  display_name: Renamed\n")
      val stderr = capturingStderr { command.runTrailLintPhase(workspace) }
      assertFalse(stderr.contains("trail-target"), "the explicit target id must resolve")

      File(workspace, "trails/flows/example.trail.yaml").writeText(trailNaming("demo"))
      val byDirName = capturingStderr { command.runTrailLintPhase(workspace) }
      assertContains(byDirName, "WARN flows/example.trail.yaml: config.target: demo")
    } finally {
      workspace.deleteRecursively()
    }
  }

  @Test
  fun `a library trailmap registers no target, so naming it is still a warning`() {
    // A manifest with no `target:` block is a library — `TrailblazeProjectConfigLoader` builds a target
    // only from that block, so `config.target: helpers` degrades to the workspace default and passes.
    // A directory that exists is not a target that resolves.
    val workspace = workspaceNaming("helpers")
    try {
      File(workspace, "trails/config/trailmaps/helpers").mkdirs()
      File(workspace, "trails/config/trailmaps/helpers/trailmap.yaml").writeText("id: helpers\n")
      val stderr = capturingStderr { command.runTrailLintPhase(workspace) }
      assertContains(stderr, "WARN flows/example.trail.yaml: config.target: helpers")
      assertFalse(
        stderr.contains("resolves: demo, helpers"),
        "a library id must not be offered as something to name instead",
      )
    } finally {
      workspace.deleteRecursively()
    }
  }

  /**
   * A standalone-layout workspace whose scan root is the workspace root itself, holding a NESTED
   * workspace with its own trailmap. Each trail names the target its OWN workspace registers.
   */
  private fun nestedWorkspaces(): File {
    val parent = Files.createTempDirectory("trailblaze-check-nested").toFile()
    File(parent, "trailblaze-config/trailmaps/parentapp").mkdirs()
    File(parent, "trailblaze-config/trailmaps/parentapp/trailmap.yaml")
      .writeText("id: parentapp\ntarget:\n  display_name: Parent\n")
    File(parent, "trails").mkdirs()
    File(parent, "trails/parent.trail.yaml").writeText(trailNaming("parentapp"))

    val nested = File(parent, "nested")
    File(nested, "trailblaze-config/trailmaps/nestedapp").mkdirs()
    File(nested, "trailblaze-config/trailmaps/nestedapp/trailmap.yaml")
      .writeText("id: nestedapp\ntarget:\n  display_name: Nested\n")
    File(nested, "trails").mkdirs()
    File(nested, "trails/nested.trail.yaml").writeText(trailNaming("nestedapp"))
    return parent
  }

  @Test
  fun `a nested workspace's trail is judged against the trailmaps that workspace registers`() {
    val parent = nestedWorkspaces()
    try {
      // `nestedapp` is invisible to the parent, but the nested trail is not the parent's to flag.
      val clean = capturingStderr { command.runTrailLintPhase(parent) }
      assertFalse(clean.contains("trail-target"), "nested trail was judged by the parent's ids")

      // The converse still holds: the PARENT naming the nested-only id IS a finding, because running
      // that trail from the parent workspace degrades to the parent's default exactly like a typo.
      File(parent, "trails/parent.trail.yaml").writeText(trailNaming("nestedapp"))
      val warned = capturingStderr { command.runTrailLintPhase(parent) }
      assertContains(warned, "WARN trails/parent.trail.yaml: config.target: nestedapp")
    } finally {
      parent.deleteRecursively()
    }
  }

  @Test
  fun `the trail-target kill-switch is independent of the other gates`() {
    val workspace = workspaceNaming("nosuchtarget")
    try {
      val silenced = capturingStderr {
        assertEquals(
          CheckCommand.EXIT_OK,
          command.runTrailLintPhase(workspace, trailTargetGateEnabled = false),
        )
      }
      assertFalse(silenced.contains("trail-target"), "the kill-switch must silence the warning")

      // Turning the OTHER two off must leave this one reporting — three lints, three independent
      // switches, so silencing a migration-era gate can't silently drop this one too.
      val stillWarns = capturingStderr {
        command.runTrailLintPhase(
          workspace,
          selectorDialectGateEnabled = false,
          devicePinGateEnabled = false,
        )
      }
      assertContains(stillWarns, "trail-target check (warning)")
    } finally {
      workspace.deleteRecursively()
    }
  }
}
