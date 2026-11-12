package xyz.block.trailblaze.cli

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
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
 * Pins how `trailblaze skill` behaves when a distribution registers a skill manager:
 *
 *  - `skill install` hands the known layouts to the manager and writes nothing itself.
 *  - When the manager can't run, the bundled copy is written — but never into a copy the manager owns.
 *  - `--dir` always writes the bundled copy (still skipping a managed directory).
 *  - `skill status` judges a managed copy by its content and source commit, not a version stamp.
 */
class SkillDistributionTest {

  @get:Rule val tempFolder = TemporaryFolder()

  /** A manager that owns any directory holding [MARKER], resolved through symlinks. */
  private class FakeDistribution(var installResult: Int?) : SkillDistribution {
    val installCalls = mutableListOf<Pair<Boolean, Path>>()
    val installHomes = mutableListOf<Path>()
    override val managerName = "fake manager"
    override val updateCommand = "fake update trailblaze"
    override fun install(global: Boolean, projectDir: Path, homeDir: Path): Int? {
      installCalls += global to projectDir
      installHomes.add(homeDir)
      return installResult
    }
    override fun owns(path: Path): Boolean {
      val real = generateSequence(path.toAbsolutePath()) { it.parent }.first { Files.exists(it) }.toRealPath()
      return generateSequence(real) { it.parent }.any { it.resolve(MARKER).exists() }
    }
    override fun sourceCommitOf(dir: Path): String? =
      dir.resolve("source-commit").takeIf { it.exists() }?.readText()?.trim()
  }

  /** A copy the fake manager owns, holding [skillMd] as its SKILL.md and published from [commit]. */
  private fun seedManagedCopy(dir: Path, skillMd: String, commit: String?): Path {
    dir.createDirectories()
    dir.resolve("SKILL.md").writeText(skillMd)
    dir.resolve(MARKER).writeText("{}")
    commit?.let { dir.resolve("source-commit").writeText(it) }
    return dir
  }

  /** A managed copy whose every bundled file matches this build's. */
  private fun seedMatchingManagedCopy(dir: Path, commit: String?): Path {
    BundledAgentSkill.installTo(dir)
    dir.resolve(MARKER).writeText("{}")
    commit?.let { dir.resolve("source-commit").writeText(it) }
    return dir
  }

  @Test
  fun `install hands off to the manager and writes no bundled copy`() {
    val base = tempFolder.newFolder("project").toPath()
    val home = tempFolder.newFolder("home").toPath()
    val manager = FakeDistribution(installResult = 0)

    val exit = SkillInstallCommand().apply { baseDir = base; homeDir = home; distribution = manager }.call()

    assertEquals(0, exit)
    assertEquals(listOf(false to base), manager.installCalls)
    assertFalse(SkillTarget.CLAUDE.projectDir(base).exists())
  }

  @Test
  fun `install --global hands off as a global install`() {
    val manager = FakeDistribution(installResult = 0)
    val home = tempFolder.newFolder("home").toPath()
    SkillInstallCommand().apply {
      global = true
      baseDir = tempFolder.newFolder("project").toPath()
      homeDir = home
      distribution = manager
    }.call()
    assertEquals(true, manager.installCalls.single().first)
    assertEquals(listOf(home), manager.installHomes)
  }

  @Test
  fun `install passes on the manager's failure exit code`() {
    val exit = SkillInstallCommand().apply {
      baseDir = tempFolder.newFolder("project").toPath()
      homeDir = tempFolder.newFolder("home").toPath()
      distribution = FakeDistribution(installResult = 3)
    }.call()
    assertEquals(3, exit)
  }

  @Test
  fun `install writes the bundled copy when the manager can't run`() {
    val base = tempFolder.newFolder("project").toPath()
    val exit = SkillInstallCommand().apply {
      baseDir = base
      homeDir = tempFolder.newFolder("home").toPath()
      distribution = FakeDistribution(installResult = null)
    }.call()
    assertEquals(TrailblazeExitCode.SUCCESS.code, exit)
    assertTrue(SkillTarget.CLAUDE.projectDir(base).resolve("SKILL.md").exists())
  }

