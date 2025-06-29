package xyz.block.trailblaze.report.utils

import java.io.BufferedReader
import java.io.InputStreamReader

object GitUtils {
  fun getGitRootViaCommand(): String? = try {
    val process = ProcessBuilder("git", "rev-parse", "--show-toplevel")
      .redirectErrorStream(true)
      .start()

    val result = BufferedReader(InputStreamReader(process.inputStream)).readText().trim()
    process.waitFor()
    if (process.exitValue() == 0) result else null
  } catch (e: Exception) {
    null
  }
}
