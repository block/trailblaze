package xyz.block.trailblaze.trailrunner

import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Behavioral tests for the tool-catalog cache contract: rebuild if and only if the change-detection
 * fingerprint changed. The expensive build is injected as a counter that returns a fresh list per call,
 * so a cache HIT is observable as "same list instance returned, build not re-run" and a MISS as "a new
 * instance built" — no assertions about internal call mechanics beyond the one effect that defines a cache.
 */
class ToolCatalogCacheCoreTest {

  private fun entry(id: String) =
    listOf(ToolCatalogEntry(id = id, flavor = ToolFlavor.SCRIPTED, trailmap = "t", sourcePath = "t/tools/$id.ts"))

  @Test
  fun `unchanged fingerprint serves the cached catalog without rebuilding`() = runBlocking {
    var builds = 0
    val cache = ToolCatalogCacheCore(
      fingerprint = { "fp-1" },
      build = { entry("build-${builds++}") },
    )

    val first = cache.get()
    val second = cache.get()

    assertEquals(1, builds, "second get with an unchanged fingerprint must not rebuild")
    assertSame(first, second, "an unchanged fingerprint must return the exact cached instance")
  }

  @Test
  fun `changed fingerprint triggers exactly one rebuild`() = runBlocking {
    var builds = 0
    var fp = "fp-1"
    val cache = ToolCatalogCacheCore(
      fingerprint = { fp },
      build = { entry("build-${builds++}") },
    )

    val first = cache.get()
    fp = "fp-2"
    val second = cache.get()
    val third = cache.get() // fp still fp-2 -> hit

    assertEquals(2, builds, "a changed fingerprint rebuilds once; a re-unchanged one then hits")
    assertEquals("build-0", first.single().id)
    assertEquals("build-1", second.single().id)
    assertSame(second, third, "fingerprint back to steady state must serve the cached rebuild")
  }

  @Test
  fun `a null fingerprint always rebuilds (degrades to pre-cache behavior)`() = runBlocking {
    var builds = 0
    val cache = ToolCatalogCacheCore(
      fingerprint = { null },
      build = { entry("build-${builds++}") },
    )

    cache.get()
    cache.get()

    assertEquals(2, builds, "a null (undetectable) fingerprint must never serve a cached result")
  }
}
