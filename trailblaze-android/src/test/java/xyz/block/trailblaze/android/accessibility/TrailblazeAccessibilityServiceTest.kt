package xyz.block.trailblaze.android.accessibility

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the IME class-name heuristic that backs
 * [TrailblazeAccessibilityService.Companion.isImeClassName].
 *
 * The function is the fallback path used by `isEventFromImeWindow` when
 * `service.windows` isn't available (older OEMs, pre-init, or
 * `flagRetrieveInteractiveWindows` not yet honored). False positives matter:
 * if a non-IME activity matches, [TrailblazeAccessibilityService.onAccessibilityEvent]
 * will drop a real activity transition from `currentActivityClass`, leaving
 * the screen-state header that the V3 verifier reads stale.
 */
class TrailblazeAccessibilityServiceTest {

  @Test
  fun `matches the canonical SoftInputWindow class`() {
    // Default IME class shipped by AOSP and inherited by every keyboard built on
    // android.inputmethodservice.InputMethodService.
    assertTrue(
      TrailblazeAccessibilityService.isImeClassName("android.inputmethodservice.SoftInputWindow"),
    )
  }

  @Test
  fun `matches Gboard SoftInputWindow`() {
    assertTrue(
      TrailblazeAccessibilityService.isImeClassName(
        "com.google.android.inputmethod.latin.SoftInputWindow",
      ),
    )
  }

  @Test
  fun `matches SwiftKey SoftInputWindow`() {
    assertTrue(
      TrailblazeAccessibilityService.isImeClassName("com.touchtype.swiftkey.SoftInputWindow"),
    )
  }

  @Test
  fun `matches Samsung Keyboard SoftInputWindow`() {
    assertTrue(
      TrailblazeAccessibilityService.isImeClassName(
        "com.samsung.android.inputmethod.SoftInputWindow",
      ),
    )
  }

  @Test
  fun `matches Fleksy SoftInputWindow`() {
    assertTrue(
      TrailblazeAccessibilityService.isImeClassName(
        "com.syntellia.fleksy.keyboard.SoftInputWindow",
      ),
    )
  }

  @Test
  fun `matches packages with the inputmethod fragment even without SoftInputWindow suffix`() {
    // Some IMEs surface non-window classes via the same package — accept any class whose
    // package contains `.inputmethod.` so we still catch them in the fallback path.
    assertTrue(
      TrailblazeAccessibilityService.isImeClassName(
        "com.google.android.inputmethod.latin.LatinIME",
      ),
    )
  }

  @Test
  fun `does not match an ordinary app activity`() {
    assertFalse(TrailblazeAccessibilityService.isImeClassName("com.example.app.MainActivity"))
  }

  @Test
  fun `does not match an app whose package contains the plural inputmethods`() {
    // Anchored on the trailing dot in `.inputmethod.` so a legitimate app package fragment
    // like `inputmethods` (plural) does NOT false-positive — that was the pre-fix bug.
    assertFalse(
      TrailblazeAccessibilityService.isImeClassName(
        "com.example.app.inputmethods.HelperActivity",
      ),
    )
  }

  @Test
  fun `does not match an app whose package contains inputmethod as part of a longer name`() {
    // Same reasoning: the fragment must be flanked by dots on both sides.
    assertFalse(
      TrailblazeAccessibilityService.isImeClassName(
        "com.example.inputmethod_helpers.Helper",
      ),
    )
    assertFalse(
      TrailblazeAccessibilityService.isImeClassName("com.foo.inputmethodutil.Tools"),
    )
  }

  @Test
  fun `does not match a class with SoftInputWindow as an internal segment but not suffix`() {
    // Defensive: only the *suffix* anchor counts. A class with SoftInputWindow in the middle
    // of its FQN (extremely unlikely but possible) is not an IME root window.
    assertFalse(
      TrailblazeAccessibilityService.isImeClassName(
        "com.example.SoftInputWindowExtension.MyClass",
      ),
    )
  }

  @Test
  fun `does not match the empty string`() {
    assertFalse(TrailblazeAccessibilityService.isImeClassName(""))
  }

