package xyz.block.trailblaze.toolcalls.commands

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import kotlin.test.assertFailsWith
import maestro.Bounds
import maestro.ScrollDirection
import maestro.SwipeDirection
import maestro.orchestra.ElementSelector
import maestro.orchestra.ScrollUntilVisibleCommand
import org.junit.Test
import xyz.block.trailblaze.devices.TrailblazeDriverType

/**
 * Locks in the LLM-facing wording of the failure message produced by
 * [ScrollUntilTextIsVisibleTrailblazeTool] when a target text cannot be located.
 *
 * The full scroll loop is heavy to fixture (Maestro driver, agent, screen state
 * provider). This test exercises the pure message-building helper because the
 * goal is to guarantee the LLM-actionable signal — the `objectiveStatus(FAILED)`
 * suggestion — appears in the right shape and position.
 */
class ScrollUntilTextIsVisibleTrailblazeToolTest {

  private fun command(): ScrollUntilVisibleCommand =
    ScrollUntilVisibleCommand(
      selector = ElementSelector(textRegex = ".*Pizza.*"),
      direction = ScrollDirection.UP,
      visibilityPercentage = ScrollUntilVisibleCommand.DEFAULT_ELEMENT_VISIBILITY_PERCENTAGE,
      centerElement = ScrollUntilVisibleCommand.DEFAULT_CENTER_ELEMENT,
    )

  @Test
  fun `plain text builds a substring-matching regex`() {
    // Existing callers pass `text`; behavior must stay a contains match (wrapped + escaped).
    val regex = ScrollUntilTextIsVisibleTrailblazeTool.buildTargetTextRegex(
      text = "Loyalty",
      textRegex = null,
    )
    assertThat(regex).isEqualTo(".*\\QLoyalty\\E.*")
    // A contains match accepts the substring occurrence…
    assertThat(regex.toRegex().matches("Loyalty Enroll")).isTrue()
    assertThat(regex.toRegex().matches("Loyalty")).isTrue()
  }

  @Test
  fun `plain text is regex-escaped so special characters are literal`() {
    val regex = ScrollUntilTextIsVisibleTrailblazeTool.buildTargetTextRegex(
      text = "Loading...",
      textRegex = null,
    )
    // The dots must be literal, not any-char wildcards.
    assertThat(regex.toRegex().matches("Loading...")).isTrue()
    assertThat(regex.toRegex().matches("LoadingXYZ")).isFalse()
  }

  @Test
  fun `textRegex is used verbatim for an anchored full match`() {
    // The opt-in anchored path: `Loyalty` must match only "Loyalty", not "Loyalty Enroll",
    // mirroring how selector tools resolve text.
    val regex = ScrollUntilTextIsVisibleTrailblazeTool.buildTargetTextRegex(
      text = "",
      textRegex = "Loyalty",
    )
    assertThat(regex).isEqualTo("Loyalty")
    assertThat(regex.toRegex().matches("Loyalty")).isTrue()
    assertThat(regex.toRegex().matches("Loyalty Enroll")).isFalse()
  }

  @Test
  fun `textRegex takes precedence over text when both are provided`() {
    val regex = ScrollUntilTextIsVisibleTrailblazeTool.buildTargetTextRegex(
      text = "ignored",
      textRegex = "Loyalty",
    )
    assertThat(regex).isEqualTo("Loyalty")
  }

  @Test
  fun `blank textRegex falls back to the substring path`() {
    val regex = ScrollUntilTextIsVisibleTrailblazeTool.buildTargetTextRegex(
      text = "Loyalty",
      textRegex = "   ",
    )
    assertThat(regex).isEqualTo(".*\\QLoyalty\\E.*")
  }

  @Test
  fun `hasScrollTarget requires at least one of text, textRegex, or id`() {
    // A call with a real target in any single slot is accepted…
    assertThat(ScrollUntilTextIsVisibleTrailblazeTool.hasScrollTarget("Loyalty", null, null)).isTrue()
    assertThat(ScrollUntilTextIsVisibleTrailblazeTool.hasScrollTarget("", "Loyalty", null)).isTrue()
    assertThat(ScrollUntilTextIsVisibleTrailblazeTool.hasScrollTarget("", null, "some_id")).isTrue()
  }

