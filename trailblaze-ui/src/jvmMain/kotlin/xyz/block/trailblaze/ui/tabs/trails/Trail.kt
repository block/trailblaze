package xyz.block.trailblaze.ui.tabs.trails

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.recordings.TrailRecordings
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.yaml.TrailComparator
import xyz.block.trailblaze.yaml.TrailConfig
import xyz.block.trailblaze.yaml.TrailSource
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import xyz.block.trailblaze.yaml.unified.UnifiedTrailTargets
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Represents a trail (test case) identified by its directory path.
 * A trail can have multiple variants (e.g., trailblaze.yaml, android.trail.yaml, ios-ipad.trail.yaml).
 *
 * @param id The trail identifier - relative path from trails root (e.g., "clock/set-alarm")
 * @param absolutePath The absolute path to the trail directory
 * @param variants List of trail file variants in this directory
 */
data class Trail(
  val id: String,
  val absolutePath: String,
  val variants: List<TrailVariant>,
) {
  /**
   * Returns the default variant (trailblaze.yaml) if it exists.
   */
  val defaultVariant: TrailVariant?
    get() = variants.find { it.isDefault }

  /**
   * Returns non-default variants (platform-specific files).
   */
  val platformVariants: List<TrailVariant>
    get() = variants.filter { !it.isDefault }

  /**
   * Returns the display name for this trail (last part of the ID).
   */
  val displayName: String
    get() = id.substringAfterLast("/").ifEmpty { id }

  /**
   * Returns the parent path of this trail (everything before the last /).
   */
  val parentPath: String?
    get() = id.substringBeforeLast("/", "").ifEmpty { null }

  /**
   * Returns the title from the default variant's config, or from any variant if default has none.
   */
  val title: String?
    get() = defaultVariant?.config?.title ?: variants.firstNotNullOfOrNull { it.config?.title }

  /**
   * Returns the description from the default variant's config, or from any variant if default has none.
   */
  val description: String?
    get() = defaultVariant?.config?.description ?: variants.firstNotNullOfOrNull { it.config?.description }

  /**
   * Returns the priority from the default variant's config, or from any variant if default has none.
   */
  val priority: String?
    get() = defaultVariant?.config?.priority ?: variants.firstNotNullOfOrNull { it.config?.priority }

  /**
   * Returns the source from the default variant's config, or from any variant if default has none.
   */
  val source: TrailSource?
    get() = defaultVariant?.config?.source ?: variants.firstNotNullOfOrNull { it.config?.source }

  /**
   * Returns merged metadata from all variants (default variant takes precedence).
   */
  val metadata: Map<String, String>
    get() {
      val result = mutableMapOf<String, String>()
      // Add metadata from all variants (later ones won't override earlier)
      variants.forEach { variant ->
        variant.config?.metadata?.forEach { (key, value) ->
          result.putIfAbsent(key, value)
        }
      }
      return result
    }

  /**
   * Returns the set of platforms this trail has variants for. For legacy per-device variants this
   * is the filename-derived platform; for a unified variant it is every platform its recordings +
   * `config.devices` declare (see [TrailVariant.platforms]).
   */
  val platforms: Set<TrailblazeDevicePlatform>
    get() = variants.flatMap { it.platforms }.toSet()

  /**
   * True when any variant is a unified single-file trail. Unified trails don't encode their target
   * in the filename, so target/platform display derives from the file content instead.
   */
  val isUnified: Boolean
    get() = variants.any { it.isUnified }

  /**
   * The device classifiers a unified trail covers — the union across variants of every
   * `config.devices` key and every step/trailhead recording classifier, sorted for display. Empty
   * for a purely-legacy trail (its coverage is the per-file [platforms] instead).
   *
   * In practice a unified trail is a single `trail.yaml`, so at most one variant is unified and the
   * cross-variant union collapses to that one file; the union keeps this correct for the (currently
   * unauthored) case of a directory pairing a unified variant with legacy siblings.
   */
  val unifiedTargets: List<String>
    get() = variants.flatMap { it.unifiedClassifiers }.distinct().sorted()

  /**
   * The unified `config.devices` classifier→driver run matrix, merged across variants. Empty for a
   * legacy trail or a unified trail that pins no drivers.
   */
  val unifiedDevices: Map<String, String>
    get() = variants.fold(mutableMapOf<String, String>()) { acc, variant ->
      variant.unifiedDevices.forEach { (device, driver) -> acc.putIfAbsent(device, driver) }
      acc
    }

  /**
   * Returns true if the natural language steps differ between any of the trail variants.
   * This indicates a conflict that may need to be resolved.
   *
   * Note: Only compares non-default variants (platform-specific files) since those
   * are the recorded trails that should stay in sync.
   */
  val hasStepConflict: Boolean
    get() {
      val variantsToCompare = variants.filter { !it.isDefault }
      if (variantsToCompare.size < 2) return false

      val stepLists = variantsToCompare.mapNotNull { variant ->
        TrailConfigCache.getNaturalLanguageSteps(variant.absolutePath)
      }

      // Only compare if we successfully read steps from at least 2 variants
      if (stepLists.size < 2) return false

      return TrailComparator.Default.hasConflictingSteps(stepLists)
    }
}

