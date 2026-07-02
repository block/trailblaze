package xyz.block.trailblaze.trailrunner

import xyz.block.trailblaze.devices.TrailblazeClassifierLineage
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import java.io.File

object TrailIndexBuilder {

  private const val MAX_DEPTH = 12

  private val TRAIL_SUFFIX = ".trail.yaml"

  // A prompt-only trail (no platform variants / recordings) that lives next to
  // its recorded siblings — same document format, surfaced as kind "blaze".
  private const val BLAZE_FILENAME = "blaze.yaml"

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
      } else if (entry.isFile && (entry.name.endsWith(TRAIL_SUFFIX) || entry.name == BLAZE_FILENAME)) {
        out.add(entry)
      }
    }
  }

  private fun build(root: File, file: File, rootIdx: Int, rootLabel: String): TrailIndexEntry {
    val relative = file.relativeTo(root).invariantSeparatorsPath
    val folder = relative.substringBeforeLast('/', "")
    val isBlaze = file.name == BLAZE_FILENAME
    val derivedId = if (isBlaze) relative.removeSuffix(".yaml") else relative.removeSuffix(TRAIL_SUFFIX)
    val derivedTitle = if (isBlaze) "blaze" else file.name.removeSuffix(TRAIL_SUFFIX).replace('-', ' ').replace('_', ' ')

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
  )

  private val configCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, CachedConfig>>()

  private fun parseConfig(file: File): CachedConfig? {
    val key = file.absolutePath
    val mtime = file.lastModified()
    configCache[key]?.let { (cachedMtime, cached) -> if (cachedMtime == mtime) return cached }
    return try {
      val yaml = file.readText()
      val config = createTrailblazeYaml().extractTrailConfig(yaml)
      val result = CachedConfig(
        title = config?.title,
        target = config?.target,
        platform = config?.platform,
        driver = config?.driver,
        priority = config?.priority,
        tags = config?.tags ?: emptyList(),
      )
      configCache[key] = mtime to result
      result
    } catch (e: Exception) {
      Console.log("[TrailIndexBuilder] config parse failed for ${file.name}: ${e.message}")
      null
    }
  }
}