  @Test
  fun `hasTextTarget reads a blank textRegex as unwritten, so text still targets`() {
    // The precedence every driver must share: a blank regex is not "a regex for empty text".
    assertThat(ScrollUntilTextIsVisibleTrailblazeTool.hasTextTarget("Loyalty", "")).isTrue()
    assertThat(ScrollUntilTextIsVisibleTrailblazeTool.hasTextTarget("Loyalty", null)).isTrue()
    assertThat(ScrollUntilTextIsVisibleTrailblazeTool.hasTextTarget("", "Loyalty")).isTrue()
    // …and with neither, the only possible target is `id` (or none — see hasScrollTarget).
    assertThat(ScrollUntilTextIsVisibleTrailblazeTool.hasTextTarget("", "  ")).isFalse()
    assertThat(ScrollUntilTextIsVisibleTrailblazeTool.hasTextTarget("   ", null)).isFalse()
  }

  @Test
  fun `hasScrollTarget rejects a target-less call (would match everything)`() {
    // No text, no textRegex, no id → the tool would build `.*\Q\E.*` (match-all) and false-pass.
    assertThat(ScrollUntilTextIsVisibleTrailblazeTool.hasScrollTarget("", null, null)).isFalse()
    // Blank/whitespace-only values (e.g. a variable that resolved to blank) are also rejected.
    assertThat(ScrollUntilTextIsVisibleTrailblazeTool.hasScrollTarget("   ", "  ", "  ")).isFalse()
  }

  @Test
  fun `resolveScrollDuration uses the caller override when provided`() {
    // A caller-supplied duration wins over the driver default (the fast-scroll case: 200ms).
    assertThat(
      ScrollUntilTextIsVisibleTrailblazeTool.resolveScrollDuration(
        scrollDurationMs = 200,
        trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
      ),
    ).isEqualTo("200")
  }

  @Test
  fun `resolveScrollDuration falls back to the driver-tuned default when null`() {
    // Null preserves prior behavior for every existing caller: 400ms on Android on-device.
    assertThat(
      ScrollUntilTextIsVisibleTrailblazeTool.resolveScrollDuration(
        scrollDurationMs = null,
        trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
      ),
    ).isEqualTo(
      ScrollUntilTextIsVisibleTrailblazeTool.scrollDurationFor(
        TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
      ),
    )
  }

  @Test
  fun `resolveScrollDuration rejects a non-positive override`() {
    // 0 or a negative override would flow into SwipeCommand.duration as an invalid gesture.
    for (invalid in listOf(0, -1, -400)) {
      val failure = assertFailsWith<IllegalArgumentException> {
        ScrollUntilTextIsVisibleTrailblazeTool.resolveScrollDuration(
          scrollDurationMs = invalid,
          trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
        )
      }
      assertThat(failure.message.orEmpty()).contains("scrollDurationMs must be > 0")
    }
  }

  @Test
  fun `resolveCenterElement uses the caller override when provided`() {
    // An explicit value always wins over the driver-tuned default, in both directions.
    assertThat(
      ScrollUntilTextIsVisibleTrailblazeTool.resolveCenterElement(
        centerElement = false,
        trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
        direction = ScrollDirection.DOWN,
      ),
    ).isFalse()
    assertThat(
      ScrollUntilTextIsVisibleTrailblazeTool.resolveCenterElement(
        centerElement = true,
        trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
        direction = ScrollDirection.DOWN,
      ),
    ).isTrue()
  }

  @Test
  fun `resolveCenterElement defaults to centering on the accessibility driver`() {
    // The accessibility driver's dispatchGesture swipes produce no fling, so per-swipe content
    // travel is ~1.4x shorter than the instrumentation driver's `input swipe`. Centering pulls
    // the found element clear of the trailing screen edge, so steps recorded against the
    // instrumentation driver's longer travel don't find their target below the fold.
    for (direction in listOf(ScrollDirection.DOWN, ScrollDirection.UP)) {
      assertThat(
        ScrollUntilTextIsVisibleTrailblazeTool.resolveCenterElement(
          centerElement = null,
          trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
          direction = direction,
        ),
      ).isTrue()
    }
  }

  @Test
  fun `resolveCenterElement does not default to centering a horizontal scroll`() {
    // A horizontal corrective swipe moves content 80% of the screen width, but the gate only
    // rejects a target past 70% of it, so a target in between overshoots off the opposite edge —
    // out of the hierarchy, where no later swipe can bring it back and the loop can only fail.
    // Vertical strokes move 40% of the height against the same gate, so they cannot overshoot.
    for (direction in listOf(ScrollDirection.LEFT, ScrollDirection.RIGHT)) {
      assertThat(
        ScrollUntilTextIsVisibleTrailblazeTool.resolveCenterElement(
          centerElement = null,
          trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
          direction = direction,
        ),
      ).isFalse()
    }
  }

