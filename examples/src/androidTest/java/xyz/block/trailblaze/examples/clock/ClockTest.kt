package xyz.block.trailblaze.examples.clock

import ai.koog.prompt.executor.clients.openai.OpenAIModels
import maestro.orchestra.LaunchAppCommand
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import xyz.block.trailblaze.android.openai.OpenAiTrailblazeRule

/**
 * Example test showing how to use Trailblaze with AI to use the Clock app via prompts.
 */
class ClockTest {

  @get:Rule
  val trailblazeRule = OpenAiTrailblazeRule(
    llmModel = OpenAIModels.Chat.GPT4_1,
  )

  @Before
  fun setUp() {
    trailblazeRule.maestroCommands(
      LaunchAppCommand(
        appId = "com.google.android.deskclock",
        stopApp = false,
        clearState = false,
      ),
    )
  }

  @Test
  fun setAnAlarm() {
    trailblazeRule.prompt(
      """
      - Add a new alarm for 7:30 AM
      - After it's been added, turn it off
      - Delete the alarm
      """.trimIndent(),
    )
  }
}
