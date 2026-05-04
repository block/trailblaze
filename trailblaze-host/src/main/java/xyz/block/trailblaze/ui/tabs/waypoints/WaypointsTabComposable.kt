package xyz.block.trailblaze.ui.tabs.waypoints

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.waypoint.WaypointDefinition
import xyz.block.trailblaze.cli.WaypointDiscovery
import xyz.block.trailblaze.config.project.LoadedTrailblazePackManifest
import xyz.block.trailblaze.config.project.PackSource
import xyz.block.trailblaze.config.project.TrailblazePackManifestLoader
import xyz.block.trailblaze.logs.client.TrailblazeJson
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.segment.SessionSegmentExtractor
import xyz.block.trailblaze.ui.composables.SelectableText
import xyz.block.trailblaze.ui.models.AppIconProvider
import xyz.block.trailblaze.ui.waypoints.SegmentDisplayItem
import xyz.block.trailblaze.ui.waypoints.SessionLensPanel
import xyz.block.trailblaze.ui.waypoints.SessionLensResult
import xyz.block.trailblaze.ui.waypoints.SessionLensState
import xyz.block.trailblaze.ui.waypoints.WaypointDisplayItem
import xyz.block.trailblaze.ui.waypoints.WaypointExample
import xyz.block.trailblaze.ui.waypoints.WaypointVisualizer
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.waypoint.SessionLogScreenState
import xyz.block.trailblaze.waypoint.WaypointLoader
import androidx.compose.runtime.collectAsState
import java.io.File
import java.io.IOException
import javax.swing.JFileChooser

/**
 * Desktop-only host for the multiplatform [WaypointVisualizer]. Loads waypoints via the
 * same code path as the `trailblaze waypoint` CLI (workspace packs + classpath packs +
 * a filesystem walk under [initialRootPath]) and surfaces parse failures inline.
 *
 * The visualizer itself lives in `:trailblaze-ui` commonMain so the WASM target can
 * reuse it; this wrapper holds the JVM-only filesystem and JFileChooser bits.
 */
