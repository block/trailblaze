package xyz.block.trailblaze.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [UiAutomationHandleErrors.isStaleHandleSignature].
 *
 * The "Cannot call disconnect() while connecting" case is the regression guard for the flaky
 * on-device Android CI failures (`trailblaze-pr-checks` on `main`, ~1/3 of builds): the eval /
 * smoke steps aborted on that transient platform race instead of recovering. If anyone narrows the
 * matched signatures, this test fails before the flake reaches CI again.
 */
class UiAutomationHandleErrorsTest {

  @Test
  fun `matches the connecting-disconnect race that flaked on-device CI`() {
    // The exact message the platform throws from Instrumentation.getUiAutomation() when it tries to
    // swap UiAutomation flags while the cached handle is still mid-handshake.
    assertTrue(
      UiAutomationHandleErrors.isStaleHandleSignature(
        "Cannot call disconnect() while connecting UiAutomation"
      )
    )
    // AOSP's literal variant of the same race (trailing "!" instead of "UiAutomation"); the matcher
    // must catch both since the exact wording differs across Android versions / OEM forks.
    assertTrue(
      UiAutomationHandleErrors.isStaleHandleSignature(
        "Cannot call disconnect() while connecting!"
      )
    )
  }

  @Test
  fun `matches all stale-handle signatures including realistic AOSP wording`() {
    listOf(
      "UiAutomation not connected!",
      "Cannot call disconnect() while connecting UiAutomation",
      "Cannot call disconnect() while connecting!",
      "UiAutomation already connected!",
      "Error while disconnecting UiAutomation",
    ).forEach { message ->
      assertTrue("expected stale-handle match for: $message", UiAutomationHandleErrors.isStaleHandleSignature(message))
    }
  }

  @Test
  fun `matching is case-insensitive`() {
    // Android framework messages vary in capitalization across versions; the classifier must not
    // miss a recoverable signature just because the case differs from the canonical wording.
    listOf(
      "CANNOT CALL DISCONNECT() WHILE CONNECTING!",
      "uiautomation already connected!",
      "UIAUTOMATION NOT CONNECTED",
    ).forEach { message ->
      assertTrue("expected case-insensitive match for: $message", UiAutomationHandleErrors.isStaleHandleSignature(message))
    }
    assertTrue(
      UiAutomationHandleErrors.isNonRecoverableStaleHandleSignature(
        "UIAUTOMATION RECONNECT RETRY ALSO FAILED — non-RECOVERABLE state"
      )
    )
  }

  @Test
  fun `recognizes Android 35 rejecting reflective stale-handle recovery`() {
    assertTrue(
      UiAutomationHandleErrors.isNonRecoverableStaleHandleSignature(
        "UiAutomation is not connected and the cached handle could not be cleared via " +
          "reflection (both Instrumentation.disconnectUiAutomation() and the mUiAutomation " +
          "field are inaccessible — Android internal API may have changed). Recover by " +
          "restarting the Trailblaze on-device server. Original error: UiAutomation not connected",
      ),
    )
  }

  @Test
  fun `does not classify unrelated reflective recovery failures as a UiAutomation wedge`() {
    assertFalse(
      UiAutomationHandleErrors.isNonRecoverableStaleHandleSignature(
        "A cached handle could not be cleared via reflection",
      ),
    )
  }

  @Test
  fun `silent-shell wedge message is a recoverable signature but not the non-recoverable one`() {
    // Recoverable → retry runs first; the non-recoverable signature is reserved for retry failure.
    val message = UiAutomationHandleErrors.silentShellWedgeMessage("pm clear com.example.app")
    assertTrue(UiAutomationHandleErrors.isStaleHandleSignature(message))
    assertFalse(UiAutomationHandleErrors.isNonRecoverableStaleHandleSignature(message))
  }

  @Test
  fun `a wedged-shell read is not retried, so the command cannot run twice`() {
    // Matching a stale-handle signature is what makes `withUiAutomation` replay the work, and a
    // wedged read must NOT be replayed: the command may have run already and only its output
    // wedged, so a second `pm clear` / `input tap` / `am force-stop` really happens.
    val message = UiAutomationHandleErrors.wedgedShellReadMessage(
      command = "adb shell pm clear com.example.app",
      timeoutMs = 300_000,
      handleDiscarded = true,
    )
    assertFalse("a wedged read must not reach the replaying recovery path", UiAutomationHandleErrors.isStaleHandleSignature(message))
    // Nor is it an escalation: the handle was dropped, so the next command reconnects on its own.
    assertFalse(UiAutomationHandleErrors.isNonRecoverableStaleHandleSignature(message))
    // The command is the whole point of bounding it here rather than in the caller — a timeout that
    // does not say which command hung is the "inexplicably slow run" this replaces.
    assertTrue("expected the command, was: $message", message.contains("pm clear com.example.app"))
    assertTrue("expected the bound, was: $message", message.contains("300000"))
  }

