package xyz.block.trailblaze.host.axe

import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [AxeCli]'s pure version-parsing/comparison helpers backing the minimum-axe-
 * version gate in `computeAvailability`. The subprocess-probing path itself needs a real (or
 * stubbed) `axe` binary and isn't covered here.
 */
class AxeCliTest {

  // --- parseAxeVersion ---

  @Test
  fun `parses a bare dotted version`() {
    assertEquals("1.8.0", AxeCli.parseAxeVersion("1.8.0"))
  }

  @Test
  fun `parses a v-prefixed version`() {
    assertEquals("1.8.0", AxeCli.parseAxeVersion("v1.8.0"))
  }

  @Test
  fun `parses a version embedded in surrounding text`() {
    assertEquals("1.8.0", AxeCli.parseAxeVersion("axe version v1.8.0\n"))
  }

  @Test
  fun `parses a 2-segment version`() {
    assertEquals("1.8", AxeCli.parseAxeVersion("axe 1.8"))
  }

  @Test
  fun `malformed output produces no version`() {
    assertNull(AxeCli.parseAxeVersion("command not found"))
  }

  @Test
  fun `blank output produces no version`() {
    assertNull(AxeCli.parseAxeVersion(""))
  }

  // --- compareVersions ---

  @Test
  fun `equal 3-segment versions compare equal`() {
    assertEquals(0, AxeCli.compareVersions("1.8.0", "1.8.0"))
  }

  @Test
  fun `an older patch version compares less than a newer one`() {
    assertTrue(AxeCli.compareVersions("1.5.2", "1.8.0") < 0)
  }

  @Test
  fun `a newer major version compares greater`() {
    assertTrue(AxeCli.compareVersions("2.0.0", "1.8.0") > 0)
  }

  @Test
  fun `a 2-segment version is padded with zero to compare against a 3-segment version`() {
    assertEquals(0, AxeCli.compareVersions("1.8", "1.8.0"))
  }

  @Test
  fun `a 2-segment version below the 3-segment minimum compares less`() {
    assertTrue(AxeCli.compareVersions("1.5", "1.8.0") < 0)
  }

  // --- computeAvailability's gating decision, expressed in terms of the pure helpers ---

  @Test
  fun `axe 1_5_2 is below MIN_VERSION`() {
    assertTrue(AxeCli.compareVersions("1.5.2", AxeCli.MIN_VERSION) < 0)
  }

  @Test
  fun `axe 1_8_0 meets MIN_VERSION`() {
    assertTrue(AxeCli.compareVersions("1.8.0", AxeCli.MIN_VERSION) >= 0)
  }

  // --- describe-ui web-content descent routing ---

  @Test
  fun `descent flags are appended when axe supports it and the kill-switch is unset`() {
    val args = AxeCli.describeUiArgs("UDID-1", webContentSupported = true, webContentDisabled = false)

    assertEquals(
      listOf(
        "describe-ui",
        "--udid",
        "UDID-1",
        "--include-web-content",
        "--web-content-grid-step",
        "25",
        "--web-content-max-points",
        "6000",
      ),
      args.drop(1),
    )
  }

  @Test
  fun `descent is skipped when axe does not support the flag`() {
    val args = AxeCli.describeUiArgs("UDID-1", webContentSupported = false, webContentDisabled = false)

    assertEquals(listOf("describe-ui", "--udid", "UDID-1"), args.drop(1))
  }

  @Test
  fun `kill-switch restores the argv Trailblaze emitted before the descent existed`() {
    val args = AxeCli.describeUiArgs("UDID-1", webContentSupported = true, webContentDisabled = true)

    assertEquals(listOf("describe-ui", "--udid", "UDID-1"), args.drop(1))
  }

  @Test
  fun `describe-ui targets the requested simulator in both modes`() {
    listOf(true, false).forEach { supported ->
      val args = AxeCli.describeUiArgs("UDID-2", webContentSupported = supported, webContentDisabled = false)
      assertEquals("UDID-2", args[args.indexOf("--udid") + 1])
    }
  }

  @Test
  fun `paste dispatch stages exact symbols and unicode and leaves the staged text available`() {
    val requestedText = "tb+usuario@example.com — español 日本語"
    val pasteboardWrites = mutableListOf<Pair<String, String>>()
    val events = mutableListOf<String>()
    var pasteUdid: String? = null

    val result = AxeCli.typeViaPasteboard(
      udid = "SIM-1",
      text = requestedText,
      writePasteboard = { udid, text ->
        events += "stage"
        pasteboardWrites += udid to text
        AxeCli.Result(0, "", "")
      },
      paste = { udid ->
        events += "paste"
        pasteUdid = udid
        AxeCli.Result(0, "", "")
      },
      waitForSettle = {
        events += "settled"
      },
    )

    assertTrue(result.success)
    assertEquals("SIM-1", pasteUdid)
    assertEquals(
      listOf("SIM-1" to requestedText),
      pasteboardWrites,
    )
    assertEquals(listOf("stage", "paste", "settled"), events)
  }

