package xyz.block.trailblaze.recordings

import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.util.Console

/**
 * A readable `class::method` identity derived from a trail file path for non-JUnit
 * (CLI / daemon) sessions. See [TrailRecordings.deriveTestIdentityFromTrailPath].
 */
data class DerivedTestIdentity(
  val className: String,
  val methodName: String,
)

object TrailRecordings {

  /**
   * Standard filename for the NL-only test definition file (the source of truth for natural
   * language test steps, without platform-specific recordings).
   *
   * Trail directory structure (with subdirectories like "generated"):
   * ```
   * trails/
   * └── generated/                                    <- Subdirectory (configurable)
   *     └── regression/suite_123/section_456/case_789/  <- Trail ID (directory = trail identity)
   *         ├── blaze.yaml (or trailblaze.yaml)         <- Source of truth: NL steps (no recordings)
   *         ├── ios-iphone.trail.yaml                 <- Recording for iOS iPhone
   *         ├── ios-ipad.trail.yaml                   <- Recording for iOS iPad
   *         └── android.trail.yaml                    <- Recording for Android
   * ```
   *
   * Trail directory structure (without subdirectories - open source default):
   * ```
   * trails/
   * └── regression/suite_123/section_456/case_789/      <- Trail ID (directory = trail identity)
   *     ├── blaze.yaml (or trailblaze.yaml)
   *     ├── ios-iphone.trail.yaml
   *     └── android.trail.yaml
   * ```
   */
  const val TRAILBLAZE_DOT_YAML = "trailblaze.yaml"
  const val BLAZE_DOT_YAML = "blaze.yaml"

  /**
   * Filename for the unified-per-test trail YAML file. The unified format
   * collapses the per-platform `<classifier>.trail.yaml` siblings plus the
   * `blaze.yaml` NL definition into a single document keyed by device
   * classifier.
   *
   * Resolution priority within a trail directory:
   *   1. Most-specific classifier match (`ios-iphone.trail.yaml`, then
   *      `ios.trail.yaml`).
   *   2. [UNIFIED_TRAIL_FILENAME] — the unified file, classifier-agnostic at
   *      the filename level (lowering happens at parse time).
   *   3. NL definition fallback ([BLAZE_DOT_YAML] / [TRAILBLAZE_DOT_YAML]).
   *
   * The unified file sits between the platform-specific legacy recordings
   * and the legacy NL definitions so directories that still contain
   * per-platform files keep running unchanged — bulk-migration deletes
   * those legacy files when the unified format is intended to win.
   */
  const val UNIFIED_TRAIL_FILENAME = "trail.yaml"
  /**
   * The default filename used when creating new NL definition files.
   * Change this single constant to switch the default across all write paths.
   */
  const val DEFAULT_NL_DEFINITION_FILENAME = BLAZE_DOT_YAML

  /**
   * List of NL-definition filenames, in priority order. Used for resolution.
   */
  val NL_DEFINITION_FILENAMES = listOf(DEFAULT_NL_DEFINITION_FILENAME) +
    listOf(TRAILBLAZE_DOT_YAML, BLAZE_DOT_YAML).filter { it != DEFAULT_NL_DEFINITION_FILENAME }

  const val DOT_TRAIL_DOT_YAML_FILE_SUFFIX = ".trail.yaml"

  // ---------------------------------------------------------------------------
  // Utility methods — use these instead of inline .endsWith() / == checks
  // ---------------------------------------------------------------------------