  @Test
  fun `resolveCenterElement honors an explicit request to center a horizontal scroll`() {
    // Only the DEFAULT is vertical-only. A caller that asks for centering knows its own screen.
    assertThat(
      ScrollUntilTextIsVisibleTrailblazeTool.resolveCenterElement(
        centerElement = true,
        trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
        direction = ScrollDirection.RIGHT,
      ),
    ).isTrue()
  }

  @Test
  fun `resolveCenterElement keeps the Maestro default on other drivers`() {
    for (driverType in listOf(
      TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
      TrailblazeDriverType.IOS_AXE,
      TrailblazeDriverType.IOS_HOST,
    )) {
      assertThat(
        ScrollUntilTextIsVisibleTrailblazeTool.resolveCenterElement(
          centerElement = null,
          trailblazeDriverType = driverType,
          direction = ScrollDirection.DOWN,
        ),
      ).isEqualTo(ScrollUntilVisibleCommand.DEFAULT_CENTER_ELEMENT)
    }
  }

  @Test
  fun `failure message leads with cannot-find-element line`() {
    val message = ScrollUntilTextIsVisibleTrailblazeTool.buildScrollFailureMessage(
      maestroCommand = command(),
    )
    val firstLine = message.lineSequence().first()
    assertThat(firstLine).contains("Could not find an element matching")
  }

  @Test
  fun `failure message suggests calling objectiveStatus FAILED`() {
    val message = ScrollUntilTextIsVisibleTrailblazeTool.buildScrollFailureMessage(
      maestroCommand = command(),
    )
    assertThat(message).contains("objectiveStatus(FAILED)")
  }

  @Test
  fun `framework tuning hints come AFTER the LLM-actionable advice`() {
    // The LLM relies on the head of the message — the give-up signal must come first
    // so the LLM doesn't get distracted by framework knobs it cannot adjust mid-run.
    val message = ScrollUntilTextIsVisibleTrailblazeTool.buildScrollFailureMessage(
      maestroCommand = command(),
    )
    val giveUpIdx = message.indexOf("objectiveStatus(FAILED)")
    val tuningIdx = message.indexOf("Framework tuning hints")
    assert(giveUpIdx in 0 until tuningIdx) {
      "Expected objectiveStatus(FAILED) (idx=$giveUpIdx) to appear before " +
        "Framework tuning hints (idx=$tuningIdx) in:\n$message"
    }
  }

  @Test
  fun `framework tuning hints retain timeout and visibility advice`() {
    val message = ScrollUntilTextIsVisibleTrailblazeTool.buildScrollFailureMessage(
      maestroCommand = command(),
    )
    assertThat(message).contains("`timeout`")
    assertThat(message).contains("`visibilityPercentage`")
    assertThat(message).contains("`centerElement`")
  }

  @Test
  fun `manual-scroll-loop drivers use the non-fling 400ms swipe duration`() {
    // Drivers without a Maestro Driver instance run the manual scroll loop; the Maestro
    // default (40ms) flings past elements there.
    assertThat(
      ScrollUntilTextIsVisibleTrailblazeTool.scrollDurationFor(TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY),
    ).isEqualTo("400")
    assertThat(
      ScrollUntilTextIsVisibleTrailblazeTool.scrollDurationFor(TrailblazeDriverType.IOS_AXE),
    ).isEqualTo("400")
    assertThat(
      ScrollUntilTextIsVisibleTrailblazeTool.scrollDurationFor(TrailblazeDriverType.IOS_HOST),
    ).isEqualTo(ScrollUntilVisibleCommand.DEFAULT_SCROLL_DURATION)
  }

  // ---- stall detection ----

  @Test
  fun `vertical scrolls measure position on the y axis and horizontal ones on the x axis`() {
    assertThat(
      ScrollUntilTextIsVisibleTrailblazeTool.scrollAxisCenter(
        direction = SwipeDirection.UP,
        bounds = Bounds(x = 0, y = 900, width = 200, height = 100),
      ),
    ).isEqualTo(950)
    assertThat(
      ScrollUntilTextIsVisibleTrailblazeTool.scrollAxisCenter(
        direction = SwipeDirection.LEFT,
        bounds = Bounds(x = 900, y = 0, width = 100, height = 50),
      ),
    ).isEqualTo(950)
  }

