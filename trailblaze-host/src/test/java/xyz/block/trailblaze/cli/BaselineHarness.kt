package xyz.block.trailblaze.cli

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.fail

/**
 * Shared harness for snapshot tests that pin CLI output against checked-in baseline files
 * under `src/test/resources/cli-output-baselines/`.
 *
 * ## Why "always write + git diff" instead of "compare in-memory"
 *
 * Earlier iterations of these tests had two modes: a normal comparison mode and a
 * `TRAILBLAZE_RECORD_SNAPSHOTS=1` env var that flipped the test into "rewrite the file"
 * mode. The split had two recurring problems:
 *  - Authors forgot to set the env var when intentionally changing output, then committed
 *    a test failure they were going to "fix later."
 *  - Reviewers had to mentally model the dual modes when reading a test failure.
 *
 * The harness used by every snapshot test now always (re)writes the baseline file from
 * the current rendered output, then asserts the file is clean in the git working tree
 * via `git status --porcelain`. The drift signal is a normal git change that:
 *  - Shows up in the author's local `git status` immediately after running tests.
 *  - Reads in PR review the same way as any other file diff — no special tooling.
 *  - Catches new untracked files (added commands) AND modified files in one shot.
 *
 * The author's flow is "run the tests, see what changed, decide if it was intentional,
 * `git add` if yes / revert source if no." No env var; no `--tests` filter to remember.
 *
 * ## Failure modes
 *
 * Two classes of failure surface here:
 *  1. **Output drift** — the test wrote a file that differs from the checked-in version
 *     (or wrote a file that wasn't tracked yet). The assertion message points at
 *     `git diff` for inspection.
 *  2. **No git available** — `git` not on PATH or not a working tree. The test fails with
 *     a clear "this test needs a git working tree" message instead of mysterious
 *     subprocess errors. CI environments and dev machines both satisfy this; CI
 *     containers without git would be the only legitimate failure case and are out of
 *     scope for these tests.
 */
internal object BaselineHarness {

  /**
   * Repo-relative path the baseline files live under. Hard-coded because the tests run
   * with the module's project directory as cwd, and the resource dir layout is stable.
   */
  val BASELINE_DIR: File = File("src/test/resources/cli-output-baselines")

  /**
   * Disclaimer prepended to every baseline file. Mirrors the message a reviewer would see
   * if they opened the file in GitHub or their editor. Stripped before any future read
   * via [stripHeader] in case we ever switch to comparison mode again — keeping the
   * symmetry now means future flexibility is free.
   */
  const val DISCLAIMER_HEADER = "# This file is a CHECKED-IN baseline of CLI output rendered by trailblaze-host's CLI helpers.\n" +
    "# It is NOT a contract — if a test fails here because output drifted, decide whether the\n" +
    "# drift is intentional. The test always (re)writes this file from the current rendered output,\n" +
    "# then asserts the working tree is clean. So `git diff` of this file IS the failure message.\n" +
    "# Reviewers: scrutinize diffs to baselines the same way you would scrutinize a UI screenshot —\n" +
    "# small wording / spacing changes can be intentional or accidental.\n" +
    "# ----------------------------------------------------------------------\n"

  /**
   * Writes [content] (with the disclaimer header prepended and a trailing newline appended)
   * to the named baseline file relative to [BASELINE_DIR], then asserts the file shows no
   * drift in the git working tree. Single-file tests should call this directly.
   */
  fun assertBaseline(filename: String, content: String) {
    val file = write(filename, content)
    assertNoDrift(file)
  }

  /**
   * Write-only variant for tests that emit many files in a loop (e.g. the picocli tree
   * walker). Pair with [assertNoDrift] called once on the directory at the end so a
   * single failure surfaces every dirty path together rather than aborting on the first.
   */
  fun write(filename: String, content: String): File {
    val file = File(BASELINE_DIR, filename)
    file.parentFile.mkdirs()
    file.writeText(DISCLAIMER_HEADER + content + (if (content.endsWith("\n")) "" else "\n"))
    return file
  }