/**
 * Represents a specific trail file variant within a trail directory.
 * 
 * Trail files follow the naming convention: {classifier1}-{classifier2}-....trail.yaml
 * where the first classifier is typically the platform (android, ios, web)
 * and subsequent classifiers describe the device (phone, tablet, iphone, ipad).
 *
 * @param fileName The file name (e.g., "trailblaze.yaml", "android-phone.trail.yaml")
 * @param absolutePath The absolute path to this file
 * @param classifiers List of device classifiers parsed from filename (e.g., [android, phone])
 */
data class TrailVariant(
  val fileName: String,
  val absolutePath: String,
  val classifiers: List<TrailblazeDeviceClassifier>,
) {
  /**
   * The parsed TrailConfig from the YAML file.
   * Loaded lazily via TrailConfigCache to optimize performance with large numbers of files.
   * The cache handles modification time tracking and automatic invalidation.
   */
  val config: TrailConfig?
    get() = TrailConfigCache.getConfig(absolutePath)

  /**
   * Whether this is the default NL definition file (trailblaze.yaml).
   */
  val isDefault: Boolean
    get() = TrailRecordings.isNlDefinitionFile(fileName)

  /**
   * Whether this is a unified single-file trail (one `trail.yaml` covering every device via
   * classifier-keyed recordings) rather than a legacy per-device recording. Content-detected via
   * the cache, so a unified trail authored under a non-`trail.yaml` name is still recognized.
   */
  val isUnified: Boolean
    get() = TrailConfigCache.isUnified(absolutePath)

  /**
   * For a unified variant, the device classifiers it covers — the union of every `config.devices`
   * key and every step/trailhead recording classifier (e.g. `android-phone`, `android-tablet`,
   * `ios`). Empty for a legacy variant, whose target is its filename instead.
   */
  val unifiedClassifiers: Set<String>
    get() = TrailConfigCache.getUnifiedClassifiers(absolutePath)

  /**
   * For a unified variant, the `config.devices` classifier→driver run matrix. Empty for a legacy
   * variant or a unified trail that pins no drivers.
   */
  val unifiedDevices: Map<String, String>
    get() = TrailConfigCache.getUnifiedDevices(absolutePath)

  /**
   * The platform classifier (first classifier), if any.
   */
  val platformClassifier: TrailblazeDeviceClassifier?
    get() = classifiers.firstOrNull()

  /**
   * The platform classifier (first classifier), if any. Filename-derived — meaningful only for
   * legacy per-device variants (a unified variant's platforms come from [platforms]).
   */
  val platform: TrailblazeDevicePlatform?
    get() = classifiers.firstOrNull()?.let { classifier ->
      TrailblazeDevicePlatform.entries.find {
        it.name.equals(classifier.classifier, ignoreCase = true)
      }
    }

  /**
   * The platforms this single variant covers. A unified variant folds its declared classifiers up
   * to their platform prefix (`android-tablet` → ANDROID, `ios` → IOS); a legacy variant is its one
   * filename-derived [platform].
   */
  val platforms: Set<TrailblazeDevicePlatform>
    get() = if (isUnified) {
      UnifiedTrailTargets.platformsOf(unifiedClassifiers)
    } else {
      setOfNotNull(platform)
    }

  /**
   * The device-classifier names this variant advertises for filtering — a unified variant's
   * declared classifiers, or a legacy variant's filename-derived classifier segments.
   */
  val classifierNames: List<String>
    get() = if (isUnified) unifiedClassifiers.toList() else classifiers.map { it.classifier }

  /**
   * Additional classifiers after the platform (e.g., phone, tablet, iphone, ipad).
   */
  val deviceClassifiers: List<TrailblazeDeviceClassifier>
    get() = classifiers.drop(1)

  /**
   * Display label for this variant.
   * Examples: "Default", "Unified", "Android", "Android Phone", "iOS", "iOS iPad"
   */
  val displayLabel: String
    get() = when {
      isDefault -> "Default"
      isUnified -> "Unified"
      classifiers.isEmpty() -> fileName.removeSuffix(".trail.yaml")
      else -> classifiers.joinToString(" ") { it.classifier.classifierDisplayName() }
    }

  companion object {
    /**
     * Creates a TrailVariant from a trail file.
     * Only parses the filename to extract classifiers - config is loaded lazily via cache.
     */
    fun fromFile(file: File): TrailVariant {
      val fileName = file.name

      // Parse classifiers from filename
      // Format: {classifier1}-{classifier2}-....trail.yaml or trailblaze.yaml
      val classifiers = if (TrailRecordings.isNlDefinitionFile(fileName)) {
        emptyList()
      } else {
        val nameWithoutExtension = fileName.removeSuffix(".trail.yaml")
        nameWithoutExtension.split("-").map { TrailblazeDeviceClassifier(it) }
      }

      return TrailVariant(
        fileName = fileName,
        absolutePath = file.absolutePath,
        classifiers = classifiers,
      )
    }
  }
}

