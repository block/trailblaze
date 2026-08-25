package xyz.block.trailblaze.usages

import java.io.File
import java.io.IOException
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Behavioral contract of the ref-tree materialization: the block sees the REF's file content (not
 * the working tree's), the temporary worktree is gone afterwards even when the block throws, and
 * an unresolvable ref fails with an actionable message instead of comparing against nothing.
 */
class GitRefTreeTest {

  private val repo: File = createTempDirectory("trailblaze-reftree-test").toFile()

  @AfterTest fun cleanup() {
    repo.deleteRecursively()
  }

  private fun git(vararg args: String) {
    val p = ProcessBuilder(listOf("git", "-C", repo.absolutePath) + args)
      .redirectErrorStream(true)
      .start()
    val output = p.inputStream.bufferedReader().readText()
    check(p.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $output" }
  }

  private fun seedCommit(fileContent: String) {
    git("init", "-q")
    File(repo, "tool.ts").writeText(fileContent)
    git("add", "-A")
    git("-c", "user.email=t@t.t", "-c", "user.name=t", "-c", "commit.gpgsign=false", "commit", "-q", "-m", "seed")
  }

  @Test
  fun `the block sees the ref's content while the working tree keeps its edits`() {
    seedCommit("committed version")
    File(repo, "tool.ts").writeText("working-tree edit")

    var refRootSeen: File? = null
    val contentAtRef = GitRefTree.withRefTree(repo, "HEAD") { refRoot, resolvedSha ->
      refRootSeen = refRoot
      assertEquals(40, resolvedSha.length, "the sha the block receives is the full resolved commit")
      File(refRoot, "tool.ts").readText()
    }

    assertEquals("committed version", contentAtRef)
    assertEquals("working-tree edit", File(repo, "tool.ts").readText(), "the working tree must be untouched")
    assertFalse(refRootSeen!!.exists(), "the materialized tree must be cleaned up after the block returns")
  }

  @Test
  fun `the materialized tree is cleaned up even when the block throws`() {
    seedCommit("v1")

    var refRootSeen: File? = null
    assertFailsWith<IllegalStateException> {
      GitRefTree.withRefTree(repo, "HEAD") { refRoot, _ ->
        refRootSeen = refRoot
        error("scan blew up")
      }
    }
    assertFalse(refRootSeen!!.exists())
  }

  @Test
  fun `an unresolvable ref names the ref in the failure`() {
    seedCommit("v1")

    assertNull(GitRefTree.resolveCommit(repo, "no-such-ref"))
    val e = assertFailsWith<IOException> {
      GitRefTree.withRefTree(repo, "no-such-ref") { _, _ -> }
    }
    assertEquals(true, e.message?.contains("no-such-ref"))
  }

  @Test
  fun `ignoredPaths reports gitignored files but not tracked or merely-untracked ones`() {
    seedCommit("v1")
    File(repo, ".gitignore").writeText("/staged/\n")
    git("add", ".gitignore")
    git("-c", "user.email=t@t.t", "-c", "user.name=t", "-c", "commit.gpgsign=false", "commit", "-q", "-m", "ignore")
    val staged = File(repo, "staged/tool.ts").apply { parentFile.mkdirs(); writeText("staged") }
    val untracked = File(repo, "wip.ts").apply { writeText("work in progress") }
    val tracked = File(repo, "tool.ts")
    val outside = File(createTempDirectory("outside").toFile(), "elsewhere.ts")

    val ignored = GitRefTree.ignoredPaths(repo, listOf(staged, untracked, tracked, outside))

    assertEquals(setOf(staged.absoluteFile), ignored.map { it.absoluteFile }.toSet())
  }

  @Test
  fun `a directory outside any git repository has no git root`() {
    val plainDir = createTempDirectory("trailblaze-not-a-repo").toFile()
    try {
      // System temp dirs aren't under a repo; if this environment nests them in one, the
      // assertion message will say so rather than silently testing nothing.
      assertNull(GitRefTree.gitRootOf(plainDir), "expected ${plainDir.path} to be outside any git work tree")
    } finally {
      plainDir.deleteRecursively()
    }
  }
}
