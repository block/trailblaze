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
  fun becomeADeveloperAi() {
    trailblazeRule.run(
      testYaml = """
- maestro:
  - launchApp:
      appId: com.android.settings
      stopApp: true
- prompts:
    - step: Open the "System" section of the Settings app
    - step: Tap on the "about device" section.
    - step: Find the "Build number" and tap on it 7 times.
      """.trimIndent(),
    )
  }

  @Test
  fun becomeADeveloperMaestroYaml() {
    trailblazeRule.run(
      """

- maestro:
  - launchApp:
      appId: com.android.settings
      stopApp: true
- prompts:
  - step: Open the "System" section of the Settings app
    recording:
      tools:
      - scrollUntilTextIsVisible:
          text: System
          direction: DOWN
      - tapOnElementWithText:
          text: System
  - step: Tap on the "about device" section.
    recording:
      tools:
      - tapOnElementWithAccessibilityText:
          accessibilityText: Back
  - step: Find the "Build number" and tap on it 7 times.
    recording:
      tools:
      - tapOnElementWithText:
          text: About emulated device
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
      - tapOnElementWithText:
          text: Build number
      """.trimIndent(),
    )
  }
}
