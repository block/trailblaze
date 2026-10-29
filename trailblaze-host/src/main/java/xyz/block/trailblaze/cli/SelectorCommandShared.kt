package xyz.block.trailblaze.cli

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.MigrationScreenState
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.SelectorTemplating
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.runJsonOutput
import xyz.block.trailblaze.waypoint.SessionLogScreenState
import java.io.File

/**
 * Bad input to a `trailblaze selector` command: a missing file, an unknown ref, a selector that
 * doesn't parse. Thrown rather than printed so the same check serves one-shot mode (print and
 * exit [TrailblazeExitCode.MISUSE]) and `--batch` mode (record it on that line and keep going).
 */
internal class SelectorInputException(message: String) : Exception(message)

/**
 * A capture too incomplete to answer the question asked of it. The command was fine; capturing
 * again may answer it, so it exits [TrailblazeExitCode.INFRA_FAILED], not MISUSE.
 */
internal class IncompleteCaptureException(message: String) : Exception(message)

/**
 * [source] can't answer the question asked of it (it lacks the tree asked for, or lost nodes). A
 * log file is fixed, so asking it is bad input; a live capture can come back incomplete (a
 * dual-tree capture can keep one tree and not the other), so asking again may answer it.
 */
internal fun unanswerable(source: SelectorScreen, message: String): Exception =
  if (source is SelectorScreen.Live) {
    IncompleteCaptureException("$message Capture the screen again.")
  } else {
    SelectorInputException(message)
  }

/**
 * Refuses a capture known to have lost nodes: it answers nothing from its primary tree, since a
 * lost node can hide a match, shift which element an index or an anchor picks, and change which
 * wrappers the accessibility filter keeps. A migration tree is captured separately and isn't
 * affected.
 */
internal fun refuseIncompleteCapture(source: SelectorScreen, screen: ScreenState) {
  if (screen.isCaptureKnownPartial) {
    throw unanswerable(
      source,
      "${source.displayName} lost ${screen.droppedNodeFetches} node fetch(es) while it was captured, so it " +
        "can't show which elements a selector matches.",
    )
  }
}

/** Encodes the `--json` / `--batch` output: one compact object per line, nulls dropped. */
internal val selectorCliJson = Json {
  explicitNulls = false
  encodeDefaults = false
  ignoreUnknownKeys = true
}

/** One node on a screen, as `--json` output reports it. */
@Serializable
internal data class SelectorNodeJson(
  val ref: String? = null,
  val description: String,
  val bounds: TrailblazeNode.Bounds? = null,
)

internal fun TrailblazeNode.toSelectorNodeJson() =
  SelectorNodeJson(ref = ref, description = describeNode(this), bounds = bounds)

/**
 * Resolves a path the caller typed against the caller's shell cwd. Forwarded to the daemon, the
 * JVM's own cwd is wherever the daemon was started, so a relative path must not be read from it.
 */
internal fun callerFile(file: File): File =
  if (file.isAbsolute) file else CliCallerContext.callerCwd().resolve(file.path).toFile()

/** A screen-state log file the caller named directly. */
internal fun requireLogFile(file: File): File {
  val resolved = callerFile(file)
  if (!resolved.isFile) {
    throw SelectorInputException("Log file does not exist or is not a file: ${resolved.absolutePath}")
  }
  return resolved
}

/**
 * Strict on purpose: the shared YAML instance drops unknown keys, which would turn a misspelled
 * field into a selector that quietly matches something else.
 */
private val strictSelectorYaml = Yaml(configuration = YamlConfiguration(strictMode = true))

/**
 * Parses a [TrailblazeNodeSelector] from YAML. JSON is a subset of YAML flow style, so the
 * selector objects `--json` prints parse here unchanged. An empty selector is rejected: it
 * matches every node, which is never what the caller meant.
 */