@Composable
fun WaypointsTabComposable(
  initialRootPath: String,
  logsRepo: LogsRepo,
  availableTargets: Set<TrailblazeHostAppTarget> = emptySet(),
  appIconProvider: AppIconProvider = AppIconProvider.DefaultAppIconProvider,
  onChangeDirectory: ((String) -> Unit)? = null,
) {
  var rootPath by remember(initialRootPath) { mutableStateOf(initialRootPath) }
  var isLoading by remember { mutableStateOf(true) }
  var loadResult by remember { mutableStateOf<WaypointLoadOutput?>(null) }
  var refreshKey by remember { mutableStateOf(0) }

  // Session-lens selection. Null = Idle. The LaunchedEffect below resolves the
  // SessionId to a directory via LogsRepo and runs the extractor when both the
  // selection and the waypoint definitions are available.
  var selectedSessionId by remember { mutableStateOf<SessionId?>(null) }
  var sessionLensState: SessionLensState by remember { mutableStateOf(SessionLensState.Idle) }

  // The same sessions list that drives the Sessions tab — recent first, reactive.
  val sessionInfos by logsRepo.sessionInfoFlow.collectAsState()
  val availableSessions = remember(sessionInfos) {
    sessionInfos.sortedByDescending { it.timestamp }
  }

  LaunchedEffect(rootPath, refreshKey) {
    isLoading = true
    loadResult = withContext(Dispatchers.IO) {
      loadWaypoints(File(rootPath))
    }
    isLoading = false
  }

  // Re-extract on any change to (selectedSessionId, waypoint definitions) so a refresh
  // of the waypoint root after picking a session also recomputes segments — definitions
  // are an input to extraction.
  LaunchedEffect(selectedSessionId, loadResult) {
    val sessionId = selectedSessionId
    if (sessionId == null) {
      sessionLensState = SessionLensState.Idle
      return@LaunchedEffect
    }
    val definitions = loadResult?.items?.map { it.definition }
    val label = availableSessions.firstOrNull { it.sessionId == sessionId }?.displayName
      ?: sessionId.value
    if (definitions == null) {
      // Waypoints still loading — keep the panel in Loading until definitions arrive.
      sessionLensState = SessionLensState.Loading(label)
      return@LaunchedEffect
    }
    sessionLensState = SessionLensState.Loading(label)
    sessionLensState = withContext(Dispatchers.IO) {
      runExtractor(logsRepo.getSessionDir(sessionId), definitions)
    }
  }

  Column(modifier = Modifier.fillMaxSize()) {
    WaypointsToolbar(
      rootPath = rootPath,
      onChangeDirectory = if (onChangeDirectory != null) {
        {
          pickDirectory(File(rootPath))?.let { picked ->
            rootPath = picked.absolutePath
            onChangeDirectory(picked.absolutePath)
          }
        }
      } else null,
      onRefresh = { refreshKey += 1 },
    )

    val result = loadResult
    when {
      isLoading && result == null -> Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
      ) { CircularProgressIndicator() }
      result == null -> Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
      ) {
        SelectableText(
          text = "Waypoint loader not yet initialized.",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      else -> {
        if (result.failureMessages.isNotEmpty()) {
          WaypointFailureBanner(result.failureMessages)
        }
        SessionLensPanel(
          state = sessionLensState,
          availableSessions = availableSessions,
          selectedSessionId = selectedSessionId,
          onSessionSelected = { selectedSessionId = it },
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        )
        // When a session is loaded, surface its matched-step map to the visualizer so
        // each waypoint card can badge "matched at steps …". Idle / Loading states yield
        // an empty map → no badges render, which is the v1.5 contract: badges appear iff
        // a session is selected and its analysis is in hand.
        //
        // Exhaustive `when` over the sealed [SessionLensState] is deliberate — adding a
        // future variant (e.g. `Error`) becomes a compile error, forcing the developer
        // to make a deliberate decision about how that state should map to badge data
        // rather than silently falling back to an empty map.
        val matchedStepsByWaypoint = remember(sessionLensState) {
          when (val s = sessionLensState) {
            is SessionLensState.Loaded -> s.result.matchedStepsByWaypoint
            SessionLensState.Idle, is SessionLensState.Loading -> emptyMap()
          }
        }
        WaypointVisualizer(
          items = result.items,
          modifier = Modifier.weight(1f).fillMaxWidth(),
          availableTargets = availableTargets,
          appIconProvider = appIconProvider,
          matchedStepsByWaypoint = matchedStepsByWaypoint,
        )
      }
    }
  }
}

@Composable
private fun WaypointsToolbar(
  rootPath: String,
  onChangeDirectory: (() -> Unit)?,
  onRefresh: () -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(
      text = "root:",
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    SelectableText(
      text = rootPath,
      style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
    )
    Spacer(Modifier.width(8.dp))
    if (onChangeDirectory != null) {
      AssistChip(
        onClick = onChangeDirectory,
        label = { Text("Change") },
        leadingIcon = {
          Icon(
            Icons.Filled.FolderOpen,
            contentDescription = null,
            modifier = Modifier.padding(end = 0.dp),
          )
        },
        colors = AssistChipDefaults.assistChipColors(),
      )
    }
    AssistChip(
      onClick = onRefresh,
      label = { Text("Refresh") },
      leadingIcon = {
        Icon(Icons.Filled.Refresh, contentDescription = null)
      },
    )
  }
}

@Composable
private fun WaypointFailureBanner(messages: List<String>) {
  var expanded by remember { mutableStateOf(false) }
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 4.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      SelectableText(
        text = "${messages.size} waypoint file(s) failed to parse.",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.error,
      )
      TextButton(onClick = { expanded = !expanded }) {
        Text(if (expanded) "Hide" else "Show details")
      }
    }
    if (expanded) {
      messages.forEach {
        SelectableText(
          text = it,
          style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
          color = MaterialTheme.colorScheme.error,
        )
      }
    }
  }
}

internal data class WaypointLoadOutput(
  val items: List<WaypointDisplayItem>,
  val failureMessages: List<String>,
)

/**
 * Cap on the number of failure messages we'll surface from each source (root walk
 * failures + example-load failures). Without a cap, a workspace with hundreds of
 * malformed files holds a string per error in memory until the next refresh and the
 * "Show details" expansion produces a wall of text that's hard to skim. Per-source
 * capping (rather than a single overall cap) preserves diversity — the user still sees
 * representative entries from each failure class instead of one source crowding the
 * others out.
 */
