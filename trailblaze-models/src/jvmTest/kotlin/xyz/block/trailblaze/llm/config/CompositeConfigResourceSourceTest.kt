package xyz.block.trailblaze.llm.config

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the "later source wins on filename collision" contract for
 * [CompositeConfigResourceSource]. That rule is load-bearing: it's how user-contributed
 * filesystem config overrides framework-shipped classpath config (e.g. a user pinning a
 * different `trailblaze-config/targets/my-app.yaml` wins against a stale classpath copy).
 */
class CompositeConfigResourceSourceTest {

  private class FakeSource(private val entries: Map<String, String>) : ConfigResourceSource {
    override fun discoverAndLoad(directoryPath: String, suffix: String): Map<String, String> =
      entries
  }

  @Test
  fun `entries from all sources are merged into one map`() {
    val composite = CompositeConfigResourceSource(
      sources = listOf(
        FakeSource(mapOf("a" to "fromClasspath")),
        FakeSource(mapOf("b" to "fromFilesystem")),
      ),
    )
    val loaded = composite.discoverAndLoad("ignored", ".yaml")
    assertEquals(mapOf("a" to "fromClasspath", "b" to "fromFilesystem"), loaded)
  }

  @Test
  fun `later source overrides earlier source on filename collision`() {
    // The "user wins" property. Put classpath first, user filesystem second — filesystem
    // value replaces the classpath one for any shared key.
    val composite = CompositeConfigResourceSource(
      sources = listOf(
        FakeSource(mapOf("sample-app" to "classpath version")),
        FakeSource(mapOf("sample-app" to "user version")),
      ),
    )
    val loaded = composite.discoverAndLoad("ignored", ".yaml")
    assertEquals(mapOf("sample-app" to "user version"), loaded)
  }

  @Test
  fun `empty source list returns empty map`() {
    val composite = CompositeConfigResourceSource(sources = emptyList())
    assertEquals(emptyMap(), composite.discoverAndLoad("ignored", ".yaml"))
  }

  @Test
  fun `empty underlying source contributes nothing but doesn't suppress others`() {
    val composite = CompositeConfigResourceSource(
      sources = listOf(
        FakeSource(emptyMap()),
        FakeSource(mapOf("only" to "from non-empty")),
      ),
    )
    assertEquals(mapOf("only" to "from non-empty"), composite.discoverAndLoad("ignored", ".yaml"))
  }
}
