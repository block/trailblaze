package xyz.block.trailblaze.host

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [GitInfoExcludeSeeder] — the one-time seed of `.git/info/exclude` with the
 * patterns matching every file `trailblaze check` generates.
 *
 * Coverage:
 *  - Resolves `info/exclude` for a plain `.git` directory.
 *  - Resolves the shared `info/exclude` for a `.git` *file* (linked worktree) via `commondir`.
 *  - Returns null when there's no enclosing git repo.
 *  - Seeds a fresh repo, appends cleanly to an existing exclude, and is idempotent.
 *  - First-generation-only: once the sentinel exists, never re-touches `info/exclude`
 *    (so a user's edit/removal sticks).
 *  - Best-effort: outside a git repo it's a silent no-op with no sentinel written.
 */
class GitInfoExcludeSeederTest {

  private val tempDirs = mutableListOf<File>()

  @AfterTest
  fun cleanup() {
    tempDirs.forEach { it.deleteRecursively() }
    tempDirs.clear()
  }

  /** A temp tree shaped `<root>/trails/.trailblaze/` with a plain `.git` dir at `<root>`. */
  private fun newGitRepoWithWorkspace(): Pair<File, File> {
    val root = createTempDirectory("git-exclude-seeder-test").toFile()
    tempDirs += root
    File(root, ".git/info").mkdirs()
    val trails = File(root, "trails/.trailblaze").apply { mkdirs() }
    return root to trails.parentFile // (repoRoot, trailsDir)
  }

  @Test
  fun `resolveGitInfoExclude finds info-exclude under a plain dot-git directory`() {
    val (root, trails) = newGitRepoWithWorkspace()

    val resolved = GitInfoExcludeSeeder.resolveGitInfoExclude(trails.toPath())

    assertEquals(
      File(root, ".git/info/exclude").toPath().toAbsolutePath().normalize(),
      resolved,
    )
  }

  @Test
  fun `resolveGitInfoExclude follows a dot-git file to the worktree common dir`() {
    // Mirror a linked worktree: the main repo holds the real .git, the worktree's .git is a
    // file pointing at .git/worktrees/<name>, whose `commondir` points back at the common dir.
    val mainRepo = createTempDirectory("git-exclude-seeder-main").toFile()
    tempDirs += mainRepo
    val commonGitDir = File(mainRepo, ".git").apply { File(this, "info").mkdirs() }
    val worktreeGitDir = File(commonGitDir, "worktrees/wt1").apply { mkdirs() }
    File(worktreeGitDir, "commondir").writeText("../..\n")

    val worktree = createTempDirectory("git-exclude-seeder-wt").toFile()
    tempDirs += worktree
    File(worktree, ".git").writeText("gitdir: ${worktreeGitDir.absolutePath}\n")
    val trails = File(worktree, "trails/.trailblaze").apply { mkdirs() }.parentFile

    val resolved = GitInfoExcludeSeeder.resolveGitInfoExclude(trails.toPath())

    assertEquals(
      File(commonGitDir, "info/exclude").toPath().toAbsolutePath().normalize(),
      resolved,
      "worktree .git file should resolve to the shared common-dir info/exclude",
    )
  }

  @Test
  fun `resolveGitInfoExclude returns null when not inside a git repo`() {
    val root = createTempDirectory("git-exclude-seeder-nogit").toFile()
    tempDirs += root
    val trails = File(root, "trails/.trailblaze").apply { mkdirs() }.parentFile

    val resolved = GitInfoExcludeSeeder.resolveGitInfoExclude(trails.toPath())
    // Deterministic on the common case (system temp outside any repo → null). If this CI's
    // temp dir happens to live inside a git checkout, resolving to that repo's exclude is
    // correct behavior, not a regression — so accept either: null, or a real info/exclude path.
    assertTrue("expected null or an info/exclude path, got $resolved") {
      resolved == null ||
        (resolved.fileName.toString() == "exclude" && resolved.parent.fileName.toString() == "info")
    }
  }

  @Test
  fun `seedOnce writes the managed block and a sentinel on a fresh repo`() {
    val (root, trails) = newGitRepoWithWorkspace()

    GitInfoExcludeSeeder.seedOnce(trails.toPath())

    val exclude = File(root, ".git/info/exclude").readText()
    assertTrue("expected managed header: $exclude") { exclude.contains(GitInfoExcludeSeeder.MANAGED_HEADER) }
    GitInfoExcludeSeeder.MANAGED_PATTERNS.forEach { pattern ->
      assertTrue("expected pattern '$pattern': $exclude") { exclude.contains(pattern) }
    }
    assertTrue("the .gitignore pattern must NOT be seeded (it stays committed): $exclude") {
      !exclude.contains("/.gitignore")
    }
    val sentinel = File(trails, "${WorkspaceTypeScriptSetup.GENERATED_DIR_NAME}/${GitInfoExcludeSeeder.SENTINEL_FILENAME}")
    assertTrue(sentinel.isFile, "expected sentinel file at $sentinel")
  }

  @Test
  fun `seedOnce appends to an existing exclude without clobbering it`() {
    val (root, trails) = newGitRepoWithWorkspace()
    val exclude = File(root, ".git/info/exclude").apply { writeText("# my own ignores\n*.local\n") }

    GitInfoExcludeSeeder.seedOnce(trails.toPath())

    val content = exclude.readText()
    assertTrue("pre-existing content preserved: $content") { content.startsWith("# my own ignores\n*.local\n") }
    assertTrue("managed block appended: $content") { content.contains(GitInfoExcludeSeeder.MANAGED_HEADER) }
  }

  @Test
  fun `seedOnce is idempotent — second run leaves the exclude byte-identical`() {
    val (root, trails) = newGitRepoWithWorkspace()
    val exclude = File(root, ".git/info/exclude")

    GitInfoExcludeSeeder.seedOnce(trails.toPath())
    val firstBytes = exclude.readBytes()
    GitInfoExcludeSeeder.seedOnce(trails.toPath())

    assertTrue("second seed must not change info/exclude") { firstBytes.contentEquals(exclude.readBytes()) }
  }

  @Test
  fun `once seeded, a user removing the block is not re-added`() {
    val (root, trails) = newGitRepoWithWorkspace()
    val exclude = File(root, ".git/info/exclude")

    GitInfoExcludeSeeder.seedOnce(trails.toPath())
    // The developer deliberately strips our block back out.
    exclude.writeText("# my own ignores\n")

    GitInfoExcludeSeeder.seedOnce(trails.toPath())

    assertFalse("sentinel present → we must not re-add our block") {
      exclude.readText().contains(GitInfoExcludeSeeder.MANAGED_HEADER)
    }
  }

  @Test
  fun `seedOnce does not duplicate a block that is already present`() {
    val (root, trails) = newGitRepoWithWorkspace()
    // Simulate a sibling worktree having already seeded the shared exclude (no local sentinel yet).
    File(root, ".git/info/exclude").writeText(
      buildString {
        append(GitInfoExcludeSeeder.MANAGED_HEADER).append('\n')
        GitInfoExcludeSeeder.MANAGED_PATTERNS.forEach { append(it).append('\n') }
      },
    )

    GitInfoExcludeSeeder.seedOnce(trails.toPath())

    val headerCount = File(root, ".git/info/exclude").readText()
      .lineSequence().count { it == GitInfoExcludeSeeder.MANAGED_HEADER }
    assertEquals(1, headerCount, "managed header must appear exactly once")
    val sentinel = File(trails, "${WorkspaceTypeScriptSetup.GENERATED_DIR_NAME}/${GitInfoExcludeSeeder.SENTINEL_FILENAME}")
    assertTrue(sentinel.isFile, "sentinel should be written even when the block already existed")
  }

  @Test
  fun `seedOnce outside a git repo is a no-op with no sentinel`() {
    val root = createTempDirectory("git-exclude-seeder-bare").toFile()
    tempDirs += root
    val trails = File(root, "trails").apply { File(this, ".trailblaze").mkdirs() }

    // Guard: if this CI's temp area is itself inside a git checkout, the precondition
    // ("no enclosing repo") doesn't hold — skip rather than seed the ambient repo.
    if (GitInfoExcludeSeeder.resolveGitInfoExclude(trails.toPath()) != null) return

    GitInfoExcludeSeeder.seedOnce(trails.toPath())

    val sentinel = File(trails, "${WorkspaceTypeScriptSetup.GENERATED_DIR_NAME}/${GitInfoExcludeSeeder.SENTINEL_FILENAME}")
    assertFalse("no sentinel should be written when there's no git repo (a later git init must still seed)") {
      sentinel.exists()
    }
  }
}
