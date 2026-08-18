package xyz.block.trailblaze.util

import xyz.block.trailblaze.util.TrailblazeProcessBuilderUtils.runProcess
import java.io.File

object GitUtils {
  /**
   * Runs a git command and returns the trimmed output if successful, null otherwise.
   * [workingDir] anchors the command in a specific checkout; null runs in the process cwd.
   */
  private fun runGitCommand(vararg args: String, workingDir: File? = null): String? = try {
    val processResult = TrailblazeProcessBuilderUtils.createProcessBuilder(
      args = listOf("git", *args),
      workingDir = workingDir,
    ).runProcess { }
    if (processResult.exitCode == 0) {
      processResult.fullOutput.trim().takeIf { it.isNotBlank() }
    } else null
  } catch (e: Exception) {
    null
  }

  /**
   * Every configured remote URL of the repository enclosing [repoDir] (git resolves the enclosing
   * repo by walking up, so [repoDir] may sit anywhere inside the checkout). Empty when [repoDir]
   * is not inside a git repository, has no remotes, or `git` isn't available — callers treat an
   * empty result as "this checkout's repository can't be identified".
   *
   * All remotes rather than just `origin`: which name the canonical remote carries is a local
   * convention (fork-based flows often have `origin` = fork, `upstream` = canonical), and callers
   * use this to answer "is this checkout a clone of repo X?" — any configured remote counts.
   *
   * A fork-ONLY clone (origin points at the fork, no `upstream` remote configured) reports just the
   * fork's `org/repo`, so an identity comparison against the upstream repo won't match even though
   * the checkout is semantically a clone of it. Nothing here can recover the upstream in that case;
   * callers that treat a mismatch as a signal should stay advisory rather than fail on it.
   */
  fun getRemoteUrls(repoDir: File): List<String> =
    runGitCommand("config", "--get-regexp", """^remote\..*\.url$""", workingDir = repoDir)
      ?.lines()
      ?.mapNotNull { line ->
        // Each line is `remote.<name>.url <url>`; the URL is everything after the first space.
        line.substringAfter(' ', missingDelimiterValue = "").trim().takeIf { it.isNotBlank() }
      }
      .orEmpty()

  /**
   * True when git ignores [path] — i.e. the path is matched by a `.gitignore` rule and is therefore
   * generated or staged content rather than committed source.
   *
   * `git check-ignore` prints the path and exits 0 on a match, prints nothing and exits 1 otherwise,
   * so a non-blank result IS the match (no `--quiet`, whose empty output the shared runner can't
   * distinguish from a failure). Anchored in [path]'s parent directory so git resolves the enclosing
   * repo by walking up from a location that exists.
   *
   * False when git isn't available or the path isn't in a repository at all — callers can't tell
   * "definitely tracked" from "couldn't check", so treat a false as the conservative answer rather
   * than proof the path is committed.
   */
  fun isPathIgnored(path: File): Boolean = runGitCommand(
    "check-ignore",
    path.absolutePath,
    workingDir = path.absoluteFile.parentFile,
  ) != null

  /**
   * Returns the git repository root directory, or null if not in a git repo.
   * Callers must handle null gracefully — release/binary builds may not be in a git repo
   * and should fall back to configured or default paths (e.g., ~/.trailblaze/logs).
   */
  fun getGitRootViaCommand(): String? = runGitCommand("rev-parse", "--show-toplevel")

  // Helper to get git root directory
  private fun getGitRoot(): File? = runGitCommand("rev-parse", "--show-toplevel")?.let { File(it) }

  fun getLatestRemoteCommitHash(remoteName: String = "origin", branchName: String = "main"): String? =
    runGitCommand("ls-remote", remoteName, "refs/heads/$branchName")?.split("\t")?.firstOrNull()

  fun getCurrentBranchName(): String? = runGitCommand("rev-parse", "--abbrev-ref", "HEAD")

  /**
   * Checks if there are any uncommitted changes (staged, unstaged, or untracked files).
   * Returns true if there are changes, false if working directory is clean, null on error.
   */
  fun hasUncommittedChanges(): Boolean? = try {
    val processResult = TrailblazeProcessBuilderUtils.createProcessBuilder(
      args = listOf("git", "status", "--porcelain"),
    ).runProcess { }
    if (processResult.exitCode == 0) {
      processResult.fullOutput.trim().isNotEmpty()
    } else null
  } catch (e: Exception) {
    null
  }

  /**
   * Gets the commit hash of the local HEAD.
   */
  fun getLocalCommitHash(): String? = runGitCommand("rev-parse", "HEAD")

  /**
   * Result of checking if local branch is in sync with remote.
   */
  sealed class OriginSyncStatus {
    /** Local commit matches the remote branch commit. */
    data class InSync(val branchName: String) : OriginSyncStatus()

    /** Local commit differs from remote branch commit. */
    data class OutOfSync(val localCommit: String, val remoteCommit: String) : OriginSyncStatus()

    /** Branch does not exist on remote. */
    data class BranchNotOnRemote(val branchName: String) : OriginSyncStatus()

    /** Could not determine status (e.g., not in a git repo, network error). */
    data class Error(val reason: String) : OriginSyncStatus()
  }

  /**
   * Checks if the local branch is in sync with the remote origin branch.
   *
   * Note: This uses `git ls-remote` to check the remote without fetching,
   * so it reflects the actual current state of the remote.
   */
  fun checkOriginSync(remoteName: String = "origin"): OriginSyncStatus {
    val branchName = getCurrentBranchName()
      ?: return OriginSyncStatus.Error("Failed to get current branch name")
    val localCommit = getLocalCommitHash()
      ?: return OriginSyncStatus.Error("Failed to get local commit hash")
    val remoteCommit = getLatestRemoteCommitHash(remoteName, branchName)
      ?: return OriginSyncStatus.BranchNotOnRemote(branchName)

    return if (localCommit == remoteCommit) {
      OriginSyncStatus.InSync(branchName)
    } else {
      OriginSyncStatus.OutOfSync(localCommit, remoteCommit)
    }
  }

  /**
   * Result of validating git sync status.
   */
  sealed class GitSyncStatus {
    /** Local is clean and in sync with origin. */
    data class Ready(val branchName: String) : GitSyncStatus()

    /** There are uncommitted local changes. */
    data object HasUncommittedChanges : GitSyncStatus()

    /** Local branch is not in sync with the remote origin branch. */
    data class OutOfSyncWithOrigin(val originSyncStatus: OriginSyncStatus) : GitSyncStatus()

    /** Could not determine status (e.g., not in a git repo, network error). */
    data class Error(val reason: String) : GitSyncStatus()
  }

  /**
   * Validates that local is clean and in sync with the remote origin.
   * Useful as a pre-flight check before running processes that require a clean state.
   */
  fun calculateGitSyncStatus(remoteName: String = "origin"): GitSyncStatus {
    val hasChanges = hasUncommittedChanges()
      ?: return GitSyncStatus.Error("Failed to check for uncommitted changes")
    if (hasChanges) return GitSyncStatus.HasUncommittedChanges

    return when (val syncStatus = checkOriginSync(remoteName)) {
      is OriginSyncStatus.InSync -> GitSyncStatus.Ready(syncStatus.branchName)
      else -> GitSyncStatus.OutOfSyncWithOrigin(syncStatus)
    }
  }
}
