package xyz.block.trailblaze.llm.config

/**
 * [ConfigResourceSource] backed by JVM classpath scanning via [ClasspathResourceDiscovery].
 *
 * This is the default source used by all YAML config loaders on JVM desktop.
 */
object ClasspathConfigResourceSource : ConfigResourceSource {
  override fun discoverAndLoad(directoryPath: String, suffix: String): Map<String, String> =
    ClasspathResourceDiscovery.discoverAndLoad(directoryPath, suffix)
}