private const val MAX_FAILURE_MESSAGES_PER_SOURCE = 25

private fun loadWaypoints(root: File): WaypointLoadOutput {
  val discovery = WaypointDiscovery.discover(root)
  val idToFile = if (root.isDirectory) buildIdToFileMap(root) else emptyMap()
  val exampleFailures = mutableListOf<String>()
  val packContents = collectPackContents(exampleFailures)

  val items = mergeWaypointSources(
    definitions = discovery.definitions,
    idToFile = idToFile,
    packContents = packContents,
    root = root,
    loadFilesystemExample = { file, id -> tryLoadFilesystemExample(file, id, exampleFailures, root) },
  )

  val rootFailureMessages = discovery.rootFailures.failures.map { failure ->
    "${failure.file.toRelativeStringOrAbsolute(root)}: ${failure.cause.message ?: failure.cause::class.simpleName}"
  }

  val failureMessages = buildList {
    addAllCapped(rootFailureMessages, MAX_FAILURE_MESSAGES_PER_SOURCE, label = "root failures")
    if (discovery.packLoadFailed) {
      add("One or more pack-bundled waypoint sources failed to load (see CLI logs for details).")
    }
    addAllCapped(exampleFailures, MAX_FAILURE_MESSAGES_PER_SOURCE, label = "example failures")
  }

  return WaypointLoadOutput(items = items, failureMessages = failureMessages)
}

/**
 * Appends up to [cap] entries from [source] and, if the source overflowed, a single
 * synthesized "...and N more {label}" tail so the banner truthfully advertises that
 * messages were dropped.
 */
private fun MutableList<String>.addAllCapped(source: List<String>, cap: Int, label: String) {
  if (source.size <= cap) {
    addAll(source)
  } else {
    addAll(source.take(cap))
    add("...and ${source.size - cap} more $label suppressed")
  }
}

/**
 * Pure merge step exposed for unit testing — given the list of definitions discovered by
 * [WaypointDiscovery] plus the side maps (filesystem id→file, classpath pack contents),
 * produces a [WaypointDisplayItem] per definition with the right source label and example.
 *
 * Source-of-truth rule (the bug originally flagged by Codex):
 * if a definition's id appears in [PackContents.ids], the pack is its provenance. Any
 * same-id filesystem file under [root] is shadowed by [WaypointDiscovery]'s pack-first
 * dedup and must NOT contribute its path or example — otherwise the visualizer would
 * describe a pack waypoint with the shadowed file's screenshot.
 *
 * `loadFilesystemExample` is parameterized so tests can stub the example loader without
 * touching the filesystem; production callers pass a lambda that wraps
 * [tryLoadFilesystemExample] with the shared failures list.
 */
internal fun mergeWaypointSources(
  definitions: List<WaypointDefinition>,
  idToFile: Map<String, File>,
  packContents: PackContents,
  root: File,
  loadFilesystemExample: (File, String) -> WaypointExample?,
): List<WaypointDisplayItem> = definitions.map { def ->
  val isPackProvenance = def.id in packContents.ids
  val packExample = packContents.examples[def.id]
  val file = if (isPackProvenance) null else idToFile[def.id]
  val sourceLabel = when {
    file != null -> file.toRelativeStringOrAbsolute(root)
    packExample?.sourceLabel != null -> packExample.sourceLabel
    else -> "(pack-bundled)"
  }
  val example = file?.let { loadFilesystemExample(it, def.id) } ?: packExample?.example
  WaypointDisplayItem(definition = def, sourceLabel = sourceLabel, example = example)
}

private fun buildIdToFileMap(root: File): Map<String, File> {
  val byId = mutableMapOf<String, File>()
  for (file in WaypointLoader.discover(root)) {
    val def = runCatching { WaypointLoader.loadFile(file) }.getOrNull() ?: continue
    byId.putIfAbsent(def.id, file)
  }
  return byId
}