  /**
   * Shortens a trail file path to a stable, human-readable name relative to the
   * conventional `trails/` root — for user-facing surfaces (the Sessions list, the
   * HTML/JSON reports).
   *
   * Strips everything up to and including the last `trails/` directory segment, then
   * drops the [DOT_TRAIL_DOT_YAML_FILE_SUFFIX]. A path with no `trails/` segment is
   * returned with only the suffix removed (best-effort — rare for authored trails).
   *
   * The unified per-test file ([UNIFIED_TRAIL_FILENAME]) takes its identity from its
   * enclosing directory, so its filename segment is dropped rather than a `.trail.yaml`
   * suffix — otherwise it would render as `.../trail.yaml` in reports and the Sessions list.
   *
   * ```
   * "/Volumes/ci/…/trails/ExperimentalIosTests/set_feature_flag.trail.yaml"
   *     -> "ExperimentalIosTests/set_feature_flag"
   * "trails/EvaluationLongTest/tenKey.trail.yaml" -> "EvaluationLongTest/tenKey"
   * "trails/clock/open-and-verify-clock-tab/trail.yaml" -> "clock/open-and-verify-clock-tab"
   * ```
   *
   * Why this exists: callers used to strip only a *literal leading* `trails/` prefix
   * (`path.removePrefix("trails/")`). But a trail run through the CLI records
   * `file.absolutePath` in its session log, which does not start with `trails/`, so the
   * prefix strip was a no-op and the Sessions list showed the entire absolute path
   * (e.g. `/Volumes/InstanceStore/.ci-storage/…/set_feature_flag`). Matching the last
   * `trails/` segment anywhere in the path fixes both the relative and absolute forms.
   *
   * Backslashes are normalized to `/` first so a Windows `file.absolutePath`
   * (`C:\repo\trails\Suite\case.trail.yaml`) shortens the same way — matching the
   * `.replace('\\', '/')` convention the rest of the codebase uses for string-based
   * path handling.
   */
  fun shortTrailName(trailFilePath: String): String {
    val relative = trailFilePath
      .replace('\\', '/')
      .substringAfterLast("/trails/")
      .removePrefix("trails/")
    return when {
      // Unified file: identity is the enclosing directory. Drop the `/trail.yaml` segment.
      relative.endsWith("/$UNIFIED_TRAIL_FILENAME") -> relative.removeSuffix("/$UNIFIED_TRAIL_FILENAME")
      // A bare `trail.yaml` with no enclosing dir has no better name; leave it as-is.
      relative == UNIFIED_TRAIL_FILENAME -> relative
      else -> relative.removeSuffix(DOT_TRAIL_DOT_YAML_FILE_SUFFIX)
    }
  }

  /**
   * Derives a readable `Suite::test` identity from a trail file path, for sessions that don't
   * run under a JUnit harness (CLI / daemon runs). Such sessions have no real test class, so
   * the host runners used to stamp an implementation base-class name
   * (`BaseHostTrailblazeTest::run`, `HostAccessibilityV3::run`) that leaks internals and tells
   * the reader nothing.
   *
   * Splits the `trails/`-relative [shortTrailName] into its last two path segments and uses them
   * verbatim: the immediate parent directory becomes the "suite" class, and the trail file's
   * base name becomes the "test method". The segments are already authored to be readable
   * (`ExperimentalIosTests`, `set_feature_flag`), so they're kept as-is — passing them through a
   * case normalizer would collapse intentional camelCase (e.g. `ExperimentalIosTests` →
   * `Experimentaliostests`).
   *
   * ```
   * ".../trails/ExperimentalIosTests/set_feature_flag.trail.yaml"
   *     -> DerivedTestIdentity("ExperimentalIosTests", "set_feature_flag")
   * ```
   *
   * Falls back to [fallbackClassName] when there is no parent segment (a trail directly under
   * `trails/`), and to `"run"` when the path yields no usable method segment.
   */
  fun deriveTestIdentityFromTrailPath(
    trailFilePath: String,
    fallbackClassName: String,
  ): DerivedTestIdentity {
    val segments = shortTrailName(trailFilePath)
      .split('/')
      .filter { it.isNotBlank() }
    val method = segments.lastOrNull()?.takeIf { it.isNotBlank() }
    val suite = segments.getOrNull(segments.size - 2)?.takeIf { it.isNotBlank() }
    return DerivedTestIdentity(
      className = suite ?: fallbackClassName,
      methodName = method ?: "run",
    )
  }

  /**
   * Returns `true` if [fileName] is a natural-language definition file
   * (`blaze.yaml` or `trailblaze.yaml`).
   */
  fun isNlDefinitionFile(fileName: String): Boolean = fileName in NL_DEFINITION_FILENAMES

  /**
   * Returns `true` if [fileName] is any Trailblaze YAML file — either an NL definition
   * (`blaze.yaml` / `trailblaze.yaml`), a unified file (`trail.yaml`), or a
   * platform-specific legacy recording (`*.trail.yaml`).
   */
  fun isTrailFile(fileName: String): Boolean =
    isNlDefinitionFile(fileName) ||
      isUnifiedTrailFile(fileName) ||
      fileName.endsWith(DOT_TRAIL_DOT_YAML_FILE_SUFFIX)

  /** Returns `true` if [fileName] is the unified per-test trail file. */
  fun isUnifiedTrailFile(fileName: String): Boolean = fileName == UNIFIED_TRAIL_FILENAME

