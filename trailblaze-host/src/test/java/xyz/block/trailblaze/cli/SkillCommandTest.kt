package xyz.block.trailblaze.cli

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the observable contract of `trailblaze skill`:
 *
 *  - The skill is actually bundled: the manifest is on the classpath, lists the entry
 *    point `SKILL.md`, and every listed file resolves. This is the test that catches a
 *    broken `copyAgentSkillResources` staging task — the whole feature exists so an
 *    installed CLI (Homebrew, install.sh) carries the skill inside its JAR.
 *  - `skill show` prints a bundled file to stdout (SKILL.md by default) and exits 0;
 *    an unknown file is MISUSE with nothing on stdout.
 *  - `skill install --dir` writes exactly the manifest's files and is idempotent
 *    (re-install overwrites cleanly — it doubles as upgrade).
 */
class SkillCommandTest {

  @get:Rule val tempFolder = TemporaryFolder()

  /** Seed a minimal installed skill copy at [dir], optionally with a version stamp. */
  private fun seedInstall(dir: Path, stampedVersion: String?) {
    dir.createDirectories()
    dir.resolve("SKILL.md").writeText("---\nname: trailblaze\n---\n")
    if (stampedVersion != null) {
      dir.resolve(BundledAgentSkill.VERSION_STAMP_FILE).writeText(stampedVersion + "\n")
    }
  }

  // ---------------------------------------------------------------------------
  // Bundled resources
  // ---------------------------------------------------------------------------

  @Test
  fun `bundled manifest lists SKILL_md and every entry resolves`() {
    val files = BundledAgentSkill.files()
    assertTrue(files.isNotEmpty(), "expected the agent skill to be bundled on the classpath")
    assertTrue("SKILL.md" in files, "manifest must include the SKILL.md entry point, got: $files")
    for (file in files) {
      val content = BundledAgentSkill.readFile(file)
      assertTrue(!content.isNullOrBlank(), "manifest entry '$file' did not resolve to a non-empty resource")
    }
  }

  @Test
  fun `bundled SKILL_md is the trailblaze skill`() {
    val skill = BundledAgentSkill.readFile("SKILL.md")!!
    // The frontmatter `name:` is the skill's identity — the one line that must hold
    // regardless of how the prose evolves.
    assertTrue(skill.contains("name: trailblaze"), "SKILL.md should declare the trailblaze skill")
  }

  // ---------------------------------------------------------------------------
  // skill show
  // ---------------------------------------------------------------------------

  @Test
  fun `show with no arg prints SKILL_md and exits SUCCESS`() {
    val cmd = SkillShowCommand()
    var exit = -1
    val stdout = captureStdout { exit = cmd.call() }
    assertEquals(TrailblazeExitCode.SUCCESS.code, exit)
    // The command guarantees a trailing newline; the content itself must be verbatim.
    assertEquals(BundledAgentSkill.readFile("SKILL.md")!!.trimEnd('\n'), stdout.trimEnd('\n'))
    assertTrue(stdout.endsWith("\n"), "shown file should end with a newline")
  }

  @Test
  fun `show with a reference path prints that file`() {
    val reference = BundledAgentSkill.files().first { it.startsWith("references/") }
    val cmd = SkillShowCommand().apply { file = reference }
    var exit = -1
    val stdout = captureStdout { exit = cmd.call() }
    assertEquals(TrailblazeExitCode.SUCCESS.code, exit)
    assertEquals(BundledAgentSkill.readFile(reference)!!.trimEnd('\n'), stdout.trimEnd('\n'))
  }

  @Test
  fun `show with an unknown file is MISUSE with nothing on stdout`() {
    val cmd = SkillShowCommand().apply { file = "references/does-not-exist.md" }
    var exit = -1
    val stdout = captureStdout { exit = cmd.call() }
    assertEquals(TrailblazeExitCode.MISUSE.code, exit)
    assertEquals("", stdout)
  }

  // ---------------------------------------------------------------------------
  // skill (bare index)
  // ---------------------------------------------------------------------------

