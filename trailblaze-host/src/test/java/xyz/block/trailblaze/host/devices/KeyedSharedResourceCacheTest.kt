package xyz.block.trailblaze.host.devices

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KeyedSharedResourceCacheTest {

  private class Resource {
    var closes = 0
  }

  private class Held(val resource: Resource, hold: AutoCloseable) : AutoCloseable by hold

  private fun cache() = KeyedSharedResourceCache<String, String, Resource, Held>(
    closeResource = { _, _, resource -> resource.closes++ },
    lease = ::Held,
  )

  @Test
  fun `different device keys remain independently reusable`() {
    val cache = cache()
    val deviceA = cache.acquireOrCreate("device-a", "plain", { true }) { Resource() }
    val deviceB = cache.acquireOrCreate("device-b", "plain", { true }) { Resource() }

    val secondA = cache.acquireOrCreate("device-a", "plain", { true }) { error("must reuse A") }
    val secondB = cache.acquireOrCreate("device-b", "plain", { true }) { error("must reuse B") }

    assertSame(deviceA.resource, secondA.resource)
    assertSame(deviceB.resource, secondB.resource)

    deviceA.close()
    secondA.close()
    deviceB.close()
    secondB.close()
  }

  @Test
  fun `changing a wrapper closes only the matching device`() {
    val cache = cache()
    val oldA = cache.acquireOrCreate("device-a", "wrapper-a", { true }) { Resource() }
    val deviceB = cache.acquireOrCreate("device-b", "wrapper-a", { true }) { Resource() }

    val newA = cache.acquireOrCreate("device-a", "wrapper-b", { true }) { Resource() }
    val secondB = cache.acquireOrCreate("device-b", "wrapper-a", { true }) { error("must reuse B") }

    assertEquals(1, oldA.resource.closes, "the superseded device must release its driver port")
    assertEquals(0, deviceB.resource.closes, "another live simulator must remain connected")
    assertNotSame(oldA.resource, newA.resource)

    oldA.close()
    newA.close()
    deviceB.close()
    secondB.close()
  }

  @Test
  fun `evicting a key closes only its resource, and its next acquire builds afresh`() {
    val cache = cache()
    val oldA = cache.acquireOrCreate("device-a", "plain", { true }) { Resource() }
    val deviceB = cache.acquireOrCreate("device-b", "plain", { true }) { Resource() }

    cache.evict("device-a") { true }
    val newA = cache.acquireOrCreate("device-a", "plain", { true }) { Resource() }
    val secondB = cache.acquireOrCreate("device-b", "plain", { true }) { error("must reuse B") }

    assertEquals(1, oldA.resource.closes, "the evicted driver must be closed while it is still leased")
    assertNotSame(oldA.resource, newA.resource)
    assertEquals(0, deviceB.resource.closes, "another device's driver must stay connected")

    oldA.close()
    assertEquals(1, oldA.resource.closes, "a late release of an evicted lease must not close it again")
    newA.close()
    deviceB.close()
    secondB.close()
  }

  @Test
  fun `an eviction meant for a variant the key has since replaced leaves the replacement alone`() {
    val cache = cache()
    val oldA = cache.acquireOrCreate("device-a", "port-1", { true }) { Resource() }
    val newA = cache.acquireOrCreate("device-a", "port-2", { true }) { Resource() }

    cache.evict("device-a") { it == "port-1" }
    val secondA = cache.acquireOrCreate("device-a", "port-2", { true }) { error("must reuse A") }

    assertEquals(0, newA.resource.closes, "the replacement must stay connected")
    assertSame(newA.resource, secondA.resource)

    oldA.close()
    newA.close()
    secondA.close()
  }

  @Test
  fun `each key is told it is building for the first time exactly once`() {
    val cache = cache()
    val lastBuiltVariants = mutableListOf<Pair<String, String?>>()

    val firstA = cache.acquireOrCreate("device-a", "plain", { true }) { lastBuilt ->
      lastBuiltVariants += "device-a" to lastBuilt
      Resource()
    }
    val firstB = cache.acquireOrCreate("device-b", "plain", { true }) { lastBuilt ->
      lastBuiltVariants += "device-b" to lastBuilt
      Resource()
    }
    firstA.close()
    firstB.close()

    val secondA = cache.acquireOrCreate("device-a", "plain", { true }) { lastBuilt ->
      lastBuiltVariants += "device-a" to lastBuilt
      Resource()
    }

    assertEquals(
      listOf("device-a" to null, "device-b" to null, "device-a" to "plain"),
      lastBuiltVariants,
    )
    secondA.close()
  }

  @Test
  fun `a rebuild for another variant is told the variant it replaces, even after an eviction`() {
    val cache = cache()
    val lastBuiltVariants = mutableListOf<String?>()

    cache.acquireOrCreate("device-a", "port-1", { true }) { Resource() }.close()
    cache.evict("device-a") { true }
    val onPort2 = cache.acquireOrCreate("device-a", "port-2", { true }) { lastBuilt ->
      lastBuiltVariants += lastBuilt
      Resource()
    }

    assertEquals(listOf<String?>("port-1"), lastBuiltVariants)
    onPort2.close()
  }

  @Test
  fun `different device keys may initialize concurrently`() {
    val cache = cache()
    val executor = Executors.newFixedThreadPool(2)
    val bothCreationsStarted = CountDownLatch(2)
    val releaseCreations = CountDownLatch(1)

    fun create(): Resource {
      bothCreationsStarted.countDown()
      check(releaseCreations.await(60, TimeUnit.SECONDS)) { "timed out waiting to release creations" }
      return Resource()
    }

    val deviceA = executor.submit<Held> {
      cache.acquireOrCreate("device-a", "plain", { true }) { create() }
    }
    val deviceB = executor.submit<Held> {
      cache.acquireOrCreate("device-b", "plain", { true }) { create() }
    }

    try {
      assertTrue(
        bothCreationsStarted.await(60, TimeUnit.SECONDS),
        "one device's initialization must not hold a cache-wide lock",
      )
      releaseCreations.countDown()
      deviceA.get(60, TimeUnit.SECONDS).close()
      deviceB.get(60, TimeUnit.SECONDS).close()
    } finally {
      releaseCreations.countDown()
      executor.shutdownNow()
    }
  }

  @Test
  fun `concurrent requests for one device initialize it once`() {
    val cache = cache()
    val executor = Executors.newFixedThreadPool(8)
    val start = CountDownLatch(1)
    val creations = AtomicInteger()
    val requests = (1..8).map {
      executor.submit<Held> {
        check(start.await(60, TimeUnit.SECONDS)) { "timed out waiting to start requests" }
        cache.acquireOrCreate("device-a", "plain", { true }) {
          creations.incrementAndGet()
          Resource()
        }
      }
    }

    try {
      start.countDown()
      val acquired = requests.map { it.get(60, TimeUnit.SECONDS) }
      assertEquals(1, creations.get())
      acquired.forEach { it.close() }
    } finally {
      start.countDown()
      executor.shutdownNow()
    }
  }
}