/**
 * Locates and parses the `<basename>.example.json` companion file next to a waypoint
 * YAML, returning a [WaypointExample] (tree + screenshot bytes) on success.
 *
 * Reuses [SessionLogScreenState.loadStep] because example.json files are deliberately
 * shape-compatible with `_TrailblazeLlmRequestLog.json` files — same `trailblazeNodeTree`,
 * `screenshotFile`, `deviceWidth`/`deviceHeight` keys. Extra waypoint-specific fields
 * (`waypointId`, `capturedAt`, `capturedFrom`) are ignored thanks to
 * [xyz.block.trailblaze.logs.client.TrailblazeJson]'s `ignoreUnknownKeys = true`.
 */
private fun tryLoadFilesystemExample(
  waypointFile: File,
  waypointId: String,
  failures: MutableList<String>,
  root: File,
): WaypointExample? {
  val basename = waypointFile.name.removeSuffix(".waypoint.yaml")
  val exampleJson = File(waypointFile.parentFile, "$basename.example.json")
  if (!exampleJson.exists()) return null
  return try {
    val state = SessionLogScreenState.loadStep(exampleJson)
    val tree = state.trailblazeNodeTree ?: run {
      failures += "${exampleJson.toRelativeStringOrAbsolute(root)}: example.json has no trailblazeNodeTree (waypoint=$waypointId)"
      return null
    }
    WaypointExample(
      tree = tree,
      screenshotBytes = state.screenshotBytes,
      deviceWidth = state.deviceWidth,
      deviceHeight = state.deviceHeight,
    )
  } catch (e: Exception) {
    failures += "${exampleJson.toRelativeStringOrAbsolute(root)}: ${e.message ?: e::class.simpleName} (waypoint=$waypointId)"
    null
  }
}

/**
 * Pack-bundled (classpath) waypoints come through [WaypointDiscovery] as plain definitions
 * with no source file we can sit next to. To still surface their captured screenshots,
 * walk the discovered classpath pack manifests directly: each manifest's `waypoints:`
 * list gives us the pack-relative path to a `.waypoint.yaml`, and the example.json /
 * screenshot live as siblings under the same path stem.
 *
 * Returned map is keyed by waypoint id (read out of the YAML) and includes a human
 * source label like `pack:clock — waypoints/clock-tab.waypoint.yaml`.
 */
internal data class PackExample(val sourceLabel: String, val example: WaypointExample)

/**
 * What the pack-side discovery contributed to the visualizer:
 *  - [ids] — every waypoint id parsed out of a classpath pack manifest, even when no
 *    `.example.json` companion exists. Used by [mergeWaypointSources] to detect
 *    provenance: if an id is in this set, [WaypointDiscovery]'s pack-first dedup means
 *    the def we have is the pack's, and any same-id filesystem file under `root` is a
 *    shadowed entry whose path/screenshot must not be shown.
 *  - [examples] — the subset of those ids that had a usable `.example.json` + tree.
 */
internal data class PackContents(
  val ids: Set<String>,
  val examples: Map<String, PackExample>,
)

private fun collectPackContents(failures: MutableList<String>): PackContents {
  val ids = mutableSetOf<String>()
  val examples = mutableMapOf<String, PackExample>()
  val classpathPacks = runCatching {
    TrailblazePackManifestLoader.discoverAndLoadFromClasspath()
  }.getOrElse { e ->
    // Don't lose this — without it, a malformed manifest silently zeros out every
    // pack-bundled waypoint and the user has no clue why their tab is empty.
    val msg = "pack manifest discovery failed: ${e.message ?: e::class.simpleName}"
    failures += msg
    Console.log("[Waypoints] $msg")
    return PackContents(emptySet(), emptyMap())
  }
  for (pack in classpathPacks) {
    for (waypointPath in pack.manifest.waypoints) {
      val parsed = loadPackWaypointAndExample(pack, waypointPath, failures) ?: continue
      // `putIfAbsent`/`add` so the first pack to claim an id wins, mirroring
      // WaypointDiscovery's dedup semantics (workspace > classpath, pack-first within each).
      ids += parsed.id
      parsed.example?.let { ex ->
        if (!examples.containsKey(parsed.id)) {
          examples[parsed.id] = PackExample(
            sourceLabel = "pack:${pack.manifest.id} — $waypointPath",
            example = ex,
          )
        }
      }
    }
  }
  return PackContents(ids = ids, examples = examples)
}

