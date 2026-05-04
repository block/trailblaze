package xyz.block.trailblaze.quickjs.tools

import java.io.File

/**
 * Tells the QuickJS-tools runtime where to load a pre-built `.js` bundle from. Mirror of
 * the legacy `:trailblaze-scripting-bundle` module's `BundleJsSource` so consumers that
 * already understood that shape can pattern-match on the same surface.
 *
 *  - [FromFile] — local filesystem path. Host JVM / desktop.
 *  - `AndroidAssetBundleSource` (in `androidMain`) — an Android APK asset. On-device.
 *  - [InlineBundleSource] — inline JS. **Tests only**, lives outside the interface so
 *    the `internal` visibility actually applies (Kotlin disallows `internal` on members
 *    of a public interface).
 *
 * The [filename] shows up in QuickJS stack traces, so name bundles clearly.
 */
interface BundleSource {
  /** Name shown in QuickJS stack traces + error messages. */
  val filename: String

  /**
   * Load the JS source. Called once per session start by [QuickJsToolBundleLauncher].
   * Implementations are not required to be idempotent; the launcher only reads each source
   * once per launch.
   */
  fun read(): String

  /** Load from a local filesystem path. Relative paths resolve against the JVM cwd. */
  class FromFile(private val path: String) : BundleSource {
    override val filename: String get() = path
    override fun read(): String {
      val file = File(path)
      require(file.exists()) { "Bundle file does not exist: ${file.absolutePath}" }
      require(file.isFile) { "Bundle path is not a regular file: ${file.absolutePath}" }
      return file.readText()
    }
  }
}

/**
 * Load a [BundleSource] from an inline string. **Tests only** — `internal` so the
 * visibility itself enforces the "no production callers" constraint. Lives at top level
 * (rather than nested inside [BundleSource]) because Kotlin forbids `internal` modifiers
 * on members of a public interface.
 *
 * Cross-module callers that want to inject inline JS for a host CLI fixture should
 * implement their own [BundleSource] rather than borrow this — keeping it module-private
 * also lets us evolve the shape without breaking anyone outside the runtime tests.
 */
internal class InlineBundleSource(
  private val source: String,
  override val filename: String = "inline-bundle.js",
) : BundleSource {
  override fun read(): String = source
}
