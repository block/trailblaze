package xyz.block.trailblaze.android.accessibility

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Coverage of [confirmSetText], the two-stage readback behind `inputText`'s `ACTION_SET_TEXT`
 * path. A scripted reader stands in for the live node and records every window it was asked to
 * poll, so the tests pin both the outcome and how long each field type is waited on.
 *
 * The regression this pins: a web page's field showed the typed text while Chromium's readback
 * still reported the old value for up to ~7s, and a 1s check called the text not entered.
 */
class ConfirmSetTextTest {

  /** Answers each poll with the next scripted [Readback], recording the window it was given. */
  private class ScriptedReadback(vararg answers: Readback) {
    private val remaining = ArrayDeque(answers.toList())
    val windowsPolled = mutableListOf<Long>()

    fun await(timeoutMs: Long): Readback {
      windowsPolled += timeoutMs
      return remaining.removeFirst()
    }
  }

  private val remainderOfWebViewWindow = WEBVIEW_READBACK_TIMEOUT_MS - SET_TEXT_VERIFY_TIMEOUT_MS

  @Test
  fun `a WebView value that reads back after the first second still counts as entered`() {
    val readback = ScriptedReadback(Readback.UNCHANGED, Readback.CHANGED)

    val outcome = confirmSetText(inWebView = { true }, isPassword = false, awaitChange = readback::await)

    assertEquals(SetTextOutcome.Landed, outcome)
    assertEquals(listOf(SET_TEXT_VERIFY_TIMEOUT_MS, remainderOfWebViewWindow), readback.windowsPolled)
  }

  @Test
  fun `a WebView value that never reads back within the full window is not entered`() {
    val readback = ScriptedReadback(Readback.UNCHANGED, Readback.UNCHANGED)

    val outcome = confirmSetText(inWebView = { true }, isPassword = false, awaitChange = readback::await)

    assertEquals(SetTextOutcome.Unconfirmed(UnconfirmedSetTextPlan.AWAIT_WEBVIEW_READBACK), outcome)
    assertEquals(listOf(SET_TEXT_VERIFY_TIMEOUT_MS, remainderOfWebViewWindow), readback.windowsPolled)
  }

  @Test
  fun `a WebView field that goes away is not waited on for the rest of the window`() {
    val readback = ScriptedReadback(Readback.NODE_GONE)

    val outcome = confirmSetText(inWebView = { true }, isPassword = false, awaitChange = readback::await)

    assertEquals(SetTextOutcome.Unconfirmed(UnconfirmedSetTextPlan.AWAIT_WEBVIEW_READBACK), outcome)
    assertEquals(listOf(SET_TEXT_VERIFY_TIMEOUT_MS), readback.windowsPolled)
  }

  @Test
  fun `a native field falls back to keystrokes after the first second`() {
    val readback = ScriptedReadback(Readback.UNCHANGED)

    val outcome = confirmSetText(inWebView = { false }, isPassword = false, awaitChange = readback::await)

    assertEquals(SetTextOutcome.Unconfirmed(UnconfirmedSetTextPlan.TYPE_KEYSTROKES), outcome)
    assertEquals(listOf(SET_TEXT_VERIFY_TIMEOUT_MS), readback.windowsPolled)
  }

  @Test
  fun `a WebView password field is accepted after the first second because it never reads back`() {
    val readback = ScriptedReadback(Readback.UNCHANGED)

    val outcome = confirmSetText(inWebView = { true }, isPassword = true, awaitChange = readback::await)

    assertEquals(SetTextOutcome.Unconfirmed(UnconfirmedSetTextPlan.ACCEPT_UNREADABLE), outcome)
    assertEquals(listOf(SET_TEXT_VERIFY_TIMEOUT_MS), readback.windowsPolled)
  }

  @Test
  fun `a value that reads back in the first second lands without checking for a WebView`() {
    val readback = ScriptedReadback(Readback.CHANGED)
    var webViewChecks = 0

    val outcome =
      confirmSetText(
        inWebView = { webViewChecks++; true },
        isPassword = false,
        awaitChange = readback::await,
      )

    assertEquals(SetTextOutcome.Landed, outcome)
    assertEquals(listOf(SET_TEXT_VERIFY_TIMEOUT_MS), readback.windowsPolled)
    assertEquals(0, webViewChecks, "the ancestor walk is only paid for an unconfirmed write")
  }
}