  @Test
  fun `digit-only input uses layout-independent keypad HID codes for protected fields`() {
    assertEquals(listOf(89, 90, 90, 95), AxeCli.keypadDigitKeycodes("1227"))
    assertEquals(listOf(89, 90, 91, 92, 93, 94), AxeCli.keypadDigitKeycodes("123456"))
    assertEquals(
      listOf("key-sequence", "--keycodes", "89,90,90,95", "--udid", "SIM-HID"),
      AxeCli.typeViaKeypadArgs("SIM-HID", listOf(89, 90, 90, 95)).drop(1),
    )
  }

  @Test
  fun `localized input is not representable as keypad events`() {
    assertNull(AxeCli.keypadDigitKeycodes("tb+usuario@example.com"))
    assertNull(AxeCli.keypadDigitKeycodes("español"))
    assertNull(AxeCli.keypadDigitKeycodes(""))
  }

  @Test
  fun `expiry uses keypad but locale-sensitive decimal punctuation does not`() {
    assertEquals(listOf(89, 90, 84, 90, 95), AxeCli.keypadDigitKeycodes("12/27"))
    assertNull(AxeCli.keypadDigitKeycodes("12.50"))
  }

  @Test
  fun `input text holds the settle interval after a failed paste attempt`() {
    var settled = false

    val result = AxeCli.typeViaPasteboard(
      udid = "SIM-PASTE-FAILURE",
      text = "user@example.com",
      writePasteboard = { _, _ -> AxeCli.Result(0, "", "") },
      paste = { AxeCli.Result(1, "", "key-combo failed") },
      waitForSettle = { settled = true },
    )

    assertFalse(result.success)
    assertTrue(settled)
  }

  @Test
  fun `input text reports an interrupted settle and preserves the interrupt`() {
    try {
      val result = AxeCli.typeViaPasteboard(
        udid = "SIM-INTERRUPTED",
        text = "user@example.com",
        writePasteboard = { _, _ -> AxeCli.Result(0, "", "") },
        paste = { AxeCli.Result(0, "", "") },
        waitForSettle = { throw InterruptedException("stop") },
      )

      assertFalse(result.success)
      assertTrue(result.stderr.contains("Interrupted"))
      assertTrue(Thread.currentThread().isInterrupted)
    } finally {
      Thread.interrupted()
    }
  }

  @Test
  fun `input text still settles when paste dispatch throws`() {
    var settled = false

    assertFailsWith<IllegalStateException> {
      AxeCli.typeViaPasteboard(
        udid = "SIM-THROW",
        text = "user@example.com",
        writePasteboard = { _, _ -> AxeCli.Result(0, "", "") },
        paste = { throw IllegalStateException("dispatch failed") },
        waitForSettle = { settled = true },
      )
    }

    assertTrue(settled)
  }

  @Test
  fun `input text does not paste when staging the requested text fails`() {
    var pasted = false

    val result = AxeCli.typeViaPasteboard(
      udid = "SIM-2",
      text = "user@example.com",
      writePasteboard = { _, _ -> AxeCli.Result(1, "", "pbcopy failed") },
      paste = {
        pasted = true
        AxeCli.Result(0, "", "")
      },
    )

    assertFalse(result.success)
    assertFalse(pasted)
  }

