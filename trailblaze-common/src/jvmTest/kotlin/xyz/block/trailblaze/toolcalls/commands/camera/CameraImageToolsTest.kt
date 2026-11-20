package xyz.block.trailblaze.toolcalls.commands.camera

import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.config.ToolNameResolver
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.barcode.BarcodeDecoder
import xyz.block.trailblaze.toolcalls.commands.barcode.screenshotBytesToLuminanceSource

class CameraImageToolsTest {
  @get:Rule val temporary = TemporaryFolder()

  @Test
  fun `recorded calls resolve both tools`() {
    val resolver = ToolNameResolver.fromBuiltInAndCustomTools()
    assertEquals(SaveScreenshotTrailblazeTool::class, resolver.resolveOrNull("saveScreenshot"))
    assertEquals(SetCameraImageTrailblazeTool::class, resolver.resolveOrNull("setCameraImage"))
  }

  @Test
  fun `a QR on one device's screen reaches another emulator's camera still readable`() = runBlocking {
    val screenshot = File(temporary.root, "seller.png").path
    val camera = temporary.newFile("camera.png").apply { writeBytes(encode(blank(), "png")) }

    assertIs<TrailblazeToolResult.Success>(
      SaveScreenshotTrailblazeTool(screenshot).execute(context(encode(qr("claim-123"), "jpg"))),
    )
    assertIs<TrailblazeToolResult.Success>(
      SetCameraImageTrailblazeTool(camera.path, screenshot).execute(context(null)),
    )

    // The emulator camera shows a 4:3 file's left 3:4 slice, so the code has to be readable there.
    val file = ImageIO.read(camera)
    assertTrue(abs(file.width * 3 - file.height * 4) <= 4, "${file.width}x${file.height} is not 4:3")
    val seen = file.getSubimage(0, 0, file.height * 3 / 4, file.height)
    assertEquals("claim-123", BarcodeDecoder.decode(screenshotBytesToLuminanceSource(encode(seen, "png"))))
    // Both writes replace their file in one move and leave nothing staged beside it.
    assertEquals(setOf("camera.png", "seller.png"), temporary.root.list()!!.toSet())
  }

  @Test
  fun `the screenshot comes from the screenshot provider, fresh on every call`() = runBlocking {
    val file = File(temporary.root, "screen.png")
    var latest = "first"
    val context = context(cached = encode(qr("stale"), "png"), fresh = { encode(qr(latest), "png") })

    SaveScreenshotTrailblazeTool(file.path).execute(context)
    assertEquals("first", BarcodeDecoder.decode(screenshotBytesToLuminanceSource(file.readBytes())))
    latest = "second"
    SaveScreenshotTrailblazeTool(file.path).execute(context)
    assertEquals("second", BarcodeDecoder.decode(screenshotBytesToLuminanceSource(file.readBytes())))
  }

  @Test
  fun `a camera file that was not set up at boot fails and is not created`() = runBlocking {
    val image = temporary.newFile("image.png").apply { writeBytes(encode(qr("x"), "png")) }
    val missing = File(temporary.root, "missing.png")

    val result = SetCameraImageTrailblazeTool(missing.path, image.path).execute(context(null))

    assertContains(assertIs<TrailblazeToolResult.Error>(result).errorMessage, "not an existing file")
    assertFalse(missing.exists())
  }

  @Test
  fun `an image that is not a picture leaves the camera unchanged`() = runBlocking {
    val camera = temporary.newFile("camera.png").apply { writeBytes(encode(blank(), "png")) }
    val before = camera.readBytes()
    val notAnImage = temporary.newFile("notes.txt").apply { writeText("not an image") }

    val result = SetCameraImageTrailblazeTool(camera.path, notAnImage.path).execute(context(null))

    assertContains(assertIs<TrailblazeToolResult.Error>(result).errorMessage, "not a PNG, JPEG or WebP")
    assertContentEquals(before, camera.readBytes())
  }

  @Test
  fun `showing the camera file on itself fails and leaves it unchanged`() = runBlocking {
    val camera = temporary.newFile("camera.png").apply { writeBytes(encode(qr("x"), "png")) }
    val before = camera.readBytes()

    val result = SetCameraImageTrailblazeTool(camera.path, camera.path).execute(context(null))

    assertContains(assertIs<TrailblazeToolResult.Error>(result).errorMessage, "camera file itself")
    assertContentEquals(before, camera.readBytes())
  }

  @Test
  fun `no screenshot from the driver fails without writing`() = runBlocking {
    val file = File(temporary.root, "screen.png")

    val result = SaveScreenshotTrailblazeTool(file.path).execute(context(ByteArray(0)))

    assertContains(assertIs<TrailblazeToolResult.Error>(result).errorMessage, "captured no screenshot")
    assertFalse(file.exists())
  }

  @Test
  fun `relative paths are refused`() = runBlocking {
    val result = SaveScreenshotTrailblazeTool("screen.png").execute(context(encode(blank(), "png")))

    assertContains(assertIs<TrailblazeToolResult.Error>(result).errorMessage, "absolute path")
  }

  private fun qr(content: String): BufferedImage {
    val bits = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, 300, 300)
    // A portrait phone screen with the code in the middle, like a "Scan to claim" display.
    return BufferedImage(400, 800, BufferedImage.TYPE_INT_RGB).apply {
      for (y in 0 until height) for (x in 0 until width) setRGB(x, y, 0xFFFFFF)
      for (y in 0 until 300) for (x in 0 until 300) if (bits[x, y]) setRGB(x + 50, y + 250, 0)
    }
  }

  private fun blank() = BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB)

  private fun encode(image: BufferedImage, format: String): ByteArray =
    ByteArrayOutputStream().use { output ->
      ImageIO.write(image, format, output)
      output.toByteArray()
    }

  private fun context(cached: ByteArray?, fresh: (() -> ByteArray)? = null): TrailblazeToolExecutionContext {
    fun screen(bytes: ByteArray) =
      object : ScreenState {
        override val screenshotBytes = bytes
        override val deviceWidth = 400
        override val deviceHeight = 800
        override val viewHierarchy = ViewHierarchyTreeNode()
        override val trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID
        override val deviceClassifiers = emptyList<TrailblazeDeviceClassifier>()
      }
    return TrailblazeToolExecutionContext(
      screenState = cached?.let(::screen),
      screenStateProvider = null,
      screenshotStateProvider = fresh?.let { provider -> { screen(provider()) } },
      traceId = null,
      trailblazeDeviceInfo =
        TrailblazeDeviceInfo(
          trailblazeDeviceId = TrailblazeDeviceId("display", TrailblazeDevicePlatform.ANDROID),
          trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
          widthPixels = 400,
          heightPixels = 800,
        ),
      sessionProvider =
        TrailblazeSessionProvider { TrailblazeSession(SessionId("camera-test"), startTime = Clock.System.now()) },
      trailblazeLogger = TrailblazeLogger.createNoOp(),
      memory = AgentMemory(),
    )
  }
}
