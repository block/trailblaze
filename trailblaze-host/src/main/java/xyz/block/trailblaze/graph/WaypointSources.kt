package xyz.block.trailblaze.graph

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.waypoint.WaypointDefinition
import xyz.block.trailblaze.cli.WaypointDiscovery
import xyz.block.trailblaze.config.project.LoadedTrailblazeTrailmapManifest
import xyz.block.trailblaze.config.project.TrailmapSource
import xyz.block.trailblaze.config.project.TrailblazeTrailmapManifestLoader
import xyz.block.trailblaze.logs.client.TrailblazeJson
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.waypoint.SessionLogScreenState
import xyz.block.trailblaze.waypoint.WaypointLoader
import java.io.File

/**
 * A waypoint plus the source label used to display it. The source label is purely
 * informational — typically a relative file path for filesystem-walked waypoints, or a
 * trailmap-id-prefixed string like `trailmap:clock` for classpath-bundled ones.
 *
 * [example] is the captured screen tree + screenshot bundled alongside the waypoint
 * (`<id>.example.json` + `<id>.example.webp/png`).
 */
data class WaypointDisplayItem(
  val definition: WaypointDefinition,
  val sourceLabel: String? = null,
  val example: WaypointExample? = null,
)

/**
 * Captured proof-screen for a waypoint: the screen tree plus the matching screenshot
 * bytes and device dimensions.
 *
 * Plain class rather than data class so [ByteArray] doesn't blow up structural equality —
 * we never need value equality on this object.
 */
class WaypointExample(
  val tree: TrailblazeNode,
  val screenshotBytes: ByteArray?,
  val deviceWidth: Int,
  val deviceHeight: Int,
)

internal data class WaypointLoadOutput(
  val items: List<WaypointDisplayItem>,
  val failureMessages: List<String>,
)

/**
 * Cap on the number of failure messages we'll surface from each source (root walk
 * failures + example-load failures). Without a cap, a workspace with hundreds of
 * malformed files produces a wall of text that's hard to skim. Per-source
 * capping (rather than a single overall cap) preserves diversity — the user still sees
 * representative entries from each failure class instead of one source crowding the
 * others out.
 */
private const val MAX_FAILURE_MESSAGES_PER_SOURCE = 25

internal fun loadWaypoints(root: File): WaypointLoadOutput {
  val discovery = WaypointDiscovery.discover(root)
  val idToFile = if (root.isDirectory) buildIdToFileMap(root) else emptyMap()
  val exampleFailures = mutableListOf<String>()
  val trailmapContents = collectTrailmapContents(exampleFailures)

  val items = mergeWaypointSources(
    definitions = discovery.definitions,
    idToFile = idToFile,
    trailmapContents = trailmapContents,
    root = root,
    loadFilesystemExample = { file, id -> tryLoadFilesystemExample(file, id, exampleFailures, root) },
  )

  val rootFailureMessages = discovery.rootFailures.failures.map { failure ->
    "${failure.file.toRelativeStringOrAbsolute(root)}: ${failure.cause.message ?: failure.cause::class.simpleName}"
  }

  val failureMessages = buildList {
    addAllCapped(rootFailureMessages, MAX_FAILURE_MESSAGES_PER_SOURCE, label = "root failures")
    if (discovery.trailmapLoadFailed) {
      add("One or more trailmap-bundled waypoint sources failed to load (see CLI logs for details).")
    }
    addAllCapped(exampleFailures, MAX_FAILURE_MESSAGES_PER_SOURCE, label = "example failures")
  }

  return WaypointLoadOutput(items = items, failureMessages = failureMessages)
}

