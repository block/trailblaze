package xyz.block.trailblaze.util

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URLClassLoader
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/**
 * `bundledLabels()` names the detector APKs a build actually ships, and it is read out in the
 * diagnostic a user sees when turbo finds nothing bundled for their app. The labels therefore have
 * to be discovered wherever the resources really sit.
 *
 * The case that matters: the detector APKs are packaged by a DIFFERENT module than the one holding
 * `InProcessIdleApkInstaller`, so they share an archive only in the shaded CLI. Every other layout
 * — IDE, unit tests, any non-shadow run — puts them on a classpath entry the installer class was
 * not loaded from. Enumerating only the class's own code source silently answers "none bundled"
 * there, which reads to the user as "this build has no detector for your app" when it has one.
 */
class InProcessIdleApkInstallerBundledLabelsTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  @Test
  fun `finds detectors in a jar the installer class was not loaded from`() {
    val loader = loaderOverJarContaining(
      "apks/inprocess-idle/trailblaze-inprocess-idle-alpha.apk",
      "apks/inprocess-idle/trailblaze-inprocess-idle-beta.apk",
    )

    assertEquals(
      listOf("alpha", "beta"),
      InProcessIdleApkInstaller.bundledLabelsFrom(loader),
    )
  }

  @Test
  fun `finds detectors in a jar that carries an explicit directory entry`() {
    // What Gradle's Jar task produces, and the only shape `getResources(<dir>)` can resolve. The
    // test above covers the other shape: file entries with no directory entry, which resolves
    // nothing through `getResources` and has to come off the classpath entry instead.
    val loader = loaderOverJarContaining(
      "apks/",
      "apks/inprocess-idle/",
      "apks/inprocess-idle/trailblaze-inprocess-idle-delta.apk",
    )

    assertEquals(listOf("delta"), InProcessIdleApkInstaller.bundledLabelsFrom(loader))
  }

  @Test
  fun `finds detectors in an exploded resources directory`() {
    val resourcesRoot = tempFolder.newFolder("exploded")
    File(resourcesRoot, "apks/inprocess-idle").apply {
      mkdirs()
      File(this, "trailblaze-inprocess-idle-gamma.apk").writeText("apk")
    }
    val loader = URLClassLoader(arrayOf(resourcesRoot.toURI().toURL()), null)

    assertEquals(listOf("gamma"), InProcessIdleApkInstaller.bundledLabelsFrom(loader))
  }

  @Test
  fun `labels are the applicationId flavors, sorted and de-duplicated`() {
    val loader = loaderOverJarContaining(
      "apks/inprocess-idle/trailblaze-inprocess-idle-zulu.apk",
      "apks/inprocess-idle/trailblaze-inprocess-idle-alpha.apk",
    )

    assertEquals(listOf("alpha", "zulu"), InProcessIdleApkInstaller.bundledLabelsFrom(loader))
  }

  @Test
  fun `ignores entries in the detector dir that are not detector apks`() {
    val loader = loaderOverJarContaining(
      "apks/inprocess-idle/trailblaze-inprocess-idle-real.apk",
      "apks/inprocess-idle/README.txt",
      "apks/inprocess-idle/something-else.apk",
      "apks/inprocess-idle/trailblaze-inprocess-idle-nope.txt",
    )

    assertEquals(listOf("real"), InProcessIdleApkInstaller.bundledLabelsFrom(loader))
  }

  @Test
  fun `a classpath with no detector resources reports none rather than failing`() {
    val loader = loaderOverJarContaining("some/other/resource.txt")

    assertEquals(emptyList<String>(), InProcessIdleApkInstaller.bundledLabelsFrom(loader))
  }

  @Test
  fun `an unreadable classpath reports none rather than throwing`() {
    assertEquals(emptyList<String>(), InProcessIdleApkInstaller.bundledLabelsFrom(null))
  }

  private fun loaderOverJarContaining(vararg entryNames: String): URLClassLoader {
    val jar = tempFolder.newFile("detectors-${entryNames.size}-${entryNames.hashCode()}.jar")
    JarOutputStream(jar.outputStream()).use { out ->
      entryNames.forEach { name ->
        out.putNextEntry(JarEntry(name))
        out.write("payload".toByteArray())
        out.closeEntry()
      }
    }
    // Null parent: the jar is the ENTIRE classpath this loader can see, so a passing assertion
    // cannot be satisfied by whatever the test runtime happens to carry.
    return URLClassLoader(arrayOf(jar.toURI().toURL()), null)
  }
}