/**
 * Converts a classifier string to display name.
 */
private fun String.classifierDisplayName(): String = when (lowercase()) {
  "android" -> "Android"
  "ios" -> "iOS"
  "web" -> "Web"
  "phone" -> "Phone"
  "tablet" -> "Tablet"
  "iphone" -> "iPhone"
  "ipad" -> "iPad"
  else -> replaceFirstChar { it.uppercase() }
}

/**
 * Cache for TrailConfig objects to avoid re-parsing YAML files on every access.
 * Uses file modification time to invalidate stale cache entries.
 * 
 * This enables efficient handling of thousands of trail files by:
 * 1. Only parsing files when their config is actually needed (lazy loading)
 * 2. Caching parsed configs to avoid redundant parsing
 * 3. Supporting background indexing for search functionality
 * 4. Automatically invalidating cache when files change
 * 5. Pruning stale entries for deleted files to prevent unbounded growth
 */
object TrailConfigCache {
  private val trailblazeYaml = createTrailblazeYaml()
  private val trailComparator = TrailComparator.Default

  /**
   * Maximum number of cache entries before triggering automatic pruning.
   * This prevents unbounded memory growth over long-running sessions.
   */
  private const val MAX_CACHE_SIZE = 10_000

  /**
   * Cached config entry with modification time for staleness checking.
   */
  private data class CacheEntry(
    val config: TrailConfig?,
    val naturalLanguageSteps: List<String>?,
    /** True when the file is a unified single-file trail (content-detected). */
    val isUnified: Boolean,
    /** Union of declared classifiers for a unified file; empty otherwise. */
    val unifiedClassifiers: Set<String>,
    /** The unified `config.devices` classifier→driver map; empty otherwise. */
    val unifiedDevices: Map<String, String>,
    val lastModified: Long,
  )

  // Thread-safe cache storage
  private val cache = ConcurrentHashMap<String, CacheEntry>()

  // Mutex for coordinating background indexing
  private val indexingMutex = Mutex()

  /**
   * Gets the config for a trail variant, using cache if available and fresh.
   * Returns null if the file doesn't exist or can't be parsed.
   */
  fun getConfig(absolutePath: String): TrailConfig? {
    return getCacheEntry(absolutePath)?.config
  }

  /**
   * Gets the natural language steps from a trail file, using cache if available and fresh.
   * Returns null if the file doesn't exist or can't be parsed.
   */
  fun getNaturalLanguageSteps(absolutePath: String): List<String>? {
    return getCacheEntry(absolutePath)?.naturalLanguageSteps
  }

  /** True when the file is a unified single-file trail (content-detected). */
  fun isUnified(absolutePath: String): Boolean = getCacheEntry(absolutePath)?.isUnified == true

  /**
   * The union of device classifiers a unified file declares (`config.devices` keys + every
   * step/trailhead recording key). Empty for a legacy file or one that couldn't be decoded.
   */
  fun getUnifiedClassifiers(absolutePath: String): Set<String> =
    getCacheEntry(absolutePath)?.unifiedClassifiers.orEmpty()

  /** The unified `config.devices` classifier→driver map. Empty for a legacy file. */
  fun getUnifiedDevices(absolutePath: String): Map<String, String> =
    getCacheEntry(absolutePath)?.unifiedDevices.orEmpty()

  /**
   * Gets or creates a cache entry for a file, parsing if necessary.
   */
  private fun getCacheEntry(absolutePath: String): CacheEntry? {
    val file = File(absolutePath)
    if (!file.exists()) return null

    val currentModTime = file.lastModified()
    val cached = cache[absolutePath]

    // Return cached value if still fresh
    if (cached != null && cached.lastModified == currentModTime) {
      return cached
    }

    // Parse and cache
    val entry = parseTrailFile(file)
    cache[absolutePath] = entry

    // Periodically check cache size and prune if needed
    pruneIfNeeded()

    return entry
  }

