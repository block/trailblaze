package xyz.block.trailblaze.mcp.newtools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import xyz.block.trailblaze.recordings.TrailRecordings
import xyz.block.trailblaze.util.Console
import java.io.File

@Suppress("unused")
class TrailFilesToolSet(
  private val trailsDirProvider: () -> File,
) : ToolSet {
  @LLMDescription("Lists all available Trailblaze file-based test cases.")
  @Tool("listFileBasedTestCases")
  fun listTestCases(): List<String> {
    val trailsDir = trailsDirProvider()
    Console.log("Listing test cases in directory: ${trailsDir.canonicalPath}")
    val dir = trailsDir.also { it.mkdirs() }

    return listAllTestCases(dir)
  }

  private fun listAllTestCases(dir: File): List<String> {
    val trailFiles = dir.walkTopDown().filter { TrailRecordings.isTrailFile(it.name) }.toList()
    return trailFiles.map {
      it.relativeTo(dir).path
    }
  }
}
