package xyz.block.trailblaze.ui.composables

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO
import org.jetbrains.skia.Image
import xyz.block.trailblaze.ui.tabs.session.SpriteSheetInfo
import xyz.block.trailblaze.util.Console

actual fun createVideoFrameCache(videoPath: String, fps: Int): VideoFrameCache {
  // Detect sprite sheet by filename
  if (videoPath.endsWith("video_sprites.jpg")) {
    return JvmSpriteFrameCache(videoPath)
  }
  return JvmVideoFrameCache(videoPath, fps)
}

/** Returns true if ffmpeg is available on the system PATH. */
private fun isFfmpegAvailable(): Boolean =
  try {
    ProcessBuilder("ffmpeg", "-version")
      .redirectErrorStream(true)
      .start()
      .also { it.inputStream.bufferedReader().readText() }
      .waitFor(5, TimeUnit.SECONDS)
  } catch (_: Exception) {
    false
  }

/**
 * Loads a sprite sheet image and crops individual frames on demand. No ffmpeg needed — the sprite
 * sheet is a single tall JPEG with frames stacked vertically at [spriteInfo.frameHeight] pixels
 * each. Frame N is at y-offset `N * frameHeight`.
 */
private class JvmSpriteFrameCache(
  private val spriteSheetPath: String,
) : VideoFrameCache {
  private val spriteImage: java.awt.image.BufferedImage?
  private val spriteInfo: SpriteSheetInfo?
  private val frames = mutableMapOf<Int, ImageBitmap>()

  init {
    val spriteFile = File(spriteSheetPath)
    spriteImage =
      if (spriteFile.exists()) {
        try {
          ImageIO.read(spriteFile)
        } catch (e: Exception) {
          Console.log("Failed to load sprite sheet: ${e.message}")
          null
        }
      } else null

    // Parse the companion metadata file
    val metaFile = File(spriteFile.parent, "video_sprites.txt")
    spriteInfo =
      if (metaFile.exists()) {
        try {
          val props =
            metaFile.readLines().associate {
              val (k, v) = it.split("=", limit = 2)
              k.trim() to v.trim()
            }
          val frameCount = props["frames"]?.toIntOrNull() ?: 0
          val uniqueFrameCount = props["uniqueFrames"]?.toIntOrNull()
          val frameMap = props["frameMap"]?.split(",")?.map { it.toInt() }?.toIntArray()
          SpriteSheetInfo(
            fps = props["fps"]?.toIntOrNull() ?: 2,
            frameCount = frameCount,
            frameHeight = props["height"]?.toIntOrNull() ?: 360,
            columns = props["columns"]?.toIntOrNull() ?: 1,
            rows = props["rows"]?.toIntOrNull() ?: (uniqueFrameCount ?: frameCount),
            uniqueFrameCount = uniqueFrameCount,
            frameMap = frameMap,
          )
        } catch (e: Exception) {
          Console.log("Failed to parse sprite metadata: ${e.message}")
          null
        }
      } else null
  }

  override fun getFrame(timestampMs: Long): ImageBitmap? {
    val info = spriteInfo ?: return null
    val image = spriteImage ?: return null
    val frameIndex =
      (timestampMs * info.fps / 1000).toInt().coerceIn(0, info.frameCount - 1)
    frames[frameIndex]?.let { return it }

    // Resolve logical frame index to physical sprite position via frameMap (if present).
    val physicalIndex = info.frameMap?.getOrNull(frameIndex) ?: frameIndex

    return try {
      // Grid layout: physical frame N is at column N/rows, row N%rows
      val col = physicalIndex / info.rows
      val row = physicalIndex % info.rows
      val frameWidth = image.width / info.columns
      val x = col * frameWidth
      val y = row * info.frameHeight
      val width = frameWidth.coerceAtMost(image.width - x)
      val height = info.frameHeight.coerceAtMost(image.height - y)
      if (width <= 0 || height <= 0) return null
      val subImage = image.getSubimage(x, y, width, height)
      // Convert BufferedImage to Skia Image via JPEG bytes
      val baos = ByteArrayOutputStream()
      ImageIO.write(subImage, "jpg", baos)
      val bitmap = Image.makeFromEncoded(baos.toByteArray()).toComposeImageBitmap()
      frames[frameIndex] = bitmap
      bitmap
    } catch (e: Exception) {
      null
    }
  }

  override fun isExtractionComplete(): Boolean = true

  override fun dispose() {
    frames.clear()
  }
}

/**
 * Extracts all video frames at [fps] into a temp directory using a single background ffmpeg process.
 * Frames are saved as JPEG files (frame_000001.jpg, frame_000002.jpg, ...) and loaded on demand.
 *
 * If ffmpeg is not installed, extraction is skipped and [getFrame] always returns null.
 */
private class JvmVideoFrameCache(
  videoPath: String,
  private val fps: Int,
) : VideoFrameCache {
  private val tempDir: File = Files.createTempDirectory("trailblaze_frames_").toFile()
  private val done = AtomicBoolean(false)
  private val extractionThread: Thread?

  init {
    extractionThread =
      if (!isFfmpegAvailable()) {
        Console.log(
          "ffmpeg not found on PATH — video frame extraction disabled. " +
            "Install ffmpeg to enable timeline video thumbnails."
        )
        done.set(true)
        null
      } else {
        Thread {
            try {
              val process =
                ProcessBuilder(
                    "ffmpeg",
                    "-i",
                    videoPath,
                    "-vf",
                    "fps=$fps,scale=-2:360",
                    "-q:v",
                    "3",
                    "-loglevel",
                    "error",
                    "${tempDir.absolutePath}/frame_%06d.jpg",
                  )
                  .redirectErrorStream(true)
                  .start()
              process.inputStream.bufferedReader().use { it.readText() }
              process.waitFor(120, TimeUnit.SECONDS)
            } catch (e: Exception) {
              Console.log("ffmpeg frame extraction failed: ${e.message}")
            }
            done.set(true)
          }
          .also {
            it.isDaemon = true
            it.start()
          }
      }
  }

  override fun getFrame(timestampMs: Long): ImageBitmap? {
    val frameIndex = (timestampMs * fps / 1000 + 1).toInt().coerceAtLeast(1)
    val file = File(tempDir, "frame_%06d.jpg".format(frameIndex))
    return try {
      if (!file.exists() || file.length() == 0L) return null
      Image.makeFromEncoded(file.readBytes()).toComposeImageBitmap()
    } catch (_: Exception) {
      null
    }
  }

  override fun isExtractionComplete(): Boolean = done.get()

  override fun dispose() {
    extractionThread?.interrupt()
    // Wait briefly for the thread to finish so ffmpeg is not left running
    extractionThread?.join(3000)
    tempDir.deleteRecursively()
  }
}
