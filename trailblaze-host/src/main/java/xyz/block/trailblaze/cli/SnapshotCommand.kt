package xyz.block.trailblaze.cli

import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.util.concurrent.Callable

/**
 * Capture the current screen state with element refs (no LLM).
 *
 * Examples:
 *   trailblaze snapshot               - Capture screen snapshot
 *   trailblaze snapshot --bounds     - Include bounding boxes
 */
@Command(
  name = "snapshot",
  mixinStandardHelpOptions = true,
  description = ["Capture the current screen's UI tree (fast, no AI, no actions)"],
)
class SnapshotCommand : Callable<Int> {

  @Option(
    names = ["-d", "--device"],
    description = ["Device: platform (android, ios, web) or platform/id"],
  )
  var device: String? = null

  @Option(
    names = ["-v", "--verbose"],
    description = ["Enable verbose output"],
  )
  var verbose: Boolean = false

  @Option(names = ["--bounds"], description = ["Include bounding box {x,y,w,h} for each element"])
  var bounds: Boolean = false

  @Option(names = ["--offscreen"], description = ["Include offscreen elements marked (offscreen)"])
  var offscreen: Boolean = false

  @Option(names = ["--screenshot"], description = ["Save a screenshot to disk and print the file path"])
  var screenshot: Boolean = false

  override fun call(): Int {
    return cliWithDevice(verbose, device) { client ->
      val yaml = "- takeSnapshot:\n    screenName: \"snap\""
      val details = buildList {
        if (bounds) add("BOUNDS")
        if (offscreen) add("OFFSCREEN")
      }.joinToString(",").ifEmpty { null }
      // Use fast mode only when no detail enrichment is needed — bounds/offscreen
      // require a full screen capture to build the compact element list with coordinates.
      val needsFullCapture = bounds || offscreen || screenshot
      val args = mutableMapOf<String, Any?>(
        "objective" to "Capture screen state",
        "tools" to yaml,
        "fast" to !needsFullCapture,
      )
      if (details != null) args["snapshotDetails"] = details
      if (screenshot) args["screenshot"] = true
      val result = client.callTool("blaze", args)
      formatBlazeResultAgent(result)
      blazeExitCode(result)
    }
  }
}
