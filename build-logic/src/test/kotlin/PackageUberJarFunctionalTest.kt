import java.io.File
import java.util.jar.JarFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome

/**
 * Contract of `packageUberJarForCurrentOS` and `run` ([registerPackageUberJarForCurrentOs],
 * [registerCliRunTask]): the shipped CLI JAR's location, manifest and duplicate handling, stale-JAR
 * pruning, and the Gradle-mode launch path. Each of these is something the launcher, install
 * scripts or release tasks depend on, and none of them fails loudly when it regresses — a wrong
 * duplicate winner or a missing `run` task only shows up when someone runs the CLI.
 */
class PackageUberJarFunctionalTest {

  private val tempDirs = mutableListOf<File>()

  @AfterTest
  fun cleanupTempDirs() {
    tempDirs.forEach { it.deleteRecursively() }
    tempDirs.clear()
  }

  @Test
  fun `packages the app and its archives into one runnable JAR where scripts look for it`() {
    val projectDir = newFixtureProject()

    runner(projectDir, "packageUberJarForCurrentOS").build()

    JarFile(packagedJar(projectDir)).use { jar ->
      assertEquals("com.example.Main", jar.manifest.mainAttributes.getValue("Main-Class"))
      assertTrue(jar.getEntry("com/example/Main.class") != null, "the app's own classes")
      assertEquals("a-only", jar.read("a-only.txt"), "a dependency's entries are flattened in")
      assertEquals("app", jar.read("shared.txt"), "the app's copy of a duplicate wins")
      assertEquals(
        "a-impl",
        jar.read("META-INF/services/com.example.Other"),
        "colliding service files keep the first copy rather than merging",
      )
      assertNull(jar.getEntry("only.pom"), "non-archive classpath files are not copied in")
    }
  }

  @Test
  fun `prunes stale JARs even when packaging is up to date, on a reused configuration cache`() {
    val projectDir = newFixtureProject()
    runner(projectDir, "packageUberJarForCurrentOS", "--configuration-cache").build()

    // Stale siblings beside an UP-TO-DATE current JAR is exactly the state the prune task exists
    // for: the dev launcher picks the newest JAR in this directory.
    val jarsDir = packagedJar(projectDir).parentFile
    val stale = File(jarsDir, "Trailblaze-macos-arm64-0.0.1.jar").apply { writeText("stale") }
    val marker = File(jarsDir, ".blaze-source-hash").apply { writeText("hash") }

    val result = runner(projectDir, "packageUberJarForCurrentOS", "--configuration-cache").build()

    assertTrue(result.output.contains("Reusing configuration cache"), result.output)
    assertEquals(TaskOutcome.UP_TO_DATE, result.task(":packageUberJarForCurrentOS")?.outcome)
    assertFalse(stale.exists(), "the stale JAR must be pruned")
    assertTrue(marker.exists(), "the dev launcher's staleness marker is not a JAR and must survive")
    assertTrue(packagedJar(projectDir).exists(), "the current JAR must survive")
  }

  @Test
  fun `run launches the main class on the app plus runtime classpath`() {
    // `./trailblaze --gradle`, and the launcher's fallback when the JAR build fails, invoke
    // `<module>:run --args=...`.
    val result = runner(newFixtureProject(), "run", "--args=hello").build()

    assertTrue(result.output.contains("ARGS=hello DEP=a-only"), result.output)
  }

  // ---- Fixtures ----

  /**
   * An app with one class and two resources, packaged against two dependency JARs and a POM-only
   * file. The app and `dep-a` both ship `shared.txt`; `dep-a` and `dep-b` both ship the same service
   * file, so the winners say which duplicate rule ran.
   */
  private fun newFixtureProject(): File {
    val dir = createTempDirectory("trailblaze-uber-jar-functional").toFile().also(tempDirs::add)
    File(dir, "settings.gradle.kts").writeText("""rootProject.name = "fixture"""")
    File(dir, "build.gradle.kts")
      .writeText(
        """
        plugins {
          java
          id("trailblaze.build-logic-classpath")
        }
        val deps = configurations.create("deps")
        dependencies { add("deps", files("libs/dep-a.jar", "libs/dep-b.jar", "libs/only.pom")) }
        registerPackageUberJarForCurrentOs(
          mainClass = "com.example.Main",
          appJar = tasks.named<Jar>("jar"),
          runtimeClasspath = deps,
          version = "1.2.3",
        )
        registerCliRunTask(
          mainClass = "com.example.Main",
          appJar = tasks.named<Jar>("jar"),
          runtimeClasspath = deps,
        )
        """
          .trimIndent()
      )
    File(dir, "src/main/java/com/example/Main.java").write(
      """
      package com.example;

      public class Main {
        public static void main(String[] args) throws Exception {
          try (java.io.InputStream in = Main.class.getResourceAsStream("/a-only.txt")) {
            System.out.println("ARGS=" + String.join(",", args) + " DEP=" + new String(in.readAllBytes()));
          }
        }
      }
      """
        .trimIndent()
    )
    File(dir, "src/main/resources/shared.txt").write("app")
    writeZip(
      File(dir, "libs/dep-a.jar"),
      "shared.txt" to "dep-a",
      "a-only.txt" to "a-only",
      "META-INF/services/com.example.Other" to "a-impl",
    )
    writeZip(File(dir, "libs/dep-b.jar"), "META-INF/services/com.example.Other" to "b-impl")
    File(dir, "libs/only.pom").write("<project/>")
    return dir
  }

  private fun packagedJar(projectDir: File): File =
    File(projectDir, "build/compose/jars/Trailblaze-${currentOsTargetId()}-1.2.3.jar")

  private fun File.write(text: String) {
    parentFile.mkdirs()
    writeText(text)
  }

  private fun writeZip(file: File, vararg entries: Pair<String, String>) {
    file.parentFile.mkdirs()
    ZipOutputStream(file.outputStream()).use { zip ->
      entries.forEach { (name, text) ->
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray())
        zip.closeEntry()
      }
    }
  }

  private fun JarFile.read(name: String): String? =
    getEntry(name)?.let { getInputStream(it).use { s -> s.readBytes().decodeToString() } }

  private fun runner(projectDir: File, vararg args: String): GradleRunner =
    GradleRunner.create()
      .withProjectDir(projectDir)
      .withArguments(*args)
      .withPluginClasspath()
      .forwardOutput()
}
