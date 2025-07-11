package xyz.block.trailblaze.examples.settings

import ai.koog.prompt.executor.clients.openai.OpenAIModels
import maestro.orchestra.LaunchAppCommand
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import xyz.block.trailblaze.android.openai.OpenAiTrailblazeRule

/**
 * Example showing how to use Trailblaze with Settings app via prompts and maestro.
 */
class AndroidSettingsTest {

  @get:Rule
  val trailblazeRule = OpenAiTrailblazeRule(
    llmModel = OpenAIModels.Reasoning.O3
  )

  @Before
  fun setUp() {
    trailblazeRule.maestroCommands(
      LaunchAppCommand(
        appId = "com.android.settings",
        stopApp = false,
        clearState = false,
      )
    )
  }

  @Test
  fun becomeADeveloperAi() {
    trailblazeRule.prompt(
      """
      - Open the "System" section of the Settings app
      - Tap on the "about device" section.
      - Find the "Build number" and tap on it 7 times.
      """.trimIndent()
    )
  }

  @Test
  fun becomeADeveloperMaestroYaml() {
    trailblazeRule.maestro(
      """
- tapOn:
    text: Search settings
- inputText:
    text: Build number
- tapOn:
    id: "android:id/title"
    text: Build number
- tapOn:
    text: Build number
    repeat: 7
    retryTapIfNoChange: false
      """.trimIndent()
    )
  }

}
