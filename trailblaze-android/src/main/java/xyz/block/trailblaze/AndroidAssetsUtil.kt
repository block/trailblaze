package xyz.block.trailblaze

import xyz.block.trailblaze.InstrumentationUtil.withInstrumentation

object AndroidAssetsUtil {

  fun readAssetAsString(assetPath: String): String = try {
    withInstrumentation {
      context.assets.open(assetPath).reader().readText()
    }
  } catch (_: Exception) {
    // Exception means that there is no saved record
    // Options after this are either fail the test or run using the LLM
    error("Could not find asset at $assetPath")
  }

  fun assetExists(assetPath: String): Boolean = try {
    withInstrumentation {
      context.assets.open(assetPath).close() // Try to open it
    }
    true
  } catch (e: Exception) {
    false
  }
}
