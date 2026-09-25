package xyz.block.trailblaze.util

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.device.AndroidShellBounds
import xyz.block.trailblaze.util.AndroidHostAdbUtils.ShellAttemptOutcome
import xyz.block.trailblaze.util.AndroidHostAdbUtils.ShellAttemptResult

/**
 * The host transport reports a wedged or broken device by throwing. The message lands in session
 * logs and in the agent's transcript, so what it contains — and what it must never contain — is
 * behavior, not formatting.
 */
class HostShellFailureMessageTest {

  private val bound = 330_000L

  private fun timedOut(command: String = "dumpsys activity activities") =
    hostShellFailureMessage(ShellAttemptOutcome.TIMED_OUT, "emulator-5554", command, bound)

  // --- what the message says ------------------------------------------------------------------

  @Test
  fun `names the device, the bound and the command that hung`() {
    // Without all three the message is unactionable: a fleet run has many devices, and "something
    // timed out" does not say which command to go look at.
    val message = timedOut("pm compile -m verify -f com.example.app")
    assertTrue("emulator-5554" in message, message)
    assertTrue("330000ms" in message, message)
    assertTrue("pm compile -m verify -f com.example.app" in message, message)
  }

  @Test
  fun `a timeout and a failure do not read the same`() {
    // The whole reason the outcome is carried through instead of flattened to null. Describing a
    // disconnected device as a hang sends the reader hunting for a wedge that never happened.
    val timeout = timedOut()
    val failure = hostShellFailureMessage(
      ShellAttemptOutcome.FAILED,
      "emulator-5554",
      "dumpsys activity activities",
      bound,
    )
    assertTrue("did not return within" in timeout, timeout)
    assertFalse("did not return within" in failure, failure)
    assertTrue("failed or was interrupted" in failure, failure)
  }

  @Test
  fun `a timeout warns the command may still run, and promises no recovery`() {
    // dadb's cached client holds no connection (`close()` is a no-op, every command opens its own
    // socket), so dropping it changes nothing and a "the next command reconnects" promise is one
    // the reader would act on wrongly. What is true is that the device may still run the command,
    // which matters before anyone retries a `pm clear`.
    assertTrue("may still complete on the device" in timedOut(), timedOut())
    assertFalse("reconnect" in timedOut(), timedOut())
    assertFalse(
      "may still complete" in
        hostShellFailureMessage(ShellAttemptOutcome.FAILED, "d", "getprop x", bound),
    )
  }

  @Test
  fun `the bound reported is the one the caller was actually given`() {
    // The message must not hardcode the constant: a caller that lowers the bound and still reads
    // "330000ms" would send the next reader after a timeout that never happened.
    val message = hostShellFailureMessage(ShellAttemptOutcome.TIMED_OUT, "d", "getprop x", 5_000L)
    assertTrue("5000ms" in message, message)
    assertFalse("330000" in message, message)
  }

  // --- what the message must not leak ---------------------------------------------------------

  @Test
  fun `redacts an llm auth token rather than printing it into the log`() {
    // `android_adbShell` carries these to the device on the instrumentation command line. A
    // timeout must not be the one path that prints a live credential in clear text.
    val message = timedOut(
      "am instrument -e trailblaze.llm.auth.token.openai sk-live-SECRETVALUE -w foo/Runner",
    )
    assertFalse("sk-live-SECRETVALUE" in message, message)
    assertTrue("<redacted>" in message, message)
  }

  @Test
  fun `a bulk payload is collapsed, not printed and not merely truncated`() {
    // `writeFileAs` puts a file's base64 body on the command line. The redactor recognises those
    // and replaces the body with its length, so the surviving text still shows the command and
    // its destination path — which truncating at 200 chars would have thrown away.
    val body = "A".repeat(50_000)
    val message = timedOut("printf %s $body > /sdcard/big")
    assertFalse(body in message, "the payload itself must not reach the message")
    assertTrue(message.length < 600, "message was ${message.length} chars")
    assertTrue("/sdcard/big" in message, message)
  }

  @Test
  fun `truncates a long command the redactor leaves alone`() {
    // Not every oversized command is a recognisable payload. A trail can hand `android_adbShell`
    // a long pipeline of ordinary argv, and that must not land whole in a thrown message.
    val message = timedOut((1..400).joinToString(" ") { "getprop ro.build.item$it" })
    assertTrue(message.length < 600, "message was ${message.length} chars")
    assertTrue("…" in message, message)
  }

  @Test
  fun `a short command is reported whole, with no truncation marker`() {
    // Truncation must not fire on the normal case; an ellipsis there would read as if the command
    // had been cut when it had not.
    val message = timedOut("getprop ro.build.version.sdk")
    assertFalse("…" in message, message)
    assertTrue("'getprop ro.build.version.sdk'" in message, message)
  }

  @Test
  fun `the truncation marker counts as part of the shown command, not extra budget`() {
    // Guards "take(200) then append" quietly becoming take(200) of an already-short string:
    // exactly-at-the-limit input must not gain an ellipsis.
    val exact = "x".repeat(200)
    assertFalse("…" in timedOut(exact))
    assertTrue("…" in timedOut(exact + "y"))
  }

  // --- the decision itself --------------------------------------------------------------------

  @Test
  fun `no answer from the transport throws instead of returning nothing`() {
    // The failure this change exists to prevent: letting a timeout reach a caller as "".
    val thrown = assertFailsWith<TrailblazeException> {
      hostShellOutputOrThrow(
        ShellAttemptResult(null, ShellAttemptOutcome.TIMED_OUT),
        "emulator-5554",
        "dumpsys activity activities",
        bound,
      )
    }
    assertTrue("did not return within 330000ms" in (thrown.message ?: ""), thrown.message ?: "")
  }

  @Test
  fun `an empty answer is a real answer and passes straight through`() {
    // The other half of the same decision, and the half a careless fix breaks: plenty of shell
    // commands legitimately print nothing, and those must not be reported as a wedged device.
    assertEquals(
      "",
      hostShellOutputOrThrow(
        ShellAttemptResult("", ShellAttemptOutcome.SUCCESS),
        "emulator-5554",
        "pm list packages nonexistent",
        bound,
      ),
    )
  }

  @Test
  fun `output is returned unchanged, not redacted or truncated on the success path`() {
    // Redaction and truncation belong to the failure message only. Mangling real output here
    // would corrupt every parser downstream of a host shell call.
    val output = "x".repeat(5_000)
    assertEquals(
      output,
      hostShellOutputOrThrow(
        ShellAttemptResult(output, ShellAttemptOutcome.SUCCESS),
        "emulator-5554",
        "dumpsys package foo",
        bound,
      ),
    )
  }

  @Test
  fun `the underlying exception is attached as the cause, not just logged`() {
    // A bare "adb shell failed" with no stack is far worse to find in a session log than the
    // IOException underneath it, which says whether the socket closed or the device vanished.
    val boom = IOException("connection reset by peer")
    val thrown = assertFailsWith<TrailblazeException> {
      hostShellOutputOrThrow(
        ShellAttemptResult(null, ShellAttemptOutcome.FAILED, boom),
        "emulator-5554",
        "getprop x",
        bound,
      )
    }
    assertSame(boom, thrown.cause)
  }

  // --- the bound itself -----------------------------------------------------------------------

  @Test
  fun `the bound the executor uses is the host bound`() {
    // Ties the tests' expectations to the constant, so a future edit cannot bound the transport at
    // one number while the docs and these assertions describe another.
    assertEquals(330_000L, AndroidShellBounds.HOST_SHELL_TIMEOUT_MS)
  }
}
