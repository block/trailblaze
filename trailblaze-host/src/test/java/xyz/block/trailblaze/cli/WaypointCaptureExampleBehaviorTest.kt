package xyz.block.trailblaze.cli

import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLClassLoader
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import picocli.CommandLine

/**
 * End-to-end behavior tests for `trailblaze waypoint capture-example`.
 *
 * `CliHelpBaselineTest` covers help-text rendering. These tests cover the actual
 * resolution / validation paths that are easy to silently regress:
 *
 *  - `--step` without `--session` must fail fast (else a pinned step number is
 *    silently dropped and the user gets an arbitrary auto-search result).
 *  - When the matched waypoint id is bundled on the classpath only (i.e. no
 *    writable on-disk YAML), the user must get a pointed "pass --root <pack-dir>"
 *    error rather than the generic "Waypoint id not found" — this was the most
 *    user-visible defect in the initial PR draft.
 *  - Filesystem-backed waypoints get the normal not-found error when the id
 *    really doesn't exist anywhere.
 *
 * Tests run the command via [CommandLine.execute] with stdout/stderr captured via
 * [CliOutCapture]. We deliberately avoid invoking the full daemon path: the
 * behaviors under test all fire BEFORE `effectiveLogsDir()` is called, so the
 * config provider is never reached.
 */
class WaypointCaptureExampleBehaviorTest {

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

  // ==========================================================================
  // --step requires --session (fail-fast)
  // ==========================================================================

  @Test
  fun `capture-example fails fast when --step is given without --session`() {
    // Concrete waypoint must exist on disk for findWaypointFile to succeed; otherwise
    // the test would fail on the wrong code path. Set up a minimal workspace pack.
    val root = newRootWithWaypoint(
      yamlFilename = "home.waypoint.yaml",
      waypointId = "myapp/home",
    )

    val exitCode = withCapture {
      execute("waypoint", "capture-example", "--id", "myapp/home", "--step", "5", "--root", root.absolutePath)
    }

    assertEquals(CommandLine.ExitCode.USAGE, exitCode, "expected USAGE exit code for --step without --session")
    val err = capturedErr.toString()
    assertTrue(
      "--step requires --session" in err,
      "expected fail-fast error explaining --step needs --session, got: $err",
    )
  }

  @Test
  fun `capture-example without --step or --session does NOT fail fast — auto-search runs`() {
    // Inverse: when --step is absent, the no-flags magic auto-search path is allowed
    // to run. We can't drive a successful auto-search here (no real session logs), but
    // we can verify that the fail-fast error from the previous test does NOT appear,
    // which proves the validation is correctly scoped to the --step case only.
    val root = newRootWithWaypoint(
      yamlFilename = "home.waypoint.yaml",
      waypointId = "myapp/home",
    )

    withCapture {
      execute("waypoint", "capture-example", "--id", "myapp/home", "--root", root.absolutePath)
    }

    val err = capturedErr.toString()
    assertTrue(
      "--step requires --session" !in err,
      "fail-fast error should NOT fire when --step is absent, got: $err",
    )
  }

  // ==========================================================================
  // Classpath-only waypoint detection
  // ==========================================================================

  @Test
  fun `capture-example with classpath-only waypoint emits pointed --root hint not generic not-found`() {
    // The capture-example user case that was silently broken: --target <pack> for
    // a classpath-bundled pack. WaypointDiscovery sees the id (it's on the classpath
    // via WaypointDiscovery.discover); WaypointLoader.discover (filesystem walk) does
    // not. Without the targeted error, the user gets "Waypoint id not found" with no
    // hint that the limitation is capture-example-specific. With the fix in place,
    // they get the pack-source-dir suggestion.
    //
    // Inject a fake classpath pack via URLClassLoader (same pattern as
    // WaypointDiscoveryTest) so the test doesn't depend on which OSS packs happen
    // to be on the test classpath.
    val classpathRoot = newTempDir()
    addClasspathPack(
      classpathRoot,
      packId = "fakeapp",
      waypoints = mapOf(
        "home.waypoint.yaml" to "id: \"fakeapp/home\"\ndescription: \"Bundled.\"",
      ),
    )
    val emptyRoot = newTempDir() // --root has nothing on it

    val exitCode = withClasspathRoot(classpathRoot) {
      withCapture {
        execute(
          "waypoint",
          "capture-example",
          "--id",
          "fakeapp/home",
          "--root",
          emptyRoot.absolutePath,
        )
      }
    }

    assertEquals(CommandLine.ExitCode.USAGE, exitCode)
    val err = capturedErr.toString()
    assertTrue(
      "bundled on the classpath only" in err,
      "expected classpath-only diagnostic, got: $err",
    )
    assertTrue(
      "Pass --root" in err,
      "expected actionable --root suggestion in error, got: $err",
    )
    assertTrue(
      "Waypoint id not found" !in err,
      "should NOT fall through to the generic not-found error when the id is on classpath, got: $err",
    )
  }

  @Test
  fun `capture-example with truly-unknown id falls through to generic not-found`() {
    val emptyRoot = newTempDir()

    val exitCode = withCapture {
      execute(
        "waypoint",
        "capture-example",
        "--id",
        "this/id/does/not/exist/anywhere",
        "--root",
        emptyRoot.absolutePath,
      )
    }

    assertEquals(CommandLine.ExitCode.USAGE, exitCode)
    val err = capturedErr.toString()
    assertTrue("Waypoint id not found" in err, "expected generic not-found error, got: $err")
    assertTrue(
      "bundled on the classpath" !in err,
      "should NOT claim classpath presence for an id that's nowhere, got: $err",
    )
  }

  // ==========================================================================
  // Test infrastructure.
  // ==========================================================================

  private fun newTempDir(): File {
    val dir = createTempDirectory(prefix = "capture-example-test-").toFile()
    tempDirs += dir
    return dir
  }

  private fun newRootWithWaypoint(yamlFilename: String, waypointId: String): File {
    val root = newTempDir()
    File(root, yamlFilename).writeText(
      """
      id: "$waypointId"
      description: "Test waypoint."
      """.trimIndent(),
    )
    return root
  }

  /**
   * Drives the command through the real picocli tree so `@ParentCommand` wiring
   * (WaypointCommand → WaypointCaptureExampleCommand) is exercised. The
   * `appProvider`/`configProvider` lambdas throw on access — the behaviors
   * we cover here all decide BEFORE [effectiveLogsDir] is reached, so the
   * provider never gets called. If a future change reaches it, the test will
   * fail loudly with the lambda's error instead of silently passing.
   */
  private fun execute(vararg args: String): Int {
    val cliRoot = TrailblazeCliCommand(
      appProvider = { error("appProvider should not be invoked in capture-example fail-fast/classpath-detect paths") },
      configProvider = { error("configProvider should not be invoked in capture-example fail-fast/classpath-detect paths") },
    )
    val cl = CommandLine(cliRoot).setCaseInsensitiveEnumValuesAllowed(true)
    return cl.execute(*args)
  }

  private fun <T> withCapture(block: () -> T): T =
    CliOutCapture.withCapture(capturedOut, capturedErr, block)

  /** Drops a fake classpath pack at `<root>/trailblaze-config/packs/<packId>/...`. */
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

  /**
   * Sets the context class loader to a [URLClassLoader] rooted at [classpathRoot] for
   * the duration of [block]. The CLI's `WaypointDiscovery` reads classpath-bundled
   * packs via `Thread.currentThread().contextClassLoader`, so this lets tests inject
   * synthetic packs without polluting the test JVM's actual classpath.
   */
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