  @Test
  fun `match is case-sensitive on the package fragment`() {
    // Anchored fragment match uses String.contains which is case-sensitive. Pin that
    // intentionally — Android package names are lowercase by convention and IMEs always
    // use lowercase `.inputmethod.`, so a camelCase or upper-case variant is NOT a real IME
    // and a future maintainer should NOT add `.lowercase()` thinking it's needed for
    // "compat".
    assertFalse(TrailblazeAccessibilityService.isImeClassName("com.example.InputMethod.Keyboard"))
    assertFalse(TrailblazeAccessibilityService.isImeClassName("com.example.INPUTMETHOD.Keyboard"))
  }

  @Test
  fun `match is case-sensitive on the SoftInputWindow suffix`() {
    // Same case-sensitivity expectation for the suffix anchor. Real IMEs all subclass
    // `android.inputmethodservice.SoftInputWindow` and inherit the exact casing, so any
    // class whose suffix is spelled differently is NOT a real IME window class.
    assertFalse(TrailblazeAccessibilityService.isImeClassName("com.example.softinputwindow"))
    assertFalse(TrailblazeAccessibilityService.isImeClassName("com.example.SOFTINPUTWINDOW"))
  }

  // --- parseDumpsysInputMethodShown ---
  //
  // Pure-function parser pulled out of `isImeShownViaDumpsys()` so the matching rules can
  // be pinned without the shell wrapper. This is the authoritative signal that gates
  // `waitForImeDismissed`, so false negatives here cause real stuck-keyboard failures to
  // silently pass the post-check, and false positives cause hideKeyboard to throw on a
  // keyboard that's already gone.

  @Test
  fun `parseDumpsysInputMethodShown returns false for empty output`() {
    assertFalse(TrailblazeAccessibilityService.parseDumpsysInputMethodShown(""))
  }

  @Test
  fun `parseDumpsysInputMethodShown returns true when mInputShown=true is on its own indented line`() {
    // The common dumpsys layout: `mInputShown` appears as a field on its own line,
    // indented under the InputMethodManagerService section header.
    val fixture =
      """
      Current Input Method Manager state:
        mCurMethodId=com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME
        mShowExplicitlyRequested=false mShowForced=false
        mInputShown=true
        mInQuickSwitch=false
      """
        .trimIndent()
    assertTrue(TrailblazeAccessibilityService.parseDumpsysInputMethodShown(fixture))
  }

  @Test
  fun `parseDumpsysInputMethodShown returns false when mInputShown=false is on its own indented line`() {
    val fixture =
      """
      Current Input Method Manager state:
        mCurMethodId=com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME
        mShowExplicitlyRequested=false mShowForced=false
        mInputShown=false
        mInQuickSwitch=false
      """
        .trimIndent()
    assertFalse(TrailblazeAccessibilityService.parseDumpsysInputMethodShown(fixture))
  }

  @Test
  fun `parseDumpsysInputMethodShown returns false when mInputShown is absent entirely`() {
    // Older Android versions or partial dumpsys outputs sometimes omit the flag — we
    // must NOT default to "shown" in that case (would convert every hideKeyboard into a
    // false-positive failure).
    val fixture =
      """
      Current Input Method Manager state:
        mShowExplicitlyRequested=false mShowForced=false
        mInQuickSwitch=false
      """
        .trimIndent()
    assertFalse(TrailblazeAccessibilityService.parseDumpsysInputMethodShown(fixture))
  }

  @Test
  fun `parseDumpsysInputMethodShown ignores mInputShown=true that appears mid-line`() {
    // The startsWith(trim) anchor exists so a field name appearing as a substring of an
    // unrelated diagnostic line (e.g. a comment, a serialized state dump) doesn't
    // false-positive. Pin that behavior intentionally.
    val fixture =
      """
        diagnostics: previous mInputShown=true at boot
        mInputShown=false
      """
        .trimIndent()
    assertFalse(TrailblazeAccessibilityService.parseDumpsysInputMethodShown(fixture))
  }

  @Test
  fun `parseDumpsysInputMethodShown returns true when any of multiple lines match`() {
    // Defensive against future Android versions printing the flag in multiple sections.
    // The function short-circuits on the first match — semantics: "IME is shown if any
    // dumpsys line says so".
    val fixture =
      """
        mInputShown=false
        mInputShown=true
      """
        .trimIndent()
    assertTrue(TrailblazeAccessibilityService.parseDumpsysInputMethodShown(fixture))
  }
}
