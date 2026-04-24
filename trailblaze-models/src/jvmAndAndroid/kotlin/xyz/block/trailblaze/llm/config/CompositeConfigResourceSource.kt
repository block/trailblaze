package xyz.block.trailblaze.llm.config

/**
 * [ConfigResourceSource] that merges results from multiple underlying sources.
 *
 * Used to compose framework-shipped classpath config with user-provided filesystem config:
 * the classpath source contributes whatever the framework bundles, and one or more
 * [FilesystemConfigResourceSource]s contribute user-authored targets / toolsets / tools that
 * live alongside the user's app.
 *
 * Later sources **override** earlier ones when filenames collide (a user-shipped
 * `targets/foo.yaml` replaces a classpath `targets/foo.yaml`). Concrete effect: put the
 * user's filesystem source **after** the classpath source so user config wins.
 */
class CompositeConfigResourceSource(
  private val sources: List<ConfigResourceSource>,
) : ConfigResourceSource {

  override fun discoverAndLoad(directoryPath: String, suffix: String): Map<String, String> =
    sources.fold(emptyMap()) { acc, source ->
      acc + source.discoverAndLoad(directoryPath, suffix)
    }
}
