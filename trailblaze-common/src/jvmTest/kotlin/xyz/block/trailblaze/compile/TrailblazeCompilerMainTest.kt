package xyz.block.trailblaze.compile

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [runCompiler] — the build-time CLI entry point that Gradle's `JavaExec`
 * task invokes. Covers argument parsing, exit-code mapping, and stream routing
 * (stdout for success, stderr for errors). Without these, an arg-parser regression
 * would ship silently and the build would mysteriously stop respecting `--input`
 * or stop returning the right exit code.
 */
class TrailblazeCompilerMainTest {

  private val workDir: File = createTempDirectory("trailblaze-compiler-main-test").toFile()
  private val originalOut: PrintStream = System.out
  private val originalErr: PrintStream = System.err
  private val capturedOut = ByteArrayOutputStream()
  private val capturedErr = ByteArrayOutputStream()

  init {
    System.setOut(PrintStream(capturedOut, /* autoFlush = */ true, Charsets.UTF_8))
    System.setErr(PrintStream(capturedErr, /* autoFlush = */ true, Charsets.UTF_8))
  }

  @AfterTest fun cleanup() {
    System.setOut(originalOut)
    System.setErr(originalErr)
    workDir.deleteRecursively()
  }

  @Test
  fun `runCompiler returns EXIT_USAGE when no args are given`() {
    val exit = runCompiler(emptyArray())
    assertEquals(2, exit)
    assertTrue(stderr().contains("--input and --output are required"), "stderr: ${stderr()}")
  }

  @Test
  fun `runCompiler returns EXIT_USAGE on unknown flag`() {
    val exit = runCompiler(arrayOf("--bogus", "value"))
    assertEquals(2, exit)
    assertTrue(stderr().contains("unexpected argument"), "stderr: ${stderr()}")
  }

  @Test
  fun `runCompiler returns EXIT_USAGE when a flag value is missing`() {
    val exit = runCompiler(arrayOf("--input"))
    assertEquals(2, exit)
    assertTrue(stderr().contains("missing value for --input"), "stderr: ${stderr()}")
  }

  @Test
  fun `runCompiler returns 0 on --help and prints usage to stderr`() {
    // `--help` is a user-asked-for action, not a usage error. Conventionally
    // help is exit 0 (e.g. `kotlinc --help`, `git --help`); a script piping
    // `if cli --help; then ...` should not see a failure code.
    val exit = runCompiler(arrayOf("--help"))
    assertEquals(0, exit)
    assertTrue(stderr().contains("Usage:"), "Help text expected in stderr; got: ${stderr()}")
  }

  @Test
  fun `runCompiler returns EXIT_USAGE when input directory does not exist`() {
    val nonexistent = File(workDir, "no-such-dir")
    val output = File(workDir, "out")
    val exit = runCompiler(arrayOf("--input", nonexistent.absolutePath, "--output", output.absolutePath))
    assertEquals(2, exit)
    assertTrue(
      stderr().contains("--input does not exist"),
      "stderr: ${stderr()}",
    )
  }

  @Test
  fun `runCompiler returns EXIT_OK and prints summary on a successful compile`() {
    // Exercises the happy path end-to-end: parses args, invokes the compiler,
    // prints the emitted-targets summary on stdout, exits 0. Uses a synthetic
    // pack with no `tool_sets:` references so the test doesn't depend on
    // classpath-discoverable toolset names.
    val packsDir = File(workDir, "packs").apply { mkdirs() }
    File(packsDir, "alpha").mkdirs()
    File(packsDir, "alpha/pack.yaml").writeText(
      """
      id: alpha
      target:
        display_name: Alpha
        platforms:
          android:
            app_ids: [com.example.alpha]
      """.trimIndent(),
    )
    val outputDir = File(workDir, "out")

    val exit = runCompiler(arrayOf("--input", packsDir.absolutePath, "--output", outputDir.absolutePath))

    assertEquals(0, exit, "Expected EXIT_OK, stderr=${stderr()} stdout=${stdout()}")
    assertTrue(stdout().contains("emitted 1 target(s)"), "stdout: ${stdout()}")
    assertTrue(File(outputDir, "alpha.yaml").exists(), "alpha.yaml should be on disk")
  }

  @Test
  fun `runCompiler returns EXIT_COMPILE_ERROR when compile fails`() {
    val packsDir = File(workDir, "packs").apply { mkdirs() }
    File(packsDir, "consumer").mkdirs()
    File(packsDir, "consumer/pack.yaml").writeText(
      """
      id: consumer
      dependencies:
        - missing-pack
      target:
        display_name: Consumer
        platforms:
          android:
            app_ids: [com.example]
      """.trimIndent(),
    )
    val outputDir = File(workDir, "out")

    val exit = runCompiler(arrayOf("--input", packsDir.absolutePath, "--output", outputDir.absolutePath))

    assertEquals(1, exit, "Expected EXIT_COMPILE_ERROR, stderr=${stderr()}")
    assertTrue(stderr().contains("compilation failed"), "stderr: ${stderr()}")
    assertTrue(stderr().contains("consumer"), "Error should name the failing pack; stderr: ${stderr()}")
  }

  @Test
  fun `runCompiler accepts short flag aliases -i and -o`() {
    val packsDir = File(workDir, "packs").apply { mkdirs() }
    File(packsDir, "alpha").mkdirs()
    File(packsDir, "alpha/pack.yaml").writeText(
      """
      id: alpha
      target:
        display_name: Alpha
        platforms:
          android:
            app_ids: [com.example.alpha]
      """.trimIndent(),
    )
    val outputDir = File(workDir, "out")

    val exit = runCompiler(arrayOf("-i", packsDir.absolutePath, "-o", outputDir.absolutePath))

    assertEquals(0, exit, "Short aliases should work; stderr=${stderr()} stdout=${stdout()}")
  }

  @Test
  fun `runCompiler errors when the same flag is provided twice`() {
    // Repeated flags are almost always a typo (`--input /a --input /b`) — silently
    // taking the last value would mask the user's intent. Surface as a usage error.
    val exit = runCompiler(
      arrayOf("--input", "/tmp/a", "--input", "/tmp/b", "--output", "/tmp/out"),
    )
    assertEquals(2, exit)
    assertTrue(
      stderr().contains("--input was specified more than once"),
      "stderr: ${stderr()}",
    )
  }

  private fun stdout(): String = capturedOut.toString(Charsets.UTF_8)
  private fun stderr(): String = capturedErr.toString(Charsets.UTF_8)
}
