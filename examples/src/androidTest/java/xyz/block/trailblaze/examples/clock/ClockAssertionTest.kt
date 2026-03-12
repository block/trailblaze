package xyz.block.trailblaze.examples.clock

import org.junit.Rule
import org.junit.Test
import xyz.block.trailblaze.examples.rules.ExamplesAndroidTrailblazeRule

/**
 * Tests that exercise assertion tools (assertVisible, assertNotVisible) against the Clock app.
 *
 * These trails use recorded tools, so they don't require an LLM for execution.
 * When run with the accessibility driver (via agentOverride), assertions go through
 * AccessibilityDeviceManager.executeAssertVisible/executeAssertNotVisible instead of Maestro.
 *
 * Trail files live in `opensource/trails/clock/`.
 */
class ClockAssertionTest {

  @get:Rule
  val trailblazeRule = ExamplesAndroidTrailblazeRule()

  /** Basic assertVisible: launch Clock, verify ALARM tab and FAB are visible. */
  @Test
  fun assertAlarmTabVisible() = trailblazeRule.runFromAsset("clock/assert-alarm-tab-visible")

  /** Tap + assertVisible: navigate between tabs, verify elements after navigation. */
  @Test
  fun assertAfterNavigation() = trailblazeRule.runFromAsset("clock/assert-after-navigation")

  /** assertNotVisible: verify elements that should NOT be on screen. */
  @Test
  fun assertNotVisible() = trailblazeRule.runFromAsset("clock/assert-not-visible")

  /** Mixed flow: create alarm, assertVisible, delete it, assertNotVisible. */
  @Test
  fun assertMixedVisibility() = trailblazeRule.runFromAsset("clock/assert-mixed-visibility")
}
