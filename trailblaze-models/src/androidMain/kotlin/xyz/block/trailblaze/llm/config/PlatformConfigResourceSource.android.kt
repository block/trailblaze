package xyz.block.trailblaze.llm.config

import xyz.block.trailblaze.util.Console

/**
 * On Android, prefer [AssetManagerConfigResourceSource] (instrumentation tests); fall back
 * to [ClasspathConfigResourceSource] when no instrumentation is registered (Robolectric /
 * plain JVM unit tests running under the `android` plugin's `testDebugUnitTest` task).
 *
 * The two sources look at different places: AssetManager enumerates Android assets; the
 * classpath source enumerates JAR entries. In a unit-test JVM, YAML resources land on the
 * regular classpath via `commonMain/resources` wiring, so the classpath fallback finds them.
 */
/**
 * On Android there is no workspace concept (no CWD walk-up to a `trails/config/` directory
 * at runtime on a device), so the "bundled" view IS the platform default. Delegating keeps
 * the two functions byte-identical on this platform — call sites pick the function name that
 * communicates intent, but the result is the same.
 */
actual fun bundledConfigResourceSource(): ConfigResourceSource = platformConfigResourceSource()

actual fun platformConfigResourceSource(): ConfigResourceSource =
  object : ConfigResourceSource {
    override fun discoverAndLoad(directoryPath: String, suffix: String): Map<String, String> {
      val fromAssets =
        try {
          AssetManagerConfigResourceSource.discoverAndLoad(directoryPath, suffix)
        } catch (_: IllegalStateException) {
          // InstrumentationRegistry not registered — non-instrumentation test context.
          null
        }
      val classpath = ClasspathConfigResourceSource.discoverAndLoad(directoryPath, suffix)
      // Merge: classpath first so instrumentation-asset keys can override.
      val merged = classpath + (fromAssets ?: emptyMap())
      Console.log(
        "[platformConfigResourceSource/android] dir=$directoryPath assets=" +
          "${fromAssets?.size ?: "unavailable"} classpath=${classpath.size} merged=${merged.size}",
      )
      return merged
    }

    override fun discoverAndLoadRecursive(directoryPath: String, suffix: String): Map<String, String> {
      val fromAssets =
        try {
          AssetManagerConfigResourceSource.discoverAndLoadRecursive(directoryPath, suffix)
        } catch (_: IllegalStateException) {
          null
        }
      val classpath = ClasspathConfigResourceSource.discoverAndLoadRecursive(directoryPath, suffix)
      val merged = classpath + (fromAssets ?: emptyMap())
      Console.log(
        "[platformConfigResourceSource/android] recursive dir=$directoryPath assets=" +
          "${fromAssets?.size ?: "unavailable"} classpath=${classpath.size} merged=${merged.size}",
      )
      return merged
    }
  }