  @Test
  fun `the first corrective swipe is never treated as stalled`() {
    // No previous position to compare against; a false positive here would disable centering.
    assertThat(
      ScrollUntilTextIsVisibleTrailblazeTool.hasStalled(
        previousPosition = null,
        currentPosition = 1900,
        direction = SwipeDirection.UP,
        widthGrid = 1080,
        heightGrid = 2000,
      ),
    ).isFalse()
  }

  @Test
  fun `a swipe that cannot move the target ends the loop instead of burning the budget`() {
    // End of a list: the target is stuck against the edge, so the remaining swipes buy nothing.
    assertThat(
      ScrollUntilTextIsVisibleTrailblazeTool.hasStalled(
        previousPosition = 1900,
        currentPosition = 1905,
        direction = SwipeDirection.UP,
        widthGrid = 1080,
        heightGrid = 2000,
      ),
    ).isTrue()
  }

  @Test
  fun `one dropped swipe cannot end the loop because movement resets the streak`() {
    // A dropped swipe is indistinguishable from a list at its end. Stopping on one would leave the
    // target un-centered — the defect centering exists to prevent.
    val tool = ScrollUntilTextIsVisibleTrailblazeTool
    var streak = tool.stallStreak(0, null, 1900, SwipeDirection.UP, 1080, 2000)
    streak = tool.stallStreak(streak, 1900, 1902, SwipeDirection.UP, 1080, 2000) // dropped swipe
    assertThat(streak).isEqualTo(1)
    streak = tool.stallStreak(streak, 1902, 1400, SwipeDirection.UP, 1080, 2000) // swipe lands
    assertThat(streak).isEqualTo(0)
    assertThat(streak >= ScrollUntilTextIsVisibleTrailblazeTool.STALLS_BEFORE_GIVING_UP).isFalse()
  }

  @Test
  fun `two no-movement readings in a row end the loop`() {
    // A list genuinely at its end: the remaining retry budget cannot improve the position.
    val tool = ScrollUntilTextIsVisibleTrailblazeTool
    var streak = tool.stallStreak(0, 1900, 1902, SwipeDirection.UP, 1080, 2000)
    streak = tool.stallStreak(streak, 1902, 1901, SwipeDirection.UP, 1080, 2000)
    assertThat(streak).isEqualTo(ScrollUntilTextIsVisibleTrailblazeTool.STALLS_BEFORE_GIVING_UP)
  }

  @Test
  fun `a swipe that DOES move the target keeps correcting`() {
    assertThat(
      ScrollUntilTextIsVisibleTrailblazeTool.hasStalled(
        previousPosition = 1900,
        currentPosition = 1500,
        direction = SwipeDirection.UP,
        widthGrid = 1080,
        heightGrid = 2000,
      ),
    ).isFalse()
  }

  @Test
  fun `a target left visible when the budget runs out is still a success`() {
    assertThat(
      ScrollUntilTextIsVisibleTrailblazeTool.timedOutTargetIsAcceptable(
        lastVisibility = 1.0,
        visibilityPercentageNormalized = 1.0,
        swipedSinceLastRead = false,
        isVerticalScroll = true,
      ),
    ).isTrue()
  }

  @Test
  fun `centering running out of time cannot fail a scroll that visibility alone would pass`() {
    // Same reading, both settings: turning centering on must not change the verdict for a target
    // that already clears the visibility bar.
    val halfVisibleBar = 0.5
    assertThat(
      ScrollUntilTextIsVisibleTrailblazeTool.timedOutTargetIsAcceptable(
        lastVisibility = 0.8,
        visibilityPercentageNormalized = halfVisibleBar,
        swipedSinceLastRead = false,
        isVerticalScroll = true,
      ),
    ).isTrue()
  }

  @Test
  fun `a target below the required visibility still fails on timeout`() {
    assertThat(
      ScrollUntilTextIsVisibleTrailblazeTool.timedOutTargetIsAcceptable(
        lastVisibility = 0.4,
        visibilityPercentageNormalized = 1.0,
        swipedSinceLastRead = false,
        isVerticalScroll = true,
      ),
    ).isFalse()
  }

  @Test
  fun `a target that scrolled out of view is not vouched for on timeout`() {
    assertThat(
      ScrollUntilTextIsVisibleTrailblazeTool.timedOutTargetIsAcceptable(
        lastVisibility = 0.0,
        visibilityPercentageNormalized = 1.0,
        swipedSinceLastRead = false,
        isVerticalScroll = true,
      ),
    ).isFalse()
  }

