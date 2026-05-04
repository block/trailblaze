package xyz.block.trailblaze.quickjs.tools

import android.content.res.AssetManager
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Android-asset-backed [BundleSource]. Reads the `.js` file at [assetPath] inside the
 * APK's `assets/` tree.
 *
 * Resolved against an [AssetManager] — either one the caller supplies or, via the no-arg
 * default, the instrumentation test application's `assets`. The default path mirrors how
 * the legacy `:trailblaze-scripting-bundle` module's `AndroidAssetBundleJsSource` resolves
 * on-device assets, which is the canonical pattern for "on-device code wanting assets from
 * the test APK." Hosts can hand in a pre-resolved [AssetManager] when they have one in
 * scope (e.g. from a specific Context).
 */
class AndroidAssetBundleSource(
  private val assetPath: String,
  private val assetManager: AssetManager = resolveDefaultAssetManager(),
) : BundleSource {
  /**
   * Normalized form of [assetPath] suitable for [AssetManager.open]. The
   * `McpServerConfig.script` convention accepts relative paths like `./foo/bar.js` (the
   * JVM cwd resolver is happy with that), but `AssetManager.open` addresses from the asset
   * root and fails the lookup if the path starts with `./` or `/`. Normalize once at
   * construction and reuse in [filename] and [read] so the error message — if it fires —
   * names the path the resolver actually tried.
   *
   * Defensive validation: reject any path containing a `..` segment. The asset path
   * originates in target YAML config (`mcp_servers.script`), which is consumer-controlled
   * — even if `AssetManager.open` happens to refuse traversal on current Android versions,
   * failing fast at the source of truth removes a category of latent bug.
   */
  private val normalizedAssetPath: String = assetPath
    .removePrefix("./")
    .trimStart('/')
    .also { normalized ->
      require(normalized.split('/').none { it == ".." }) {
        "Asset path '$assetPath' must not contain '..' segments — bundle assets must " +
          "resolve relative to the test APK's asset root."
      }
    }

  override val filename: String get() = "asset:$normalizedAssetPath"

  override fun read(): String =
    assetManager.open(normalizedAssetPath).use { it.readBytes().decodeToString() }

  companion object {
    private fun resolveDefaultAssetManager(): AssetManager =
      InstrumentationRegistry.getInstrumentation().context.assets
  }
}
