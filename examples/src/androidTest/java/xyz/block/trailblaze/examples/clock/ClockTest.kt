package xyz.block.trailblaze.examples.clock

import org.junit.Rule
import org.junit.Test
import xyz.block.trailblaze.examples.ExamplesAndroidTrailblazeRuleOpenAiTrailblazeRule

/**
 * Example test showing how to use Trailblaze with AI to use the Clock app via prompts.
 */
class ClockTest {

  @get:Rule
  val trailblazeRule = ExamplesAndroidTrailblazeRuleOpenAiTrailblazeRule()

  @Test
  fun setAnAlarm() = trailblazeRule.runFromAsset()
}
