package xyz.block.trailblaze.testing

import xyz.block.trailblaze.llm.config.ConfigResourceSource

/**
 * A [ConfigResourceSource] over an in-memory `resource path -> text` map — a stand-in for the
 * Android asset source, serving files that are not on the test classpath. Records every suffix it
 * is asked to enumerate and every path it is asked to read.
 */
class InMemoryConfigResourceSource(private val files: Map<String, String>) : ConfigResourceSource {

  val enumeratedSuffixes = mutableListOf<String>()
  val readPaths = mutableListOf<String>()

  override fun discoverAndLoad(directoryPath: String, suffix: String): Map<String, String> =
    discoverAndLoadRecursive(directoryPath, suffix)
      .filterKeys { !it.contains('/') }
      .mapKeys { (key, _) -> key.removeSuffix(suffix) }

  override fun discoverAndLoadRecursive(directoryPath: String, suffix: String): Map<String, String> {
    enumeratedSuffixes += suffix
    return files
      .filterKeys { it.startsWith("$directoryPath/") && it.endsWith(suffix) }
      .mapKeys { (key, _) -> key.removePrefix("$directoryPath/") }
  }

  override fun readResource(path: String): String? {
    readPaths += path
    return files[path]
  }
}