internal fun parseNodeSelector(text: String, label: String = "--selector"): TrailblazeNodeSelector {
  val selector = try {
    strictSelectorYaml.decodeFromString(TrailblazeNodeSelector.serializer(), text)
  } catch (e: Exception) {
    throw SelectorInputException(
      "Could not parse $label as a selector: ${e.message}\n" +
        "Expected selector YAML or JSON, e.g. '{ androidAccessibility: { resourceIdRegex: \"^foo$\" } }'",
    )
  }
  // An empty `containsDescendants` list constrains nothing either, so it counts as absent here.
  if (selector.copy(containsDescendants = selector.containsDescendants?.ifEmpty { null }) == TrailblazeNodeSelector()) {
    throw SelectorInputException("$label is empty; it would match every node.")
  }
  // A capture carries no target to fill `{{target.appId}}` from, and the literal placeholder
  // matches nothing, which would read as "absent" rather than "not checked".
  if (SelectorTemplating.containsUnresolvedPlaceholder(selector)) {
    throw SelectorInputException(
      "$label contains ${SelectorTemplating.APP_ID_PLACEHOLDER}; replace it with the app id the captures ran.",
    )
  }
  return selector
}

/** One screen a selector command reads: a screen-state log, or the connected device's current screen. */
internal sealed interface SelectorScreen {
  /** How text output and error messages name the screen. */
  val displayName: String

  data class Log(val file: File) : SelectorScreen {
    override val displayName: String get() = file.name
  }

  /** The connected device's current screen (`--live`), captured once per invocation. */
  data object Live : SelectorScreen {
    override val displayName: String = "live screen"
  }
}

/**
 * Loads each screen once per invocation. A `--batch` file usually asks many questions about the
 * same few screens, and decoding a log (or capturing the device) is the expensive part of every
 * question. [live] captures the device's screen; it runs at most once, on first use.
 */
internal class ScreenStateCache(
  private val maxEntries: Int = 32,
  private val live: (() -> ScreenState)? = null,
) {
  private val entries = object : LinkedHashMap<String, ScreenState>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ScreenState>?) = size > maxEntries
  }

  // A failed capture is kept too, so a batch asks the device once however many lines want it.
  private var liveScreen: Result<ScreenState>? = null

  fun load(screen: SelectorScreen): ScreenState = when (screen) {
    is SelectorScreen.Log -> load(screen.file)
    SelectorScreen.Live -> (
      liveScreen ?: runCatching {
        (live ?: throw SelectorInputException("No live screen is available here.")).invoke()
      }.also { liveScreen = it }
      ).getOrThrow()
  }

  private val sessionLogs = mutableMapOf<String, List<File>>()

  /**
   * The [step]th (1-based; default last) screen capture in [sessionDir], counting screen-state
   * logs in time order (LLM requests, snapshots, driver captures), so the last step is the last
   * screen the session saw. Logs with no element tree (a driver log for an app launch or stop)
   * aren't screens, so they aren't steps. `waypoint validate --session` counts those logs too, so
   * its step N can be a different capture than step N here. Listing reads every log in the
   * session, so it runs once per session per invocation.
   */
  fun sessionStep(sessionDir: File, step: Int?): File {
    val dir = callerFile(sessionDir)
    if (!dir.isDirectory) {
      throw SelectorInputException("--session does not exist or is not a directory: ${dir.absolutePath}")
    }
    val logs = sessionLogs.getOrPut(dir.canonicalPath) {
      // Each log is read once, and checked and ordered from that one read: a log still being
      // written could otherwise be ordered from a partial read and checked from a whole one.
      val screens = SessionLogScreenState.screenStateLogFiles(dir).mapNotNull { log ->
        val text = try {
          log.readText()
        } catch (e: java.io.IOException) {
          throw SelectorInputException("Could not read ${log.absolutePath}: ${e.message}")
        }
        // A cut-off log has no reliable timestamp or tree, so it is reported rather than placed.
        val complete = runCatching { Json.parseToJsonElement(text) is JsonObject }.getOrDefault(false)
        if (!complete) {
          throw SelectorInputException("${log.absolutePath} is empty or cut off, so it can't be placed among the steps.")
        }
        // A log with no tree (an app launch or stop) isn't a screen.
        if (NODE_TREE_PRESENT.containsMatchIn(text)) log to SessionLogScreenState.logTimeOf(text) else null
      }
      SessionLogScreenState.sortChronologically(dir, screens)
    }
    if (logs.isEmpty()) {
      throw SelectorInputException("No screen captures with an element tree found in: ${dir.absolutePath}")
    }
    val idx = step?.let { it - 1 } ?: (logs.size - 1)
    if (idx !in logs.indices) {
      throw SelectorInputException("--step $step out of range for ${dir.name}: 1..${logs.size}")
    }
    return logs[idx]
  }

  private companion object {
    /** A `trailblazeNodeTree` object in a log's raw JSON, found without decoding the log. */
    val NODE_TREE_PRESENT = Regex(""""trailblazeNodeTree"\s*:\s*\{""")
  }

  fun load(file: File): ScreenState = entries.getOrPut(file.canonicalPath) {
    try {
      SessionLogScreenState.loadStep(file)
    } catch (e: Exception) {
      throw SelectorInputException("Could not read screen state from ${file.name}: ${e.message}")
    }
  }
}

