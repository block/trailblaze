package xyz.block.trailblaze.toolcalls.commands

import assertk.assertThat
import assertk.assertions.contains
import maestro.ScrollDirection
import maestro.orchestra.ElementSelector
import maestro.orchestra.ScrollUntilVisibleCommand
import org.junit.Test

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
}
