package xyz.block.trailblaze.examples.calculator

import maestro.orchestra.LaunchAppCommand
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import xyz.block.trailblaze.android.AndroidTrailblazeRule
import xyz.block.trailblaze.exception.TrailblazeException

/**
 * Example test showing how to use Trailblaze with AI to use the Calculator app via prompts.
 */
class AndroidCalculatorOnePlusTwoAiTest {

  @get:Rule
  val trailblazeRule = AndroidTrailblazeRule()

  @Before
  fun setUp() {
    trailblazeRule.maestroCommands(
      LaunchAppCommand(
        appId = "com.android.calculator2"
      )
    )
  }

  @Test
  fun trailblazeSuccess() {
    trailblazeRule.prompt(
      """
      - calculate 1+2
      - verify the result is 3
      """.trimIndent()
    )
  }

  @Test(expected = TrailblazeException::class)
  fun trailblazeExpectedFailure() {
    trailblazeRule.prompt(
      """
      - calculate 1+2
      - verify the result is 4
      """.trimIndent()
    )
  }

}
