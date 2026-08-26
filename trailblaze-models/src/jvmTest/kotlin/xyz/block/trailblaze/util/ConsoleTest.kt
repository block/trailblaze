package xyz.block.trailblaze.util

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Tests for [Console]'s output-routing toggles on the JVM: quiet mode (drop [Console.log])
 * and json mode (move [Console.log] and [Console.info] to stderr so stdout carries only a
 * machine-readable document).
 *
 * `Console` is an `expect object` with global mutable state (shared flags and cached output
 * streams), so tests must save/restore `System.out` in `@Before`/`@After` and explicitly clear
 * both modes between cases to avoid order-dependent failures.
 */
class ConsoleTest {

  private lateinit var originalOutField: PrintStream
  private lateinit var originalUserOutField: PrintStream
  private lateinit var captured: ByteArrayOutputStream

  @Before fun setUp() {
    // Clear json mode before snapshotting the fields — a case that left it on would otherwise
    // make us record stderr as the "original" stream and restore that in tearDown.
    Console.disableJsonMode()
    // `Console` caches `System.out` into a private `out` field at class-init
    // time, so `System.setOut` is not enough — we have to re-point the cached
    // field via reflection. Tolerate either order (some other test in the
    // module may have already loaded the class).
    captured = ByteArrayOutputStream()
    val newStream = PrintStream(captured, /* autoFlush = */ true, Charsets.UTF_8)
    originalOutField = Console::class.java.getDeclaredField("out").apply { isAccessible = true }
      .get(Console) as PrintStream
    originalUserOutField = Console::class.java.getDeclaredField("userOut").apply { isAccessible = true }
      .get(Console) as PrintStream
    Console::class.java.getDeclaredField("out").apply { isAccessible = true }.set(Console, newStream)
    Console::class.java.getDeclaredField("userOut").apply { isAccessible = true }.set(Console, newStream)
    Console.disableQuietMode()
  }

  @After fun tearDown() {
    Console.disableQuietMode()
    Console.disableJsonMode()
    Console::class.java.getDeclaredField("out").apply { isAccessible = true }.set(Console, originalOutField)
    Console::class.java.getDeclaredField("userOut").apply { isAccessible = true }.set(Console, originalUserOutField)
  }

  @Test fun `log writes to stdout by default`() {
    Console.log("visible")
    assertTrue(captured.toString(Charsets.UTF_8).contains("visible"))
  }

  @Test fun `enableQuietMode suppresses log output`() {
    Console.enableQuietMode()
    Console.log("should not appear")
    assertFalse(
      captured.toString(Charsets.UTF_8).contains("should not appear"),
      "log() must not write after enableQuietMode",
    )
  }

  @Test fun `disableQuietMode restores log output`() {
    Console.enableQuietMode()
    Console.log("suppressed")
    Console.disableQuietMode()
    Console.log("restored")
    val text = captured.toString(Charsets.UTF_8)
    assertFalse(text.contains("suppressed"), "text from quiet-mode window should not be present")
    assertTrue(text.contains("restored"), "log() must resume writing after disableQuietMode")
  }

  @Test fun `disableQuietMode without prior enable is a no-op`() {
    // Safety property: blanket-reset from non-quiet to non-quiet should not
    // break anything (the daemon's cli-exec finally block relies on this).
    Console.disableQuietMode()
    Console.log("still visible")
    assertTrue(captured.toString(Charsets.UTF_8).contains("still visible"))
    assertFalse(Console.isQuietMode())
  }

  @Test fun `isQuietMode reflects current state`() {
    assertFalse(Console.isQuietMode())
    Console.enableQuietMode()
    assertTrue(Console.isQuietMode())
    Console.disableQuietMode()
    assertFalse(Console.isQuietMode())
  }

  @Test fun `json mode moves both log and info off stdout`() {
    val err = withStdErrCaptured {
      Console.runJsonOutput {
        Console.log("breadcrumb")
        Console.info("progress")
      }
    }
    val stdout = captured.toString(Charsets.UTF_8)
    assertFalse(stdout.contains("breadcrumb"), "log() must not reach stdout while json mode is on")
    assertFalse(stdout.contains("progress"), "info() must not reach stdout while json mode is on")
    // Redirected, not dropped: a `| jq` consumer never sees stderr, but a human debugging does.
    assertTrue(err.contains("breadcrumb"), "log() should still be readable on stderr: $err")
    assertTrue(err.contains("progress"), "info() should still be readable on stderr: $err")
  }

  @Test fun `json mode leaves System out usable for the report itself`() {
    val report = ByteArrayOutputStream()
    val originalSystemOut = System.out
    System.setOut(PrintStream(report, /* autoFlush = */ true, Charsets.UTF_8))
    try {
      withStdErrCaptured { Console.runJsonOutput { println("""{"schemaVersion":1}""") } }
    } finally {
      System.setOut(originalSystemOut)
    }
    assertTrue(
      report.toString(Charsets.UTF_8).contains("""{"schemaVersion":1}"""),
      "json mode must not redirect System.out — the command prints its document there",
    )
  }

  @Test fun `runJsonOutput restores the streams when the block throws`() {
    withStdErrCaptured {
      runCatching { Console.runJsonOutput { error("boom") } }
    }
    Console.log("after the throw")
    assertTrue(
      captured.toString(Charsets.UTF_8).contains("after the throw"),
      "a throw inside json mode must not leave a long-lived JVM logging to stderr forever",
    )
  }

  @Test fun `json mode and quiet mode compose, in either nesting order`() {
    // A `--json` command wants both: json mode so nothing can reach stdout, quiet mode so bulk
    // per-item chatter is dropped rather than dumped on the terminal. `enableQuietMode` points
    // `userOut` at the current `out`, so getting this wrong in one order would put `info()` back
    // on stdout — assert both orders route identically.
    val jsonOuter = withStdErrCaptured {
      Console.runJsonOutput { Console.runQuiet { Console.log("chatter-a"); Console.info("note-a") } }
    }
    val quietOuter = withStdErrCaptured {
      Console.runQuiet { Console.runJsonOutput { Console.log("chatter-b"); Console.info("note-b") } }
    }
    val stdout = captured.toString(Charsets.UTF_8)
    assertFalse(stdout.contains("note-a") || stdout.contains("note-b"), "info() must never reach stdout")
    assertFalse(stdout.contains("chatter-a") || stdout.contains("chatter-b"), "log() must never reach stdout")
    assertTrue(jsonOuter.contains("note-a"), "info() belongs on stderr: $jsonOuter")
    assertTrue(quietOuter.contains("note-b"), "info() belongs on stderr in the other order too: $quietOuter")
    assertFalse(jsonOuter.contains("chatter-a"), "quiet mode must still drop log(), not relocate it")
    assertFalse(quietOuter.contains("chatter-b"), "quiet mode must still drop log(), not relocate it")
  }

  @Test fun `disableJsonMode without prior enable is a no-op`() {
    Console.disableJsonMode()
    Console.log("still visible")
    assertTrue(captured.toString(Charsets.UTF_8).contains("still visible"))
  }

  private fun withStdErrCaptured(block: () -> Unit): String {
    val err = ByteArrayOutputStream()
    val originalSystemErr = System.err
    System.setErr(PrintStream(err, /* autoFlush = */ true, Charsets.UTF_8))
    try {
      block()
    } finally {
      System.setErr(originalSystemErr)
    }
    return err.toString(Charsets.UTF_8)
  }
}