  @Test
  fun `the bundled fallback never writes through a symlink into a managed copy`() {
    val home = tempFolder.newFolder("home").toPath()
    // The shape skill managers commonly leave: the agents dir holds the copy, Claude's dir links to it.
    val managed = seedManagedCopy(SkillTarget.AGENTS.globalDir(home), skillMd = "managed\n", commit = null)
    val claudeDir = SkillTarget.CLAUDE.globalDir(home)
    claudeDir.parent.createDirectories()
    Files.createSymbolicLink(claudeDir, managed)

    val exit = SkillInstallCommand().apply {
      all = true
      global = true
      baseDir = tempFolder.newFolder("project").toPath()
      homeDir = home
      distribution = FakeDistribution(installResult = null)
    }.call()

    assertEquals(TrailblazeExitCode.SUCCESS.code, exit)
    assertEquals("managed\n", managed.resolve("SKILL.md").readText())
    assertFalse(managed.resolve(BundledAgentSkill.VERSION_STAMP_FILE).exists())
  }

  @Test
  fun `--dir writes the bundled copy without asking the manager, but skips a managed dir`() {
    val manager = FakeDistribution(installResult = 0)
    val plain = tempFolder.newFolder("plain").toPath()
    SkillInstallCommand().apply { dir = plain; distribution = manager }.call()
    assertTrue(plain.resolve("SKILL.md").exists())
    assertTrue(manager.installCalls.isEmpty())

    val managed = seedManagedCopy(tempFolder.newFolder("managed").toPath(), skillMd = "managed\n", commit = null)
    SkillInstallCommand().apply { dir = managed; distribution = manager }.call()
    assertEquals("managed\n", managed.resolve("SKILL.md").readText())
  }

  @Test
  fun `--dir skips a destination whose references directory links into a managed copy`() {
    val managed = seedManagedCopy(tempFolder.newFolder("managed").toPath(), skillMd = "managed\n", commit = null)
    val managedRefs = managed.resolve("references").createDirectories()
    val dest = tempFolder.newFolder("dest").toPath()
    Files.createSymbolicLink(dest.resolve("references"), managedRefs)

    SkillInstallCommand().apply { dir = dest; distribution = FakeDistribution(installResult = 0) }.call()

    assertFalse(dest.resolve("SKILL.md").exists(), "no file of a destination that reaches a managed copy is written")
    assertTrue(Files.list(managedRefs).use { it.count() } == 0L, "nothing is written through the linked references/")
  }

  @Test
  fun `status reports a managed copy that matches this CLI`() {
    val home = tempFolder.newFolder("home").toPath()
    seedMatchingManagedCopy(SkillTarget.AGENTS.globalDir(home), commit = "aaaaaaa000")
    val stdout = status(home, bundledCommits = listOf("bbbbbbb000"))
    assertTrue("matches this CLI" in stdout, stdout)
    assertTrue("managed by fake manager" in stdout, stdout)
    assertFalse("unstamped" in stdout, "a managed copy has no version stamp and must not be called unstamped:\n$stdout")
  }

  @Test
  fun `status warns that a managed copy is older and names the update command`() {
    val home = tempFolder.newFolder("home").toPath()
    seedManagedCopy(SkillTarget.AGENTS.globalDir(home), skillMd = "old\n", commit = "ccccccc000")
    val stdout = status(home, bundledCommits = listOf("aaaaaaa000", "bbbbbbb000", "ccccccc000"))
    assertTrue("older than this CLI (2 skill change(s) behind" in stdout, stdout)
    assertTrue("fake update trailblaze" in stdout, stdout)
    assertFalse("project's directory" in stdout, "a global copy needs no project hint:\n$stdout")
  }

  @Test
  fun `status says a stale managed project copy updates from the project directory`() {
    val home = tempFolder.newFolder("home").toPath()
    val base = tempFolder.newFolder("project").toPath()
    seedManagedCopy(SkillTarget.AGENTS.projectDir(base), skillMd = "old\n", commit = "ccccccc000")
    val stdout = status(home, bundledCommits = listOf("aaaaaaa000", "ccccccc000"), base = base)
    assertTrue("Run it from this project's directory to update the project copy." in stdout, stdout)
  }

