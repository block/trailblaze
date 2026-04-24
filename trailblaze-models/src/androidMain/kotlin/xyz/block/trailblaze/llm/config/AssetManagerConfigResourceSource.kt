package xyz.block.trailblaze.llm.config

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
}
