package xyz.block.trailblaze.util

import xyz.block.trailblaze.util.TrailblazeProcessBuilderUtils.runProcess
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

object GitUtils {
  fun getGitRootViaCommand(): String? = try {
    val processResult = TrailblazeProcessBuilderUtils.createProcessBuilder(
      args = listOf(
        "git",
        "rev-parse",
        "--show-toplevel",
      ),
    ).runProcess { }
    if (processResult.exitCode == 0) processResult.fullOutput.trim() else null
  } catch (e: Exception) {
    null
  }

  // Helper to get git root directory
  private fun getGitRoot(): File? = try {
    val process = ProcessBuilder("git", "rev-parse", "--show-toplevel")
      .redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText().trim()
    val exit = process.waitFor()
    if (exit == 0 && output.isNotBlank()) File(output) else null
  } catch (e: Exception) {
    null
  }

  fun getLatestRemoteCommitHash(remoteName: String = "origin", branchName: String = "main"): String? = try {
    val process = ProcessBuilder("git", "ls-remote", remoteName, "refs/heads/$branchName")
      .redirectErrorStream(true)
      .start()

    val result = BufferedReader(InputStreamReader(process.inputStream)).readText().trim()
    process.waitFor()
    if (process.exitValue() == 0 && result.isNotEmpty()) {
      result.split("\t").firstOrNull()
    } else {
      null
    }
  } catch (e: Exception) {
    null
  }
}
