package xyz.block.trailblaze.android

import android.content.res.AssetManager
import xyz.block.trailblaze.config.AppTargetYamlLoader
import xyz.block.trailblaze.surveys.PackagedSurveyBundleSource
import xyz.block.trailblaze.surveys.SurveyBundles

/**
 * `ctx.surveys` on a device: serves the surveys this test APK packaged under
 * [SurveyBundles.PACKAGED_DIR], with the targets of the APK's own `targets/<id>.yaml`.
 */
object AndroidAssetSurveyBundles {

  /** Installs this APK's surveys as the process's source, unless something installed one already. */
  fun installIfAbsent(assetManager: AssetManager) {
    if (SurveyBundles.source != null) return
    synchronized(this) {
      if (SurveyBundles.source == null) SurveyBundles.source = sourceFor(assetManager)
    }
  }

  fun sourceFor(assetManager: AssetManager): PackagedSurveyBundleSource =
    PackagedSurveyBundleSource(
      paths = { packagedPaths(assetManager) },
      read = { path ->
        try {
          assetManager.open("${SurveyBundles.PACKAGED_DIR}/$path").use { it.readBytes().decodeToString() }
        } catch (_: Exception) {
          null
        }
      },
      targets = {
        AppTargetYamlLoader.discoverConfigs().associate { config ->
          config.id to config.platforms.orEmpty().mapNotNull { (platform, it) -> it.appIds?.takeIf { ids -> ids.isNotEmpty() }?.let { platform to it } }.toMap()
        }.filterValues { it.isNotEmpty() }
      },
    )

  /**
   * Every file under [SurveyBundles.PACKAGED_DIR], relative to it. `AssetManager.list` names a
   * directory's children and cannot tell a file from a directory, so a name with no children is a file.
   */
  private fun packagedPaths(assets: AssetManager): List<String> {
    val out = mutableListOf<String>()
    fun walk(relative: String) {
      val children = try {
        assets.list(if (relative.isEmpty()) SurveyBundles.PACKAGED_DIR else "${SurveyBundles.PACKAGED_DIR}/$relative").orEmpty()
      } catch (_: Exception) {
        emptyArray()
      }
      if (children.isEmpty()) {
        if (relative.isNotEmpty()) out += relative
        return
      }
      children.forEach { walk(if (relative.isEmpty()) it else "$relative/$it") }
    }
    walk("")
    return out
  }
}
