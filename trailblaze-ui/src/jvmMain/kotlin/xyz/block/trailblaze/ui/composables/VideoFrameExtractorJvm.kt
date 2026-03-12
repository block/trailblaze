package xyz.block.trailblaze.ui.composables

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import java.util.concurrent.TimeUnit

/**
 * Extracts a single video frame at [timestampMs] using ffmpeg, returning it as an [ImageBitmap].
 *
 * The frame is piped as raw PNG from ffmpeg's stdout — no temp files needed.
 */
actual suspend fun extractVideoFrame(videoPath: String, timestampMs: Long): ImageBitmap? =
  withContext(Dispatchers.IO) {
    try {
      val seconds = timestampMs / 1000.0
      val ts = String.format("%.3f", seconds)
      val process =
        ProcessBuilder(
            "ffmpeg",
            "-ss",
            ts,
            "-i",
            videoPath,
            "-frames:v",
            "1",
            "-f",
            "image2pipe",
            "-vcodec",
            "png",
            "-loglevel",
            "error",
            "pipe:1",
          )
          .redirectErrorStream(false)
          .start()
      val bytes = process.inputStream.readBytes()
      process.waitFor(5, TimeUnit.SECONDS)
      if (bytes.isNotEmpty()) {
        Image.makeFromEncoded(bytes).toComposeImageBitmap()
      } else null
    } catch (_: Exception) {
      null
    }
  }
