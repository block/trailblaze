package xyz.block.trailblaze.examples.calculator

import org.junit.Rule
import org.junit.Test
import xyz.block.trailblaze.examples.ExamplesAndroidTrailblazeRuleOpenAiTrailblazeRule
import xyz.block.trailblaze.exception.TrailblazeException

/**
 * Example test showing how to use Trailblaze with AI to use the Calculator app via prompts.
 */
class AndroidCalculatorOnePlusTwoAiTest {

  @get:Rule
  val trailblazeRule = ExamplesAndroidTrailblazeRuleOpenAiTrailblazeRule()

  @Test
  fun trailblazeSuccess() {
    trailblazeRule.run(
      testYaml = """
- maestro:
  - launchApp:
      appId: com.android.calculator2
- prompts:
  - step: calculate 1+2
  - step: verify the result is 3
      """.trimIndent(),
    )
  }

  @Test(expected = TrailblazeException::class)
  fun trailblazeExpectedFailure() {
    trailblazeRule.run(
      """
- maestro:
  - launchApp:
      appId: com.android.calculator2
- prompts:
  - step: calculate 1+2
  - step: verify the result is 4
      """.trimIndent(),
    )
  }
}