/**
 * Captures the connected device's current screen for `--live`. [device] is the `--device` value;
 * null picks the only connected device, as every device command does.
 */
internal fun interface LiveScreenProvider {
  /** Throws [LiveScreenUnavailable] once it has printed why it couldn't. */
  fun capture(device: String?): ScreenState
}

/** `--live` could not read a screen. The reason is already printed; [exitCode] is what to exit with. */
internal class LiveScreenUnavailable(val exitCode: Int) : Exception()

/** A `--batch` line's error when its live screen couldn't be read; the reason is on stderr. */
internal const val LIVE_SCREEN_UNAVAILABLE = "the device's screen could not be read (see stderr)"

/**
 * Whether a batch line asks for the device's screen (`"live": true`), which it may only do when
 * the command was given --live. A daemon from before --live, which a call can be forwarded to,
 * ignores the key and would answer from the line's logs alone; it rejects --live as an unknown
 * option, so requiring the flag turns that silent answer into a refusal.
 */
internal fun JsonObject.wantsLiveScreen(liveAllowed: Boolean): Boolean {
  if (booleanField("live") != true) return false
  if (!liveAllowed) {
    throw SelectorInputException("this line reads the device's screen (\"live\": true); pass --live with --batch to allow that")
  }
  return true
}

/**
 * Captures through the daemon's `snapshot` path: the same capture `trailblaze snapshot --all`
 * makes, returned with the refs it prints stamped on the tree, so a ref read off
 * `snapshot --all` resolves here. No device, or more than one without `--device`, fails the same
 * way `snapshot` does.
 */
internal object DaemonLiveScreenProvider : LiveScreenProvider {
  private const val VERB = "Live screen capture"

  override fun capture(device: String?): ScreenState {
    var screen: ScreenState? = null
    // Console.log is closed inside the wrapper; the caller prints its report after this returns.
    // Progress it still prints (starting a stopped daemon) goes to stderr, so a `--json` or
    // `--batch` stdout carries nothing but the report.
    // Resolved inside the wrapper so a config-read warning stays off stdout. Binding a web device
    // persists its browser mode, so this honors the configured one the way `snapshot` does.
    val exit = Console.runJsonOutput { cliReusableWithDevice(
      verbose = false,
      device = device,
      webHeadless = HeadlessOption().resolve(),
      verb = VERB,
    ) { client ->
      val result = client.callTool(
        "step",
        mapOf(
          "objective" to "Capture screen state",
          "tools" to SnapshotCommand.SNAPSHOT_TOOLS_YAML,
          "fast" to true,
          "snapshotDetails" to "ALL_ELEMENTS",
          "screenJson" to true,
        ),
      )
      when (val decoded = decodeLiveScreen(result)) {
        is LiveScreenResult.Captured -> {
          screen = decoded.screen
          TrailblazeExitCode.SUCCESS.code
        }
        is LiveScreenResult.Failed -> {
          reportCliError(verb = VERB, target = device, reason = decoded.reason, hint = decoded.hint)
          TrailblazeExitCode.INFRA_FAILED.code
        }
      }
    } }
    return screen ?: throw LiveScreenUnavailable(
      exit.takeIf { it != TrailblazeExitCode.SUCCESS.code } ?: TrailblazeExitCode.INFRA_FAILED.code,
    )
  }
}

internal sealed interface LiveScreenResult {
  data class Captured(val screen: ScreenState) : LiveScreenResult
  data class Failed(val reason: String, val hint: String? = null) : LiveScreenResult
}

