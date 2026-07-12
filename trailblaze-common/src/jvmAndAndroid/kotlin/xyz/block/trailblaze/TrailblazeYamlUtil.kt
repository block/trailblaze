package xyz.block.trailblaze

import xyz.block.trailblaze.recordings.TrailRecordings
import xyz.block.trailblaze.rules.TestStackTraceUtil

object TrailblazeYamlUtil {

  fun TestStackTraceUtil.TestMethodInfo.calculateTrailblazeYamlAssetPathFromStackTrace() = "trails/$simpleClassName/$methodName.trail.yaml"

  fun TestStackTraceUtil.TestMethodInfo.calculateTrailblazeYamlAssetWithPackagePathFromStackTrace() = "trails/$packageName/$simpleClassName/$methodName.trail.yaml"

/** The path reflects the nested structure of the test's package and class hierarchy.
   * Example: For a test method `testFoo` in class `com.example.tests.MyTest`, the generated path will be:
   * trails/com/example/tests/MyTest/testFoo.trail.yaml
   * This helps Trailblaze locate test-specific YAML assets based on their source location.
   **/
  fun TestStackTraceUtil.TestMethodInfo.calculateTrailblazeYamlAssetPathWithNestedStructure(): String {
    val packageAsPath = packageName.replace(".", "/")
    return "trails/$packageAsPath/$simpleClassName/$methodName.trail.yaml"
  }

  fun calculateTrailblazeYamlAssetPathFromStackTrace(doesFileExistAtPath: (String) -> Boolean): String {
    val testMethod = TestStackTraceUtil.getJUnit4TestMethodFromCurrentStacktrace()
    return calculateTrailblazeYamlAssetPath(
      namedFilePaths = listOf(
        testMethod.calculateTrailblazeYamlAssetWithPackagePathFromStackTrace(),
        testMethod.calculateTrailblazeYamlAssetPathFromStackTrace(),
        testMethod.calculateTrailblazeYamlAssetPathWithNestedStructure(),
      ),
      doesFileExistAtPath = doesFileExistAtPath,
    )
  }

  /**
   * The one probing order for resolving a test method's trail asset: every named-file candidate
   * as-is first, then each candidate's directory-per-test unified variant (the candidate with its
   * `.trail.yaml` suffix stripped — the default new-recording layout, where the test's identity is
   * the enclosing directory name). Named files always win over recording directories.
   *
   * The resolvers return non-null on a hit; what they return is caller-defined —
   * [calculateTrailblazeYamlAssetPath] returns the probed path itself (the DIRECTORY path on a
   * recording-dir hit, so `TrailRecordings.findBestTrailResourcePath` can pick the best file
   * within), while the on-device per-trail driver peek (an internal caller) returns the best
   * playable file INSIDE the directory. Both share this function so the candidate order can't
   * drift between the runners and the peek.
   */
  fun <T : Any> resolveTrailAsset(
    namedFilePaths: List<String>,
    resolveNamedFile: (String) -> T?,
    resolveRecordingDir: (String) -> T?,
  ): T? {
    namedFilePaths.forEach { assetPath -> resolveNamedFile(assetPath)?.let { return it } }
    namedFilePaths.forEach { namedPath ->
      resolveRecordingDir(namedPath.removeSuffix(".trail.yaml"))?.let { return it }
    }
    return null
  }

  /**
   * Resolves the trail asset path for a test method via [resolveTrailAsset]: a named-file hit
   * returns that path; a directory-per-test hit (`…/<Class>/<method>/trail.yaml`) returns the
   * DIRECTORY path, not the `trail.yaml` inside it.
   *
   * Pulled out of [calculateTrailblazeYamlAssetPathFromStackTrace] so the probing order is
   * unit-testable without a JUnit stack frame.
   */
  internal fun calculateTrailblazeYamlAssetPath(
    namedFilePaths: List<String>,
    doesFileExistAtPath: (String) -> Boolean,
  ): String = resolveTrailAsset(
    namedFilePaths = namedFilePaths,
    resolveNamedFile = { assetPath -> assetPath.takeIf(doesFileExistAtPath) },
    resolveRecordingDir = { dirPath ->
      dirPath.takeIf { doesFileExistAtPath("$it/${TrailRecordings.UNIFIED_TRAIL_FILENAME}") }
    },
  ) ?: error(
    "Could not locate asset at any of the following paths: " +
      (
        namedFilePaths +
          namedFilePaths.map {
            "${it.removeSuffix(".trail.yaml")}/${TrailRecordings.UNIFIED_TRAIL_FILENAME}"
          }
        ),
  )

  fun calculateTrailblazeYamlAssetWithPackagePathFromStackTrace() = TestStackTraceUtil.getJUnit4TestMethodFromCurrentStacktrace()
    .calculateTrailblazeYamlAssetWithPackagePathFromStackTrace()
}