/**
 * Appends up to [cap] entries from [source] and, if the source overflowed, a single
 * synthesized "...and N more {label}" tail so the output truthfully advertises that
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
 * [WaypointDiscovery] plus the side maps (filesystem id→file, classpath trailmap contents),
 * produces a [WaypointDisplayItem] per definition with the right source label and example.
 *
 * Source-of-truth rule (the bug originally flagged by Codex):
 * if a definition's id appears in [TrailmapContents.ids], the trailmap is its provenance. Any
 * same-id filesystem file under [root] is shadowed by [WaypointDiscovery]'s trailmap-first
 * dedup and must NOT contribute its path or example — otherwise the graph would
 * describe a trailmap waypoint with the shadowed file's screenshot.
 *
 * `loadFilesystemExample` is parameterized so tests can stub the example loader without
 * touching the filesystem; production callers pass a lambda that wraps
 * [tryLoadFilesystemExample] with the shared failures list.
 */
internal fun mergeWaypointSources(
  definitions: List<WaypointDefinition>,
  idToFile: Map<String, File>,
  trailmapContents: TrailmapContents,
  root: File,
  loadFilesystemExample: (File, String) -> WaypointExample?,
): List<WaypointDisplayItem> = definitions.map { def ->
  val isTrailmapProvenance = def.id in trailmapContents.ids
  val trailmapExample = trailmapContents.examples[def.id]
  val file = if (isTrailmapProvenance) null else idToFile[def.id]
  val sourceLabel = when {
    file != null -> file.toRelativeStringOrAbsolute(root)
    trailmapExample?.sourceLabel != null -> trailmapExample.sourceLabel
    // Trailmap-provenance with no captured example: fall back to the manifest path so
    // the source label still encodes the platform sub-dir
    // (`trailmaps/<trailmap>/waypoints/<platform>/...`). Downstream consumers that derive
    // platform from sourceLabel (e.g. WaypointGraphBuilder) need this for every
    // waypoint, not just the ones lucky enough to have an example.json sibling.
    isTrailmapProvenance -> trailmapContents.idToTrailmapPath[def.id] ?: "(trailmap-bundled)"
    else -> "(trailmap-bundled)"
  }
  val example = file?.let { loadFilesystemExample(it, def.id) } ?: trailmapExample?.example
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
 * Trailmap-bundled (classpath) waypoints come through [WaypointDiscovery] as plain definitions
 * with no source file we can sit next to. To still surface their captured screenshots,
 * walk the discovered classpath trailmap manifests directly: each manifest's `waypoints:`
 * list gives us the trailmap-relative path to a `.waypoint.yaml`, and the example.json /
 * screenshot live as siblings under the same path stem.
 *
 * Returned map is keyed by waypoint id (read out of the YAML) and includes a human
 * source label like `trailmap:clock — waypoints/clock-tab.waypoint.yaml`.
 */
internal data class TrailmapExample(val sourceLabel: String, val example: WaypointExample)

/**
 * What the trailmap-side discovery contributed:
 *  - [ids] — every waypoint id parsed out of a classpath trailmap manifest, even when no
 *    `.example.json` companion exists. Used by [mergeWaypointSources] to detect
 *    provenance: if an id is in this set, [WaypointDiscovery]'s trailmap-first dedup means
 *    the def we have is the trailmap's, and any same-id filesystem file under `root` is a
 *    shadowed entry whose path/screenshot must not be shown.
 *  - [examples] — the subset of those ids that had a usable `.example.json` + tree.
 */
internal data class TrailmapContents(
  val ids: Set<String>,
  val examples: Map<String, TrailmapExample>,
  /**
   * Every trailmap-provided waypoint id → its trailmap-relative manifest label, e.g.
   * `trailmap:myapp — waypoints/android/home.waypoint.yaml`. Populated even for ids
   * with no captured example, so downstream consumers can read platform out of
   * the path segment without needing an example.json sibling. Defaults to empty
   * for backward compatibility with the test fixtures that pre-date this field.
   */
  val idToTrailmapPath: Map<String, String> = emptyMap(),
)

private fun collectTrailmapContents(failures: MutableList<String>): TrailmapContents {
  val classpathTrailmaps = runCatching {
    TrailblazeTrailmapManifestLoader.discoverAndLoadFromClasspath()
  }.getOrElse { e ->
    // Don't lose this — without it, a malformed manifest silently zeros out every
    // trailmap-bundled waypoint and the user has no clue why the graph is empty.
    val msg = "trailmap manifest discovery failed: ${e.message ?: e::class.simpleName}"
    failures += msg
    Console.log("[Waypoints] $msg")
    return TrailmapContents(emptySet(), emptyMap())
  }
  return buildTrailmapContents(classpathTrailmaps, failures)
}

/**
 * Pure function over a list of pre-loaded trailmap manifests — separates the classpath-
 * discovery side effect (which the test suite can't easily control) from the per-trailmap
 * waypoint enumeration that the test suite actually wants to verify. `internal` so
 * `WaypointTrailmapContentsTest` can drive it with `TrailmapSource.Filesystem`-backed trailmaps.
 */
internal fun buildTrailmapContents(
  classpathTrailmaps: List<LoadedTrailblazeTrailmapManifest>,
  failures: MutableList<String>,
): TrailmapContents {
  val ids = mutableSetOf<String>()
  val examples = mutableMapOf<String, TrailmapExample>()
  val idToTrailmapPath = mutableMapOf<String, String>()
  for (trailmap in classpathTrailmaps) {
    // Modern trailmaps leave `manifest.waypoints` empty and rely on auto-discovery from
    // `<trailmap>/waypoints/**/*.waypoint.yaml` (mirroring TrailblazeProjectConfigLoader's
    // resolveSingleTrailmap). Without this fallback, every modern trailmap contributes zero
    // to TrailmapContents — so `idToTrailmapPath` stays empty, the platform-from-source-label
    // derivation in WaypointGraphBuilder always returns null, and the graph viewer's
    // platform filter pills silently disappear. Iterate the manifest list when present
    // (legacy trailmaps still parse), else walk `waypoints/` directly.
    val waypointPaths = trailmap.manifest.waypoints.takeIf { it.isNotEmpty() }
      ?: runCatching {
        trailmap.source.listSiblingsRecursive(
          relativeDir = "waypoints",
          suffixes = listOf(".waypoint.yaml"),
        )
      }.getOrElse { e ->
        failures += "trailmap:${trailmap.manifest.id}: failed to enumerate waypoints/ (${e.message ?: e::class.simpleName})"
        emptyList()
      }
    for (waypointPath in waypointPaths) {
      val parsed = loadTrailmapWaypointAndExample(trailmap, waypointPath, failures) ?: continue
      // `putIfAbsent`/`add` so the first trailmap to claim an id wins, mirroring
      // WaypointDiscovery's dedup semantics (workspace > classpath, trailmap-first within each).
      val trailmapLabel = "trailmap:${trailmap.manifest.id} — $waypointPath"
      ids += parsed.id
      idToTrailmapPath.putIfAbsent(parsed.id, trailmapLabel)
      parsed.example?.let { ex ->
        if (!examples.containsKey(parsed.id)) {
          examples[parsed.id] = TrailmapExample(
            sourceLabel = trailmapLabel,
            example = ex,
          )
        }
      }
    }
  }
  return TrailmapContents(ids = ids, examples = examples, idToTrailmapPath = idToTrailmapPath)
}

internal data class TrailmapParse(val id: String, val example: WaypointExample?)

/**
 * `internal` for unit testing — the test suite drives a `TrailmapSource.Filesystem` with
 * various sibling-file shapes through this function to lock down the failure-message
 * strings that surface in the load output.
 */
internal fun loadTrailmapWaypointAndExample(
  trailmap: LoadedTrailblazeTrailmapManifest,
  waypointPath: String,
  failures: MutableList<String>,
): TrailmapParse? {
  val trailmapLabel = "trailmap:${trailmap.manifest.id} — $waypointPath"
  // `readSibling` returns null when the file isn't present — for the waypoint yaml itself
  // (declared in the manifest) that's a real "manifest references missing file" error,
  // distinct from an exception during read. Surface both to the user.
  val yamlText: String = runCatching { trailmap.source.readSibling(waypointPath) }
    .getOrElse { e ->
      failures += "$trailmapLabel: failed to read waypoint yaml (${e.message ?: e::class.simpleName})"
      return null
    }
    ?: run {
      failures += "$trailmapLabel: waypoint yaml not found in trailmap source"
      return null
    }
  val def = runCatching {
    TRAILMAP_WAYPOINT_YAML.decodeFromString(WaypointDefinition.serializer(), yamlText)
  }.getOrElse { e ->
    failures += "$trailmapLabel: failed to parse waypoint yaml (${e.message ?: e::class.simpleName})"
    return null
  }
  val basename = waypointPath.removeSuffix(".waypoint.yaml")
  val exampleJsonPath = "$basename.example.json"
  // example.json is optional — readSibling returning null means "no companion file",
  // not a parse error, so we don't add a failure entry here.
  val exampleText = runCatching { trailmap.source.readSibling(exampleJsonPath) }.getOrNull()
    ?: return TrailmapParse(def.id, example = null)
  val projection = runCatching {
    TrailblazeJson.defaultWithoutToolsInstance
      .decodeFromString(TrailmapExampleProjection.serializer(), exampleText)
  }.getOrElse { e ->
    failures += "$trailmapLabel: failed to parse example.json (${e.message ?: e::class.simpleName}) (waypoint=${def.id})"
    return TrailmapParse(def.id, example = null)
  }
  val tree = projection.trailblazeNodeTree ?: run {
    failures += "$trailmapLabel: example.json has no trailblazeNodeTree (waypoint=${def.id})"
    return TrailmapParse(def.id, example = null)
  }
  val parentDir = waypointPath.substringBeforeLast('/', missingDelimiterValue = "")
  val screenshotBytes = projection.screenshotFile?.let { fileName ->
    val relPath = if (parentDir.isEmpty()) fileName else "$parentDir/$fileName"
    readTrailmapBytes(trailmap.source, relPath)
  }
  return TrailmapParse(
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
private val TRAILMAP_WAYPOINT_YAML = Yaml(
  configuration = YamlConfiguration(strictMode = false, encodeDefaults = false),
)

/**
 * Minimal projection of an `*.example.json` for example-screen loading. Mirrors the
 * private `LlmRequestLogProjection` in [SessionLogScreenState] but stays in this file
 * because that one isn't part of the public API. We only consume the fields the
 * graph needs — the matcher's deeper plumbing isn't relevant here.
 */
@Serializable
private data class TrailmapExampleProjection(
  val trailblazeNodeTree: TrailblazeNode? = null,
  val screenshotFile: String? = null,
  val deviceWidth: Int = 0,
  val deviceHeight: Int = 0,
)

/**
 * Reads a trailmap-relative resource as raw bytes. Mirrors [TrailmapSource.readSibling] except
 * that one returns text only — screenshots are binary (.webp/.png) so we need a bytes
 * variant. Goes through the same context classloader path the manifest loader uses so
 * jar:/file: classpath entries both work.
 */
private fun readTrailmapBytes(source: TrailmapSource, relativePath: String): ByteArray? {
  return when (source) {
    is TrailmapSource.Filesystem -> {
      val target = File(source.trailmapDir, relativePath)
      if (target.isFile) target.readBytes() else null
    }
    is TrailmapSource.Classpath -> {
      val cl = Thread.currentThread().contextClassLoader
        ?: TrailmapSource::class.java.classLoader
      cl?.getResourceAsStream("${source.resourceDir}/$relativePath")?.use { it.readBytes() }
    }
  }
}

private fun File.toRelativeStringOrAbsolute(root: File): String = try {
  toRelativeString(root)
} catch (_: IllegalArgumentException) {
  absolutePath
}
