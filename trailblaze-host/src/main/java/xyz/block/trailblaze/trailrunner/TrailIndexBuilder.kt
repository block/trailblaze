package xyz.block.trailblaze.trailrunner

import xyz.block.trailblaze.config.project.TrailDiscovery
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

  // Directories that never hold authored trails but do hold the overwhelming majority of a
  // workspace's files. The index is re-scanned on every request, so descending into these is the
  // difference between a walk and a crawl: on a monorepo checkout used as the trails root, skipping
  // them takes the traversal from ~33,700 directories / ~100,400 files to ~3,100 / ~8,200.
  //
  // Taken from [TrailDiscovery] rather than spelled out again, because this index and that walk
  // answer the same question about the same tree. A name only this list prunes is a trail the CLI
  // runs and the UI can't see, which reads as a missing file rather than as an exclude. The
  // leading-dot check below is broader than the shared set and predates it.
  private val SKIPPED_DIRS = TrailDiscovery.DEFAULT_EXCLUDED_DIRS

  fun scan(root: File): List<TrailIndexEntry> = scanAll(primary = root, extras = emptyList())

  private fun File.isSkippedDir(): Boolean = name.startsWith(".") || name in SKIPPED_DIRS

  /**
   * Directories with no children at all, labeled like [TrailIndexEntry.folder]
   * (`<rootLabel>/<relative-dir>`). The tree derives its folder rows from trail file paths, so a
   * freshly created (still empty) directory would be invisible without this — surfacing them is
   * what makes "create a folder, then add trails to it" workable in the UI. A directory holding
   * only non-trail files needs no entry here: it either contains trails eventually or isn't part
   * of the authoring flow.
   */
  fun scanEmptyDirs(primary: File, extras: List<File>): List<String> {
    val roots = listOf(primary to true) + extras.map { it to false }
    return roots.flatMap { (root, isPrimary) ->
      // Labeled here rather than inside the walk: the same directory is the primary root in one
      // workspace and an extra root in the next, and the cached walk is keyed only on its path.
      val label = labelFor(root, isPrimary)
      indexRoot(root).emptyDirs.map { "$label/$it" }
    }.sorted()
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
    indexRoot(root).trailFiles.forEach { out.add(build(root, it, rootIdx, rootLabel)) }
  }

  private fun labelFor(root: File, isPrimary: Boolean): String {
    val name = root.name.ifBlank { root.absolutePath }
    return if (isPrimary) name else "$name (${root.parent ?: ""})"
  }

  /**
   * One traversal's worth of a root: the trail files in it, its childless directories, and the
   * mtimes that say whether any of that is still true.
   */
  private class RootIndex(
    val trailFiles: List<File>,
    /** Root-relative, so the same walk can be labeled for whichever root slot it is serving. */
    val emptyDirs: List<String>,
    /** Every directory whose children were listed, plus the childless ones we stopped at. */
    val dirMtimes: Map<String, Long>,
  ) {
    /**
     * This index describes which files exist, not what is in them, and that set only changes when
     * some directory gains, loses or renames a child — which moves that directory's mtime. So
     * re-stat'ing the directories we visited is enough. A deleted directory reports mtime 0, which
     * reads as a mismatch. An in-place content edit needs no invalidation here: every scan rebuilds
     * its entries through [configCache], which is keyed on the trail file's own mtime.
     */
    fun stillValid(): Boolean = dirMtimes.all { (path, mtime) -> File(path).lastModified() == mtime }
  }

  private val rootIndexCache = java.util.concurrent.ConcurrentHashMap<String, RootIndex>()

  /**
   * The root's trail files and empty directories, re-walked only when something on disk moved.
   *
   * The index is rebuilt from scratch on every request, and the request is polled — so on a
   * workspace whose trails root is a repo root, the walk WAS the cost of the app: ~3,100
   * directories and ~8,200 files per pass, twice per request (trails and empty dirs were separate
   * traversals), about 550ms, several times a second. Everything else the daemon owed the UI —
   * opening a trail, saving one, answering a completion — queued behind it.
   *
   * Validating the previous walk instead costs one stat per known directory: ~5ms against ~308ms of
   * walking, and the walk only comes back when the answer would actually differ.
   */
  private fun indexRoot(root: File): RootIndex {
    val key = root.absolutePath
    rootIndexCache[key]?.let { if (it.stillValid()) return it }
    val trailFiles = mutableListOf<File>()
    val emptyDirs = mutableListOf<String>()
    val dirMtimes = mutableMapOf<String, Long>()
    // Read BEFORE the listing it vouches for, here and in [walk]: a child added between the two
    // would otherwise be recorded against the mtime it just moved, and the listing that missed it
    // would then validate forever. Sampling first can only make us re-walk a directory we already
    // read correctly.
    val rootMtime = root.lastModified()
    val rootEntries = root.listFiles()
    // A root we couldn't list — it doesn't exist yet, or isn't readable — is answered but never
    // cached. Its walk records no directories, and a validity check with nothing to check is
    // vacuously true, so caching it would pin an empty index for the life of the daemon: a trails
    // root that appears later (a workspace folder cloned after startup, a `trails/` directory
    // someone adds) would never be picked up. Re-answering from scratch costs one failed listing.
    if (rootEntries == null) return RootIndex(emptyList(), emptyList(), emptyMap())
    dirMtimes[key] = rootMtime
    val complete = walk(root, rootEntries, 0, trailFiles, emptyDirs, dirMtimes)
    val fresh = RootIndex(trailFiles, emptyDirs, dirMtimes)
    // Same rule as an unlistable root, one level down: a directory we couldn't read makes this index
    // describe less than the tree holds, and becoming readable moves no mtime, so nothing here would
    // ever invalidate it. Answer from this walk, then throw it away.
    if (complete) rootIndexCache[key] = fresh
    return fresh
  }

  /** False when any directory in this subtree couldn't be listed, which makes the walk uncacheable. */
  private fun walk(
    root: File,
    entries: Array<File>,
    depth: Int,
    trailFiles: MutableList<File>,
    emptyDirs: MutableList<String>,
    dirMtimes: MutableMap<String, Long>,
  ): Boolean {
    if (depth > MAX_DEPTH) return true
    var complete = true
    for (entry in entries) {
      if (entry.isDirectory) {
        if (entry.isSkippedDir()) continue
        // Recorded even for a childless directory: a trail created inside it moves ITS mtime and
        // nothing above it, so without this the first trail in a new folder would stay invisible.
        val mtime = entry.lastModified()
        val children = entry.listFiles()
        dirMtimes[entry.absolutePath] = mtime
        if (children == null) {
          // Unreadable, which is not the same as childless: reporting it as an empty folder would
          // invent a row in the tree for a directory whose contents we don't know.
          complete = false
        } else if (children.isEmpty()) {
          emptyDirs.add(entry.relativeTo(root).invariantSeparatorsPath)
        } else {
          complete = walk(root, children, depth + 1, trailFiles, emptyDirs, dirMtimes) && complete
        }
      } else if (
        entry.isFile &&
        (entry.name.endsWith(TRAIL_SUFFIX) || entry.name == BLAZE_FILENAME || entry.name == UNIFIED_FILENAME)
      ) {
        trailFiles.add(entry)
      }
    }
    return complete
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
      hasRecordedSteps = cfg?.hasRecordedSteps ?: false,
    )
  }

  /**
   * Backfills a platform for a trail that doesn't declare `config.platform`. Recorded variants are
   * conventionally named after the device classifier they were recorded on (`android-phone`,
   * `ios-iphone`, or a hardware classifier a downstream build registers a lineage override for), so
   * resolving the filename stem through [TrailblazeClassifierLineage] and looking for a platform
   * ancestor recovers the platform without hardcoding any classifier names here. Unknown stems
   * resolve to no known platform and stay platform-agnostic, as before. (Every chain now ends at
   * the universal root `all`, so the platform is the first platform-rooted ancestor on the chain,
   * not the chain's last entry.)
   */
  private val PLATFORM_ROOTS = setOf("android", "ios", "web")

  internal fun platformFromFileName(fileName: String): String? {
    if (!fileName.endsWith(TRAIL_SUFFIX)) return null
    val stem = fileName.removeSuffix(TRAIL_SUFFIX).lowercase()
    if (stem.isBlank()) return null
    return TrailblazeClassifierLineage.chainFor(TrailblazeDeviceClassifier(stem))
      .firstOrNull { it.classifier in PLATFORM_ROOTS }?.classifier
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
    /** Whether the file carries any recorded (deterministically replayable) steps. */
    val hasRecordedSteps: Boolean,
  )

  // A null payload is a CACHED FAILURE, not a cache miss. A trail that doesn't parse is re-read and
  // re-decoded on every scan otherwise, and a YAML parse that ends in an exception costs far more
  // than one that succeeds — so a handful of malformed files in a workspace dominated the scan and
  // re-logged the same complaint every few seconds (measured: 14 files, 1330 identical log lines
  // across 95 scans). A malformed file is as immutable as a valid one until its mtime moves.
  private val configCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, CachedConfig?>>()

  private fun parseConfig(file: File): CachedConfig? {
    val key = file.absolutePath
    val mtime = file.lastModified()
    configCache[key]?.let { (cachedMtime, cached) -> if (cachedMtime == mtime) return cached }
    return try {
      val yaml = file.readText()
      val tb = createTrailblazeYaml()
      // `decodeTrailDocument` also runs inside `extractTrailConfig` below, so this decodes twice. Kept
      // separate deliberately: it avoids duplicating extractTrailConfig's config-lowering here, and
      // this whole result is memoized per file mtime (`configCache`), so the second parse is paid
      // once per changed file at scan time, not per request.
      val format = when (tb.decodeTrailDocument(yaml)) {
        is xyz.block.trailblaze.yaml.unified.TrailDocument.Unified -> "unified"
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
        hasRecordedSteps = tb.hasRecordedSteps(yaml),
      )
      configCache[key] = mtime to result
      result
    } catch (e: Exception) {
      // Logged here rather than on every scan: the cache write below means this line is emitted once
      // per file per edit, which is what makes it readable as "this trail is broken".
      Console.log("[TrailIndexBuilder] config parse failed for ${file.absolutePath}: ${e.message}")
      configCache[key] = mtime to null
      null
    }
  }
}
