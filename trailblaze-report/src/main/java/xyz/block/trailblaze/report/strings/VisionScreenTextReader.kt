package xyz.block.trailblaze.report.strings

import kotlinx.serialization.json.Json
import xyz.block.trailblaze.api.ScreenTextReader
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.isMacOs
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Reads text off a screenshot with the macOS Vision framework, through the JavaScript for
 * Automation bridge every Mac ships, so nothing has to be built or installed. iOS simulators only
 * run on a Mac, so every capture this is needed for has one. Reads nothing on any other host.
 *
 * [deviceWidth] and [deviceHeight] are the space the tree's bounds are in. The screenshot is
 * usually a different size (a 3x simulator, or bytes scaled down on the host), and Vision's
 * regions are fractions of the image, so the boxes are converted through them.
 */
class VisionScreenTextReader(
  private val screenshot: File,
  private val deviceWidth: Int,
  private val deviceHeight: Int,
) : ScreenTextReader {

  override fun read(boxes: List<TrailblazeNode.Bounds>): List<List<String>?>? {
    if (timedOut || !isMacOs() || !screenshot.isFile || deviceWidth <= 0 || deviceHeight <= 0) return null
    val regions = boxes.map { visionRegion(it, deviceWidth, deviceHeight) }
    val readable = regions.filterNotNull()
    if (readable.isEmpty()) return regions.map { null }
    val recognized = recognize(readable) ?: return null
    if (recognized.size != readable.size) return null
    val lines = recognized.iterator()
    return regions.map { region -> region?.let { lines.next() } }
  }

  private fun recognize(regions: List<List<Double>>): List<List<String>>? {
    val output = File.createTempFile("trailblaze-ocr", ".json")
    return try {
      val process = ProcessBuilder(
        "osascript", "-l", "JavaScript", "-e", SCRIPT, screenshot.absolutePath, JSON.encodeToString(regions),
      ).redirectOutput(output).redirectError(ProcessBuilder.Redirect.DISCARD).start()
      if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        timedOut = true
        Console.log(
          "[VisionScreenTextReader] OCR of ${screenshot.name} timed out after ${TIMEOUT_SECONDS}s; reading no more screenshots",
        )
        return null
      }
      if (process.exitValue() != 0) return null
      JSON.decodeFromString<List<List<String>>>(output.readText())
    } catch (e: Exception) {
      Console.log("[VisionScreenTextReader] OCR of ${screenshot.name} failed: ${e.message}")
      null
    } finally {
      output.delete()
    }
  }

  private companion object {
    /** Vision loads its recognition model on the first request after a boot, which took 30s on an
     *  idle laptop; every request after it takes well under a second. */
    const val TIMEOUT_SECONDS = 45L

    /** A Vision that ran past it once is wedged or starved, and every later capture would wait out
     *  the full timeout too, at the end of a run that has already finished. */
    @Volatile
    var timedOut = false

    val JSON = Json { ignoreUnknownKeys = true }

    val SCRIPT: String by lazy {
      VisionScreenTextReader::class.java.getResource("recognize-text.jxa")!!.readText()
    }
  }
}

/**
 * The part of [bounds] on a [deviceWidth] by [deviceHeight] screen as Vision's region of interest:
 * `[x, y, width, height]` as fractions of the image, from its bottom-left corner. Null with no area.
 */
internal fun visionRegion(bounds: TrailblazeNode.Bounds, deviceWidth: Int, deviceHeight: Int): List<Double>? {
  val left = bounds.left.coerceIn(0, deviceWidth)
  val right = bounds.right.coerceIn(0, deviceWidth)
  val top = bounds.top.coerceIn(0, deviceHeight)
  val bottom = bounds.bottom.coerceIn(0, deviceHeight)
  if (right <= left || bottom <= top) return null
  return listOf(
    left.toDouble() / deviceWidth,
    1.0 - bottom.toDouble() / deviceHeight,
    (right - left).toDouble() / deviceWidth,
    (bottom - top).toDouble() / deviceHeight,
  )
}
