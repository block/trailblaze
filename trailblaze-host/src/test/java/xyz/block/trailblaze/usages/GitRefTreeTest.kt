package xyz.block.trailblaze.usages

import java.io.File
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    // Fixture independence from host git config, set before anything touches the index. Note the
    // reason this arrived (#6441) — a host-global `core.fsmonitor = true` reporting a just-committed
    // file as modified — was a misread: git reported the tree clean on stdout and wrote a warning to
    // stderr, and runGit counted the merged text as status output. runGit reads stdout only now, and
    // the two stderr-warning tests below install a noisy fsmonitor hook on purpose to hold that.
    // Repo config rather than `-c` because GitRefTree spawns its own git processes.
    git("config", "core.fsmonitor", "false")
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
    val outsideDir = createTempDirectory("trailblaze-outside").toFile()
    val outside = File(outsideDir, "elsewhere.ts")

    try {
      val ignored = GitRefTree.ignoredPaths(repo, listOf(staged, untracked, tracked, outside))

      assertEquals(setOf(staged.absoluteFile), ignored.map { it.absoluteFile }.toSet())
    } finally {
      outsideDir.deleteRecursively()
    }
  }

  @Test
  fun `the working-tree state is HEAD plus whether tracked content differs from it`() {
    // Half the provenance of a `--changed-since` report: the ref side is already recorded, and
    // without this a stale report and a real change are indistinguishable.
    seedCommit("v1")
    val head = GitRefTree.resolveCommit(repo, "HEAD")!!

    val clean = GitRefTree.workingTreeStateOf(repo)!!
    assertEquals(head, clean.headSha)
    // Check workingTreeStateOf first if this fails: host git config leaking in is the rarer cause,
    // and seedCommit already pins the one setting known to do it.
    assertFalse(clean.dirty, "a seeded repo must read clean")

    // An UNTRACKED file must not read as dirty: it is ordinary work in progress that the comparison
    // itself reports as `added`, so counting it here would mark every mid-edit report unreproducible.
    File(repo, "brand-new.ts").writeText("wip")
    assertFalse(GitRefTree.workingTreeStateOf(repo)!!.dirty, "an untracked file is not a modification")

    File(repo, "tool.ts").writeText("edited")
    val dirty = GitRefTree.workingTreeStateOf(repo)!!
    assertEquals(head, dirty.headSha, "editing a file does not move HEAD")
    assertTrue(dirty.dirty, "a tracked edit means HEAD alone no longer reproduces the report")
  }

  @Test
  fun `a stderr warning from git is not mistaken for a dirty working tree`() {
    // A wedged core.fsmonitor daemon makes every git call in the repo write "could not read IPC
    // response" to stderr while still exiting 0 and reporting a clean tree on stdout. Reading the
    // two streams as one counted that text as status output, so a clean checkout reported dirty and
    // `--changed-since` provenance was lost for a reason that had nothing to do with the tree.
    seedCommit("v1")
    installNoisyFsmonitorHook()

    // The trigger has to be live, or what follows passes for the wrong reason: git must be writing
    // to stderr while stdout still reports a genuinely clean tree, and it must still be SUCCEEDING
    // — a git version that made a failing fsmonitor hook fatal would otherwise look the same as the
    // bug under test.
    val (stdout, stderr, exitCode) = statusStreams()
    assertTrue(stderr.isNotBlank(), "expected git to warn on stderr, got none")
    assertEquals("", stdout.trim())
    assertEquals(0, exitCode, "git status must still succeed for this to be the case under test")

    val state = GitRefTree.workingTreeStateOf(repo)!!

    // headSha is asserted as a well-formed sha rather than "unaffected by the warning": index-reading
    // commands consult core.fsmonitor, but `rev-parse` does not, so this leg never sees the warning
    // and claiming otherwise would overstate what the test covers.
    assertEquals(40, state.headSha.length, "HEAD still resolves to a full sha")
    assertFalse(state.dirty, "a warning on stderr is not a tracked modification")
  }

  @Test
  fun `a real modification is still seen through the same stderr warning`() {
    // The other half of the contract, and the reason the test above cannot stand alone: filtering
    // the warning must not also filter the signal. Code that reported "clean" unconditionally passes
    // the test above and fails this one.
    seedCommit("v1")
    installNoisyFsmonitorHook()
    File(repo, "tool.ts").writeText("edited")

    val (stdout, stderr, exitCode) = statusStreams()
    assertTrue(stderr.isNotBlank(), "expected git to warn on stderr, got none")
    assertTrue(stdout.isNotBlank(), "expected git to report the edit on stdout")
    assertEquals(0, exitCode, "git status must still succeed for this to be the case under test")

    assertTrue(
      GitRefTree.workingTreeStateOf(repo)!!.dirty,
      "a tracked edit is still a modification when git is also warning",
    )
  }

  /**
   * Points the repo's `core.fsmonitor` at a hook that writes to stderr and fails — what a wedged
   * fsmonitor daemon looks like from git's side. The hook lives under `.git/` so it is not itself a
   * change the repo's status could report.
   */
  private fun installNoisyFsmonitorHook() {
    val hook = File(repo, ".git/noisy-fsmonitor.sh")
    hook.writeText("#!/bin/sh\necho 'error: could not read IPC response' >&2\nexit 1\n")
    check(hook.setExecutable(true)) { "could not make $hook executable" }
    git("config", "core.fsmonitor", hook.absolutePath)
  }

  /**
   * stdout, stderr and exit code of the status call [GitRefTree.workingTreeStateOf] makes.
   *
   * stderr drains on its own thread: reading one stream to EOF before touching the other is the
   * deadlock the code under test avoids, and a helper that reproduced it would hang the suite
   * instead of failing it.
   */
  private fun statusStreams(): Triple<String, String, Int> {
    val process = ProcessBuilder("git", "-C", repo.absolutePath, "status", "--porcelain", "--untracked-files=no")
      .start()
    val stderr = CompletableFuture.supplyAsync { process.errorStream.bufferedReader().readText() }
    val stdout = process.inputStream.bufferedReader().readText()
    check(process.waitFor(60, TimeUnit.SECONDS)) { "git status did not finish within 60s" }
    return Triple(stdout, stderr.get(30, TimeUnit.SECONDS), process.exitValue())
  }

  @Test
  fun `outside a git repository the working-tree state is absent, not a claim of cleanliness`() {
    // Reporting `dirty = false` here would assert something the command could not read. Null is the
    // only honest answer, and it is what a consumer keys on to know the provenance is unavailable.
    val plainDir = createTempDirectory("trailblaze-not-a-repo-state").toFile()
    try {
      assertNull(GitRefTree.workingTreeStateOf(plainDir), "expected no working-tree state for ${plainDir.path}")
    } finally {
      plainDir.deleteRecursively()
    }
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
