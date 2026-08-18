package xyz.block.trailblaze.util

/**
 * Result from running a command line process.
 */
data class CommandProcessResult(
  val outputLines: List<String>,
  val exitCode: Int,
  val errorMessage: String? = null,
) {
  val fullOutput: String = outputLines.joinToString("\n")
  val isSuccess: Boolean = exitCode == 0
}