/** Reads the daemon's answer to a `screenJson` snapshot: the screen, or why there isn't one. */
internal fun decodeLiveScreen(result: CliMcpClient.ToolResult): LiveScreenResult {
  if (result.isFailure) return LiveScreenResult.Failed(result.content.trim())
  val json = try {
    selectorCliJson.parseToJsonElement(result.content).jsonObject
  } catch (_: Exception) {
    null
  } ?: return LiveScreenResult.Failed(
    "The daemon answered without a screen.",
    hint = "a daemon older than this CLI ignores the request; restart it with `trailblaze app --stop`",
  )
  (json["error"] as? JsonPrimitive)?.takeIf { it.isString }?.let { return LiveScreenResult.Failed(it.content) }
  val screen = try {
    SessionLogScreenState.decode(result.content)
  } catch (e: Exception) {
    return LiveScreenResult.Failed("Could not read the captured screen: ${e.message}")
  }
  // A screen with no element tree is a failed capture, not a bad command: say so here rather
  // than let it reach the offline "pick another log" error.
  if (screen.trailblazeNodeTree == null && (screen as? MigrationScreenState)?.driverMigrationTreeNode == null) {
    return LiveScreenResult.Failed(
      "The device returned a screen with no element tree.",
      hint = "capture again once the app is in the foreground",
    )
  }
  return LiveScreenResult.Captured(screen)
}

/**
 * Reads a `--batch` JSONL file: one JSON object per non-blank line, paired with its 1-based line
 * number. A line that isn't an object comes back as an [SelectorInputException] so the caller
 * can report it on that line and keep going.
 */
internal fun readBatchLines(batch: File): List<Pair<Int, Result<JsonObject>>> {
  val file = callerFile(batch)
  if (!file.isFile) {
    throw SelectorInputException("--batch file does not exist or is not a file: ${file.absolutePath}")
  }
  return file.readLines().withIndex()
    .filter { it.value.isNotBlank() }
    .map { (i, line) ->
      (i + 1) to runCatching {
        try {
          selectorCliJson.parseToJsonElement(line).jsonObject
        } catch (e: Exception) {
          throw SelectorInputException("not a JSON object: ${e.message}")
        }
      }
    }
}

/** A field of a batch line, with a JSON `null` treated as absent — the way serializers write unset fields. */
internal fun JsonObject.field(key: String): JsonElement? = this[key]?.takeUnless { it is JsonNull }

/** A string field of a batch line; a non-string value is passed through as its JSON text. */
internal fun JsonObject.stringField(key: String): String? =
  field(key)?.let { if (it is JsonPrimitive && it.isString) it.content else it.toString() }

internal fun JsonObject.intField(key: String): Int? =
  field(key)?.let {
    (it as? JsonPrimitive)?.content?.toIntOrNull()
      ?: throw SelectorInputException("\"$key\" must be an integer, got $it")
  }

internal fun JsonObject.booleanField(key: String): Boolean? =
  field(key)?.let {
    (it as? JsonPrimitive)?.booleanOrNull
      ?: throw SelectorInputException("\"$key\" must be true or false, got $it")
  }

/** Prints a [SelectorInputException]'s lines to stderr, one `Console.error` per line. */
internal fun reportSelectorInputError(e: SelectorInputException) {
  e.message.orEmpty().lines().forEach { Console.error(it) }
}

/** One-line human summary of a node: class, labels, id, and the state flags that matter. */
internal fun describeNode(target: TrailblazeNode): String {
  val parts = mutableListOf<String>()
  when (val d = target.driverDetail) {
    is DriverNodeDetail.AndroidAccessibility -> {
      d.className?.substringAfterLast('.')?.let { parts += it }
      d.text?.takeIf { it.isNotBlank() }?.let { parts += "text=\"$it\"" }
      d.contentDescription?.takeIf { it.isNotBlank() }?.let { parts += "desc=\"$it\"" }
      d.resourceId?.let { parts += "id=\"$it\"" }
      if (d.isSelected) parts += "isSelected"
      if (d.isHeading) parts += "isHeading"
      if (d.isClickable) parts += "isClickable"
    }
    is DriverNodeDetail.AndroidView -> {
      d.className?.substringAfterLast('.')?.let { parts += it }
      d.text?.takeIf { it.isNotBlank() }?.let { parts += "text=\"$it\"" }
      d.contentDescription?.takeIf { it.isNotBlank() }?.let { parts += "desc=\"$it\"" }
      d.resourceId?.let { parts += "id=\"$it\"" }
      d.tag?.takeIf { it.isNotBlank() }?.let { parts += "tag=\"$it\"" }
      if (d.isSelected) parts += "isSelected"
      if (d.isClickable) parts += "isClickable"
    }
    else -> parts += d::class.simpleName.orEmpty()
  }
  return if (parts.isEmpty()) "(no identifying properties)" else parts.joinToString(" ")
}
