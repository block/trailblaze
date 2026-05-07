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
import xyz.block.trailblaze.api.ImageFormatDetector
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
    "With no --session/--step/positional-log, walks every session under logs/ and picks",
    "the most recent step that the waypoint matches AND that has a real screenshot.",
  ],
)
class WaypointCaptureExampleCommand : Callable<Int> {

  /** Picocli wires this to the enclosing `WaypointCommand` so we can reach `cliRoot.configProvider()`. */
  @CommandLine.ParentCommand
  private lateinit var parent: WaypointCommand

  @Parameters(
    arity = "0..1",
    description = ["Direct path to a screen-state log (alternative to auto-search / --session)"],
  )
  var positionalLogFile: File? = null

  @Option(names = ["--id"], description = ["Waypoint id to capture an example for (matches the YAML's top-level `id:` field). Required."], required = true)
  lateinit var waypointId: String

  @Option(
    names = ["--session"],
    description = [
      "Session id (the directory name under --logs-dir, e.g. `2026_05_07_22_26_48_yaml_6258`). " +
        "Restricts the auto-search to that session. Combine with --step to pin a specific step.",
    ],
  )
  var sessionId: String? = null

  @Option(names = ["--step"], description = ["1-based step within --session. Skips auto-search; uses this step verbatim. Requires --session."])
  var step: Int? = null

  @Option(
    names = ["--target"],
    paramLabel = "<id>",
    description = [
      "Pack id to operate on. Resolves --root to <workspace>/packs/<id>/waypoints/ — the " +
        "canonical workspace-pack location. Warns if no such pack exists. Mutually exclusive " +
        "with --root (--root wins if both given).",
    ],
  )
  var targetId: String? = null

  @Option(
    names = ["--root"],
    paramLabel = "<path>",
    description = ["Explicit root directory to scan for *.waypoint.yaml files. Overrides --target. (Convention: $DEFAULT_WAYPOINT_ROOT)"],
  )
  var rootOverride: File? = null

  @Option(
    names = ["--logs-dir"],
    paramLabel = "<path>",
    description = ["Override the directory containing per-session log dirs. Defaults to the running daemon's resolved logsDir."],
  )
  var logsDirOverride: File? = null

  @Option(
    names = ["--force"],
    description = ["Overwrite an existing example pair without prompting."],
  )
  var force: Boolean = false

