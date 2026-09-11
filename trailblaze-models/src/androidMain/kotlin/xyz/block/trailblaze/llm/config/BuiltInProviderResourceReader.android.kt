package xyz.block.trailblaze.llm.config

/**
 * Android resolves provider YAML through [platformConfigResourceSource], which merges
 * `AssetManager` discovery with classpath discovery (assets winning) and memoizes the result
 * process-wide.
 *
 * Assets have to lead because on a device they are the only copy that can be FOUND. An APK stores
 * no zip directory entries, so `ClassLoader.getResources("trails/config/providers")` is always
 * empty there and the classpath half of that merge contributes nothing — which is what lets a
 * packaged APK drop the duplicate Java-resource copy of the tree and ship it once. A plain JVM
 * unit test (`testDebugUnitTest` / Robolectric) is the mirror image: no `InstrumentationRegistry`
 * to hand out an `AssetManager`, so the classpath half is the entire result.
 *
 * Both functions go assets-first with an exact-path classpath read as the per-key backstop.
 * Keeping them in the SAME order is load-bearing: an APK that still ships both copies would
 * otherwise serve a single lookup and a full-catalog scan from different copies of one provider
 * id, and the asset leg would go unexercised there.
 *
 * A miss is silent rather than loud — `BuiltInLlmModelRegistry.find` returns null and
 * `AndroidLlmClientResolver.findOrFallback` substitutes a model with zeroed pricing and default
 * capabilities — so `BundledProviderConfigOnDeviceTest` asserts these reads on a real device.
 */
actual fun readBuiltInProviderYamlResources(): Map<String, String> {
  val discovered = discoverProviderYamlFromPlatformSources()
  // Gap-filled per key, not all-or-nothing. A module dropping its `assets.srcDirs` yields a
  // PARTIAL map, which any "fall back when empty" shape sails straight past.
  val missingCore =
    (CORE_PROVIDERS - discovered.keys).mapNotNull { providerId ->
      readBuiltInProviderYamlFromClasspath(providerId)?.let { providerId to it }
    }
  return discovered + missingCore
}

actual fun readBuiltInProviderYaml(providerId: String): String? =
  discoverProviderYamlFromPlatformSources()[providerId]
    ?: readBuiltInProviderYamlFromClasspath(providerId)

/**
 * A FLAT `AssetManager.list` of the providers directory (five files today) merged with classpath
 * discovery — not the recursive trailmap walk — memoized per directory+suffix by
 * [platformConfigResourceSource], so the listing is paid once per process rather than once per
 * provider lookup.
 */
private fun discoverProviderYamlFromPlatformSources(): Map<String, String> =
  platformConfigResourceSource().discoverAndLoad(directoryPath = PROVIDERS_PATH, suffix = ".yaml")
