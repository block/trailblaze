package xyz.block.trailblaze.cli

import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLClassLoader
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import picocli.CommandLine

/**
 * End-to-end behavior tests for `trailblaze waypoint validate`.
 *
 * Mirrors [WaypointCaptureExampleBehaviorTest] for the validate command — the
 * `--step` fail-fast contract applies to both commands and we want both pinned.
 *
 * Without this guard, a user pinning `validate --step 5` (forgetting `--session`)
 * would silently fall through to the auto-resolve sibling-example path and never
 * see step 5 — defeating the point of the flag.
 */
class WaypointValidateBehaviorTest {

  private val tempDirs = mutableListOf<File>()
  private lateinit var capturedErr: ByteArrayOutputStream
  private lateinit var capturedOut: ByteArrayOutputStream

  @BeforeTest
  fun setUpCapture() {
    CliOutCapture.install()
    capturedErr = ByteArrayOutputStream()
    capturedOut = ByteArrayOutputStream()
  }

  @AfterTest
  fun cleanup() {
    tempDirs.forEach { it.deleteRecursively() }
    tempDirs.clear()
  }

  @Test
  fun `validate fails fast when --step is given without --session`() {
    // Same fail-fast contract as capture-example. Set up a real waypoint on a
    // classpath pack so discovery doesn't error before the --step check fires.
    val classpathRoot = newTempDir()
    addClasspathPack(
      classpathRoot,
      packId = "myapp",
      waypoints = mapOf(
        "home.waypoint.yaml" to "id: \"myapp/home\"\ndescription: \"Test.\"",
      ),
    )
    val emptyRoot = newTempDir()

    val exitCode = withClasspathRoot(classpathRoot) {
      withCapture {
        execute(
          "waypoint",
          "validate",
          "--id",
          "myapp/home",
          "--step",
          "3",
          "--root",
          emptyRoot.absolutePath,
        )
      }
    }

    assertTrue(
      exitCode != CommandLine.ExitCode.OK,
      "validate must NOT exit OK when --step is given without --session",
    )
    val err = capturedErr.toString()
    assertTrue(
      "--step requires --session" in err,
      "expected fail-fast error explaining --step needs --session, got: $err",
    )
  }

  // ==========================================================================
  // Test infrastructure (parallel to WaypointCaptureExampleBehaviorTest).
  // ==========================================================================

  private fun newTempDir(): File =
    createTempDirectory(prefix = "validate-test-").toFile().also { tempDirs += it }

  private fun execute(vararg args: String): Int {
    val cliRoot = TrailblazeCliCommand(
      appProvider = { error("appProvider should not be invoked in --step fail-fast path") },
      configProvider = { error("configProvider should not be invoked in --step fail-fast path") },
    )
    val cl = CommandLine(cliRoot).setCaseInsensitiveEnumValuesAllowed(true)
    return cl.execute(*args)
  }

  private fun <T> withCapture(block: () -> T): T =
    CliOutCapture.withCapture(capturedOut, capturedErr, block)

  private fun addClasspathPack(
    root: File,
    packId: String,
    waypoints: Map<String, String>,
  ) {
    val packDir = File(root, "trailblaze-config/packs/$packId").apply { mkdirs() }
    val waypointDir = File(packDir, "waypoints").apply { mkdirs() }
    val waypointRefs = waypoints.keys.joinToString("\n") { "  - waypoints/$it" }
    File(packDir, "pack.yaml").writeText(
      """
      id: $packId
      target:
        display_name: $packId
      waypoints:
      $waypointRefs
      """.trimIndent(),
    )
    waypoints.forEach { (filename, content) ->
      File(waypointDir, filename).writeText(content)
    }
  }

  private fun <T> withClasspathRoot(classpathRoot: File, block: () -> T): T {
    val classLoader = URLClassLoader(arrayOf(classpathRoot.toURI().toURL()), null)
    val originalCcl = Thread.currentThread().contextClassLoader
    Thread.currentThread().contextClassLoader = classLoader
    try {
      return block()
    } finally {
      Thread.currentThread().contextClassLoader = originalCcl
      classLoader.close()
    }
  }
}
