package xyz.block.trailblaze.scripting.bundle

import android.content.res.AssetManager
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Android-asset-backed bundle source. Reads the `.js` file at [assetPath] inside the APK's
 * `assets/` tree.
 *
 * Resolved against an [AssetManager] — either one the caller supplies or, via the no-arg
 * constructor, the instrumentation test application's `assets`. The no-arg path mirrors how
 * the rest of trailblaze-android / trailblaze-common resolve on-device assets
 * (`AssetManagerConfigResourceSource` in `:trailblaze-models/src/androidMain`), which is
 * the canonical pattern for "on-device code wanting assets from the test APK."
 *
 * The default path is a fallback — the host can also hand in a pre-resolved
 * [AssetManager] when it has one in scope (e.g. from a specific Context).
 */
class AndroidAssetBundleJsSource(
  private val assetPath: String,
  private val assetManager: AssetManager = resolveDefaultAssetManager(),
) : BundleJsSource {
  /**
   * Normalized form of [assetPath] suitable for [AssetManager.open]. The host
   * `McpServerConfig.script` convention accepts relative paths like `./foo/bar.js` (the
   * JVM cwd resolver is happy with that), but `AssetManager.open` addresses from the
   * asset root and fails the lookup if the path starts with `./` or `/`. Normalize once
   * at construction and reuse in both [filename] and [read] so the error message — if it
   * fires — names the path the resolver actually tried. Flagged during code review.
   */
  private val normalizedAssetPath: String = assetPath.removePrefix("./").trimStart('/')

  override val filename: String get() = "asset:$normalizedAssetPath"

  override fun read(): String =
    assetManager.open(normalizedAssetPath).use { it.readBytes().decodeToString() }

  companion object {

    /**
     * Pulls [AssetManager] off the instrumentation's context, same as the opensource target
     * YAML loader (`AssetManagerConfigResourceSource`). `InstrumentationRegistry` is
     * available in the `androidx.test.monitor` artifact which `:trailblaze-common` already
     * exposes, so we don't add a new Android testing dependency to the consumer surface.
     */
    private fun resolveDefaultAssetManager(): AssetManager =
      InstrumentationRegistry.getInstrumentation().context.assets
  }
}
