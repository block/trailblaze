package xyz.block.trailblaze.examples.calculator

import ai.koog.prompt.executor.clients.openai.OpenAIModels
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import xyz.block.trailblaze.android.openai.OpenAiTrailblazeRule
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.toolcalls.commands.LaunchAppTrailblazeTool

/**
 * Example test showing how to mix prompts with static commands.
 */
class AndroidCalculatorOnePlusTwoMixedTest {

  @get:Rule
  val trailblazeRule = OpenAiTrailblazeRule(
    llmModel = OpenAIModels.Chat.GPT4_1,
  )

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
    trailblazeRule.prompt(
      """
      - calculate 1+2
      """.trimIndent(),
    )
    trailblazeRule.maestro(
      """
- assertVisible:
    id: "com.android.calculator2:id/result"
    text: "3"
      """.trimIndent(),
    )
  }

  @Test(expected = TrailblazeException::class)
  fun trailblazeSuccessWithManualAssertionExpectedFailure() {
    trailblazeRule.prompt(
      """
      - calculate 1+2
      """.trimIndent(),
    )
    // This will fail because the result is 3, not 4.
    trailblazeRule.maestro(
      """
- assertVisible:
    id: "com.android.calculator2:id/result"
    text: "4"
      """.trimIndent(),
    )
  }
}
