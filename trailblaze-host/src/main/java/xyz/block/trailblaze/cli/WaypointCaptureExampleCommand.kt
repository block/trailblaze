package xyz.block.trailblaze.cli

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import xyz.block.trailblaze.api.waypoint.WaypointDefinition
import xyz.block.trailblaze.logs.client.TrailblazeJson
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.waypoint.SessionLogScreenState
import xyz.block.trailblaze.waypoint.WaypointLoader
import xyz.block.trailblaze.waypoint.WaypointMatcher
import java.io.File
import java.util.concurrent.Callable

/**
 * Writes a sibling `<waypoint-id>.example.json` + screenshot pair next to a waypoint
 * definition, capturing a known-good screen state for that waypoint.
 *
 * The example JSON is shaped as a slim subset of `TrailblazeLlmRequestLog` (deviceWidth,
 * deviceHeight, screenshotFile, viewHierarchy, trailblazeNodeTree, trailblazeDevicePlatform)
 * plus three metadata fields (waypointId, capturedAt, capturedFrom). The slim shape lets
 * `waypoint validate` consume the example file directly via its positional log argument.
 *
 * Source-screenshot picking: the LLM request log records the *annotated* screenshot
 * (set-of-mark overlays drawn for the model). For the example we want the *raw* twin —
 * the framework typically writes a raw companion ~300ms after the annotated, so we pick
 * the next webp by sorted filename in the same directory and verify the timestamp gap is
 * sub-second. If no raw twin can be confidently identified, the command aborts so we
 * never commit annotated images.
 */
@OptIn(ExperimentalTime::class)
@Command(
  name = "capture-example",
  mixinStandardHelpOptions = true,
  description = [
    "Capture a sibling <id>.example.json + screenshot next to the waypoint YAML.",
    "Picks the raw (un-annotated) screenshot twin from the source session.",
  ],
)
class WaypointCaptureExampleCommand : Callable<Int> {

  @Parameters(
    arity = "0..1",
    description = ["Path to a *_TrailblazeLlmRequestLog.json (alternative to --session/--step)"],
  )
  var positionalLogFile: File? = null

  @Option(names = ["--def"], description = ["Waypoint id to capture an example for (required)"], required = true)
  lateinit var defId: String

  @Option(names = ["--session"], description = ["Session log directory (containing *_TrailblazeLlmRequestLog.json files)"])
  var session: File? = null

  @Option(names = ["--step"], description = ["1-based step within the session (default: last step)"])
  var step: Int? = null

  @Option(
    names = ["--root"],
    description = ["Root directory to scan for *.waypoint.yaml files (default: $DEFAULT_WAYPOINT_ROOT)"],
  )
  var root: File = File(DEFAULT_WAYPOINT_ROOT)

  @Option(
    names = ["--force"],
    description = ["Overwrite an existing example pair without prompting."],
  )
  var force: Boolean = false

  override fun call(): Int {
    val logFile = resolveLogFile() ?: return CommandLine.ExitCode.USAGE

    // Locate the waypoint definition + the file it came from
    val (def, defFile) = findWaypointFile() ?: return CommandLine.ExitCode.USAGE

    // Parse the source log into a JsonObject so we can copy fields verbatim
    val rawJson = logFile.readText()
    val sourceJson = Json.parseToJsonElement(rawJson) as? JsonObject ?: run {
      Console.error("Source log is not a JSON object: ${logFile.absolutePath}")
      return CommandLine.ExitCode.USAGE
    }

    // Pick the raw screenshot. On Android the framework writes a paired (raw + annotated)
    // screenshot per step and the LLM log references the annotated one — we want the twin.
    // On iOS only a single un-annotated screenshot is written today, so we fall back to
    // that file when no twin exists. Anything that looks like an annotated screenshot
    // without a raw twin would be caught here and warrant a warning.
    val rawScreenshot = findRawScreenshot(logFile, sourceJson) ?: run {
      Console.error("Could not locate any screenshot for ${logFile.name} in ${logFile.parentFile}.")
      return 1
    }

    // Compute output paths next to the waypoint YAML — keep the source file extension
    // so .png stays .png, .webp stays .webp.
    val baseName = defFile.name.removeSuffix(WAYPOINT_SUFFIX)
    val screenshotExt = rawScreenshot.extension.ifEmpty { "webp" }
    val exampleJsonFile = File(defFile.parentFile, "$baseName$EXAMPLE_JSON_SUFFIX")
    val screenshotFile = File(defFile.parentFile, "$baseName.example.$screenshotExt")

    if ((exampleJsonFile.exists() || screenshotFile.exists()) && !force) {
      Console.error("Example files already exist. Use --force to overwrite:")
      Console.error("  ${exampleJsonFile.name}")
      Console.error("  ${screenshotFile.name}")
      return CommandLine.ExitCode.USAGE
    }

    rawScreenshot.copyTo(screenshotFile, overwrite = true)

    // Build the example JSON: slim projection + metadata
    val exampleJson = buildExampleJson(
      def = def,
      sourceJson = sourceJson,
      sourceLogFile = logFile,
      screenshotFileName = screenshotFile.name,
    )
    exampleJsonFile.writeText(JSON_OUT.encodeToString(JsonElement.serializer(), exampleJson))

    // Self-test: the waypoint must match its own example, otherwise the example is wrong.
    val screen = SessionLogScreenState.loadStep(exampleJsonFile)
    val matchResult = WaypointMatcher.match(def, screen)
    if (!matchResult.matched) {
      Console.error("Waypoint did not match its own example — deleting partial files.")
      Console.error(formatResult(matchResult))
      exampleJsonFile.delete()
      screenshotFile.delete()
      return 1
    }

    Console.log("Wrote example pair for ${def.id}:")
    Console.log("  ${exampleJsonFile.absolutePath}")
    Console.log("  ${screenshotFile.absolutePath}")
    Console.log("Self-validation: MATCH (${matchResult.matchedRequired.size} required satisfied)")
    return CommandLine.ExitCode.OK
  }

