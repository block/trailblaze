package xyz.block.trailblaze.examples.clock

import org.junit.Before
import org.junit.Rule
import org.junit.Test
import xyz.block.trailblaze.examples.ExamplesAndroidTrailblazeRuleOpenAiTrailblazeRule
import xyz.block.trailblaze.toolcalls.commands.LaunchAppTrailblazeTool

/**
 * Example test showing how to use Trailblaze with AI to use the Clock app via prompts.
 */
class ClockTest {

  @get:Rule
  val trailblazeRule = ExamplesAndroidTrailblazeRuleOpenAiTrailblazeRule()

  @Before
  fun setUp() {
    trailblazeRule.tool(
      LaunchAppTrailblazeTool(
        appId = "com.google.android.deskclock",
        launchMode = LaunchAppTrailblazeTool.LaunchMode.FORCE_RESTART,
      ),
    )
  }

  @Test
  fun setAnAlarm() {
    trailblazeRule.run(
      """
- maestro:
  - launchApp:
      appId: com.google.android.deskclock
- prompts:
  - step: Add a new alarm for 7:30 AM
  - step: After it's been added, turn it off
  - step: Delete the alarm
      """.trimIndent(),
    )
  }
}
