package xyz.block.trailblaze.scripting.bundle

import xyz.block.trailblaze.config.McpServerConfig
import java.io.File

/**
 * Tells the runtime where to load a pre-built `.js` bundle from.
 *
 *  - [FromFile] — local filesystem path. Host JVM / desktop.
 *  - `AndroidAssetBundleJsSource` (in `androidMain`) — an Android APK asset. On-device
 *    production.
 *  - [FromString] — inline JS. Tests only.
 *
 * The [filename] shows up in QuickJS stack traces, so name bundles clearly.
 */
interface BundleJsSource {
  /** Name shown in QuickJS stack traces + error messages. */
  val filename: String

  /** Load the JS source. Called once per session start by [McpBundleSession.connect]. */
  fun read(): String

  /** Load from a local filesystem path. Relative paths resolve against the JVM cwd. */
  class FromFile(private val path: String) : BundleJsSource {
    override val filename: String get() = path
    override fun read(): String {
      val file = File(path)
      require(file.exists()) { "Bundle file does not exist: ${file.absolutePath}" }
      require(file.isFile) { "Bundle path is not a regular file: ${file.absolutePath}" }
      return file.readText()
    }
  }

  /** Load from an inline string. Tests only. */
  class FromString(
    private val source: String,
    override val filename: String = "inline-bundle.js",
  ) : BundleJsSource {
    override fun read(): String = source
  }
}

/** Turn a target-YAML `McpServerConfig.script:` path into a [BundleJsSource.FromFile]. */
fun McpServerConfig.toBundleJsSourceFromFile(): BundleJsSource {
  val scriptPath = requireNotNull(script) {
    "McpServerConfig missing `script:` — `command:` entries aren't bundleable and must " +
      "be filtered out before reaching the bundle launcher."
  }
  return BundleJsSource.FromFile(scriptPath)
}
