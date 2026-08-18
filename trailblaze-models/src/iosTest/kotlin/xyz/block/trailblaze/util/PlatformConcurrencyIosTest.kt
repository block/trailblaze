package xyz.block.trailblaze.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract tests for the Kotlin/Native actuals of [concurrentMutableMap] / [concurrentMutableSet].
 *
 * These are the only actuals with real logic rather than a one-line degrade, and this is the only
 * platform where the collections are genuinely reachable from multiple threads — so the
 * concurrency assertions here are the ones that would catch a regression. `Dispatchers.Default`
 * is multi-threaded on Native; the same test against an unguarded `LinkedHashMap` loses writes.
 *
 * Run with (the first flag declares the target, which `gradle.properties` pins off; the second
 * enables the simulator run, which the deterministic gate leaves out):
 * ```
 * ./gradlew :trailblaze-models:iosSimulatorArm64Test \
 *   -Ptrailblaze.ios=true -Ptrailblaze.ios.simulator.tests=true
 * ```
 */
class PlatformConcurrencyIosTest {

  @Test
  fun `map supports the single-key read and write surface`() {
    val map = concurrentMutableMap<String, String>()

    assertTrue(map.isEmpty())
    assertNull(map.put("a", "1"))
    assertEquals("1", map.put("a", "2"))
    assertEquals("2", map["a"])
    assertEquals(1, map.size)
    assertTrue(map.containsKey("a"))
    assertTrue(map.containsValue("2"))
    assertFalse(map.containsKey("missing"))

    map.putAll(mapOf("b" to "3", "c" to "4"))
    assertEquals(setOf("a", "b", "c"), map.keys)
    assertEquals(listOf("2", "3", "4"), map.values.toList())

    assertEquals("3", map.remove("b"))
    assertNull(map.remove("b"))
    assertEquals(2, map.size)

    map.clear()
    assertTrue(map.isEmpty())
  }

  @Test
  fun `map views are snapshots that neither observe nor write through later mutation`() {
    val map = concurrentMutableMap<String, String>()
    map["a"] = "1"

    val keys = map.keys
    val values = map.values
    val entries = map.entries

    map["b"] = "2"

    // Snapshot, so a view taken before the write doesn't see it. This is what makes iterating a
    // view safe while another thread writes.
    assertEquals(setOf("a"), keys)
    assertEquals(listOf("1"), values.toList())
    assertEquals(1, entries.size)

    // ...and mutating a view doesn't reach the map. Callers mutate through the map API.
    keys.clear()
    entries.single().setValue("clobbered")
    assertEquals("1", map["a"])
    assertEquals(2, map.size)
  }

  @Test
  fun `map and set compare structurally like the actuals on other platforms`() {
    val map = concurrentMutableMap<String, String>()
    map["a"] = "1"
    map["b"] = "2"

    // The seams hand back standard collections on JVM/Android and Wasm, so callers can compare
    // contents. A wrapper inheriting identity equality from `Any` would break that here only.
    assertEquals<Map<String, String>>(mapOf("a" to "1", "b" to "2"), map)
    assertEquals(mapOf("a" to "1", "b" to "2").hashCode(), map.hashCode())
    assertTrue(map == mapOf("b" to "2", "a" to "1"), "map equality is order-independent")
    assertTrue(mapOf("a" to "1", "b" to "2") == map, "equality holds with the wrapper on the right")
    assertFalse(map == mapOf("a" to "1"))
    assertEquals("{a=1, b=2}", map.toString())

    // Two independently-created instances with the same contents, so neither comparison can be
    // satisfied by reference identity.
    val other = concurrentMutableMap<String, String>()
    other.putAll(mapOf("a" to "1", "b" to "2"))
    assertEquals<Map<String, String>>(map, other)

    val set = concurrentMutableSet<String>()
    set.addAll(listOf("x", "y"))
    assertEquals<Set<String>>(setOf("y", "x"), set)
    assertEquals(setOf("x", "y").hashCode(), set.hashCode())
    assertEquals(mapOf("a" to "1", "b" to "2").entries, map.entries)
  }

  @Test
  fun `set supports the membership surface with snapshot iteration`() {
    val set = concurrentMutableSet<String>()

    assertTrue(set.add("a"))
    assertFalse(set.add("a"))
    assertTrue(set.contains("a"))
    assertEquals(1, set.size)

    set.addAll(listOf("b", "c"))
    assertTrue(set.containsAll(listOf("a", "b", "c")))

    val iterator = set.iterator()
    set.add("d")
    // Snapshot: the in-flight iterator neither sees "d" nor throws for the concurrent write.
    assertEquals(listOf("a", "b", "c"), iterator.asSequence().toList())

    assertTrue(set.remove("d"))
    assertTrue(set.removeAll(listOf("b", "c")))
    set.add("z")
    assertTrue(set.retainAll(listOf("a")))
    assertEquals(setOf("a"), set.toSet())
    // No change to make, so no change reported.
    assertFalse(set.retainAll(listOf("a")))

    set.clear()
    assertTrue(set.isEmpty())
  }

  @Test
  fun `concurrent writers from multiple threads all land`() = runBlocking {
    val map = concurrentMutableMap<String, Int>()
    val set = concurrentMutableSet<String>()
    val writers = 8
    val perWriter = 500

    (0 until writers).map { writer ->
      async(Dispatchers.Default) {
        repeat(perWriter) { i ->
          val key = "w$writer-$i"
          map[key] = i
          set.add(key)
        }
      }
    }.awaitAll()

    assertEquals(writers * perWriter, map.size)
    assertEquals(writers * perWriter, set.size)
    assertEquals(perWriter - 1, map["w0-${perWriter - 1}"])
    assertTrue(set.contains("w${writers - 1}-0"))
  }

  @Test
  fun `concurrent readers see a consistent map while a writer mutates it`() = runBlocking {
    val map = concurrentMutableMap<String, Int>()
    repeat(100) { map["seed-$it"] = it }

    val writer = async(Dispatchers.Default) {
      repeat(1_000) { map["churn-$it"] = it }
    }
    val readers = (0 until 4).map {
      async(Dispatchers.Default) {
        // Iterating a snapshot view while the writer runs must neither throw nor lose the seeds.
        repeat(100) {
          assertEquals(100, map.entries.count { entry -> entry.key.startsWith("seed-") })
        }
      }
    }

    (readers + writer).awaitAll()
    assertEquals(1_100, map.size)
  }
}
