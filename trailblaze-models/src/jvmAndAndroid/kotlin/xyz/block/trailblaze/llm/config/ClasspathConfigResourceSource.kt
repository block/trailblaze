package xyz.block.trailblaze.llm.config

/**
 * [ConfigResourceSource] backed by JVM classpath scanning via [ClasspathResourceDiscovery].
 *
 * This is the default source used by all YAML config loaders on JVM desktop.
 */
object ClasspathConfigResourceSource : ConfigResourceSource {
  override fun discoverAndLoad(directoryPath: String, suffix: String): Map<String, String> =
    ClasspathResourceDiscovery.discoverAndLoad(directoryPath, suffix)

  override fun discoverAndLoadRecursive(directoryPath: String, suffix: String): Map<String, String> =
    ClasspathResourceDiscovery.discoverAndLoadRecursive(directoryPath, suffix)

  override fun readResource(path: String): String? = ClasspathResourceDiscovery.loadResource(path)

  override fun resourceExists(path: String): Boolean = ClasspathResourceDiscovery.resourceExists(path)

  override fun discoverFilenamesRecursive(directoryPath: String, suffix: String): Set<String> =
    ClasspathResourceDiscovery.discoverFilenamesRecursive(directoryPath, suffix)
}
