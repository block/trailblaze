package xyz.block.trailblaze.toolcalls.commands

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
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
  fun `hasScrollTarget rejects a target-less call (would match everything)`() {
    // No text, no textRegex, no id → the tool would build `.*\Q\E.*` (match-all) and false-pass.
    assertThat(ScrollUntilTextIsVisibleTrailblazeTool.hasScrollTarget("", null, null)).isFalse()
    // Blank/whitespace-only values (e.g. a variable that resolved to blank) are also rejected.
    assertThat(ScrollUntilTextIsVisibleTrailblazeTool.hasScrollTarget("   ", "  ", "  ")).isFalse()
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
}
