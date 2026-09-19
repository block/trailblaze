package xyz.block.trailblaze.scripting

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.runQuiet

/**
 * Which STREAM the analyzer's diagnostics land on, which is a contract and not a detail: the
 * analyzer runs during tool discovery on nearly every CLI command, so a diagnostic on stdout ends
 * up inside whatever a caller piped that command into (`trailblaze config | jq` parsed one for a
 * release).
 *
 * [Console.info] is reserved for output the user must always see and is neither suppressible nor
 * redirectable, so it is the wrong level for every message here. The rest of the analyzer suite
 * asserts extracted files and resolver results and would stay green if these moved back to `info`
 * — hence separate cases for one success path and two of the degradation paths.
 */
class AnalyzerDiagnosticStreamRoutingTest {

  private val tempDirs = mutableListOf<File>()

  /** `Console` caches `System.out` into private fields at class-init time, so `System.setOut`
   *  alone would not be observed — the cached fields have to be re-pointed. Same approach as
   *  `ConsoleTest` in `:trailblaze-models`. */
  private val stdout = ByteArrayOutputStream()
  private val outField = Console::class.java.getDeclaredField("out").apply { isAccessible = true }
  private val userOutField = Console::class.java.getDeclaredField("userOut").apply { isAccessible = true }
  private lateinit var originalOut: PrintStream
  private lateinit var originalUserOut: PrintStream
  private var wasQuiet: Boolean = false

  @BeforeTest
  fun captureStdout() {
    val capture = PrintStream(stdout, /* autoFlush = */ true, Charsets.UTF_8)
    originalOut = outField.get(Console) as PrintStream
    originalUserOut = userOutField.get(Console) as PrintStream
    outField.set(Console, capture)
    userOutField.set(Console, capture)
    // Quiet mode is a JVM-global flag another test in this module may have left on; the
    // non-quiet control below would then assert against a state it didn't choose.
    wasQuiet = Console.isQuietMode()
    Console.disableQuietMode()
  }

  @AfterTest
  fun cleanup() {
    // Restore the streams BEFORE the quiet flag: `enableQuietMode` points `userOut` at whatever
    // `out` currently is, so re-arming it first would pin the capture stream into `Console`.
    outField.set(Console, originalOut)
    userOutField.set(Console, originalUserOut)
    if (wasQuiet) Console.enableQuietMode() else Console.disableQuietMode()
    tempDirs.forEach { it.deleteRecursively() }
    tempDirs.clear()
  }

  private fun tempRoot(): File = createTempDirectory("analyzer-streams").toFile().also { tempDirs += it }

  private fun stdoutText(): String = stdout.toString(Charsets.UTF_8)

  private fun withStdErrCaptured(block: () -> Unit): String {
    val err = ByteArrayOutputStream()
    val originalErr = System.err
    System.setErr(PrintStream(err, /* autoFlush = */ true, Charsets.UTF_8))
    try {
      block()
    } finally {
      System.setErr(originalErr)
    }
    return err.toString(Charsets.UTF_8)
  }

  @Test
  fun `the chosen-shim diagnostic is suppressible, so a quiet command's stdout stays clean`() {
    // Control first: the line must actually be emitted, or the quiet-mode assertion below would
    // pass for a build that stopped logging it at all.
    ScriptedToolDefinitionAnalyzer.extractBundledAnalyzerShim(
      cacheRoot = File(tempRoot(), "analyzer"),
      shimResource = { "// self-contained shim bundle\n".toByteArray() },
      tsLibArchive = { null },
    )
    assertTrue(
      "using JAR-bundled analyzer shim" in stdoutText(),
      "the chosen-shim breadcrumb should be visible on a verbose (non-quiet) command: ${stdoutText()}",
    )
    stdout.reset()

    Console.runQuiet {
      ScriptedToolDefinitionAnalyzer.extractBundledAnalyzerShim(
        cacheRoot = File(tempRoot(), "analyzer"),
        shimResource = { "// self-contained shim bundle\n".toByteArray() },
        tsLibArchive = { null },
      )
    }

    assertFalse(
      "ScriptedToolDefinitionAnalyzer" in stdoutText(),
      "which shim path won is diagnostics: quiet mode must drop it, which `Console.info` cannot. " +
        "Got: ${stdoutText()}",
    )
  }

  @Test
  fun `an empty bundled shim resource is reported on stderr, never on stdout`() {
    // A stripped or corrupt build silently loses typed-tool analysis, so this one stays visible
    // even in quiet mode — but on stderr, where a `| jq` consumer never sees it.
    val err = withStdErrCaptured {
      Console.runQuiet {
        ScriptedToolDefinitionAnalyzer.extractBundledAnalyzerShim(
          cacheRoot = File(tempRoot(), "analyzer"),
          shimResource = { ByteArray(0) },
          tsLibArchive = { null },
        )
      }
    }

    assertTrue(
      "bundled analyzer shim resource is empty" in err,
      "a degradation that disables typed-tool analysis must stay visible on stderr: $err",
    )
    assertFalse(
      "ScriptedToolDefinitionAnalyzer" in stdoutText(),
      "no analyzer diagnostic may reach stdout. Got: ${stdoutText()}",
    )
  }

  @Test
  fun `an ignored TRAILBLAZE_SDK_DIR override is reported on stderr, never on stdout`() {
    val notAnSdk = tempRoot()

    val err = withStdErrCaptured {
      Console.runQuiet {
        ScriptedToolDefinitionAnalyzer.resolveSdkDir(
          explicitSdkDir = notAnSdk.absolutePath,
          startDir = tempRoot(),
          requireAnalyzerTooling = true,
          bundledFallback = { null },
        )
      }
    }

    assertTrue(
      "TRAILBLAZE_SDK_DIR=${notAnSdk.absolutePath}" in err,
      "an override being ignored must stay visible on stderr: $err",
    )
    assertFalse(
      "ScriptedToolDefinitionAnalyzer" in stdoutText(),
      "no analyzer diagnostic may reach stdout. Got: ${stdoutText()}",
    )
  }
}