internal data class PackParse(val id: String, val example: WaypointExample?)

/**
 * `internal` for unit testing — the test suite drives a `PackSource.Filesystem` with
 * various sibling-file shapes through this function to lock down the failure-message
 * strings that surface in the visualizer's banner.
 */
internal fun loadPackWaypointAndExample(
  pack: LoadedTrailblazePackManifest,
  waypointPath: String,
  failures: MutableList<String>,
): PackParse? {
  val packLabel = "pack:${pack.manifest.id} — $waypointPath"
  // `readSibling` returns null when the file isn't present — for the waypoint yaml itself
  // (declared in the manifest) that's a real "manifest references missing file" error,
  // distinct from an exception during read. Surface both to the user.
  val yamlText: String = runCatching { pack.source.readSibling(waypointPath) }
    .getOrElse { e ->
      failures += "$packLabel: failed to read waypoint yaml (${e.message ?: e::class.simpleName})"
      return null
    }
    ?: run {
      failures += "$packLabel: waypoint yaml not found in pack source"
      return null
    }
  val def = runCatching {
    PACK_WAYPOINT_YAML.decodeFromString(WaypointDefinition.serializer(), yamlText)
  }.getOrElse { e ->
    failures += "$packLabel: failed to parse waypoint yaml (${e.message ?: e::class.simpleName})"
    return null
  }
  val basename = waypointPath.removeSuffix(".waypoint.yaml")
  val exampleJsonPath = "$basename.example.json"
  // example.json is optional — readSibling returning null means "no companion file",
  // not a parse error, so we don't add a failure entry here.
  val exampleText = runCatching { pack.source.readSibling(exampleJsonPath) }.getOrNull()
    ?: return PackParse(def.id, example = null)
  val projection = runCatching {
    TrailblazeJson.defaultWithoutToolsInstance
      .decodeFromString(PackExampleProjection.serializer(), exampleText)
  }.getOrElse { e ->
    failures += "$packLabel: failed to parse example.json (${e.message ?: e::class.simpleName}) (waypoint=${def.id})"
    return PackParse(def.id, example = null)
  }
  val tree = projection.trailblazeNodeTree ?: run {
    failures += "$packLabel: example.json has no trailblazeNodeTree (waypoint=${def.id})"
    return PackParse(def.id, example = null)
  }
  val parentDir = waypointPath.substringBeforeLast('/', missingDelimiterValue = "")
  val screenshotBytes = projection.screenshotFile?.let { fileName ->
    val relPath = if (parentDir.isEmpty()) fileName else "$parentDir/$fileName"
    readPackBytes(pack.source, relPath)
  }
  return PackParse(
    id = def.id,
    example = WaypointExample(
      tree = tree,
      screenshotBytes = screenshotBytes,
      deviceWidth = projection.deviceWidth,
      deviceHeight = projection.deviceHeight,
    ),
  )
}

/** Same kaml config as `WaypointLoader.yaml`, duplicated here to avoid widening that internal API. */
private val PACK_WAYPOINT_YAML = Yaml(
  configuration = YamlConfiguration(strictMode = false, encodeDefaults = false),
)

/**
 * Minimal projection of an `*.example.json` for example-screen loading. Mirrors the
 * private `LlmRequestLogProjection` in [SessionLogScreenState] but stays in this file
 * because that one isn't part of the public API. We only consume the fields the
 * visualizer needs — the matcher's deeper plumbing isn't relevant here.
 */
@Serializable
private data class PackExampleProjection(
  val trailblazeNodeTree: TrailblazeNode? = null,
  val screenshotFile: String? = null,
  val deviceWidth: Int = 0,
  val deviceHeight: Int = 0,
)

/**
 * Reads a pack-relative resource as raw bytes. Mirrors [PackSource.readSibling] except
 * that one returns text only — screenshots are binary (.webp/.png) so we need a bytes
 * variant. Goes through the same context classloader path the manifest loader uses so
 * jar:/file: classpath entries both work.
 */
