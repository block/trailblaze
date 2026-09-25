package xyz.block.trailblaze.capture.video

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import xyz.block.trailblaze.capture.model.CaptureType

/**
 * [RecordingFormat] is the one decision every recorder shares, so its observable contract is
 * pinned here: which format a host lands on, what the recording is called, how it is published
 * in `capture_metadata.json`, and which codec the after-the-fact encodes use.
 */
class RecordingFormatTest {

  @Test
  fun `a host with a VP9 encoder records webm and one without falls back to mp4`() {
    assertEquals(RecordingFormat.WEBM, RecordingFormat.forVp9Encoder(available = true))
    assertEquals(RecordingFormat.MP4, RecordingFormat.forVp9Encoder(available = false))
  }

  @Test
  fun `an ffmpeg whose re-encode loses wall-clock time records mp4 even with a VP9 encoder`() {
    fun support(version: String?) = WallClockMuxConsumer.Output.LiveMuxSupport(vp9Encoder = true, ffmpegVersion = version)
    assertEquals(RecordingFormat.MP4, RecordingFormat.forLiveMux(support("6.1.1-3ubuntu5")))
    assertEquals(RecordingFormat.WEBM, RecordingFormat.forLiveMux(support("6.1.3")))
    assertEquals(RecordingFormat.WEBM, RecordingFormat.forLiveMux(support(null)))
    assertEquals(
      RecordingFormat.MP4,
      RecordingFormat.forLiveMux(WallClockMuxConsumer.Output.LiveMuxSupport(vp9Encoder = false, ffmpegVersion = "8.1")),
    )
  }

  @Test
  fun `each format names the canonical recording and publishes the matching capture type`() {
    assertEquals("video.webm", RecordingFormat.WEBM.canonicalFilename)
    assertEquals(CaptureType.VIDEO_WEBM, RecordingFormat.WEBM.captureType)
    assertEquals("video.mp4", RecordingFormat.MP4.canonicalFilename)
    assertEquals(CaptureType.VIDEO, RecordingFormat.MP4.captureType)
  }

  @Test
  fun `after-the-fact encodes use VP9 for webm and H264 for mp4`() {
    val webm = RecordingFormat.WEBM.encodeArgs()
    assertEquals("libvpx-vp9", webm[webm.indexOf("-c:v") + 1])
    assertTrue("passthrough" in webm, "a re-encode must keep the source's timeline, not resample it")

    val mp4 = RecordingFormat.MP4.encodeArgs()
    assertEquals("libx264", mp4[mp4.indexOf("-c:v") + 1])
  }

  @Test
  fun `the live-file crash-safety settings stay out of the encodes that run after the session`() {
    // `-lag-in-frames 0` buys one thing: bytes on disk while the file is still being written. The
    // simctl transcode, the iOS stitch and the web screencast mux all run to completion at stop,
    // so carrying it there is a live-capture setting charged to a path that cannot use it.
    val afterTheFact = RecordingFormat.WEBM.encodeArgs()
    assertFalse(
      "-lag-in-frames" in afterTheFact,
      "an encode that runs to completion has no partial file to protect: $afterTheFact",
    )
    assertFalse("-cluster_time_limit" in afterTheFact, "mid-session cluster flushing is a live-file concern")
    assertFalse("-flush_packets" in afterTheFact, "mid-session packet flushing is a live-file concern")

    // The live encode still sets them — this is a scoping fix, not a removal.
    val live = WallClockMuxConsumer.Output.WebmVp9().ffmpegArgs()
    assertEquals("0", live[live.indexOf("-lag-in-frames") + 1])
    assertEquals("1", live[live.indexOf("-flush_packets") + 1])

    // Both paths share the codec and the deadline, so a stitched recording still looks like a live
    // one and a stop-time encode still beats the teardown it runs inside.
    assertEquals("libvpx-vp9", afterTheFact[afterTheFact.indexOf("-c:v") + 1])
    assertEquals("realtime", afterTheFact[afterTheFact.indexOf("-deadline") + 1])
  }

  @Test
  fun `the live mux writes the container the format names`() {
    assertTrue(RecordingFormat.WEBM.liveMuxOutput() is WallClockMuxConsumer.Output.WebmVp9)
    assertEquals(WallClockMuxConsumer.Output.Mp4Copy, RecordingFormat.MP4.liveMuxOutput())
  }
}
