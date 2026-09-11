package xyz.block.trailblaze.cli

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import picocli.CommandLine
import xyz.block.trailblaze.util.Console
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the verdicts `trailblaze strings diff` reports, which are the part a CI step reads.
 *
 * The exit code alone is not the whole contract here: "no text changed" and "no text changed in
 * the part we could see" are different claims, and only one of them is a clean result.
 */
class StringsDiffCommandTest {

  @get:Rule
  val tmp = TemporaryFolder()

  private fun screenLine(
    stepIndex: Int,
    strings: String,
    partial: Boolean = false,
  ): String = buildString {
    append("""{"v":1,"kind":"screen","stepIndex":$stepIndex,"captureId":"shot-$stepIndex.png",""")
    append(""""logType":"AgentDriverLog","timestamp":"2026-09-09T17:04:11Z",""")
    append(""""deviceWidth":1080,"deviceHeight":1920,"screenId":"id-$stepIndex",""")
    if (partial) append(""""partialCapture":true,""")
    append(""""strings":[$strings]}""")
  }

  /** `source` is the enum's `@SerialName`, which is camelCase and not the constant's own name. */
  private fun str(text: String, source: String = "text") =
    """{"text":"$text","source":"$source"}"""

  private fun file(name: String, locale: String, vararg screens: String): File =
    File(tmp.root, name).apply {
      writeText(
        (listOf("""{"v":1,"kind":"run","session":"s","locale":"$locale"}""") + screens)
          .joinToString("\n", postfix = "\n"),
      )
    }

  private data class Run(val exitCode: Int, val output: String)

  /**
   * [Console] caches its user-output stream at class-init, so `System.setOut` alone never reaches
   * [Console.info]. `enableJsonMode` is the supported redirect: it points user output at
   * `System.err`, which this has already replaced, and `disableJsonMode` puts the real one back.
   */
  private fun diff(vararg args: String): Run {
    val captured = ByteArrayOutputStream()
    val originalOut = System.out
    val originalErr = System.err
    System.setOut(PrintStream(captured, true))
    System.setErr(PrintStream(captured, true))
    Console.enableJsonMode()
    try {
      val root = CommandLine(
        TrailblazeCliCommand(
          appProvider = { error("strings diff reads two files and must not boot the app") },
          configProvider = { error("strings diff takes explicit paths and reads no config") },
        ),
      ).setCaseInsensitiveEnumValuesAllowed(true)
      installTrailblazeExceptionHandlers(root)
      val code = root.execute("strings", "diff", *args)
      return Run(code, captured.toString())
    } finally {
      Console.disableJsonMode()
      System.setOut(originalOut)
      System.setErr(originalErr)
    }
  }

  /**
   * A caption that moved from `text` to `contentDescription` is a change, but the copy is still
   * untranslated. Matching on the text-and-property pair drops it from the one report whose whole
   * job is to find it.
   */
  @Test
  fun `untranslated text that only moved property is still reported`() {
    val a = file("en.ndjson", "en", screenLine(0, str("Checkout")))
    val b = file("es.ndjson", "es", screenLine(0, str("Checkout", "contentDescription")))

    val run = diff(a.path, b.path, "--untranslated")

    assertEquals(TrailblazeExitCode.ASSERTION_FAILED.code, run.exitCode, run.output)
    assertTrue(run.output.contains("= Checkout"), run.output)
  }

  @Test
  fun `genuinely translated text passes the untranslated check`() {
    val a = file("en.ndjson", "en", screenLine(0, str("Checkout")))
    val b = file("es.ndjson", "es", screenLine(0, str("Pagar")))

    assertEquals(TrailblazeExitCode.SUCCESS.code, diff(a.path, b.path, "--untranslated").exitCode)
  }

  /**
   * A partial capture means absent strings prove nothing, so an empty change set is not evidence
   * that nothing changed. Still exits zero: a truncated Android tree is a capture-quality problem,
   * and failing on it would make this unusable as a CI gate for a reason unrelated to copy.
   */
  @Test
  fun `a partial capture with no observed changes does not get a clean verdict`() {
    val a = file("before.ndjson", "en", screenLine(0, str("Checkout"), partial = true))
    val b = file("after.ndjson", "en", screenLine(0, str("Checkout")))

    val run = diff(a.path, b.path)

    assertEquals(TrailblazeExitCode.SUCCESS.code, run.exitCode, run.output)
    assertFalse(run.output.contains("✅"), run.output)
    assertTrue(run.output.contains("partial capture"), run.output)
    assertTrue(run.output.contains("step 0"), run.output)
  }

  @Test
  fun `a complete capture with no changes still gets a clean verdict`() {
    val a = file("before.ndjson", "en", screenLine(0, str("Checkout")))
    val b = file("after.ndjson", "en", screenLine(0, str("Checkout")))

    val run = diff(a.path, b.path)

    assertEquals(TrailblazeExitCode.SUCCESS.code, run.exitCode, run.output)
    assertTrue(run.output.contains("✅"), run.output)
  }

  /** A dropped record would resurface as path divergence, which reads as a finding about the app. */
  @Test
  fun `an unrecognized record kind is a misuse that names the line`() {
    val a = File(tmp.root, "bad.ndjson").apply {
      writeText(
        """
        {"v":1,"kind":"run","session":"s"}
        {"v":1,"kind":"scren","stepIndex":0}
        """.trimIndent(),
      )
    }
    val b = file("good.ndjson", "en", screenLine(0, str("Checkout")))

    val run = diff(a.path, b.path)

    assertEquals(TrailblazeExitCode.MISUSE.code, run.exitCode, run.output)
    assertTrue(run.output.contains("line 2"), run.output)
    assertTrue(run.output.contains("bad.ndjson"), "the message must name which file: ${run.output}")
  }
}
