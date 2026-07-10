package xyz.block.trailblaze.trailrunner

import xyz.block.trailblaze.devices.TrailblazeClassifierLineage
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.recordings.TrailRecordings
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import java.io.File

object TrailIndexBuilder {

  private const val MAX_DEPTH = 12

  private val TRAIL_SUFFIX = ".trail.yaml"

  // A prompt-only trail (no platform variants / recordings) that lives next to
  // its recorded siblings — same document format, surfaced as kind "blaze".
  private const val BLAZE_FILENAME = "blaze.yaml"

  // The unified format's canonical per-directory file — a BARE `trail.yaml` with no
  // `<device>` prefix, so it does NOT end in [TRAIL_SUFFIX] (`.trail.yaml`) and would be
  // invisible to a suffix-only match. Its identity is the enclosing directory, like
  // [BLAZE_FILENAME]. Sourced from the recording-layer constant so the index stays in sync
  // with the runtime's notion of "unified filename".
  private val UNIFIED_FILENAME = TrailRecordings.UNIFIED_TRAIL_FILENAME

  fun scan(root: File): List<TrailIndexEntry> = scanAll(primary = root, extras = emptyList())

  /**
   * Directories with no children at all, labeled like [TrailIndexEntry.folder]
   * (`<rootLabel>/<relative-dir>`). The tree derives its folder rows from trail file paths, so a
   * freshly created (still empty) directory would be invisible without this — surfacing them is
   * what makes "create a folder, then add trails to it" workable in the UI. A directory holding
   * only non-trail files needs no entry here: it either contains trails eventually or isn't part
   * of the authoring flow.
   */
  fun scanEmptyDirs(primary: File, extras: List<File>): List<String> {
    val out = mutableListOf<String>()
    collectEmptyDirs(primary, labelFor(primary, isPrimary = true), out)
    extras.forEach { collectEmptyDirs(it, labelFor(it, isPrimary = false), out) }
    return out.sorted()
  }

  private fun collectEmptyDirs(root: File, rootLabel: String, out: MutableList<String>) {
    if (!root.exists() || !root.isDirectory) return
    walkEmptyDirs(root, root, rootLabel, out, 0)
  }

  private fun walkEmptyDirs(root: File, dir: File, rootLabel: String, out: MutableList<String>, depth: Int) {
    if (depth > MAX_DEPTH) return
    val entries = dir.listFiles() ?: return
    for (entry in entries) {
      if (!entry.isDirectory || entry.name.startsWith(".")) continue
      val children = entry.listFiles()
      if (children.isNullOrEmpty()) {
        out.add("$rootLabel/${entry.relativeTo(root).invariantSeparatorsPath}")
      } else {
        walkEmptyDirs(root, entry, rootLabel, out, depth + 1)
      }
    }
  }

  fun scanAll(primary: File, extras: List<File>): List<TrailIndexEntry> {
    val out = mutableListOf<TrailIndexEntry>()
    scanOne(primary, rootIdx = 0, rootLabel = labelFor(primary, isPrimary = true), out = out)
    extras.forEachIndexed { i, extraRoot ->
      scanOne(extraRoot, rootIdx = i + 1, rootLabel = labelFor(extraRoot, isPrimary = false), out = out)
    }
    return out.sortedWith(compareBy({ it.rootIdx }, { it.path }))
  }

  private fun scanOne(root: File, rootIdx: Int, rootLabel: String, out: MutableList<TrailIndexEntry>) {
    if (!root.exists() || !root.isDirectory) {
      Console.log("[TrailIndexBuilder] trails root does not exist: ${root.absolutePath}")
      return
    }
    val files = mutableListOf<File>()
    walk(root, files, 0)
    files.forEach { out.add(build(root, it, rootIdx, rootLabel)) }
  }

  private fun labelFor(root: File, isPrimary: Boolean): String {
    val name = root.name.ifBlank { root.absolutePath }
    return if (isPrimary) name else "$name (${root.parent ?: ""})"
  }

  private fun walk(dir: File, out: MutableList<File>, depth: Int) {
    if (depth > MAX_DEPTH) return
    val entries = dir.listFiles() ?: return
    for (entry in entries) {
      if (entry.isDirectory && !entry.name.startsWith(".")) {
        walk(entry, out, depth + 1)
      } else if (
        entry.isFile &&
        (entry.name.endsWith(TRAIL_SUFFIX) || entry.name == BLAZE_FILENAME || entry.name == UNIFIED_FILENAME)
      ) {
        out.add(entry)
      }
    }
  }

