package xyz.block.trailblaze.android.accessibility

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The deferred log join is a per-DISPATCH decision, not a global one. A dispatch that owns either
 * end of its session finishes that session as soon as it returns, so for it the end-of-run join is
 * the only thing standing between "the trail passed" and "the report has its screenshots" — the
 * gate must not reach it.
 */
class DeferredLogJoinDecisionTest {

  @Test
  fun `a per-tool dispatch defers when the gate is on`() {
    assertTrue(
      ReplayCaptureOptions.shouldDeferDispatchLogJoin(
        gateOn = true,
        sendSessionStartLog = false,
        sendSessionEndLog = false,
      ),
    )
  }

  @Test
  fun `a whole-trail dispatch still joins even when the gate is on`() {
    assertFalse(
      ReplayCaptureOptions.shouldDeferDispatchLogJoin(
        gateOn = true,
        sendSessionStartLog = true,
        sendSessionEndLog = true,
      ),
    )
  }

  @Test
  fun `the dispatch that emits the session-end log still joins even when the gate is on`() {
    // The host generates the report off SessionEnded, which this dispatch emits the moment the
    // rule returns. Deferring here would publish a trail whose last screenshot is still uploading
    // — and a final dispatch carrying no actions never reaches the deferred join at all.
    assertFalse(
      ReplayCaptureOptions.shouldDeferDispatchLogJoin(
        gateOn = true,
        sendSessionStartLog = false,
        sendSessionEndLog = true,
      ),
    )
  }

  @Test
  fun `nothing defers while the gate is off`() {
    assertFalse(
      ReplayCaptureOptions.shouldDeferDispatchLogJoin(
        gateOn = false,
        sendSessionStartLog = false,
        sendSessionEndLog = false,
      ),
    )
    assertFalse(
      ReplayCaptureOptions.shouldDeferDispatchLogJoin(
        gateOn = false,
        sendSessionStartLog = true,
        sendSessionEndLog = false,
      ),
    )
  }
}
