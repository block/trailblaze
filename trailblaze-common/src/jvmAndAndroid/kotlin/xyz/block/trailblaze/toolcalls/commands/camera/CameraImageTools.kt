package xyz.block.trailblaze.toolcalls.commands.camera

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.toolcalls.HostLocalExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * Saves the active device's screen to a host file. With [SetCameraImageTrailblazeTool], one
 * device's screen becomes what another emulator's camera sees, e.g. a QR code to scan.
 */
@Serializable
@TrailblazeToolClass(name = "saveScreenshot", surfaceToLlm = false, requiresHost = true)
@LLMDescription("Save the active device's current screen as a PNG file on the host.")
data class SaveScreenshotTrailblazeTool(
  @param:LLMDescription("Absolute host path for the PNG. Its directory must exist; a file there is replaced.")
  val file: String,
) : HostLocalExecutableTrailblazeTool {
  override val advertisedToolName: String
    get() = "saveScreenshot"

  override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
    // Prefer the screenshot provider: some drivers' plain screen-state provider skips the
    // screenshot to keep tool dispatch fast.
    val screenState = (toolExecutionContext.screenshotStateProvider ?: toolExecutionContext.screenStateProvider)?.invoke()
      ?: toolExecutionContext.screenState
    // Empty, not just null: the on-device Android driver yields an empty array when it skipped
    // or failed the capture.
    val bytes = screenState?.screenshotBytes
    if (bytes == null || bytes.isEmpty()) {
      return TrailblazeToolResult.Error.InvalidToolCall("saveScreenshot: the driver captured no screenshot.", this)
    }
    return writeOrFail(this, "saveScreenshot") {
      writeImageAsPng(bytes, file)
      "Saved the screen to $file."
    }
  }
}

/**
 * Shows any image on an Android emulator's back camera, which reads a PNG named at boot
 * (`-camera-back imagefile:<path>`). The emulator crops that file, so the image is first placed
 * on white where the camera shows all of it. It replaces the file whole, so a camera opening at
 * the same moment never reads half of it.
 */
@Serializable
@TrailblazeToolClass(name = "setCameraImage", surfaceToLlm = false, requiresHost = true)
@LLMDescription(
  "Show an image on an Android emulator's back camera. Boot the emulator with " +
    "-camera-back imagefile:<cameraFile>; an app sees the new image the next time it opens the camera."
)
data class SetCameraImageTrailblazeTool(
  @param:LLMDescription("Absolute host path of the PNG the emulator's camera was booted with.")
  val cameraFile: String,
  @param:LLMDescription("Absolute host path of the image to show: PNG, JPEG or WebP, e.g. one saveScreenshot wrote.")
  val image: String,
) : HostLocalExecutableTrailblazeTool {
  override val advertisedToolName: String
    get() = "setCameraImage"

  override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult =
    writeOrFail(this, "setCameraImage") {
      // Must already exist: the emulator reads only the file it was booted with, so a mistyped
      // path would otherwise succeed and change nothing.
      requireExistingFile(cameraFile, "cameraFile")
      val bytes = readImageFile(image)
      // Re-placing the camera's own file would shrink the picture again on every call.
      requireDifferentFiles(image, cameraFile)
      writeImageAsPng(bytes, cameraFile, fitToCameraFrame = true)
      "The camera now shows $image."
    }
}

private inline fun writeOrFail(tool: TrailblazeTool, name: String, write: () -> String): TrailblazeToolResult =
  try {
    TrailblazeToolResult.Success(message = write())
  } catch (cancellation: CancellationException) {
    throw cancellation
  } catch (e: Exception) {
    TrailblazeToolResult.Error.ExceptionThrown(errorMessage = "$name: ${e.message}", command = tool)
  }

/** Reads the image file at [path], an absolute host path. */
internal expect fun readImageFile(path: String): ByteArray

/** Fails unless [path] is an absolute path to an existing regular file. */
internal expect fun requireExistingFile(path: String, name: String)

/** Fails if [image] and [cameraFile], both existing files, are the same file. */
internal expect fun requireDifferentFiles(image: String, cameraFile: String)

/**
 * Decodes [imageBytes] (PNG, JPEG or WebP) and writes it as a PNG to [destination], an absolute
 * host path, replacing any file there in one move. [fitToCameraFrame] places the image on white
 * where an emulator's camera shows all of it.
 */
internal expect fun writeImageAsPng(imageBytes: ByteArray, destination: String, fitToCameraFrame: Boolean = false)
