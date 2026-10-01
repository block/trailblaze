package xyz.block.trailblaze.capture.video

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VideoStillsTest {

  // Frames every 1/3s from 0.061s, the shape of a real 3fps recording, in a 1/1000 time base.
  private val recording = VideoStills.Recording(
    durationSec = 2.0,
    width = 1080,
    height = 2340,
    timeBaseSec = 0.001,
    framePts = longArrayOf(61, 394, 728, 1061),
  )

  @Test
  fun `the frame at an instant is the last one presented at or before it`() {
    assertEquals(728, recording.frameAt(1.0), "1.0s is still showing the frame from 0.728s")
    assertEquals(1061, recording.frameAt(1.061), "a frame presented exactly then is the one on screen")
    assertEquals(1061, recording.frameAt(1.9), "the last frame holds to the end")
  }

  @Test
  fun `an instant before the first frame shows the first frame, as a player does`() {
    assertEquals(61, recording.frameAt(0.0))
  }

  @Test
  fun `a recording with no frames has none to give`() {
    assertNull(VideoStills.Recording(1.0, 1, 1, 0.001, longArrayOf()).frameAt(0.5))
  }

  @Test
  fun `packets are read as presentation times, dropping ones never presented`() {
    val (timeBase, pts) = VideoStills.parsePackets(
      """
      728,___
      61,K__
      N/A,___
      394,__D
      1061,___
      1/1000
      """.trimIndent(),
    )!!

    assertEquals(0.001, timeBase)
    assertEquals(listOf(61L, 728L, 1061L), pts.toList())
  }

  @Test
  fun `no time base is no recording`() {
    assertNull(VideoStills.parsePackets("61,K__\n394,___\n"))
  }

  @Test
  fun `showinfo lines give each written frame's pts in order`() {
    val output = """
      Input #0, matroska,webm, from 'video.webm':
      [Parsed_showinfo_1 @ 0x934c14b40] config in time_base: 1/1000, frame_rate: 3/1
      [Parsed_showinfo_1 @ 0x934c14b40] n:   0 pts:    728 pts_time:0.728   duration:    333
      [Parsed_showinfo_1 @ 0x934c14b40] n:   1 pts: 119971 pts_time:119.971 duration:    333
    """.trimIndent()

    assertEquals(listOf(728L, 119_971L), VideoStills.parseShowinfoPts(output))
  }
}