  @Test
  fun `bare skill lists every bundled file on stdout`() {
    var exit = -1
    val stdout = captureStdout { exit = SkillCommand().call() }
    assertEquals(TrailblazeExitCode.SUCCESS.code, exit)
    for (file in BundledAgentSkill.files()) {
      assertTrue(file in stdout, "index output should list bundled file '$file'")
    }
  }

  // ---------------------------------------------------------------------------
  // skill install
  // ---------------------------------------------------------------------------

  @Test
  fun `install writes every manifest file into --dir and re-install overwrites cleanly`() {
    val dest = tempFolder.newFolder("skills").toPath()
    val cmd = SkillInstallCommand().apply { dir = dest }

    assertEquals(TrailblazeExitCode.SUCCESS.code, cmd.call())
    for (file in BundledAgentSkill.files()) {
      val written = dest.resolve(file).toFile()
      assertTrue(written.isFile, "expected installed file at $written")
      assertEquals(BundledAgentSkill.readFile(file)!!, written.readText())
    }

    // Re-install over a locally-modified copy restores the shipped content (install
    // doubles as upgrade), and leaves unrelated user files alone.
    dest.resolve("SKILL.md").toFile().writeText("locally modified")
    val userFile = dest.resolve("my-notes.md").toFile().apply { writeText("mine") }
    assertEquals(TrailblazeExitCode.SUCCESS.code, cmd.call())
    assertEquals(BundledAgentSkill.readFile("SKILL.md")!!, dest.resolve("SKILL.md").toFile().readText())
    assertEquals("mine", userFile.readText())
  }

  @Test
  fun `install stamps the destination with the CLI version`() {
    val dest = tempFolder.newFolder("skills").toPath()
    assertEquals(TrailblazeExitCode.SUCCESS.code, SkillInstallCommand().apply { dir = dest }.call())
    val stamp = dest.resolve(BundledAgentSkill.VERSION_STAMP_FILE)
    assertTrue(stamp.exists(), "install should write the version stamp")
    assertEquals(BundledAgentSkill.version, stamp.readText().trim())
    // The stamp is install metadata, not a shipped file, so it stays out of the manifest.
    assertFalse(BundledAgentSkill.VERSION_STAMP_FILE in BundledAgentSkill.files())
  }

  @Test
  fun `install --agent codex writes into the shared agents directory, not claude's`() {
    val base = tempFolder.newFolder("project").toPath()
    val cmd = SkillInstallCommand().apply {
      agentName = "codex"
      baseDir = base
    }
    assertEquals(TrailblazeExitCode.SUCCESS.code, cmd.call())
    val expected = base.resolve(".agents").resolve("skills").resolve("trailblaze")
    assertTrue(expected.resolve("SKILL.md").exists(), "codex install should land under .agents/skills/trailblaze")
    // The default (claude) location is untouched.
    assertFalse(base.resolve(".claude").resolve("skills").resolve("trailblaze").resolve("SKILL.md").exists())
  }

  @Test
  fun `every non-claude agent alias resolves to the shared agents directory`() {
    val base = tempFolder.newFolder("project").toPath()
    for (alias in listOf("codex", "cursor", "gemini", "goose", "agents", "standard")) {
      val cmd = SkillInstallCommand().apply { agentName = alias; baseDir = base }
      assertEquals(TrailblazeExitCode.SUCCESS.code, cmd.call(), "install --agent $alias should succeed")
    }
    assertTrue(SkillTarget.AGENTS.projectDir(base).resolve("SKILL.md").exists())
    assertFalse(SkillTarget.CLAUDE.projectDir(base).resolve("SKILL.md").exists())
  }

  @Test
  fun `install --all writes both the claude and shared agents locations`() {
    val base = tempFolder.newFolder("project").toPath()
    assertEquals(TrailblazeExitCode.SUCCESS.code, SkillInstallCommand().apply { all = true; baseDir = base }.call())
    for (target in SkillTarget.entries) {
      assertTrue(
        target.projectDir(base).resolve("SKILL.md").exists(),
        "expected an installed copy at ${target.id}",
      )
    }
  }