  @Test
  fun `a wedged read whose handle could not be dropped escalates to a runner restart`() {
    // The one case in-process recovery cannot fix: the connection is wedged AND still cached, so
    // every later command pays the same bound. Only restarting the on-device server clears it, and
    // the host decides that from this signature.
    val message = UiAutomationHandleErrors.wedgedShellReadMessage(
      command = "adb shell pm clear com.example.app",
      timeoutMs = 300_000,
      handleDiscarded = false,
    )
    assertTrue(
      "an undroppable wedged handle must reach the host's runner-restart path, was: $message",
      UiAutomationHandleErrors.isNonRecoverableStaleHandleSignature(message),
    )
  }

  @Test
  fun `does not match unrelated runtime errors`() {
    listOf(
      "java.lang.NullPointerException",
      "Element not found: Text matching regex: Forms",
      "Timed out waiting for run to complete: no progress for 600s",
      "",
    ).forEach { message ->
      assertFalse("did not expect stale-handle match for: $message", UiAutomationHandleErrors.isStaleHandleSignature(message))
    }
  }

  @Test
  fun `treats a null message as non-matching`() {
    assertFalse(UiAutomationHandleErrors.isStaleHandleSignature(null))
  }

  /** Verbatim from an API 34 emulator instrumented WITHOUT `--no-hidden-api-checks`. */
  private val hiddenApiSignature = "No field mUiAutomation in class Landroid/app/Instrumentation;"

  @Test
  fun `a blocked cache clear on Trailblaze's own runner offers both causes and how to tell them apart`() {
    // Enforcement reports the private field as ABSENT, which reads exactly like an Android SDK
    // rename — the wrong diagnosis, and the one an investigation already chased. But a real removal
    // on a future platform throws the IDENTICAL exception, and no flag makes a removed field
    // reappear, so naming only the flag would just send the next reader the other wrong way.
    val diagnosis = UiAutomationHandleErrors.cacheClearFailureDiagnosis(
      throwableClassName = "java.lang.NoSuchFieldException",
      throwableMessage = hiddenApiSignature,
      instrumentationProcessIsTrailblazeOwned = true,
    )

    assertTrue("expected the flag to be named, was: $diagnosis", diagnosis.contains("--no-hidden-api-checks"))
    // The other candidate, and the discriminator — without both, this is an assertion rather than a
    // diagnosis. The discriminator has to be the platform's OWN verdict: the launch argv alone
    // cannot settle it, because the field is max-target-o and a runner targeting API <= 26 is
    // allowed it with no flag at all (measured: targetSdk 26 allowed, targetSdk 36 denied).
    assertTrue("expected the platform-removal cause, was: $diagnosis", diagnosis.contains("really is gone"))
    assertTrue("expected the platform's own verdict, was: $diagnosis", diagnosis.contains("denied"))
    assertTrue("expected the targetSdk caveat, was: $diagnosis", diagnosis.contains("max-target-o"))
    // The cost has to be in the line too: otherwise a silent fall-back to server restarts looks
    // like nothing happened.
    assertTrue("expected the consequence, was: $diagnosis", diagnosis.contains("restart"))
  }

  @Test
  fun `the same failure inside an app under test never advises adding the flag`() {
    // The dangerous direction. This diagnosis is emitted by shared Android code that also runs in
    // the in-process harness, where the instrumented process IS the app under test and the flag is
    // deliberately absent. "Restore the flag" there is advice to let the app reach blocklisted
    // hidden APIs it would be denied in production — i.e. to break the contract the harness exists
    // to test.
    val diagnosis = UiAutomationHandleErrors.cacheClearFailureDiagnosis(
      throwableClassName = "java.lang.NoSuchFieldException",
      throwableMessage = hiddenApiSignature,
      instrumentationProcessIsTrailblazeOwned = false,
    )

    assertTrue("expected this to be called out as expected, was: $diagnosis", diagnosis.contains("Expected here"))
    assertTrue("expected an explicit do-not, was: $diagnosis", diagnosis.contains("Do NOT add that flag"))
    assertTrue("expected the consequence, was: $diagnosis", diagnosis.contains("restart"))
  }

  @Test
  fun `a genuinely different reflection failure is not blamed on the launch flag`() {
    val diagnosis = UiAutomationHandleErrors.cacheClearFailureDiagnosis(
      throwableClassName = "java.lang.IllegalAccessException",
      throwableMessage = "access to field denied",
      instrumentationProcessIsTrailblazeOwned = true,
    )

    assertFalse("did not expect the flag to be blamed, was: $diagnosis", diagnosis.contains("--no-hidden-api-checks"))
    assertTrue("expected the consequence, was: $diagnosis", diagnosis.contains("restart"))
  }
}
