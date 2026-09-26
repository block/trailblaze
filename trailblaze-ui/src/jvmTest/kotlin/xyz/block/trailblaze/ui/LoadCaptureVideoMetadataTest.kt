package xyz.block.trailblaze.ui

import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Which recording the desktop app offers as a session's video. */
class LoadCaptureVideoMetadataTest {

  @get:Rule val tempFolder: TemporaryFolder = TemporaryFolder()

  private fun session(id: String, metadata: String, vararg files: String) {
    val dir = File(tempFolder.root, id).apply { mkdirs() }
    files.forEach { File(dir, it).writeBytes(byteArrayOf(1)) }
    File(dir, "capture_metadata.json").writeText(metadata)
  }

  @Test
  fun `a single-device session's webm is preferred over its mp4`() = runBlocking {
    setLogsDirectory(tempFolder.root)
    session(
      "single",
      """{"artifacts":[
        {"filename":"video.mp4","type":"VIDEO","startTimestampMs":1000},
        {"filename":"video.webm","type":"VIDEO_WEBM","startTimestampMs":900}
      ]}""",
      "video.mp4", "video.webm",
    )
    assertEquals("video.webm", File(assertNotNull(loadCaptureVideoMetadata("single")).filePath).name)
  }

  @Test
  fun `a multi-device session offers the start device's recording, not a companion's in the preferred format`() = runBlocking {
    // An iOS start device records mp4; its Android companion records webm.
    setLogsDirectory(tempFolder.root)
    session(
      "cast",
      """{"artifacts":[
        {"filename":"video.mp4","type":"VIDEO","startTimestampMs":1000,"deviceName":"seller"},
        {"filename":"video-buyer.webm","type":"VIDEO_WEBM","startTimestampMs":950,"deviceName":"buyer"}
      ]}""",
      "video.mp4", "video-buyer.webm",
    )
    val video = assertNotNull(loadCaptureVideoMetadata("cast"))
    assertEquals("video.mp4", File(video.filePath).name)
    assertEquals(1000L, video.startTimestampMs)
  }
}
