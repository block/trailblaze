package xyz.block.trailblaze.examples.settings

import org.junit.Rule
import org.junit.Test
import xyz.block.trailblaze.examples.ExamplesAndroidTrailblazeRuleOpenAiTrailblazeRule

/**
 * Example showing how to use Trailblaze with Settings app via prompts and maestro.
 */
class AndroidSettingsTest {

  @get:Rule
  val trailblazeRule = ExamplesAndroidTrailblazeRuleOpenAiTrailblazeRule()

  @Test
  fun almostBecomeADeveloperAi() {
    trailblazeRule.run(
      """
- maestro:
  - launchApp:
      appId: com.android.settings
      stopApp: true
- prompts:
  - step: Open the "About emulated device" section of the Settings app
  - step: Tap on "Build number" 6 times.
      """.trimIndent(),
    )
  }

  @Test
  fun almostBecomeADeveloperRecorded() {
    trailblazeRule.run(
      """
- maestro:
  - launchApp:
      appId: com.android.settings
      stopApp: true
- prompts:
  - step: Open the "About emulated device" section of the Settings app
    recording:
      tools:
      - scrollUntilTextIsVisible:
          text: About emulated device
          direction: DOWN
      - tapOnElementWithText:
          text: About emulated device
  - step: Tap on "Build number" 6 times.
    recording:
      tools:
      - scrollUntilTextIsVisible:
          text: Build number
          direction: DOWN
      - tapOnElementWithText:
          text: Build number
      - tapOnElementWithText:
          text: Build number
      - tapOnElementWithText:
          text: Build number
      - tapOnElementWithText:
          text: Build number
      - tapOnElementWithText:
          text: Build number
      - tapOnElementWithText:
          text: Build number
      """.trimIndent(),
    )
  }
}