  @Test
  fun `install --global writes under the home directory`() {
    val home = tempFolder.newFolder("home").toPath()
    val base = tempFolder.newFolder("project").toPath()
    assertEquals(
      TrailblazeExitCode.SUCCESS.code,
      SkillInstallCommand().apply { global = true; baseDir = base; homeDir = home }.call(),
    )
    assertTrue(SkillTarget.CLAUDE.globalDir(home).resolve("SKILL.md").exists())
    assertFalse(SkillTarget.CLAUDE.projectDir(base).resolve("SKILL.md").exists())
  }

  @Test
  fun `install rejects --dir combined with --agent`() {
    val cmd = SkillInstallCommand().apply {
      dir = tempFolder.newFolder("x").toPath()
      agentName = "codex"
    }
    assertEquals(TrailblazeExitCode.MISUSE.code, cmd.call())
  }

  @Test
  fun `install rejects --all combined with --agent`() {
    assertEquals(
      TrailblazeExitCode.MISUSE.code,
      SkillInstallCommand().apply { all = true; agentName = "codex" }.call(),
    )
  }

  @Test
  fun `install rejects an unknown agent`() {
    assertEquals(TrailblazeExitCode.MISUSE.code, SkillInstallCommand().apply { agentName = "bogus" }.call())
  }

  // ---------------------------------------------------------------------------
  // skill status
  // ---------------------------------------------------------------------------

  @Test
  fun `status reports no installs when the known directories are empty`() {
    val base = tempFolder.newFolder("project").toPath()
    val home = tempFolder.newFolder("home").toPath()
    var exit = -1
    val stdout = captureStdout { exit = SkillStatusCommand().apply { baseDir = base; homeDir = home }.call() }
    assertEquals(TrailblazeExitCode.SUCCESS.code, exit)
    assertTrue("No installed copies" in stdout, "status should say nothing is installed, got:\n$stdout")
  }

  @Test
  fun `status finds an installed copy after install`() {
    val base = tempFolder.newFolder("project").toPath()
    val home = tempFolder.newFolder("home").toPath()
    assertEquals(
      TrailblazeExitCode.SUCCESS.code,
      SkillInstallCommand().apply { agentName = "codex"; baseDir = base; homeDir = home }.call(),
    )
    var exit = -1
    val stdout = captureStdout { exit = SkillStatusCommand().apply { baseDir = base; homeDir = home }.call() }
    assertEquals(TrailblazeExitCode.SUCCESS.code, exit)
    assertTrue(".agents" in stdout, "status should list the shared .agents install, got:\n$stdout")
  }

  @Test
  fun `status flags a stale copy and an unstamped copy against a newer CLI`() {
    val base = tempFolder.newFolder("project").toPath()
    val home = tempFolder.newFolder("home").toPath()
    // Older stamp than the (injected) running CLI → stale; no stamp → unstamped.
    seedInstall(SkillTarget.CLAUDE.projectDir(base), stampedVersion = "2026.06.01")
    seedInstall(SkillTarget.AGENTS.projectDir(base), stampedVersion = null)
    var exit = -1
    val stdout = captureStdout {
      exit = SkillStatusCommand().apply {
        baseDir = base
        homeDir = home
        currentVersion = "2026.07.12"
        currentIsVersioned = true
      }.call()
    }
    assertEquals(TrailblazeExitCode.SUCCESS.code, exit)
    assertTrue("stale" in stdout, "expected a stale marker, got:\n$stdout")
    assertTrue("2026.06.01" in stdout, "the stale line should name the installed version, got:\n$stdout")
    assertTrue("unstamped" in stdout, "expected an unstamped marker, got:\n$stdout")
  }

  // ---------------------------------------------------------------------------
  // skill (bare index) stale nudge
  // ---------------------------------------------------------------------------

  @Test
  fun `bare skill nudges toward status when an installed copy is stale`() {
    val base = tempFolder.newFolder("project").toPath()
    val home = tempFolder.newFolder("home").toPath()
    seedInstall(SkillTarget.CLAUDE.projectDir(base), stampedVersion = "2026.06.01")
    var exit = -1
    val stdout = captureStdout {
      exit = SkillCommand().apply {
        baseDir = base
        homeDir = home
        currentVersion = "2026.07.12"
        currentIsVersioned = true
      }.call()
    }
    assertEquals(TrailblazeExitCode.SUCCESS.code, exit)
    // The banner always mentions `skill status`; assert the nudge-specific line the banner lacks.
    assertTrue("don't match this CLI" in stdout, "a stale install should print the mismatch nudge, got:\n$stdout")
  }