  @Test
  fun `input text serializes pasteboard transactions across simulators`() {
    val firstStaged = CountDownLatch(1)
    val releaseFirst = CountDownLatch(1)
    val secondStarted = CountDownLatch(1)
    val secondWrite = CountDownLatch(1)
    val events = Collections.synchronizedList(mutableListOf<String>())
    val executor = Executors.newFixedThreadPool(2)

    val first = executor.submit<AxeCli.Result> {
      AxeCli.withPasteboardLock(AxeCli.TimeoutBudget(5)) {
        AxeCli.typeViaPasteboard(
          udid = "SIM-ONE",
          text = "first",
          writePasteboard = { _, text ->
            events += "write-$text"
            if (text == "first") {
              firstStaged.countDown()
              releaseFirst.await(5, TimeUnit.SECONDS)
            }
            AxeCli.Result(0, "", "")
          },
          paste = {
            events += "paste-first"
            AxeCli.Result(0, "", "")
          },
        )
      }
    }

    assertTrue(firstStaged.await(5, TimeUnit.SECONDS))
    val second = executor.submit<AxeCli.Result> {
      secondStarted.countDown()
      AxeCli.withPasteboardLock(AxeCli.TimeoutBudget(5)) {
        AxeCli.typeViaPasteboard(
          udid = "SIM-TWO",
          text = "second",
          writePasteboard = { _, text ->
            secondWrite.countDown()
            events += "write-$text"
            AxeCli.Result(0, "", "")
          },
          paste = {
            events += "paste-second"
            AxeCli.Result(0, "", "")
          },
        )
      }
    }

    assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
    assertFalse(secondWrite.await(200, TimeUnit.MILLISECONDS))
    releaseFirst.countDown()
    assertTrue(first.get(5, TimeUnit.SECONDS).success)
    assertTrue(second.get(5, TimeUnit.SECONDS).success)
    executor.shutdownNow()

    assertTrue(events.indexOf("write-second") > events.indexOf("paste-first"))
  }

  @Test
  fun `paste command uses the command modifier and V key for the requested simulator`() {
    assertEquals(
      listOf(
        "key-combo",
        "--modifiers",
        "227",
        "--key",
        "25",
        "--udid",
        "SIM-ARGS",
      ),
      AxeCli.pasteFromPasteboardArgs("SIM-ARGS").drop(1),
    )
    assertEquals(
      listOf("xcrun", "simctl", "pbcopy", "SIM-ARGS"),
      AxeCli.writePasteboardArgs("SIM-ARGS"),
    )
  }

  @Test
  fun `timeout budget rejects work after its deadline without invoking the operation`() {
    var nowNanos = 0L
    var invoked = false
    val budget = AxeCli.TimeoutBudget(timeoutSeconds = 1, nanoTime = { nowNanos })
    nowNanos = TimeUnit.SECONDS.toNanos(2)

    val result = budget.run("simctl pbcopy") {
      invoked = true
      AxeCli.Result(0, "", "")
    }

    assertFalse(result.success)
    assertFalse(invoked)
    assertTrue(result.stderr.contains("exceeded the 1s inputText timeout"))
  }

  @Test
  fun `timeout budget passes millisecond precision instead of rounding up to a second`() {
    var nowNanos = 0L
    var receivedMillis = -1L
    val budget = AxeCli.TimeoutBudget(timeoutSeconds = 1, nanoTime = { nowNanos })
    nowNanos = TimeUnit.MILLISECONDS.toNanos(999)

    val result = budget.run("simctl pbcopy") { remainingMillis ->
      receivedMillis = remainingMillis
      AxeCli.Result(0, "", "")
    }

    assertTrue(result.success)
    assertEquals(1L, receivedMillis)
  }

  @Test
  fun `timeout budget rejects an operation that returns after its deadline`() {
    var nowNanos = 0L
    val budget = AxeCli.TimeoutBudget(timeoutSeconds = 1, nanoTime = { nowNanos })

    val result = budget.run("simctl pbcopy") {
      nowNanos = TimeUnit.SECONDS.toNanos(2)
      AxeCli.Result(0, "", "")
    }

    assertFalse(result.success)
    assertTrue(result.stderr.contains("exceeded the 1s inputText timeout"))
  }

  @Test(timeout = 60_000)
  fun `process timeout includes a stdin writer blocked by pipe backpressure`() {
    val result = AxeCli.runWithTimeoutMillis(
      args = listOf("/bin/sh", "-c", "sleep 30"),
      timeoutMillis = 100,
      stdin = "x".repeat(1024 * 1024),
      timeoutDescription = "blocked stdin",
    )

    assertFalse(result.success)
    assertTrue(result.stderr.contains("blocked stdin timed out after 100ms"))
  }

  @Test
  fun `expired timeout budget does not wait for or enter the pasteboard lock`() {
    var nowNanos = 0L
    var invoked = false
    val budget = AxeCli.TimeoutBudget(timeoutSeconds = 1, nanoTime = { nowNanos })
    nowNanos = TimeUnit.SECONDS.toNanos(2)

    val result = AxeCli.withPasteboardLock(budget) {
      invoked = true
      AxeCli.Result(0, "", "")
    }

    assertFalse(result.success)
    assertFalse(invoked)
    assertTrue(result.stderr.contains("pasteboard lock exceeded the 1s inputText timeout"))
  }
}
