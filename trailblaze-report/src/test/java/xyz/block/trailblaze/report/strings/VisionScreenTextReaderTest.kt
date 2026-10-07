package xyz.block.trailblaze.report.strings

import org.junit.Test
import xyz.block.trailblaze.api.TrailblazeNode
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VisionScreenTextReaderTest {

  private fun assertRegion(expected: List<Double>, actual: List<Double>?) {
    requireNotNull(actual)
    expected.zip(actual).forEach { (e, a) -> assertEquals(e, a, 1e-9) }
  }

  @Test
  fun `a box becomes fractions of the screen measured from its bottom-left corner`() {
    assertRegion(
      listOf(0.25, 0.75, 0.5, 0.125),
      visionRegion(TrailblazeNode.Bounds(100, 100, 300, 200), deviceWidth = 400, deviceHeight = 800),
    )
  }

  @Test
  fun `a box partly offscreen keeps only its part on screen`() {
    assertRegion(
      listOf(0.0, 0.0, 0.25, 0.125),
      visionRegion(TrailblazeNode.Bounds(-50, 700, 100, 900), deviceWidth = 400, deviceHeight = 800),
    )
  }

  @Test
  fun `a box with no area on screen has no region`() {
    assertNull(visionRegion(TrailblazeNode.Bounds(0, 900, 400, 1000), deviceWidth = 400, deviceHeight = 800))
    assertNull(visionRegion(TrailblazeNode.Bounds(100, 100, 100, 200), deviceWidth = 400, deviceHeight = 800))
  }

  @Test
  fun `nothing is read without a screenshot or a screen size`() {
    val box = listOf(TrailblazeNode.Bounds(0, 0, 100, 100))
    assertNull(VisionScreenTextReader(File("missing.png"), 400, 800).read(box))
    val screenshot = File.createTempFile("shot", ".png").apply { deleteOnExit() }
    assertNull(VisionScreenTextReader(screenshot, 0, 800).read(box))
  }
}
