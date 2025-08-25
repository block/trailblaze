package xyz.block.trailblaze.mcp.utils

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