  // ---------------------------------------------------------------------------
  // Pure install-target resolution (no filesystem)
  // ---------------------------------------------------------------------------

  @Test
  fun `planner defaults to Claude Code project dir`() {
    val targets = SkillInstallPlanner.resolve(
      target = null, all = false, global = false, explicitDir = null,
      baseDir = Path.of("/proj"), homeDir = Path.of("/home"),
    )
    assertEquals(1, targets.size)
    assertEquals(SkillTarget.CLAUDE, targets.single().target)
    assertEquals(Path.of("/proj/.claude/skills/trailblaze"), targets.single().dir)
  }

  @Test
  fun `planner maps each target bucket to its directory`() {
    fun dirFor(target: SkillTarget, global: Boolean) = SkillInstallPlanner.resolve(
      target = target, all = false, global = global, explicitDir = null,
      baseDir = Path.of("/proj"), homeDir = Path.of("/home"),
    ).single().dir

    assertEquals(Path.of("/proj/.agents/skills/trailblaze"), dirFor(SkillTarget.AGENTS, global = false))
    assertEquals(Path.of("/proj/.claude/skills/trailblaze"), dirFor(SkillTarget.CLAUDE, global = false))
    assertEquals(Path.of("/home/.agents/skills/trailblaze"), dirFor(SkillTarget.AGENTS, global = true))
    assertEquals(Path.of("/home/.claude/skills/trailblaze"), dirFor(SkillTarget.CLAUDE, global = true))
  }

  @Test
  fun `agent aliases resolve to the two buckets`() {
    assertEquals(SkillTarget.CLAUDE, SkillTarget.fromCliName("claude"))
    for (alias in listOf("agents", "standard", "codex", "cursor", "gemini", "goose", "opencode")) {
      assertEquals(SkillTarget.AGENTS, SkillTarget.fromCliName(alias), "$alias should map to the shared bucket")
    }
    assertNull(SkillTarget.fromCliName("bogus"))
  }

  @Test
  fun `planner --all yields both buckets`() {
    val targets = SkillInstallPlanner.resolve(
      target = null, all = true, global = false, explicitDir = null,
      baseDir = Path.of("/proj"), homeDir = Path.of("/home"),
    )
    assertEquals(SkillTarget.entries.toSet(), targets.mapNotNull { it.target }.toSet())
  }

  @Test
  fun `planner honors an explicit dir and drops the target`() {
    val target = SkillInstallPlanner.resolve(
      target = SkillTarget.AGENTS, all = false, global = false, explicitDir = Path.of("/tmp/custom"),
      baseDir = Path.of("/proj"), homeDir = Path.of("/home"),
    ).single()
    assertEquals(Path.of("/tmp/custom"), target.dir)
    assertNull(target.target)
  }

  // ---------------------------------------------------------------------------
  // Pure staleness classification (no filesystem)
  // ---------------------------------------------------------------------------

  @Test
  fun `classify treats a matching version as current and a differing one as stale`() {
    fun classify(present: Boolean, installed: String?, current: String, versioned: Boolean) =
      SkillVersionStatus.classify(present, installed, current, versioned)

    assertEquals(SkillVersionState.NOT_INSTALLED, classify(false, null, "2026.07.12", true))
    assertEquals(SkillVersionState.CURRENT, classify(true, "2026.07.12", "2026.07.12", true))
    assertEquals(SkillVersionState.STALE, classify(true, "2026.06.01", "2026.07.12", true))
    assertEquals(SkillVersionState.NOT_STAMPED, classify(true, null, "2026.07.12", true))
    // A Developer Build has no release version to compare against — never flagged stale.
    assertEquals(SkillVersionState.CURRENT, classify(true, "whatever", "Developer Build", false))
  }
}