  /**
   * Parses a trail file to extract config, natural language steps, and — for a unified single-file
   * trail — the device coverage (declared classifiers + the `config.devices` driver map).
   *
   * `isUnified` is content-detected up front (a cheap line scan that never throws) so it is set even
   * when the fuller decode below fails, e.g. a trail using app-specific tools not on this classpath.
   */
  private fun parseTrailFile(file: File): CacheEntry {
    val modified = file.lastModified()
    val yamlContent = try {
      file.readText()
    } catch (e: Exception) {
      return CacheEntry(null, null, isUnified = false, emptySet(), emptyMap(), modified)
    }

    val isUnified = TrailRecordings.isUnifiedTrailContent(yamlContent)
    val config = try {
      trailblazeYaml.extractTrailConfig(yamlContent)
    } catch (e: Exception) {
      null
    }
    val steps = try {
      trailComparator.extractNaturalLanguageSteps(yamlContent, trailblazeYaml)
    } catch (e: Exception) {
      null
    }

    var classifiers: Set<String> = emptySet()
    var devices: Map<String, String> = emptyMap()
    if (isUnified) {
      try {
        val trail = trailblazeYaml.decodeUnifiedTrail(yamlContent)
        classifiers = UnifiedTrailTargets.declaredClassifiers(trail)
        devices = trail.config.devices.orEmpty()
      } catch (e: Throwable) {
        // Content is unified but wouldn't decode here — keep the unified flag, drop the targets.
        // Most often a trail using app-specific tools not on the desktop classpath. Log once per
        // file (mtime-cached), so a chip-less unified trail is diagnosable instead of silent.
        Console.log("[TrailConfigCache] unified trail ${file.name} declares targets but wouldn't decode: ${e.message}")
      }
    }

    return CacheEntry(config, steps, isUnified, classifiers, devices, modified)
  }

  /**
   * Pre-indexes configs for a list of trails in parallel on background threads.
   * This enables fast search/filter without blocking the UI.
   * 
   * @param trails The trails to index
   * @param onProgress Optional callback with (indexed, total) counts for progress UI
   */
  suspend fun indexTrailsInBackground(
    trails: List<Trail>,
    onProgress: ((indexed: Int, total: Int) -> Unit)? = null,
  ) = coroutineScope {
    indexingMutex.withLock {
      // Prune stale entries before indexing to prevent unbounded growth
      pruneStaleEntries()

      val allVariants = trails.flatMap { it.variants }
      val total = allVariants.size
      var indexed = 0

      // Process in batches to avoid overwhelming the system
      val batchSize = 50
      allVariants.chunked(batchSize).forEach { batch ->
        // Parse each file in the batch in parallel
        batch.map { variant ->
          async(Dispatchers.IO) {
            getConfig(variant.absolutePath)
          }
        }.awaitAll()

        indexed += batch.size
        onProgress?.invoke(indexed, total)
      }
    }
  }

  /**
   * Checks if a config is already cached and fresh for a given path.
   */
  fun isCached(absolutePath: String): Boolean {
    val file = File(absolutePath)
    if (!file.exists()) return false

    val cached = cache[absolutePath] ?: return false
    return cached.lastModified == file.lastModified()
  }

  /**
   * Invalidates a single cache entry for a specific file path.
   * Use this after editing/saving a trail file to force re-parsing on next access.
   */
  fun invalidate(absolutePath: String) {
    cache.remove(absolutePath)
  }
  
  /**
   * Clears the entire cache. Useful when switching trails directories.
   */
  fun clearCache() {
    cache.clear()
  }

  /**
   * Removes cache entries for files that no longer exist.
   * Call this periodically or when switching directories to prevent unbounded growth.
   * 
   * @return The number of stale entries that were removed
   */
  fun pruneStaleEntries(): Int {
    val staleKeys = cache.keys.filter { path -> !File(path).exists() }
    staleKeys.forEach { cache.remove(it) }
    return staleKeys.size
  }

  /**
   * Prunes the cache if it exceeds the maximum size by removing entries
   * for files that no longer exist. If still over limit after pruning,
   * removes oldest entries until under limit.
   */
  private fun pruneIfNeeded() {
    if (cache.size <= MAX_CACHE_SIZE) return

    // First, remove entries for deleted files
    pruneStaleEntries()

    // If still over limit, remove arbitrary entries to get back under limit
    // (ConcurrentHashMap doesn't maintain insertion order, so we just remove some)
    if (cache.size > MAX_CACHE_SIZE) {
      val toRemove = cache.size - MAX_CACHE_SIZE
      cache.keys.take(toRemove).forEach { cache.remove(it) }
    }
  }

  /**
   * Returns the current cache size (number of entries).
   */
  fun cacheSize(): Int = cache.size
}