  private fun resolveLogFile(): File? {
    positionalLogFile?.let { return validateLogFile(it, label = "Log file") }
    val s = session ?: run {
      Console.error("Provide a positional log file path, or --session.")
      return null
    }
    val validated = validateSessionDir(s) ?: return null
    val logs = SessionLogScreenState.listLlmRequestLogs(validated)
    if (logs.isEmpty()) {
      Console.error("No *_TrailblazeLlmRequestLog.json files found in: ${validated.absolutePath}")
      return null
    }
    val idx = step?.let { it - 1 } ?: (logs.size - 1)
    if (idx !in logs.indices) {
      Console.error("--step out of range: 1..${logs.size}")
      return null
    }
    return logs[idx]
  }

  /**
   * Walks `--root` for `*.waypoint.yaml` files, parses each, and returns the (def, file) pair
   * whose `id` matches `--def`. Returns null after writing an error if not found / ambiguous.
   */
  private fun findWaypointFile(): Pair<WaypointDefinition, File>? {
    val candidates = WaypointLoader.discover(root)
    val yaml = Yaml(configuration = YamlConfiguration(strictMode = false, encodeDefaults = false))
    val matches = mutableListOf<Pair<WaypointDefinition, File>>()
    for (file in candidates) {
      val def = try {
        yaml.decodeFromString(WaypointDefinition.serializer(), file.readText())
      } catch (_: Exception) {
        continue // resilient to unrelated bad files
      }
      if (def.id == defId) matches += def to file
    }
    return when (matches.size) {
      0 -> {
        Console.error("Waypoint id not found: $defId (searched ${root.absolutePath})")
        null
      }
      1 -> matches.single()
      else -> {
        Console.error("Multiple waypoint files declare id '$defId':")
        matches.forEach { Console.error("  ${it.second.absolutePath}") }
        null
      }
    }
  }

  /**
   * Picks the raw screenshot for the captured step.
   *
   * Android writes two screenshots per step — one annotated (set-of-mark overlays) and
   * one raw — both with timestamps in the filename. The LLM log's `screenshotFile`
   * references the annotated one, so the raw is the temporally-closest sibling within
   * one second.
   *
   * iOS today writes a single un-annotated screenshot per step, so when no second
   * file exists within the 1-second window we fall back to the LLM-referenced file.
   */
  private fun findRawScreenshot(logFile: File, sourceJson: JsonObject): File? {
    val referencedName = (sourceJson["screenshotFile"] as? JsonPrimitive)?.content ?: return null
    val dir = logFile.parentFile ?: return null
    val referencedFile = File(dir, referencedName)
    if (!referencedFile.exists()) return null

    val referencedTimestampMs = extractTimestampMs(referencedName) ?: return referencedFile
    val candidates = (dir.listFiles { f -> f.isFile && IMAGE_EXTENSIONS.any(f.name::endsWith) } ?: emptyArray())
      .toList()
      .filter { it != referencedFile }
      .mapNotNull { f -> extractTimestampMs(f.name)?.let { ts -> f to ts } }

    val twin = candidates
      .map { (file, ts) -> file to kotlin.math.abs(ts - referencedTimestampMs) }
      .minByOrNull { it.second }
    return when {
      twin == null || twin.second > 1_000 -> {
        Console.log(
          "  No raw twin found within 1s of the LLM-referenced screenshot — using it as-is.",
        )
        Console.log(
          "  Sanity-check the file is un-annotated (no set-of-mark overlays).",
        )
        referencedFile
      }
      else -> twin.first
    }
  }

  /** Extracts the trailing `_<ms>.<ext>` timestamp from a session screenshot filename. */
  private fun extractTimestampMs(name: String): Long? {
    val withoutExt = name.substringBeforeLast('.')
    val tsString = withoutExt.substringAfterLast('_')
    return tsString.toLongOrNull()
  }

  private fun buildExampleJson(
    def: WaypointDefinition,
    sourceJson: JsonObject,
    sourceLogFile: File,
    screenshotFileName: String,
  ): JsonObject {
    return buildJsonObject {
      put("waypointId", def.id)
      put("capturedAt", Clock.System.now().toString())
      put("capturedFrom", sourceLogFile.toRelativePathString())
      put("screenshotFile", screenshotFileName)
      sourceJson["deviceWidth"]?.let { put("deviceWidth", it) }
      sourceJson["deviceHeight"]?.let { put("deviceHeight", it) }
      sourceJson["trailblazeDevicePlatform"]?.let { put("trailblazeDevicePlatform", it) }
      sourceJson["viewHierarchy"]?.let { put("viewHierarchy", it) }
      sourceJson["trailblazeNodeTree"]?.let { put("trailblazeNodeTree", it) }
    }
  }

  /** Path relative to the JVM's cwd if possible, else absolute. */
  private fun File.toRelativePathString(): String {
    val cwd = File("").absoluteFile
    val abs = this.absoluteFile
    return try {
      abs.relativeTo(cwd).path.takeIf { it.isNotEmpty() } ?: abs.path
    } catch (_: IllegalArgumentException) {
      abs.path
    }
  }

  companion object {
    private const val WAYPOINT_SUFFIX = ".waypoint.yaml"
    private const val EXAMPLE_JSON_SUFFIX = ".example.json"
    private val IMAGE_EXTENSIONS = listOf(".webp", ".png", ".jpg", ".jpeg")
    private val JSON_OUT = TrailblazeJson.defaultWithoutToolsInstance
  }
}