  private fun build(root: File, file: File, rootIdx: Int, rootLabel: String): TrailIndexEntry {
    val relative = file.relativeTo(root).invariantSeparatorsPath
    val folder = relative.substringBeforeLast('/', "")
    val isBlaze = file.name == BLAZE_FILENAME
    val isUnifiedBare = file.name == UNIFIED_FILENAME
    // A bare `trail.yaml` and `blaze.yaml` both take their identity from the enclosing directory —
    // neither has a meaningful `<device>`/`<name>` filename stem. The id strips only `.yaml` (NOT
    // the `.trail.yaml` suffix, which a bare `trail.yaml` doesn't carry) so `resolveTrailFile`'s
    // `<id>.yaml` probe reconstructs the file on disk — the same round-trip `blaze.yaml` relies on.
    // A directory-only id (`.../case_5374124`) would 404: the resolver never probes `.../trail.yaml`.
    // Assumes no sibling recording is literally `trail.trail.yaml` (classifier stem "trail"): it would
    // share this id and win the resolver's `.trail.yaml`-before-`.yaml` probe, shadowing the unified file.
    val derivedId = if (isBlaze || isUnifiedBare) {
      relative.removeSuffix(".yaml")
    } else {
      relative.removeSuffix(TRAIL_SUFFIX)
    }
    // Title: literal "blaze" for a blaze file; the enclosing directory's name for a bare unified
    // file (its filename stem is just "trail"); the filename stem for a `<device>.trail.yaml`
    // recording. `dirName` falls back to the on-disk parent name for a root-level bare file.
    val dirName = folder.substringAfterLast('/').ifEmpty { file.parentFile?.name.orEmpty() }
    val derivedTitle = when {
      isBlaze -> "blaze"
      isUnifiedBare -> dirName.replace('-', ' ').replace('_', ' ')
      else -> file.name.removeSuffix(TRAIL_SUFFIX).replace('-', ' ').replace('_', ' ')
    }

    val cfg = parseConfig(file)
    val platform = cfg?.platform ?: platformFromFileName(file.name)
    return TrailIndexEntry(
      id = "$rootIdx/$derivedId",
      path = relative,
      title = cfg?.title ?: derivedTitle,
      target = cfg?.target,
      platform = platform,
      driver = cfg?.driver,
      priority = cfg?.priority,
      tags = cfg?.tags ?: emptyList(),
      folder = if (folder.isEmpty()) rootLabel else "$rootLabel/$folder",
      rootIdx = rootIdx,
      kind = if (isBlaze) "blaze" else "trail",
      // A bare `trail.yaml` is unified by definition, so an unparseable one is still "unified" — only
      // a `<device>.trail.yaml` whose content we couldn't classify falls back to "v1".
      format = cfg?.format ?: if (isUnifiedBare) "unified" else "v1",
      configId = cfg?.id,
    )
  }

  /**
   * Backfills a platform for a trail that doesn't declare `config.platform`. Recorded variants are
   * conventionally named after the device classifier they were recorded on (`android-phone`,
   * `ios-iphone`, or a hardware classifier a downstream build registers a lineage override for), so
   * resolving the filename stem through [TrailblazeClassifierLineage] and checking whether its
   * family root is a platform recovers the platform without hardcoding any classifier names here.
   * Unknown stems resolve to no known platform and stay platform-agnostic, as before.
   */
  private val PLATFORM_ROOTS = setOf("android", "ios", "web")

  internal fun platformFromFileName(fileName: String): String? {
    if (!fileName.endsWith(TRAIL_SUFFIX)) return null
    val stem = fileName.removeSuffix(TRAIL_SUFFIX).lowercase()
    if (stem.isBlank()) return null
    val root = TrailblazeClassifierLineage.chainFor(TrailblazeDeviceClassifier(stem)).lastOrNull() ?: return null
    return root.classifier.takeIf { it in PLATFORM_ROOTS }
  }

  private data class CachedConfig(
    val title: String?,
    val target: String?,
    val platform: String?,
    val driver: String?,
    val priority: String?,
    val tags: List<String>,
    val id: String?,
    /** On-disk YAML shape from [xyz.block.trailblaze.yaml.unified.TrailDocument]: "unified" or "v1". */
    val format: String,
  )

  private val configCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, CachedConfig>>()

  private fun parseConfig(file: File): CachedConfig? {
    val key = file.absolutePath
    val mtime = file.lastModified()
    configCache[key]?.let { (cachedMtime, cached) -> if (cachedMtime == mtime) return cached }
    return try {
      val yaml = file.readText()
      val tb = createTrailblazeYaml()
      // `decodeTrailDocument` also runs inside `extractTrailConfig` below, so this decodes twice. Kept
      // separate deliberately: it avoids duplicating extractTrailConfig's V1/Unified config-lowering
      // branches here, and this whole result is memoized per file mtime (`configCache`), so the second
      // parse is paid once per changed file at scan time, not per request.
      val format = when (tb.decodeTrailDocument(yaml)) {
        is xyz.block.trailblaze.yaml.unified.TrailDocument.Unified -> "unified"
        is xyz.block.trailblaze.yaml.unified.TrailDocument.V1 -> "v1"
      }
      val config = tb.extractTrailConfig(yaml)
      val result = CachedConfig(
        title = config?.title,
        target = config?.target,
        platform = config?.platform,
        driver = config?.driver,
        priority = config?.priority,
        tags = config?.tags ?: emptyList(),
        id = config?.id,
        format = format,
      )
      configCache[key] = mtime to result
      result
    } catch (e: Exception) {
      Console.log("[TrailIndexBuilder] config parse failed for ${file.absolutePath}: ${e.message}")
      null
    }
  }
}
