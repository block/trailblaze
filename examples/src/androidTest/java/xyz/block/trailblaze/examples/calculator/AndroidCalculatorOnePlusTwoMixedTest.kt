package xyz.block.trailblaze.examples.calculator

import org.junit.Before
import org.junit.Rule
import org.junit.Test
import xyz.block.trailblaze.examples.ExamplesAndroidTrailblazeRuleOpenAiTrailblazeRule
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.toolcalls.commands.LaunchAppTrailblazeTool

/**
 * Example test showing how to mix prompts with static commands.
 */
class AndroidCalculatorOnePlusTwoMixedTest {

  @get:Rule
  val trailblazeRule = ExamplesAndroidTrailblazeRuleOpenAiTrailblazeRule()

  @Before
  fun setUp() {
    trailblazeRule.tool(
      LaunchAppTrailblazeTool(
        appId = "com.android.calculator2",
        launchMode = LaunchAppTrailblazeTool.LaunchMode.REINSTALL,
      ),
    )
  }

  @Test
  fun trailblazeSuccessWithManualAssertion() {
    trailblazeRule.run(
      """
- maestro:
  - launchApp:
      appId: com.android.calculator2
- prompts:
  - step: calculate 1+2
- maestro:
    - assertVisible:
        id: "com.android.calculator2:id/result"
        text: "3"
      """.trimIndent(),
    )
  }

  @Test(expected = TrailblazeException::class)
  fun trailblazeSuccessWithManualAssertionExpectedFailure() {
    trailblazeRule.run(
      """
- maestro:
  - launchApp:
      appId: com.android.calculator2
- prompts:
  - step: calculate 1+2
- maestro:
    - assertVisible:
        id: "com.android.calculator2:id/result"
        text: "4"
      """.trimIndent(),
    )
  }
}
