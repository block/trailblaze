package xyz.block.trailblaze.examples.clock

import maestro.orchestra.LaunchAppCommand
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import xyz.block.trailblaze.android.AndroidTrailblazeRule

/**
 * Example test showing how to use Trailblaze with AI to use the Clock app via prompts.
 */
class ClockTest {

  @get:Rule
  val trailblazeRule = AndroidTrailblazeRule()

  @Before
  fun setUp() {
    trailblazeRule.maestroCommands(
      LaunchAppCommand(
        appId = "com.google.android.deskclock",
        stopApp = false,
        clearState = false,
      )
    )
  }

  @Test
  fun setAnAlarm() {
    trailblazeRule.prompt(
      """
      - Add a new alarm for 7:30 AM
      - After it's been added, turn it off
      - Delete the alarm
      """.trimIndent()
    )
  }

}
