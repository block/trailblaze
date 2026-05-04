package xyz.block.trailblaze.llm.config

import java.io.File
import java.net.URLClassLoader
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression test for [ClasspathResourceDiscovery.discoverFilenamesRecursive] under the
 * `file:` classpath protocol. Earlier the file branch matched [suffix] against
 * `File.name` only, so a caller passing `suffix = "/pack.yaml"` (to require nested
 * pack.yaml under a pack-id subdirectory, mirroring jar-branch semantics) silently got
 * zero matches whenever the classpath was an exploded directory rather than a JAR. CI
 * caught this; this test pins the relative-path semantics so it cannot regress.
 */
class ClasspathResourceDiscoveryRecursiveTest {

  private val tempDirs = mutableListOf<File>()

  @AfterTest
  fun cleanup() {
    tempDirs.forEach { it.deleteRecursively() }
    tempDirs.clear()
  }

  @Test
  fun `recursive discovery matches suffix against relative path on file protocol`() {
    val rootDir = newTempDir()
    val packsDir = File(rootDir, "trailblaze-config/packs").apply { mkdirs() }
    File(packsDir, "alpha").mkdirs()
    File(packsDir, "alpha/pack.yaml").writeText("id: alpha")
    File(packsDir, "beta").mkdirs()
    File(packsDir, "beta/pack.yaml").writeText("id: beta")
    // A flat sibling YAML directly under packs/ — must NOT match the "/pack.yaml" suffix.
    File(packsDir, "siblings.yaml").writeText("not-a-pack")

    withClasspathRoot(rootDir) {
      val results = ClasspathResourceDiscovery.discoverFilenamesRecursive(
        directoryPath = "trailblaze-config/packs",
        suffix = "/pack.yaml",
      )
      assertEquals(setOf("alpha/pack.yaml", "beta/pack.yaml"), results)
    }
  }

  @Test
  fun `recursive discovery still works with bare-suffix matches like dot-yaml`() {
    val rootDir = newTempDir()
    val packsDir = File(rootDir, "trailblaze-config/packs").apply { mkdirs() }
    File(packsDir, "alpha").mkdirs()
    File(packsDir, "alpha/pack.yaml").writeText("id: alpha")
    File(packsDir, "siblings.yaml").writeText("not-a-pack")

    withClasspathRoot(rootDir) {
      val results = ClasspathResourceDiscovery.discoverFilenamesRecursive(
        directoryPath = "trailblaze-config/packs",
        suffix = ".yaml",
      )
      // Bare suffix matches both nested and flat entries — relative-path semantics
      // do not change the meaning of suffixes that don't include `/`.
      assertTrue("alpha/pack.yaml" in results)
      assertTrue("siblings.yaml" in results)
    }
  }

  @Test
  fun `recursive discovery returns empty when directory is missing`() {
    val rootDir = newTempDir()
    withClasspathRoot(rootDir) {
      val results = ClasspathResourceDiscovery.discoverFilenamesRecursive(
        directoryPath = "trailblaze-config/packs",
        suffix = "/pack.yaml",
      )
      assertEquals(emptySet(), results)
    }
  }

  private fun newTempDir(): File =
    createTempDirectory("classpath-recursive-test").toFile().also { tempDirs += it }

  private fun withClasspathRoot(root: File, block: () -> Unit) {
    val classLoader = URLClassLoader(arrayOf(root.toURI().toURL()), null)
    val originalCcl = Thread.currentThread().contextClassLoader
    Thread.currentThread().contextClassLoader = classLoader
    try {
      block()
    } finally {
      Thread.currentThread().contextClassLoader = originalCcl
      classLoader.close()
    }
  }
}