private fun readPackBytes(source: PackSource, relativePath: String): ByteArray? {
  return when (source) {
    is PackSource.Filesystem -> {
      val target = File(source.packDir, relativePath)
      if (target.isFile) target.readBytes() else null
    }
    is PackSource.Classpath -> {
      val cl = Thread.currentThread().contextClassLoader
        ?: PackSource::class.java.classLoader
      cl?.getResourceAsStream("${source.resourceDir}/$relativePath")?.use { it.readBytes() }
    }
  }
}

private fun File.toRelativeStringOrAbsolute(root: File): String = try {
  toRelativeString(root)
} catch (_: IllegalArgumentException) {
  absolutePath
}

private fun pickDirectory(start: File): File? {
  val chooserStart = start.takeIf { it.exists() && it.isDirectory }
    ?: File(System.getProperty("user.dir"))
  val chooser = JFileChooser(chooserStart)
  chooser.dialogTitle = "Choose waypoint root directory"
  chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
  return when (chooser.showOpenDialog(null)) {
    JFileChooser.APPROVE_OPTION -> chooser.selectedFile
    else -> null
  }
}

/**
 * Runs [SessionSegmentExtractor.analyze] against [sessionDir] and converts the JVM-only
 * `Analysis` shape into the commonMain [SessionLensResult] the panel renders. Wraps
 * exceptions (`require` mismatches, IOException from listFiles == null) into a Loaded
 * state with zero counts so the panel can still surface the diagnostic — we'd rather
 * see a path-with-no-data card than have the whole tab error out.
 *
 * Returns a [SessionLensState.Loaded]; failure cases produce a result with a
 * [SessionLensResult.parseFailures] count to flag the issue. Once we add an explicit
 * Error variant to `SessionLensState`, this can surface the original message verbatim.
 */
private fun runExtractor(
  sessionDir: File,
  definitions: List<xyz.block.trailblaze.api.waypoint.WaypointDefinition>,
): SessionLensState.Loaded {
  val analysis = try {
    SessionSegmentExtractor.analyze(sessionDir, definitions)
  } catch (e: IllegalArgumentException) {
    Console.error("[Waypoints SessionLens] not a directory: ${sessionDir.absolutePath}: ${e.message}")
    return SessionLensState.Loaded(
      SessionLensResult(
        sessionPath = sessionDir.absolutePath,
        totalRequestLogs = 0,
        stepsWithNodeTree = 0,
        stepsWithMatchedWaypoint = 0,
        stepsWithAmbiguousMatch = 0,
        parseFailures = 0,
        segments = emptyList(),
        matchedStepsByWaypoint = emptyMap(),
      ),
    )
  } catch (e: IOException) {
    Console.error("[Waypoints SessionLens] failed to read ${sessionDir.absolutePath}: ${e.message}")
    return SessionLensState.Loaded(
      SessionLensResult(
        sessionPath = sessionDir.absolutePath,
        totalRequestLogs = 0,
        stepsWithNodeTree = 0,
        stepsWithMatchedWaypoint = 0,
        stepsWithAmbiguousMatch = 0,
        // Surface a synthetic "1 file failed" so the panel's diagnostic chips flag the
        // issue; full-fidelity error reporting waits for an Error variant on the state.
        parseFailures = 1,
        segments = emptyList(),
        matchedStepsByWaypoint = emptyMap(),
      ),
    )
  }
  return SessionLensState.Loaded(
    SessionLensResult(
      sessionPath = sessionDir.absolutePath,
      totalRequestLogs = analysis.totalRequestLogs,
      stepsWithNodeTree = analysis.stepsWithNodeTree,
      stepsWithMatchedWaypoint = analysis.stepsWithMatchedWaypoint,
      stepsWithAmbiguousMatch = analysis.stepsWithAmbiguousMatch,
      parseFailures = analysis.parseFailures,
      segments = analysis.segments.map {
        SegmentDisplayItem(
          from = it.from,
          to = it.to,
          triggers = it.triggers,
          fromStep = it.observation.fromStep,
          toStep = it.observation.toStep,
          durationMs = it.observation.durationMs,
        )
      },
      matchedStepsByWaypoint = analysis.matchedStepsByWaypoint,
    ),
  )
}
