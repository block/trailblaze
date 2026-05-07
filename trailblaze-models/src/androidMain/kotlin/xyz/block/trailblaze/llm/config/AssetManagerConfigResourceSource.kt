package xyz.block.trailblaze.llm.config

import android.content.res.AssetManager
import androidx.test.platform.app.InstrumentationRegistry

/**
 * [ConfigResourceSource] backed by Android's [android.content.res.AssetManager]. Needed
 * because Android's classloader cannot enumerate resource directories — we list the assets
 * directory directly and read each file as a stream.
 *
 * Resolves the `AssetManager` via `InstrumentationRegistry.getInstrumentation().context`.
 * Trailblaze on Android runs only under `androidx.test` instrumentation, so this context
 * is always available when the lazy getters fire.
 */
object AssetManagerConfigResourceSource : ConfigResourceSource {
  override fun discoverAndLoad(directoryPath: String, suffix: String): Map<String, String> {
    val assets = InstrumentationRegistry.getInstrumentation().context.assets
    val filenames =
      try {
        assets.list(directoryPath)?.filter { it.endsWith(suffix) } ?: emptyList()
      } catch (_: Exception) {
        emptyList()
      }
    return filenames
      .mapNotNull { filename ->
        try {
          val content = assets.open("$directoryPath/$filename").bufferedReader().readText()
          filename.removeSuffix(suffix) to content
        } catch (_: Exception) {
          null
        }
      }
      .toMap()
  }

  override fun discoverAndLoadRecursive(directoryPath: String, suffix: String): Map<String, String> {
    val assets = InstrumentationRegistry.getInstrumentation().context.assets
    val results = mutableMapOf<String, String>()
    walkAssets(assets, basePath = directoryPath, currentPath = directoryPath) { entryFullPath ->
      if (!entryFullPath.endsWith(suffix)) return@walkAssets
      val relativePath = entryFullPath.removePrefix("$directoryPath/")
      try {
        val content = assets.open(entryFullPath).bufferedReader().readText()
        results[relativePath] = content
      } catch (_: Exception) {
        // Skip unreadable entries — same lenient contract as the flat path.
      }
    }
    return results
  }

  /**
   * `AssetManager.list(path)` returns immediate children only and gives no way to tell a file
   * apart from a directory. Recurse: every name is treated as a potential directory; if
   * `list(child)` returns a non-empty array we descend, otherwise we hand the path to
   * [onFile]. Empty directories and unreadable files both look like leaves here, so [onFile]
   * still has to verify-by-open before recording a result. The empty-dir collision is a
   * harmless no-op — `assets.open` on a directory throws and the catch in the caller drops it.
   */
  private fun walkAssets(
    assets: AssetManager,
    basePath: String,
    currentPath: String,
    onFile: (fullPath: String) -> Unit,
  ) {
    val children = try {
      assets.list(currentPath) ?: emptyArray()
    } catch (_: Exception) {
      return
    }
    if (children.isEmpty()) {
      if (currentPath != basePath) onFile(currentPath)
      return
    }
    for (child in children) {
      walkAssets(assets, basePath, "$currentPath/$child", onFile)
    }
  }
}