  @Test
  fun `a target that never matched is never reported as found on timeout`() {
    // Maestro floors the bar to 0.0 for every percentage below 100, so a "matched nothing" reading
    // has to stay distinguishable from a 0.0 visibility one or it would clear that bar.
    assertThat(
      ScrollUntilTextIsVisibleTrailblazeTool.timedOutTargetIsAcceptable(
        lastVisibility = null,
        visibilityPercentageNormalized = 0.0,
        swipedSinceLastRead = false,
        isVerticalScroll = true,
      ),
    ).isFalse()
  }

  @Test
  fun `a matched target still passes a zero bar on timeout`() {
    assertThat(
      ScrollUntilTextIsVisibleTrailblazeTool.timedOutTargetIsAcceptable(
        lastVisibility = 0.0,
        visibilityPercentageNormalized = 0.0,
        swipedSinceLastRead = false,
        isVerticalScroll = true,
      ),
    ).isTrue()
  }

  @Test
  fun `any visibilityPercentage below 100 normalizes to a zero bar`() {
    // Pins the Maestro behavior the null-check above exists for: this is integer division, so 50%
    // does NOT become 0.5. If this ever returns 0.5, revisit that reasoning.
    assertThat(
      ScrollUntilVisibleCommand(
        selector = ElementSelector(textRegex = "anything"),
        direction = ScrollDirection.DOWN,
        visibilityPercentage = 50,
        centerElement = true,
      ).visibilityPercentageNormalized,
    ).isEqualTo(0.0)
  }

  @Test
  fun `a stalled list still has to clear the visibility bar`() {
    // The end of a list holds the target wherever it landed. Giving up on centering there says
    // nothing about how much of it is on screen, so a sliver must not be reported as found.
    assertThat(
      ScrollUntilTextIsVisibleTrailblazeTool.stalledTargetIsAcceptable(
        consecutiveStalls = ScrollUntilTextIsVisibleTrailblazeTool.STALLS_BEFORE_GIVING_UP,
        visibility = 0.15,
        visibilityPercentageNormalized = 1.0,
      ),
    ).isFalse()
  }

  @Test
  fun `a stalled list that meets the bar stops scrolling immediately`() {
    assertThat(
      ScrollUntilTextIsVisibleTrailblazeTool.stalledTargetIsAcceptable(
        consecutiveStalls = ScrollUntilTextIsVisibleTrailblazeTool.STALLS_BEFORE_GIVING_UP,
        visibility = 1.0,
        visibilityPercentageNormalized = 1.0,
      ),
    ).isTrue()
  }

  @Test
  fun `a list that is still moving is never treated as stalled`() {
    // A fully visible target that could still be scrolled closer to center is the centering
    // loop's normal case, not an early exit.
    assertThat(
      ScrollUntilTextIsVisibleTrailblazeTool.stalledTargetIsAcceptable(
        consecutiveStalls = ScrollUntilTextIsVisibleTrailblazeTool.STALLS_BEFORE_GIVING_UP - 1,
        visibility = 1.0,
        visibilityPercentageNormalized = 1.0,
      ),
    ).isFalse()
  }

  @Test
  fun `a horizontal correction cannot vouch for the position it may have swiped away from`() {
    // Centering defaults to vertical only, but an explicit `centerElement: true` still reaches the
    // timeout on a horizontal scroll, where the stroke can carry the target off the opposite edge.
    assertThat(
      ScrollUntilTextIsVisibleTrailblazeTool.timedOutTargetIsAcceptable(
        lastVisibility = 1.0,
        visibilityPercentageNormalized = 1.0,
        swipedSinceLastRead = true,
        isVerticalScroll = false,
      ),
    ).isFalse()
  }

  @Test
  fun `a horizontal reading with no swipe after it is still current`() {
    assertThat(
      ScrollUntilTextIsVisibleTrailblazeTool.timedOutTargetIsAcceptable(
        lastVisibility = 1.0,
        visibilityPercentageNormalized = 1.0,
        swipedSinceLastRead = false,
        isVerticalScroll = false,
      ),
    ).isTrue()
  }

  @Test
  fun `a bounded vertical correction still vouches for the target`() {
    // The whole point of accepting a timed-out reading: vertical travel is bounded, so the target
    // is still on screen and a scroll that was visible all along must not fail for want of budget.
    assertThat(
      ScrollUntilTextIsVisibleTrailblazeTool.timedOutTargetIsAcceptable(
        lastVisibility = 1.0,
        visibilityPercentageNormalized = 1.0,
        swipedSinceLastRead = true,
        isVerticalScroll = true,
      ),
    ).isTrue()
  }
}
