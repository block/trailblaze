package xyz.block.trailblaze.util

import xyz.block.trailblaze.util.TrailblazeProcessBuilderUtils.runProcess
import java.io.File

object GitUtils {
  /**
   * Runs a git command and returns the trimmed output if successful, null otherwise.
   */
  private fun runGitCommand(vararg args: String): String? = try {
    val processResult = TrailblazeProcessBuilderUtils.createProcessBuilder(
      args = listOf("git", *args),
    ).runProcess { }
    if (processResult.exitCode == 0) {
      processResult.fullOutput.trim().takeIf { it.isNotBlank() }
    } else null
  } catch (e: Exception) {
    null
  }

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
