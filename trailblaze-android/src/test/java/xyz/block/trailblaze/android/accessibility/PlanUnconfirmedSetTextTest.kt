package xyz.block.trailblaze.android.accessibility

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pure-function coverage of [planUnconfirmedSetText] — what `inputText` does when a field accepted
 * `ACTION_SET_TEXT` but never read back as changed.
 *
 * The regression this pins: a password field on a web sign-in page reads back empty whatever it
 * holds, so a selector-bearing `inputText` failed there with the text actually entered. The live
 * dispatch and readback stay an integration concern; this test pins only the decision.
 */
class PlanUnconfirmedSetTextTest {

  @Test
  fun `a WebView password field counts as entered because its value is never exposed`() {
    assertEquals(
      UnconfirmedSetTextPlan.ACCEPT_UNREADABLE,
      planUnconfirmedSetText(inWebView = true, isPassword = true),
    )
  }

  @Test
  fun `a plain WebView field that did not change gives up rather than typing a second copy`() {
    assertEquals(
      UnconfirmedSetTextPlan.GIVE_UP,
      planUnconfirmedSetText(inWebView = true, isPassword = false),
    )
  }

  @Test
  fun `a native field falls back to keystrokes`() {
    assertEquals(
      UnconfirmedSetTextPlan.TYPE_KEYSTROKES,
      planUnconfirmedSetText(inWebView = false, isPassword = false),
    )
  }

  @Test
  fun `a native password field still falls back to keystrokes because it reads back masked`() {
    assertEquals(
      UnconfirmedSetTextPlan.TYPE_KEYSTROKES,
      planUnconfirmedSetText(inWebView = false, isPassword = true),
    )
  }
}
