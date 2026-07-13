package xyz.block.trailblaze.llm.config

import java.util.concurrent.ConcurrentHashMap
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

/**
 * Process-wide memo of discovery results. APK assets and the classpath are immutable for the
 * life of the process, so a (directory, suffix) listing can never change once computed. The
 * recursive trailmap scan costs hundreds of ms per call on-device, and the
 * host→on-device RPC path used to re-run it per dispatched tool via each request's fresh
 * tool-repo build — a large slice of the per-action tax in https://github.com/block/trailblaze/issues/210.
 */
private val discoveryCache = ConcurrentHashMap<String, Map<String, String>>()

private fun discoverMerged(
  key: String,
  fromAssetsSource: () -> Map<String, String>,
  fromClasspathSource: () -> Map<String, String>,
): Map<String, String> {
  discoveryCache[key]?.let { return it }
  val fromAssets =
    try {
      fromAssetsSource()
    } catch (_: IllegalStateException) {
      // InstrumentationRegistry not registered — non-instrumentation test context.
      null
    }
  // Merge: classpath first so instrumentation-asset keys can override.
  val classpath = fromClasspathSource()
  val merged = classpath + (fromAssets ?: emptyMap())
  // Logged on cache miss only — one line per distinct discovery, not per request.
  Console.log(
    "[platformConfigResourceSource/android] $key assets=" +
      "${fromAssets?.size ?: "unavailable"} classpath=${classpath.size} merged=${merged.size}",
  )
  // Memoize only the healthy shape: a degraded (assets-unavailable) result must not pin the
  // process to classpath-only when a later call CAN see assets.
  if (fromAssets != null) discoveryCache[key] = merged
  return merged
}

actual fun platformConfigResourceSource(): ConfigResourceSource =
  object : ConfigResourceSource {
    override fun discoverAndLoad(directoryPath: String, suffix: String): Map<String, String> =
      discoverMerged(
        key = "flat|$directoryPath|$suffix",
        fromAssetsSource = { AssetManagerConfigResourceSource.discoverAndLoad(directoryPath, suffix) },
        fromClasspathSource = { ClasspathConfigResourceSource.discoverAndLoad(directoryPath, suffix) },
      )

    override fun discoverAndLoadRecursive(directoryPath: String, suffix: String): Map<String, String> =
      discoverMerged(
        key = "recursive|$directoryPath|$suffix",
        fromAssetsSource = { AssetManagerConfigResourceSource.discoverAndLoadRecursive(directoryPath, suffix) },
        fromClasspathSource = { ClasspathConfigResourceSource.discoverAndLoadRecursive(directoryPath, suffix) },
      )
  }
