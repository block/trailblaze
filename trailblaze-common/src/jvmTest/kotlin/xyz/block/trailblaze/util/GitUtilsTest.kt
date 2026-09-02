package xyz.block.trailblaze.util

import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Behavioral contract of [GitUtils.hasUncommittedChanges]: only what git reports on STDOUT counts
 * as a change. git writes warnings to stderr while still exiting 0 (a wedged core.fsmonitor daemon
 * repeats "could not read IPC response" on every call), and reading the two streams as one made a
 * clean checkout report dirty.
 */
class GitUtilsTest {

  private val repo: File = createTempDirectory("trailblaze-gitutils-test").toFile()

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
    // Fixture independence from any host-global fsmonitor config; the stderr-warning tests below
    // override this with a noisy hook on purpose.
    git("config", "core.fsmonitor", "false")
    File(repo, "tool.ts").writeText(fileContent)
    git("add", "-A")
    // --no-verify so a host's global `core.hooksPath` (husky and friends set one) can't run an
    // unrelated pre-commit hook here, fail the check, and error every test in the class on that
    // machine alone — the host-config-dependent failure this PR is about.
    git(
      "-c", "user.email=t@t.t", "-c", "user.name=t", "-c", "commit.gpgsign=false",
      "commit", "-q", "--no-verify", "-m", "seed",
    )
  }

  @Test
  fun `a stderr warning from git is not mistaken for a dirty working tree`() {
    seedCommit("v1")
    installNoisyFsmonitorHook()

    // The trigger has to be live, or what follows passes for the wrong reason: git must be writing
    // to stderr while stdout still reports a genuinely clean tree, and it must still be SUCCEEDING
    // — a git version that made a failing fsmonitor hook fatal would otherwise look the same as
    // the bug under test.
    val (stdout, stderr, exitCode) = statusStreams()
    assertTrue(stderr.isNotBlank(), "expected git to warn on stderr, got none")
    assertEquals("", stdout.trim())
    assertEquals(0, exitCode, "git status must still succeed for this to be the case under test")

    assertEquals(false, GitUtils.hasUncommittedChanges(workingDir = repo), "a warning on stderr is not a change")
  }

  @Test
  fun `a real modification is still seen through the same stderr warning`() {
    // The other half of the contract, and the reason the test above cannot stand alone: filtering
    // the warning must not also filter the signal. Code that reported "clean" unconditionally
    // passes the test above and fails this one.
    seedCommit("v1")
    installNoisyFsmonitorHook()
    File(repo, "tool.ts").writeText("edited")

    val (stdout, stderr, exitCode) = statusStreams()
    assertTrue(stderr.isNotBlank(), "expected git to warn on stderr, got none")
    assertTrue(stdout.isNotBlank(), "expected git to report the edit on stdout")
    assertEquals(0, exitCode, "git status must still succeed for this to be the case under test")

    assertEquals(
      true,
      GitUtils.hasUncommittedChanges(workingDir = repo),
      "a tracked edit is still a change when git is also warning",
    )
  }

  @Test
  fun `an untracked file counts as an uncommitted change`() {
    // Unlike a `--untracked-files=no` status, this check exists to answer "would a build from this
    // checkout reproduce?" — untracked work in progress means no.
    seedCommit("v1")
    File(repo, "wip.ts").writeText("work in progress")

    assertEquals(true, GitUtils.hasUncommittedChanges(workingDir = repo))
  }

  @Test
  fun `outside a git repository the answer is null, not a claim of cleanliness`() {
    val plainDir = createTempDirectory("trailblaze-not-a-repo").toFile()
    try {
      assertEquals(null, GitUtils.hasUncommittedChanges(workingDir = plainDir))
    } finally {
      plainDir.deleteRecursively()
    }
  }

  /**
   * Points the repo's `core.fsmonitor` at a hook that writes to stderr and fails — what a wedged
   * fsmonitor daemon looks like from git's side. The hook lives under `.git/` so it is not itself
   * a change the repo's status could report.
   */
  private fun installNoisyFsmonitorHook() {
    val hook = File(repo, ".git/noisy-fsmonitor.sh")
    hook.writeText("#!/bin/sh\necho 'error: could not read IPC response' >&2\nexit 1\n")
    check(hook.setExecutable(true)) { "could not make $hook executable" }
    git("config", "core.fsmonitor", hook.absolutePath)
  }

  /**
   * stdout, stderr and exit code of the status call [GitUtils.hasUncommittedChanges] makes.
   *
   * stderr drains on its own thread: reading one stream to EOF before touching the other is the
   * deadlock the code under test avoids, and a helper that reproduced it would hang the suite
   * instead of failing it.
   */
  private fun statusStreams(): Triple<String, String, Int> {
    val process = ProcessBuilder("git", "-C", repo.absolutePath, "status", "--porcelain")
      .start()
    val stderr = CompletableFuture.supplyAsync { process.errorStream.bufferedReader().readText() }
    val stdout = process.inputStream.bufferedReader().readText()
    check(process.waitFor(60, TimeUnit.SECONDS)) { "git status did not finish within 60s" }
    return Triple(stdout, stderr.get(30, TimeUnit.SECONDS), process.exitValue())
  }
}
