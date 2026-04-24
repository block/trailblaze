package xyz.block.trailblaze.util

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Tests for [Console] quiet-mode toggles on the JVM.
 *
 * `Console` is an `expect object` with global mutable state (a shared
 * `quietMode` flag and cached output streams), so tests must save/restore
 * `System.out` in `@Before`/`@After` and explicitly call
 * [Console.disableQuietMode] between cases to avoid order-dependent failures.
 */
class ConsoleTest {

  private lateinit var originalOutField: PrintStream
  private lateinit var originalUserOutField: PrintStream
  private lateinit var captured: ByteArrayOutputStream

  @Before fun setUp() {
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
}