  @Test
  fun `status does not warn about a managed copy from a commit this build doesn't know`() {
    val home = tempFolder.newFolder("home").toPath()
    seedManagedCopy(SkillTarget.AGENTS.globalDir(home), skillMd = "newer\n", commit = "ddddddd000")
    val stdout = status(home, bundledCommits = listOf("aaaaaaa000"))
    assertTrue("likely newer" in stdout, stdout)
    assertFalse("fake update trailblaze" in stdout, stdout)
  }

  @Test
  fun `status names the install that refreshes each stale copy`() {
    val home = tempFolder.newFolder("home").toPath()
    val base = tempFolder.newFolder("project").toPath()
    for (dir in listOf(SkillTarget.CLAUDE.globalDir(home), SkillTarget.AGENTS.projectDir(base))) {
      BundledAgentSkill.installTo(dir)
      dir.resolve(BundledAgentSkill.VERSION_STAMP_FILE).writeText("2026.06.01\n")
    }
    fun run(manager: SkillDistribution?) = captureStdout {
      SkillStatusCommand().apply {
        baseDir = base
        homeDir = home
        currentVersion = "2026.07.12"
        currentIsVersioned = true
        distribution = manager
      }.call()
    }

    val bundledOnly = run(manager = null)
    assertTrue("  trailblaze skill install --global\n" in bundledOnly, bundledOnly)
    assertTrue("  trailblaze skill install --agent agents\n" in bundledOnly, bundledOnly)

    // A manager links every agent's directory, so only the scope is named, and it replaces the copy.
    val managed = run(manager = FakeDistribution(installResult = 0))
    assertTrue("when fake manager can run, it replaces the copy" in managed, managed)
    assertTrue("  trailblaze skill install --global\n" in managed, managed)
    assertTrue("  trailblaze skill install\n" in managed, managed)
    assertFalse("--agent" in managed, managed)
  }

  @Test
  fun `bare skill does not nudge for a managed copy that is newer`() {
    val home = tempFolder.newFolder("home").toPath()
    seedManagedCopy(SkillTarget.AGENTS.globalDir(home), skillMd = "newer\n", commit = "ddddddd000")
    val stdout = captureStdout {
      SkillCommand().apply {
        baseDir = tempFolder.newFolder("project").toPath()
        homeDir = home
        distribution = FakeDistribution(installResult = 0)
      }.call()
    }
    assertFalse("don't match this CLI" in stdout, stdout)
    assertTrue("installs through fake manager" in stdout, stdout)
  }

  @Test
  fun `managed classification uses content first, then the source commit's place in history`() {
    val history = listOf("aaaaaaa111", "bbbbbbb222")
    assertEquals(SkillVersionState.CURRENT, ManagedSkillStatus.classify(true, "bbbbbbb222", history))
    assertEquals(SkillVersionState.STALE, ManagedSkillStatus.classify(false, "bbbbbbb", history))
    // The newest skill commit with different content is a local edit or a newer copy, not older.
    assertEquals(SkillVersionState.DIFFERENT, ManagedSkillStatus.classify(false, "aaaaaaa111", history))
    assertEquals(SkillVersionState.DIFFERENT, ManagedSkillStatus.classify(false, "eeeeeee", history))
    assertEquals(SkillVersionState.DIFFERENT, ManagedSkillStatus.classify(false, null, history))
    assertNull(ManagedSkillStatus.changesBehind("abc", history), "too short a prefix to match safely")
  }

  @Test
  fun `home dir prefers HOME over the JVM's user home`() {
    assertEquals(Path.of("/sandbox/home"), homeDir(env = { "/sandbox/home" }, userHome = "/Users/real"))
    assertEquals(Path.of("/Users/real"), homeDir(env = { null }, userHome = "/Users/real"))
    assertEquals(Path.of("/Users/real"), homeDir(env = { " " }, userHome = "/Users/real"))
  }

  private fun status(
    home: Path,
    bundledCommits: List<String>,
    base: Path = tempFolder.newFolder("project-${System.nanoTime()}").toPath(),
  ): String = captureStdout {
    SkillStatusCommand().apply {
      baseDir = base
      homeDir = home
      distribution = FakeDistribution(installResult = 0)
      this.bundledCommits = bundledCommits
    }.call()
  }

  private companion object {
    const val MARKER = ".fake-manager"
  }
}
