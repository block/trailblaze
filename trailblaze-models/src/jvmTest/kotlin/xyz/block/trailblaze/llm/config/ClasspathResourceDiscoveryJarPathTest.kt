package xyz.block.trailblaze.llm.config

import java.io.File
import java.net.URLClassLoader
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A JAR's resources are found wherever it is installed, including under a directory whose name
 * a URL has to percent-encode (`Trailblaze[ci]`, `My Tools`).
 */
class ClasspathResourceDiscoveryJarPathTest {

  private val tempDirs = mutableListOf<File>()

  @AfterTest
  fun cleanup() {
    tempDirs.forEach { it.deleteRecursively() }
    tempDirs.clear()
  }

  @Test
  fun `a jar under a directory with brackets and a space has its resources discovered`() {
    val jar = jarWith(
      dirName = "Trailblaze[ci] tools",
      "trails/config/toolsets/web_core.toolset.yaml",
      "trails/config/trailmaps/wikipedia/trailmap.yaml",
    )

    withClasspath(jar) {
      assertEquals(
        setOf("web_core.toolset.yaml"),
        ClasspathResourceDiscovery.discoverFilenames("trails/config/toolsets", ".toolset.yaml"),
      )
      assertEquals(
        setOf("wikipedia/trailmap.yaml"),
        ClasspathResourceDiscovery.discoverFilenamesRecursive("trails/config/trailmaps", "/trailmap.yaml"),
      )
    }
  }

  private fun jarWith(dirName: String, vararg entries: String): File {
    val dir = File(createTempDirectory("classpath-jar-path-test").toFile().also { tempDirs += it }, dirName)
    dir.mkdirs()
    val jar = File(dir, "trailblaze.jar")
    // Directory entries too, as a built JAR has them: a classloader only resolves a directory
    // resource through one.
    val dirs = entries.flatMap { name ->
      name.split('/').dropLast(1).runningReduce { parent, child -> "$parent/$child" }.map { "$it/" }
    }.distinct()
    JarOutputStream(jar.outputStream()).use { out ->
      dirs.forEach { name ->
        out.putNextEntry(ZipEntry(name))
        out.closeEntry()
      }
      entries.forEach { name ->
        out.putNextEntry(ZipEntry(name))
        out.write("id: x".toByteArray())
        out.closeEntry()
      }
    }
    return jar
  }

  private fun withClasspath(jar: File, block: () -> Unit) {
    val classLoader = URLClassLoader(arrayOf(jar.toURI().toURL()), null)
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