  override fun call(): Int {
    // Resolve --target / --root to the effective waypoint root. See [resolveWaypointRoot] for
    // the precedence rules; in short, --root wins, then --target → workspace pack convention,
    // then a default with a "no target specified" warning.
    val root = resolveWaypointRoot(rootOverride = rootOverride, targetId = targetId)
    // We need the waypoint definition early — both for the search (so the matcher can
    // identify a matching step) and for the eventual write. Resolve it once up front.
    val (def, defFile) = findWaypointFile(root) ?: return CommandLine.ExitCode.USAGE
    val logFile = resolveLogFile(def) ?: return CommandLine.ExitCode.USAGE

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

    // Compute output paths next to the waypoint YAML.
    //
    // Pick the extension by **sniffing the source bytes**, NOT by trusting the source
    // file's extension. The session log directory has historically contained `.png`-named
    // files whose bytes were actually WebP (caused by `LogsRepo.saveScreenshotBytes`
    // defaulting the extension and the on-device screencap pipeline returning WebP for
    // wire-size). Trusting `rawScreenshot.extension` would propagate that lie into the
    // committed example pair. Sniffing makes the output extension always match the bytes.
    val baseName = defFile.name.removeSuffix(WAYPOINT_SUFFIX)
    val sourceBytes = rawScreenshot.readBytes()
    val screenshotExt = ImageFormatDetector.detectFormat(sourceBytes).fileExtension
      .ifEmpty { rawScreenshot.extension.ifEmpty { "webp" } }
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

  /**
   * Finds the screen-state log file we'll source the example pair from.
   *
   * Resolution order:
   * 1. **Positional `<log-file>`** — caller knows exactly which log; use it verbatim, no search.
   * 2. **`--session <id>` + `--step <n>`** — caller knows the session AND the step; pin to that
   *    step (using the broader screen-state log listing, so AgentDriverLog / SnapshotLog /
   *    LlmRequestLog all qualify).
   * 3. **`--session <id>`** alone — search just that session for the most recent step where
   *    `WaypointMatcher.match(def, screen)` returns MATCH AND the log carries a screenshot.
   * 4. **No args** (the magic path) — walk every session under the effective logs dir and
   *    pick the most recent matching log. Slow on large dirs, intentional: spec says "go get
   *    an example for this id, that's it." The effective logs dir is whatever the running
   *    daemon resolved (`config.logsRepo.logsDir`), so callers don't need to know its path.
   *    `--logs-dir <path>` overrides for the rare case where the corpus lives elsewhere
   *    (e.g. CI artifacts staged into a one-off directory).
   */
  private fun resolveLogFile(def: WaypointDefinition): File? {
    positionalLogFile?.let { return validateLogFile(it, label = "Log file") }
    // --step is meaningful ONLY in combination with --session — it indexes into a
    // session's step list. Silently dropping it (and falling through to global auto-search)
    // hides the user's intent: they pinned a step number and got an arbitrary other step.
    // Fail fast with a usage error instead.
    if (step != null && sessionId == null) {
      Console.error("--step requires --session. Pass --session <id> [--step <n>], or drop --step to auto-search.")
      return null
    }
    val effectiveLogsDir = effectiveLogsDir()

    if (sessionId != null) {
      val sessionDir = resolveSessionDir(sessionId!!, effectiveLogsDir) ?: return null
      step?.let { stepNum ->
        // --session + --step: pin to that exact step from the broader screen-state log set.
        val logs = SessionLogScreenState.listScreenStateLogs(sessionDir)
        if (logs.isEmpty()) {
          Console.error("No screen-state logs found in: ${sessionDir.absolutePath}")
          return null
        }
        val idx = stepNum - 1
        if (idx !in logs.indices) {
          Console.error("--step out of range: 1..${logs.size}")
          return null
        }
        return logs[idx]
      }
      // --session alone: search just this session.
      return findMatchingLog(def, listOf(sessionDir), scopeLabel = "session $sessionId")
    }

    // No args — magic auto-search across every session.
    if (!effectiveLogsDir.isDirectory) {
      Console.error("Logs directory does not exist or is not a directory: ${effectiveLogsDir.absolutePath}")
      return null
    }
    val sessionDirs = effectiveLogsDir.listFiles { f -> f.isDirectory }?.toList().orEmpty()
    if (sessionDirs.isEmpty()) {
      Console.error("No session directories found under: ${effectiveLogsDir.absolutePath}")
      return null
    }
    return findMatchingLog(def, sessionDirs, scopeLabel = "all sessions under ${effectiveLogsDir.absolutePath}")
  }

  /**
   * Resolves the logs-directory to walk. Caller-supplied [logsDirOverride] wins. Otherwise we
   * use the running daemon's [TrailblazeDesktopAppConfig.logsDir], which has already been
   * resolved to the canonical install/cwd-aware location at startup. Reading [logsDir] is
   * deliberately path-only — it does NOT trigger [LogsRepo] construction, so a one-shot CLI
   * invocation doesn't spawn the LogsRepo's non-daemon file-watcher threads (which would
   * keep the JVM alive after the command completes). Falls back to `./logs` only if the
   * configProvider isn't initialized (shouldn't happen via the normal CLI entrypoint, but
   * guards against test harnesses that bypass `TrailblazeCli.run`).
   */
  private fun effectiveLogsDir(): File {
    logsDirOverride?.let { return it }
    return try {
      parent.cliRoot.configProvider().logsDir
    } catch (_: UninitializedPropertyAccessException) {
      File("./logs")
    }
  }

  /**
   * Resolves a `--session <id>` arg to a directory under [effectiveLogsDir]. Allows the caller
   * to pass either a bare session id (typical: `2026_05_07_22_26_48_yaml_6258`) or, as an
   * escape hatch, a full path. Returns null after writing an error if neither resolves.
   */
  private fun resolveSessionDir(id: String, effectiveLogsDir: File): File? {
    // Try as-id first (the documented form).
    val asId = File(effectiveLogsDir, id)
    if (asId.isDirectory) return asId
    // Escape hatch: treat as a literal path. Not advertised in --session help text, but
    // refusing it would just send users to look for the directory and re-type.
    val asPath = File(id)
    if (asPath.isDirectory) return asPath
    Console.error("--session $id: no directory found at ${asId.absolutePath} (or as a literal path).")
    return null
  }

  /**
   * Walks every screen-state log file under [sessionDirs], runs [WaypointMatcher.match] against
   * each, and returns the chronologically most recent log file where the waypoint matches AND
   * the log has a non-null `screenshotFile`. Returns null after writing a `Console.error` if
   * nothing matches.
   *
   * Pre-filters via [SessionLogScreenState.hasScreenshot] so the expensive `loadStep` (which
   * loads the screenshot bytes into memory) only runs on screenshot-bearing logs. The matcher
   * itself does not look at the bytes; this is purely a cheap shortcut.
   *
   * "Most recent" is decided by the JSON `timestamp` field on each log, NOT by filename: ATF
   * / CI accessibility-driver runs use hex-hash filenames that don't sort chronologically by
   * name. Local CLI numeric-prefix filenames (`008_…`) sort correctly under either key, so
   * timestamp-sort is uniformly correct.
   */
  private fun findMatchingLog(
    def: WaypointDefinition,
    sessionDirs: List<File>,
    scopeLabel: String,
  ): File? {
    // Display key (session/file) is kept separate from the sort key so the status line stays
    // human-readable while ordering is driven by parsed timestamp.
    data class Candidate(val file: File, val timestamp: String?, val displayKey: String)

    val matches = mutableListOf<Candidate>()
    var totalScanned = 0
    var totalEligible = 0
    for (sessionDir in sessionDirs) {
      for (logFile in SessionLogScreenState.listScreenStateLogs(sessionDir)) {
        totalScanned++
        if (!SessionLogScreenState.hasScreenshot(logFile)) continue
        totalEligible++
        val screen = try {
          SessionLogScreenState.loadStep(logFile)
        } catch (_: Exception) {
          continue
        }
        if (WaypointMatcher.match(def, screen).matched) {
          matches += Candidate(
            file = logFile,
            timestamp = SessionLogScreenState.readTimestamp(logFile),
            displayKey = "${sessionDir.name}/${logFile.name}",
          )
        }
      }
    }
    if (matches.isEmpty()) {
      Console.error("No log step in $scopeLabel matched waypoint '${def.id}' (scanned $totalScanned logs, $totalEligible had screenshots).")
      Console.error("Hint: drive the device to the screen this waypoint represents, then re-run.")
      return null
    }
    // ISO-8601 string compare is order-preserving. Files with no readable timestamp fall back
    // to displayKey so the sort stays total — but they rank below any timestamp-bearing match.
    val pick = matches.maxWithOrNull(
      compareBy({ it.timestamp ?: "" }, { it.displayKey }),
    )!!
    Console.log("Auto-search picked ${pick.displayKey} (${matches.size} candidate(s) across $totalScanned scanned).")
    return pick.file
  }

  /**
   * Walks [root] for `*.waypoint.yaml` files, parses each, and returns the (def, file) pair
   * whose `id` matches `--id`. Returns null after writing an error if not found / ambiguous.
   *
   * `capture-example` writes its example pair (`<base>.example.json` + screenshot) NEXT TO
   * the resolved YAML on disk. That requires a writable file path, which classpath-bundled
   * YAMLs (loaded from JAR resources via the pack manifest) don't have. So this lookup is
   * deliberately filesystem-only. When the id IS declared in a classpath-bundled pack but
   * not on the filesystem, we surface a targeted error pointing the user at `--root` rather
   * than the generic "not found" — that distinguishes "you need to point me at the on-disk
   * pack source" from "this id doesn't exist anywhere."
   */
  private fun findWaypointFile(root: File): Pair<WaypointDefinition, File>? {
    val candidates = WaypointLoader.discover(root)
    val yaml = Yaml(configuration = YamlConfiguration(strictMode = false, encodeDefaults = false))
    val matches = mutableListOf<Pair<WaypointDefinition, File>>()
    for (file in candidates) {
      val def = try {
        yaml.decodeFromString(WaypointDefinition.serializer(), file.readText())
      } catch (_: Exception) {
        continue // resilient to unrelated bad files
      }
      if (def.id == waypointId) matches += def to file
    }
    return when (matches.size) {
      1 -> matches.single()
      0 -> {
        // Filesystem walk turned up nothing. Before giving the generic "not found" error,
        // probe the classpath via WaypointDiscovery — if the id IS bundled there, the user
        // hit the most common foot-gun (`capture-example --target <id>` against a
        // classpath-only pack) and deserves a pointed error rather than confusion.
        val discoveredOnClasspath = WaypointDiscovery.discover(root)
          .definitions
          .any { it.id == waypointId }
        if (discoveredOnClasspath) {
          Console.error(
            "Waypoint id '$waypointId' is bundled on the classpath only — capture-example " +
              "writes its example pair next to the on-disk *.waypoint.yaml, which JAR-bundled " +
              "definitions don't have. Pass --root <pack-source-dir> pointing at the on-disk " +
              "pack source so the example file can be written next to the YAML there.",
          )
        } else {
          Console.error("Waypoint id not found: $waypointId (searched ${root.absolutePath})")
          maybeWarnNoTarget(rootOverride, targetId, resultIsEmpty = true)
        }
        null
      }
      else -> {
        Console.error("Multiple waypoint files declare id '$waypointId':")
        matches.forEach { Console.error("  ${it.second.absolutePath}") }
        null
      }
    }
  }

  /**
   * Picks the raw screenshot for the captured step.
   *
   * Twin-search applies ONLY to `_TrailblazeLlmRequestLog.json` sources. On Android the
   * framework writes two screenshots per step — one annotated (set-of-mark overlays for
   * the model) and one raw — and the LLM log's `screenshotFile` field points at the
   * annotated one. The raw twin is the temporally-closest sibling within ~1 second.
   *
   * `_AgentDriverLog.json` and `_TrailblazeSnapshotLog.json` reference the raw image
   * directly — they're not LLM-facing and never go through set-of-mark annotation. Doing
   * twin-search on those would risk picking a NEIGHBORING step's image whose filename
   * timestamp happens to land within the 1s window, silently committing the wrong screen.
   * For those log types we use [referencedFile] as-is and skip the twin lookup entirely.
   *
   * iOS today writes a single un-annotated screenshot per LLM-step, so when no second
   * file exists within the 1-second window we fall back to the referenced file.
   */
  private fun findRawScreenshot(logFile: File, sourceJson: JsonObject): File? {
    val referencedName = (sourceJson["screenshotFile"] as? JsonPrimitive)?.content ?: return null
    val dir = logFile.parentFile ?: return null
    val referencedFile = File(dir, referencedName)
    if (!referencedFile.exists()) return null

    // Non-LLM logs don't have an annotated/raw split — the referenced image IS raw.
    val isLlmRequestLog = logFile.name.endsWith("_TrailblazeLlmRequestLog.json")
    if (!isLlmRequestLog) return referencedFile

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
