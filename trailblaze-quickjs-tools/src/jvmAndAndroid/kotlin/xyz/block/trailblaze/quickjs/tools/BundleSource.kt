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
   * Which bundle this source loads, independent of how [filename] happens to spell it.
   *
   * [filename] is a display label — `foo.js` and `./foo.js` name one file, and nothing stops
   * two unrelated sources from carrying the same label. [QuickJsToolBundleLauncher] compares
   * this instead when it decides whether two `mcp_servers` entries staged the SAME bundle
   * twice or two DIFFERENT bundles that clash, because those two diagnoses need opposite
   * fixes (drop the repeated entry vs. rename an export).
   *
   * Defaults to [filename], which is correct only when an implementation's label already
   * identifies the source uniquely — one spelling per source, and never a constant shared by
   * instances that load different JS. Override when it doesn't.
   */
  val bundleId: String get() = filename

  /**
   * Load the JS source. Called once per session start by [QuickJsToolBundleLauncher].
   * Implementations are not required to be idempotent; the launcher only reads each source
   * once per launch.
   */
  fun read(): String

  /** Load from a local filesystem path. Relative paths resolve against the JVM cwd. */
  class FromFile(private val path: String) : BundleSource {
    override val filename: String get() = path

    /**
     * Canonical path, so `foo.js`, `./foo.js` and an absolute spelling of the same file are one
     * bundle. Falls back to the raw path when the filesystem can't canonicalize (missing file,
     * unreadable parent) — [read] fails with a better message than an identity lookup would.
     */
    override val bundleId: String
      get() = runCatching { File(path).canonicalPath }.getOrDefault(path)

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
  /**
   * The JS itself is the identity: inline sources share a default [filename], so two fixtures
   * with different bodies must not read as one bundle staged twice. Computed once — this copies
   * the bundle text, which a per-read `get()` would repeat.
   */
  override val bundleId: String = "inline:$filename:$source"

  override fun read(): String = source
}
