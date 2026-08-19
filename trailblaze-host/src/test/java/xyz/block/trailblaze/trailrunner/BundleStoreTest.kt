package xyz.block.trailblaze.trailrunner

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BundleStoreTest {

  @get:Rule
  val tmp = TemporaryFolder()

  @Test
  fun `explicit create refuses to replace a recording`() {
    val bundle = tmp.newFolder("bundle")
    File(bundle, "ios.trail.yaml").writeText("old")

    val result = BundleStore.writeFile(bundle, "ios.trail.yaml", "new", operation = "create")

    assertEquals(BundleStore.FileWriteResult.ALREADY_EXISTS, result)
    assertEquals("old", File(bundle, "ios.trail.yaml").readText())
  }

  @Test
  fun `explicit update refuses to create a missing recording`() {
    val bundle = tmp.newFolder("bundle")

    val result = BundleStore.writeFile(bundle, "ios.trail.yaml", "new", operation = "update")

    assertEquals(BundleStore.FileWriteResult.NOT_FOUND, result)
    assertTrue(!File(bundle, "ios.trail.yaml").exists())
  }

  @Test
  fun `simultaneous creates have exactly one winner`() {
    val bundle = tmp.newFolder("bundle")
    val ready = CountDownLatch(8)
    val start = CountDownLatch(1)
    val pool = Executors.newFixedThreadPool(8)
    try {
      val results = (1..8).map { n ->
        pool.submit<BundleStore.FileWriteResult> {
          ready.countDown()
          start.await()
          BundleStore.writeFile(bundle, "android.trail.yaml", "writer-$n", operation = "create")
        }
      }
      ready.await()
      start.countDown()

      val outcomes = results.map { it.get() }

      assertEquals(1, outcomes.count { it == BundleStore.FileWriteResult.WRITTEN })
      assertEquals(7, outcomes.count { it == BundleStore.FileWriteResult.ALREADY_EXISTS })
      assertTrue(File(bundle, "android.trail.yaml").readText().startsWith("writer-"))
    } finally {
      pool.shutdownNow()
    }
  }

  @Test
  fun `simultaneous bundle creates choose distinct folders without replacing yaml`() {
    val root = tmp.newFolder("trails")
    val ready = CountDownLatch(2)
    val start = CountDownLatch(1)
    val pool = Executors.newFixedThreadPool(2)
    try {
      val results = listOf("first", "second").map { yaml ->
        pool.submit<Pair<String, String>> {
          ready.countDown()
          start.await()
          BundleStore.createAt(root, "checkout", yaml) to yaml
        }
      }
      ready.await()
      start.countDown()

      val created = results.map { it.get() }

      assertEquals(2, created.map { it.first }.distinct().size)
      created.forEach { (id, yaml) ->
        val relative = id.removePrefix("0/")
        assertEquals(yaml, File(root, "$relative/blaze.yaml").readText())
      }
    } finally {
      pool.shutdownNow()
    }
  }

  @Test
  fun `detail reports classifier coverage and freshness`() {
    val root = tmp.newFolder("trails")
    val bundle = File(root, "checkout").apply { mkdirs() }
    File(bundle, "blaze.yaml").writeText(
      """
      config:
        title: Checkout
      trail:
        - step: Pay
      """.trimIndent(),
    )
    File(bundle, "ios.trail.yaml").writeText(
      """
      config:
        title: Checkout
      trail:
        - step: Pay
          recording:
            ios-iphone:
              - pressBack: {}
            ios-ipad:
              - pressBack: {}
      """.trimIndent(),
    )

    val variant = BundleStore.detail(bundle, "0/checkout", "checkout", root).variants.single()

    assertEquals("ios", variant.platform)
    assertEquals(listOf("ios-ipad", "ios-iphone"), variant.classifiers)
    assertTrue((variant.updatedAtMs ?: 0L) > 0L)
  }

  @Test
  fun `detail keeps an unreadable recording visible for recovery`() {
    val root = tmp.newFolder("trails")
    val bundle = File(root, "checkout").apply { mkdirs() }
    File(bundle, "blaze.yaml").writeText("config:\n  title: Checkout\ntrail: []")
    val recording = File(bundle, "ios.trail.yaml").apply { writeText("trail: []") }
    val originalPermissions = Files.getPosixFilePermissions(recording.toPath())
    try {
      Files.setPosixFilePermissions(recording.toPath(), emptySet<PosixFilePermission>())

      val variant = BundleStore.detail(bundle, "0/checkout", "checkout", root).variants.single()

      assertEquals("ios.trail.yaml", variant.name)
      assertEquals("ios", variant.platform)
      assertEquals(emptyList(), variant.classifiers)
    } finally {
      Files.setPosixFilePermissions(recording.toPath(), originalPermissions)
    }
  }
}
