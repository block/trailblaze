package xyz.block.trailblaze.usages

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Materializes a git ref's WHOLE repository tree as a temporary detached worktree, runs [block]
 * against it, and removes it.
 *
 * The whole tree (not just the trailmaps subtree) is deliberate: scripted tools import shared
 * helpers by relative path (`../shared`, `../../sdks/typescript`), so bundling only
 * works when the ref side preserves the same repo-relative layout as the working tree. A git
 * worktree also gives a WRITABLE tree, which bundling needs (the bundler synthesizes a
 * temporary wrapper file next to each tool source).
 */
object GitRefTree {

  /** The repo root containing [dir], or null when [dir] isn't inside a git work tree. */
  fun gitRootOf(dir: File): File? =
    runGit(dir, "rev-parse", "--show-toplevel").getOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let(::File)

  /** The commit [ref] resolves to in [gitRoot], or null when it doesn't name a commit there. */
  fun resolveCommit(gitRoot: File, ref: String): String? =
    runGit(gitRoot, "rev-parse", "--verify", "--quiet", "$ref^{commit}").getOrNull()?.trim()
      ?.takeIf { it.isNotEmpty() }

  /**
   * The subset of [paths] git IGNORES in [gitRoot] — content no ref checkout will ever contain,
   * so a working-tree-vs-ref comparison can say nothing about it. (Plain untracked-but-not-ignored
   * files are NOT in this set: those are ordinary work in progress that a ref comparison should
   * report as added.) Paths outside [gitRoot] are skipped, not errors.
   */
  fun ignoredPaths(gitRoot: File, paths: Collection<File>): Set<File> {
    val inRepo = paths.filter { it.absoluteFile.startsWith(gitRoot.absoluteFile) }
    if (inRepo.isEmpty()) return emptySet()
    // Not routed through runGit: check-ignore exits 1 to mean "none ignored", which is an answer,
    // not a failure — and the paths travel over stdin (NUL-separated) to dodge arg-length limits.
    val process = ProcessBuilder("git", "check-ignore", "--stdin", "-z")
      .directory(gitRoot)
      .start()
    // The reader starts before stdin is written so a large ignored set can't deadlock the pipe,
    // and so waitFor's timeout stays enforceable (destroyForcibly closes the pipe, unblocking it).
    val output = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().readText() }
    process.outputStream.bufferedWriter().use { w ->
      inRepo.forEach { w.write(it.absolutePath); w.write("\u0000") }
    }
    if (!process.waitFor(120, TimeUnit.SECONDS)) {
      process.destroyForcibly()
      throw IOException("git check-ignore timed out after 120s")
    }
    if (process.exitValue() > 1) {
      throw IOException("git check-ignore failed (exit ${process.exitValue()})")
    }
    return output.get(10, TimeUnit.SECONDS)
      .split('\u0000').filter { it.isNotEmpty() }.map(::File).toSet()
  }

  fun <T> withRefTree(repoDir: File, ref: String, block: (root: File, resolvedSha: String) -> T): T {
    val gitRoot = gitRootOf(repoDir)
      ?: throw IOException("${repoDir.path} is not inside a git repository, so --changed-since has no ref to compare against.")
    val sha = resolveCommit(gitRoot, ref)
      ?: throw IOException("'$ref' does not resolve to a commit in ${gitRoot.path} (try fetching it first).")
    val tempDir = Files.createTempDirectory("trailblaze-usages-ref").toFile()
    // `worktree add` refuses an existing directory; give it a child path instead.
    val refRoot = File(tempDir, "tree")
    runGit(gitRoot, "worktree", "add", "--detach", refRoot.absolutePath, sha).getOrElse { e ->
      tempDir.deleteRecursively()
      throw IOException("git worktree add failed for '$ref' ($sha): ${e.message}")
    }
    try {
      return block(refRoot, sha)
    } finally {
      // Best-effort teardown: `worktree remove` also clears the repo's worktree registration;
      // the recursive delete covers anything it leaves (or a remove failure).
      runGit(gitRoot, "worktree", "remove", "--force", refRoot.absolutePath)
      tempDir.deleteRecursively()
    }
  }

  private fun runGit(workingDir: File, vararg args: String): Result<String> = runCatching {
    val process = ProcessBuilder("git", *args)
      .directory(workingDir)
      .redirectErrorStream(true)
      .start()
    // Read on a separate thread so waitFor's timeout stays enforceable: a synchronous readText()
    // would block right past the deadline on a hung git, and reading only after waitFor would
    // deadlock a git that fills the pipe buffer. destroyForcibly() closes the pipe, which
    // unblocks the reader.
    val output = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().readText() }
    if (!process.waitFor(120, TimeUnit.SECONDS)) {
      process.destroyForcibly()
      throw IOException("git ${args.joinToString(" ")} timed out after 120s")
    }
    if (process.exitValue() != 0) {
      throw IOException(
        "git ${args.joinToString(" ")} failed (exit ${process.exitValue()}): ${output.get(10, TimeUnit.SECONDS).trim()}",
      )
    }
    output.get(10, TimeUnit.SECONDS)
  }
}