  /**
   * Content-level (not filename) detection of a unified-format trail: `true` if [yaml] has a
   * root-level `trail:` key. The canonical unified layout uses NAMED files (`<case>.trail.yaml`)
   * as well as the bare [UNIFIED_TRAIL_FILENAME], so a named unified file is indistinguishable from
   * a legacy per-device recording by filename alone — the content is the only reliable
   * discriminator.
   *
   * A v1 trail is a YAML *list* (`- config:` … at the root) and never carries a column-0 `trail:`
   * key; the unified format is a mapping whose required `trail:` key sits at column 0. This mirrors
   * the discriminator the coverage scripts use (`generate_testcase_coverage_summary_doc.sh` greps
   * `^trail:`; `scripts/dashboard-coverage/merge.py` uses `line.startswith("trail:")`) so CI
   * selection, the coverage reports, and the runtime agree on what "unified" means.
   *
   * Cheap by design — a line scan, no YAML parse — so a caller can classify a large corpus without
   * paying a full decode for the (currently dominant) legacy files.
   */
  fun isUnifiedTrailContent(yaml: String): Boolean =
    // The required root key is `trail:` at column 0 (`trailhead:` starts with "trail" but not
    // "trail:", so it never matches).
    yaml.lineSequence().any { it.startsWith("trail:") }

  /**
   * Returns `true` if [fileName] is a platform-specific legacy recording file —
   * ends with `.trail.yaml`, is not the NL definition, and is not the unified
   * file (`trail.yaml` happens to end with `.trail.yaml`).
   */
  fun isRecordingFile(fileName: String): Boolean =
    fileName.endsWith(DOT_TRAIL_DOT_YAML_FILE_SUFFIX) &&
      !isNlDefinitionFile(fileName) &&
      !isUnifiedTrailFile(fileName)

  /**
   * Based on the classifiers, determine possible names, in order of most specific to least specific
   */
  fun computePossibleFileNamesForDeviceClassifiers(
    deviceClassifiers: List<TrailblazeDeviceClassifier>,
  ): List<String> {
    // Build list of candidate filenames in priority order
    // e.g., for ["pixel", "android", "5g"]:
    //   ["pixel-android-5g.trail.yaml", "pixel-android.trail.yaml", "pixel.trail.yaml",
    //    "blaze.yaml", "trailblaze.yaml"]
    return buildList {
      // Add device-specific combinations from most to least specific
      for (count in deviceClassifiers.size downTo 1) {
        add(
          deviceClassifiers.take(count).joinToString("-") { it.classifier } +
            DOT_TRAIL_DOT_YAML_FILE_SUFFIX
        )
      }
      // Unified-format trail file — chosen after the per-classifier legacy
      // recordings (so back-compat dirs that still have those keep working)
      // but BEFORE the NL-definition fallback (so a unified-migrated test
      // wins over an obsolete blaze.yaml that wasn't deleted alongside the
      // recordings).
      add(UNIFIED_TRAIL_FILENAME)
      // Add default fallback
      addAll(NL_DEFINITION_FILENAMES)
    }
  }

  fun findBestTrailForClassifiers(
    trailPaths: List<String>,
    deviceClassifiers: List<TrailblazeDeviceClassifier>,
  ): String? {
    // Build list of candidate filenames in priority order
    val candidateFileNames = computePossibleFileNamesForDeviceClassifiers(deviceClassifiers)

    // Find the first trail file that matches any candidate
    val matchedFile =
      candidateFileNames.firstNotNullOfOrNull { candidateFileName ->
        trailPaths.find { trail -> trail.endsWith(candidateFileName) }
      }

    Console.log("No matching trail file found matching ($candidateFileNames) for classifiers $deviceClassifiers in $trailPaths")

    return matchedFile
  }

  /**
   * Finds the best trail resource path based on the given path and device classifiers.
   *
   * @param path The input path - can be either a specific trail/blaze YAML file or a directory
   *   containing trail files
   * @param deviceClassifiers List of device classifiers to determine priority of trail files
   * @param doesResourceExist Function to check if a resource exists at the given path
   * @return The best matching trail resource path, or null if none found
   */
  fun findBestTrailResourcePath(
    path: String,
    deviceClassifiers: List<TrailblazeDeviceClassifier>,
    doesResourceExist: (String) -> Boolean,
  ): String? =
    if (isTrailFile(path.substringAfterLast("/"))) {
      // Path is already a specific trail/blaze file
      path
    } else {
      // Path is a directory - find the best matching trail file
      val possibleFileNames = computePossibleFileNamesForDeviceClassifiers(deviceClassifiers)
      val possibleResourcePaths =
        possibleFileNames.map { possibleFileName -> "$path/$possibleFileName" }
      possibleResourcePaths.firstOrNull { resourcePath -> doesResourceExist(resourcePath) }
    }
}
