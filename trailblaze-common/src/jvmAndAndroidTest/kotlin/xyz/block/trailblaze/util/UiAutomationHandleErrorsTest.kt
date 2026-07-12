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
  fun `silent-shell wedge message is a recoverable signature but not the non-recoverable one`() {
    // Recoverable → retry runs first; the non-recoverable signature is reserved for retry failure.
    val message = UiAutomationHandleErrors.silentShellWedgeMessage("pm clear com.example.app")
    assertTrue(UiAutomationHandleErrors.isStaleHandleSignature(message))
    assertFalse(UiAutomationHandleErrors.isNonRecoverableStaleHandleSignature(message))
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
}