  /**
   * Asserts `git status --porcelain` reports nothing for [target]. [target] may be a
   * single file or a directory; passing a directory catches both modifications and new
   * untracked files within it.
   *
   * Defensive against a few environmental failure modes that a bare `git status` can hit:
   *  - **git not on PATH** (e.g. minimal CI container): pre-flight via `git --version` so
   *    the failure message says "git is unavailable" instead of a vague IOException.
   *  - **cwd not inside a git working tree** (test launched outside the repo): pre-flight
   *    via `git rev-parse --git-dir` so the failure points at the cwd, not a phantom
   *    drift report.
   *  - **subprocess hangs** (network filesystem stall, runaway hook, zombie git process):
   *    bound `waitFor` to [GIT_TIMEOUT_SECONDS] so a stuck `git status` doesn't burn the
   *    full CI job timeout. Mid-rebase / detached-HEAD states return quickly under this
   *    cap; if you hit the timeout something is genuinely wrong.
   */
  fun assertNoDrift(target: File) {
    val absolute = target.absoluteFile
    preflightGitAvailable()

    val proc = try {
      ProcessBuilder("git", "status", "--porcelain", "--", absolute.path)
        .redirectErrorStream(true)
        .start()
    } catch (e: Exception) {
      fail(
        "Could not invoke `git status` to check baseline drift: ${e.message}. " +
          "These tests need a git working tree.",
      )
    }
    val output = proc.inputStream.bufferedReader().readText()
    val finished = proc.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    if (!finished) {
      proc.destroyForcibly()
      fail(
        "`git status --porcelain` did not complete within ${GIT_TIMEOUT_SECONDS}s. " +
          "Possible causes: stuck pre-commit hook, network filesystem stall, " +
          "or a runaway git subprocess. Investigate the working tree at ${absolute.path}.",
      )
    }
    val exit = proc.exitValue()
    if (exit != 0) {
      fail(
        "`git status --porcelain` exited with code $exit (is HEAD detached or " +
          "is a rebase in progress?). Output:\n$output",
      )
    }
    if (output.isBlank()) return

    fail(
      buildString {
        appendLine("CLI output baseline drift detected.")
        appendLine()
        appendLine("The test re-wrote baseline file(s); the working tree now differs from")
        appendLine("what's checked in. `git diff` of those files IS the drift report.")
        appendLine()
        appendLine("If the change is intentional:")
        appendLine("    git diff -- ${absolute.path}")
        appendLine("    git add   -- ${absolute.path}")
        appendLine("    git commit")
        appendLine()
        appendLine("If unintentional, revert the source change that caused it.")
        appendLine()
        appendLine("Working-tree status:")
        append(output.trimEnd())
      },
    )
  }

  /**
   * For callers that ever want to read a baseline back instead of (re)writing it — strips
   * the header so they get the rendered content alone.
   */
  fun stripHeader(text: String): String = text.removePrefix(DISCLAIMER_HEADER).trimEnd('\n')

  /**
   * Per-`git`-subprocess timeout for [assertNoDrift]. 30s is generously above any normal
   * `git status` on a workspace this size; hitting it means something genuinely went
   * wrong (stuck hook, network FS stall, runaway process) and we'd rather fail the test
   * with a clear message than block the whole CI job for hours.
   */
  private const val GIT_TIMEOUT_SECONDS: Long = 30

  /**
   * Pre-flight check that `git` is on PATH and that the cwd is inside a working tree.
   * Failing here gives a clear "this test needs a git working tree" message instead of
   * the misleading "drift detected" output you'd otherwise get from a downstream
   * subprocess error.
   *
   * Two distinct failure modes:
   *  - `git --version` errors → git isn't on PATH. Common in minimal CI containers.
   *  - `git rev-parse --git-dir` errors → cwd is outside a working tree (e.g. a test
   *    runner that builds the test binary outside the repo, or a sandboxed cwd).
   */
  private fun preflightGitAvailable() {
    val versionOk = runProcessQuiet(listOf("git", "--version"))
    if (!versionOk) {
      fail("`git` is not on PATH. These baseline tests require git in the test environment.")
    }
    val repoOk = runProcessQuiet(listOf("git", "rev-parse", "--git-dir"))
    if (!repoOk) {
      fail(
        "Test cwd is not inside a git working tree (cwd=${File(".").absolutePath}). " +
          "These baseline tests must run from within the repository.",
      )
    }
  }

  /**
   * Runs [args] with stdout/stderr discarded and returns true iff exit code is 0 within
   * [GIT_TIMEOUT_SECONDS]. Used by [preflightGitAvailable] for "is this command healthy?"
   * checks where we don't care about the output.
   */
  private fun runProcessQuiet(args: List<String>): Boolean = try {
    val proc = ProcessBuilder(args)
      .redirectOutput(ProcessBuilder.Redirect.DISCARD)
      .redirectError(ProcessBuilder.Redirect.DISCARD)
      .start()
    val finished = proc.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    if (!finished) {
      proc.destroyForcibly()
      false
    } else {
      proc.exitValue() == 0
    }
  } catch (_: Exception) {
    false
  }
}
