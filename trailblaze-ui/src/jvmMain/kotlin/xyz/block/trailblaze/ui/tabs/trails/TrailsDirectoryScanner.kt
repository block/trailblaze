package xyz.block.trailblaze.ui.tabs.trails

import java.io.File
import xyz.block.trailblaze.config.project.TrailDiscovery

/**
 * Utility for scanning a directory and finding trails.
 *
 * A trail is a directory containing one or more trail artifacts — `.trail.yaml`
 * recordings and/or the NL-only definition file (`blaze.yaml` or the legacy
 * `trailblaze.yaml` when it sits below the workspace root). The directory's relative
 * path from the scan root is the trail id; files in the same directory are collapsed
 * into a single [Trail] with one [TrailVariant] per file.
 *
 * The flat file-set this scanner groups into [Trail] objects comes from [TrailDiscovery] —
 * extension-based globbing with hardcoded excludes (`build/`, `.gradle/`, `.git/`,
 * `node_modules/`, `.trailblaze/`). The grouping logic (variants per directory) is
 * independent of the discovery layer.
 */
object TrailsDirectoryScanner {

  /**
   * Scans [trailsDir] and returns trails grouped by directory. The scan is recursive
   * and respects [TrailDiscovery]'s default excludes — `build/` and friends never leak
   * into results. [trailsDir] itself is the grouping anchor, so a trail file sitting
   * directly inside [trailsDir] becomes a trail whose id is the directory's own name
   * (via [File.getName]) and whose relative path is empty.
   *
   * Returns an empty list when [trailsDir] does not exist or is not a directory —
   * discovery is a read-only survey and never throws.
   *
   * **Precondition:** [trailsDir] must be a non-root directory — every discovered trail
   * must have a parent directory for grouping. Scanning the filesystem root (`/` on
   * Unix, a drive root on Windows) is unsupported and would throw
   * [IllegalArgumentException] from the grouping step; callers should resolve a real
   * workspace directory via `findWorkspaceRoot` or the CLI's working directory first.
   */
  fun scanForTrails(trailsDir: File): List<Trail> {
    if (!trailsDir.exists() || !trailsDir.isDirectory) return emptyList()
    val trailFiles = TrailDiscovery.discoverTrailFiles(trailsDir.toPath())
    if (trailFiles.isEmpty()) return emptyList()
    return groupByDirectory(trailFiles, trailsDir)
  }

  /**
   * Groups [trailFiles] by parent directory, producing one [Trail] per directory with
   * its variants sorted default-first, then by platform, then by first device
   * classifier. The pre-Phase-3 sort is preserved so UI ordering is stable across the
   * refactor.
   */
  private fun groupByDirectory(trailFiles: List<File>, rootFile: File): List<Trail> {
    // TrailDiscovery.discoverTrailFiles already guarantees every entry is a regular
    // trail-shaped file — no re-filtering needed here. `parentFile` is non-null by
    // construction: every discovered trail lives below `rootFile`, which itself has
    // a parent for any real workspace dir. A null `parentFile` would mean a
    // filesystem-root-level match, which is not a supported scan target.
    return trailFiles
      .groupBy { requireNotNull(it.parentFile) { "trail file $it has no parent directory" } }
      .map { (dir, files) ->
        val relativePath = dir.relativeTo(rootFile).path
        val trailId = relativePath.ifEmpty { dir.name }
        val variants = files
          .map { TrailVariant.fromFile(it) }
          .sortedWith(
            compareBy(
              { !it.isDefault },
              { it.platform?.name ?: "" },
              { it.deviceClassifiers.firstOrNull()?.classifier ?: "" },
            ),
          )
        Trail(
          id = trailId,
          absolutePath = dir.absolutePath,
          variants = variants,
        )
      }
      .sortedBy { it.id.lowercase() }
  }

  /**
   * Counts the total number of trails.
   */
  fun countTrails(trails: List<Trail>): Int = trails.size

  /**
   * Counts the total number of trail variants across all trails.
   */
  fun countVariants(trails: List<Trail>): Int = trails.sumOf { it.variants.size }

  /**
   * Finds a trail by its ID.
   */
  fun findTrailById(trails: List<Trail>, id: String): Trail? {
    return trails.find { it.id == id }
  }
}
