package xyz.block.trailblaze.toolcalls.commands

import maestro.orchestra.TapOnPointV2Command
import org.junit.Test
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/** [TapOnPercentTrailblazeTool] taps a point expressed as a percentage of the screen. */
class TapOnPercentTrailblazeToolTest {

  private val trailblazeYaml = createTrailblazeYaml()

  private fun point(tool: TapOnPercentTrailblazeTool): String =
    assertIs<TapOnPointV2Command>(tool.toMaestroCommands().single()).point

  private fun decode(args: String): TapOnPercentTrailblazeTool {
    val yaml = "config: {}\ntrail:\n  - step: recorded\n    recording:\n      android:\n" +
      "        - tapOnPercent:\n" + args.lines().joinToString("\n") { "            $it" }
    val tool = trailblazeYaml.decodeTrail(yaml, listOf(TrailblazeDeviceClassifier("android")))
      .filterIsInstance<TrailYamlItem.PromptsTrailItem>().single()
      .promptSteps.single().recording!!.tools.single().trailblazeTool
    return assertIs<TapOnPercentTrailblazeTool>(tool)
  }

  private fun encode(tool: TapOnPercentTrailblazeTool): String =
    trailblazeYaml.encodeToolToYaml("tapOnPercent", tool)

  @Test
  fun `taps the percent point of the screen, with no repeat or long press`() {
    val command = assertIs<TapOnPointV2Command>(
      TapOnPercentTrailblazeTool(x = 50.0, y = 26.0).toMaestroCommands().single(),
    )

    assertEquals("50%,26%", command.point)
    assertNull(command.repeat)
    assertNull(command.longPress)
  }

  @Test
  fun `a decimal percent keeps its fraction`() {
    assertEquals("50.5%,26.25%", point(TapOnPercentTrailblazeTool(x = 50.5, y = 26.25)))
  }

  @Test
  fun `accepts both screen edges`() {
    assertEquals("0%,100%", point(TapOnPercentTrailblazeTool(x = 0.0, y = 100.0)))
  }

  @Test
  fun `a whole-number trail round-trips with no decimal point`() {
    val tool = decode("x: 50\ny: 26")

    assertEquals(TapOnPercentTrailblazeTool(x = 50.0, y = 26.0), tool)
    assertEquals("50%,26%", point(tool))
    assertEquals("tapOnPercent:\n  x: 50\n  y: 26", encode(tool).trim())
  }

  @Test
  fun `a decimal trail round-trips unchanged`() {
    val tool = decode("x: 50.5\ny: 26")

    assertEquals(TapOnPercentTrailblazeTool(x = 50.5, y = 26.0), tool)
    assertEquals("50.5%,26%", point(tool))
    assertEquals("tapOnPercent:\n  x: 50.5\n  y: 26", encode(tool).trim())
  }

  @Test
  fun `rejects a percentage outside the screen`() {
    val tooFarRight = assertFailsWith<IllegalArgumentException> {
      TapOnPercentTrailblazeTool(x = 101.0, y = 10.0)
    }
    assertEquals(
      "tapOnPercent: x and y must each be between 0 and 100, got x=101, y=10.",
      tooFarRight.message,
    )
    assertFailsWith<IllegalArgumentException> { TapOnPercentTrailblazeTool(x = 10.0, y = -1.0) }
    assertFailsWith<IllegalArgumentException> { TapOnPercentTrailblazeTool(x = 100.5, y = 10.0) }
    assertFailsWith<IllegalArgumentException> { TapOnPercentTrailblazeTool(x = 10.0, y = -0.5) }
    assertFailsWith<IllegalArgumentException> { TapOnPercentTrailblazeTool(x = Double.NaN, y = 10.0) }
  }
}
