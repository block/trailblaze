package xyz.block.trailblaze

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import xyz.block.trailblaze.util.Console

/**
 * Pins the log-redaction half of the sensitive-key contract: a value stored under a key marked
 * via [AgentMemory.rememberSensitive] (`--secret`) must never reach the console log in cleartext —
 * including through a plain [AgentMemory.remember] of the already-marked key, and after a
 * [AgentMemory.delete] of it (the marker is sticky for the session).
 *
 * Lives in `jvmTest` (not `jvmAndAndroidTest`) because it captures output through the JVM
 * `Console` actual's cached stream fields, using the same save/restore harness as [ConsoleTest]
 * (`Console` caches `System.out` at class-init time, so `System.setOut` alone can't intercept).
 */
class AgentMemoryLogRedactionTest {

  private lateinit var originalOutField: PrintStream
  private lateinit var originalUserOutField: PrintStream
  private lateinit var captured: ByteArrayOutputStream

  @BeforeTest fun setUp() {
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

  @AfterTest fun tearDown() {
    Console.disableQuietMode()
    Console::class.java.getDeclaredField("out").apply { isAccessible = true }.set(Console, originalOutField)
    Console::class.java.getDeclaredField("userOut").apply { isAccessible = true }.set(Console, originalUserOutField)
  }

  private fun logOutput(): String = captured.toString(Charsets.UTF_8)

  @Test
  fun `rememberSensitive does not log the value`() {
    val memory = AgentMemory()
    memory.rememberSensitive("pin", "s3cret-1234")
    val log = logOutput()
    assertFalse(log.contains("s3cret-1234"))
    // The write is still logged (the key appears in the line), so absence of the secret can't
    // be satisfied by logging being suppressed entirely. Deliberately does not pin the
    // redaction wording itself.
    assertTrue(log.contains("pin"))
    assertEquals("s3cret-1234", memory.variables["pin"])
  }

  @Test
  fun `remember on a key marked sensitive logs the write without the value`() {
    val memory = AgentMemory()
    memory.rememberSensitive("pin", "s3cret-1234")
    // Isolate the overwrite's log line from the initial mark's, so the redaction assertion
    // below is about the plain-remember path specifically.
    captured.reset()
    memory.remember("pin", "s3cret-5678")
    val log = logOutput()
    assertFalse(log.contains("s3cret-5678"))
    assertTrue(log.contains("pin"))
    assertEquals("s3cret-5678", memory.variables["pin"])
  }

  @Test
  fun `remember after delete of a sensitive key does not log the value`() {
    // The full --secret acceptance sequence: seed-mark, delete, re-remember. The sticky marker
    // means the re-remember stays redacted instead of re-logging the rotated value.
    val memory = AgentMemory()
    memory.seedFrom(
      yamlDefaults = null,
      cliSeeds = emptyMap(),
      cliSensitiveSeeds = mapOf("apiToken" to "s3cret-original"),
    )
    memory.delete("apiToken")
    assertFalse(logOutput().contains("s3cret-original"))
    // Isolate the re-remember's log line: the write is logged (key appears), the value isn't.
    captured.reset()
    memory.remember("apiToken", "s3cret-rotated")
    val log = logOutput()
    assertFalse(log.contains("s3cret-rotated"))
    assertTrue(log.contains("apiToken"))
    assertEquals("s3cret-rotated", memory.variables["apiToken"])
  }

  @Test
  fun `remember on an unmarked key still logs the value`() {
    // Contrast pin: redaction is scoped to marked keys — a blanket-redaction over-fix that
    // stopped logging ordinary remembered values would break this.
    val memory = AgentMemory()
    memory.remember("businessName", "Coffee Corner")
    assertTrue(logOutput().contains("Coffee Corner"))
  }
}
