package xyz.block.trailblaze.toolcalls.commands.screenimage

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.WebpEncoder

/** Every image here is drawn in the test, so what each pixel should be is known exactly. */
class ReadScreenImageTrailblazeToolTest {

  private val red = Color(255, 0, 0)
  private val white = Color(255, 255, 255)
  private val black = Color(0, 0, 0)

  /** A [width] x [height] white screen with [paint] drawn on it. */
  private fun screen(width: Int, height: Int, background: Color = white, paint: (BufferedImage) -> Unit = {}) =
    BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { img ->
      img.createGraphics().apply { color = background; fillRect(0, 0, width, height); dispose() }
      paint(img)
    }

  private fun BufferedImage.fill(color: Color, left: Int, top: Int, right: Int, bottom: Int) =
    createGraphics().apply { this.color = color; fillRect(left, top, right - left, bottom - top); dispose() }

  private fun BufferedImage.encode(format: String): ByteArray {
    val out = ByteArrayOutputStream()
    val target = if (format == "jpg") {
      BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).also { it.createGraphics().drawImage(this, 0, 0, null) }
    } else {
      this
    }
    check(ImageIO.write(target, format, out)) { "no ImageIO writer for $format" }
    return out.toByteArray()
  }

  private fun state(bytes: ByteArray, deviceWidth: Int, deviceHeight: Int): ScreenState = object : ScreenState {
    override val screenshotBytes = bytes
    override val deviceWidth = deviceWidth
    override val deviceHeight = deviceHeight
    override val viewHierarchy = ViewHierarchyTreeNode()
    override val trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID
    override val deviceClassifiers = emptyList<TrailblazeDeviceClassifier>()
  }

  private fun ctx(
    bytes: ByteArray,
    deviceWidth: Int,
    deviceHeight: Int,
    screenshotState: ScreenState? = null,
  ): TrailblazeToolExecutionContext {
    val screen = state(bytes, deviceWidth, deviceHeight)
    return TrailblazeToolExecutionContext(
      screenState = screen,
      traceId = null,
      trailblazeDeviceInfo = TrailblazeDeviceInfo(
        trailblazeDeviceId = TrailblazeDeviceId("t", TrailblazeDevicePlatform.ANDROID),
        trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
        widthPixels = deviceWidth,
        heightPixels = deviceHeight,
      ),
      sessionProvider = TrailblazeSessionProvider { TrailblazeSession(SessionId("t"), Clock.System.now()) },
      screenStateProvider = { screen },
      trailblazeLogger = TrailblazeLogger.createNoOp(),
      memory = AgentMemory(),
      screenshotStateProvider = screenshotState?.let { state -> { state } },
    )
  }

  private class Read(val result: ReadScreenImageResult) {
    val rgba = Base64.decode(result.data)
    fun pixel(x: Int, y: Int): Color {
      val i = (y * result.width + x) * 4
      fun c(o: Int) = rgba[i + o].toInt() and 0xFF
      return Color(c(0), c(1), c(2), c(3))
    }
    val pixels get() = (0 until result.height).flatMap { y -> (0 until result.width).map { x -> pixel(x, y) } }
  }

  private fun run(tool: ReadScreenImageTrailblazeTool, bytes: ByteArray, deviceWidth: Int, deviceHeight: Int) =
    runBlocking { tool.execute(ctx(bytes, deviceWidth, deviceHeight)) }

  private fun read(tool: ReadScreenImageTrailblazeTool, bytes: ByteArray, deviceWidth: Int, deviceHeight: Int): Read {
    val success = assertIs<TrailblazeToolResult.Success>(run(tool, bytes, deviceWidth, deviceHeight))
    val read = Read(TrailblazeJsonInstance.decodeFromJsonElement(ReadScreenImageResult.serializer(), success.structuredContent!!))
    assertEquals(read.result.width * read.result.height * 4, read.rgba.size)
    return read
  }

  @Test
  fun `crops to the element's bounds and returns its exact pixels`() {
    val png = screen(100, 200) { it.fill(red, 10, 20, 30, 60) }.encode("png")

    val read = read(ReadScreenImageTrailblazeTool(TrailblazeNode.Bounds(10, 20, 30, 60)), png, 100, 200)

    assertEquals(20, read.result.width)
    assertEquals(40, read.result.height)
    assertEquals(TrailblazeNode.Bounds(10, 20, 30, 60), read.result.pixelRect)
    assertTrue(read.result.lossless)
    assertEquals("png", read.result.source.encoding)
    assertTrue(read.pixels.all { it == red }, "every pixel of the crop is the red fill")
  }

  @Test
  fun `returns RGBA in that byte order, alpha included`() {
    val png = screen(4, 4) { it.setRGB(1, 2, Color(10, 20, 30, 40).rgb) }.encode("png")

    val read = read(ReadScreenImageTrailblazeTool(TrailblazeNode.Bounds(1, 2, 2, 3)), png, 4, 4)

    assertEquals(listOf(10, 20, 30, 40), read.rgba.map { it.toInt() and 0xFF })
  }

  @Test
  fun `scales driver units to screenshot pixels on each axis`() {
    // iOS reports points (here 3x); a driver can also downscale its screenshot unevenly.
    val png = screen(300, 600) { it.fill(red, 30, 60, 90, 120) }.encode("png")
    val threeX = read(ReadScreenImageTrailblazeTool(TrailblazeNode.Bounds(10, 20, 30, 40)), png, 100, 200)
    assertEquals(TrailblazeNode.Bounds(30, 60, 90, 120), threeX.result.pixelRect)
    assertEquals(3.0, threeX.result.source.scaleX)
    assertTrue(threeX.pixels.all { it == red })

    val downscaled = screen(50, 100) { it.fill(red, 5, 25, 15, 50) }.encode("png")
    val uneven = read(ReadScreenImageTrailblazeTool(TrailblazeNode.Bounds(10, 100, 30, 200)), downscaled, 100, 400)
    assertEquals(TrailblazeNode.Bounds(5, 25, 15, 50), uneven.result.pixelRect)
    assertTrue(uneven.pixels.all { it == red })
  }

  @Test
  fun `rounds fractional edges outward so the element's border pixels are kept`() {
    val png = screen(10, 10).encode("png")
    val read = read(ReadScreenImageTrailblazeTool(TrailblazeNode.Bounds(1, 1, 2, 2)), png, 4, 4)
    // 1 * 2.5 = 2.5 floors to 2; 2 * 2.5 = 5.0 stays 5.
    assertEquals(TrailblazeNode.Bounds(2, 2, 5, 5), read.result.pixelRect)
  }

  @Test
  fun `clamps a rectangle that runs off the screen`() {
    val png = screen(100, 200).encode("png")
    val read = read(ReadScreenImageTrailblazeTool(TrailblazeNode.Bounds(-10, 190, 20, 260)), png, 100, 200)
    assertEquals(TrailblazeNode.Bounds(0, 190, 20, 200), read.result.pixelRect)
    assertEquals(20, read.result.width)
    assertEquals(10, read.result.height)
  }

  @Test
  fun `a rectangle wholly off screen fails with a clear reason`() {
    val png = screen(100, 200).encode("png")
    val error = assertIs<TrailblazeToolResult.Error.InvalidToolCall>(
      run(ReadScreenImageTrailblazeTool(TrailblazeNode.Bounds(150, 10, 180, 40)), png, 100, 200),
    )
    assertContains(error.errorMessage, "outside the 100x200 screen")
  }

  @Test
  fun `an empty rectangle fails with a clear reason`() {
    val png = screen(100, 200).encode("png")
    val error = assertIs<TrailblazeToolResult.Error.InvalidToolCall>(
      run(ReadScreenImageTrailblazeTool(TrailblazeNode.Bounds(10, 10, 10, 40)), png, 100, 200),
    )
    assertContains(error.errorMessage, "has no area")
  }

  @Test
  fun `whole-screen reads are capped at 512 pixels by default`() {
    val png = screen(600, 1200).encode("png")
    val read = read(ReadScreenImageTrailblazeTool(), png, 600, 1200)
    assertEquals(256, read.result.width)
    assertEquals(512, read.result.height)
    assertEquals(TrailblazeNode.Bounds(0, 0, 600, 1200), read.result.pixelRect)
  }

  @Test
  fun `maxDimension downsamples without blending colours`() {
    // One-pixel red and white columns: any blend would produce pink.
    val stripes = screen(100, 100) { img -> for (x in 0 until 100 step 2) img.fill(red, x, 0, x + 1, 100) }.encode("png")
    val read = read(ReadScreenImageTrailblazeTool(maxDimension = 30), stripes, 100, 100)
    assertEquals(30, read.result.width)
    assertEquals(30, read.result.height)
    assertTrue(read.pixels.all { it == red || it == white }, "nearest-neighbour keeps exact colours")
    assertTrue(read.result.lossless)
  }

  @Test
  fun `an element smaller than maxDimension is returned at full size`() {
    val png = screen(1080, 2400).encode("png")
    val read = read(ReadScreenImageTrailblazeTool(TrailblazeNode.Bounds(0, 2000, 400, 2200)), png, 1080, 2400)
    assertEquals(400, read.result.width)
    assertEquals(200, read.result.height)
  }

  @Test
  fun `rejects a non-positive maxDimension`() {
    val png = screen(10, 10).encode("png")
    assertIs<TrailblazeToolResult.Error.InvalidToolCall>(run(ReadScreenImageTrailblazeTool(maxDimension = 0), png, 10, 10))
  }

  @Test
  fun `an all-black capture is reported as uncheckable, not returned as black pixels`() {
    val capture = screen(100, 200, background = black).encode("png")

    val whole = assertIs<TrailblazeToolResult.Error.ExceptionThrown>(run(ReadScreenImageTrailblazeTool(), capture, 100, 200))
    assertContains(whole.errorMessage, "FLAG_SECURE")
    assertIs<TrailblazeToolResult.Error.ExceptionThrown>(
      run(ReadScreenImageTrailblazeTool(TrailblazeNode.Bounds(10, 10, 40, 40)), capture, 100, 200),
    )
  }

  @Test
  fun `a black element on a screen with content is a real answer`() {
    val png = screen(100, 200) { it.fill(Color.BLACK, 10, 10, 40, 40) }.encode("png")
    val read = read(ReadScreenImageTrailblazeTool(TrailblazeNode.Bounds(10, 10, 40, 40)), png, 100, 200)
    assertTrue(read.pixels.all { it.red == 0 && it.green == 0 && it.blue == 0 })
  }

  @Test
  fun `a thin line of content on an otherwise black screen is not mistaken for a black capture`() {
    // Rows 101-106 of 1024: a sample of the whole screen at a lower resolution can step over them.
    val png = screen(512, 1024, background = black) { it.fill(white, 0, 101, 512, 107) }.encode("png")

    val read = read(ReadScreenImageTrailblazeTool(TrailblazeNode.Bounds(0, 0, 100, 100)), png, 512, 1024)

    assertTrue(read.pixels.all { it == black }, "the black element comes back as black pixels")
  }

  @Test
  fun `a lossy screenshot says so`() {
    val jpeg = screen(100, 200) { it.fill(red, 10, 20, 30, 60) }.encode("jpg")
    val read = read(ReadScreenImageTrailblazeTool(TrailblazeNode.Bounds(10, 20, 30, 60)), jpeg, 100, 200)
    assertFalse(read.result.lossless)
    assertEquals("jpeg", read.result.source.encoding)
  }

  @Test
  fun `decodes the drivers' default lossy WebP`() {
    // Proves the WebP reader is on this module's classpath: without it ImageIO.read returns null
    // and every read of a default-format screenshot fails.
    val image = screen(100, 200) { it.fill(red, 10, 20, 30, 60) }
    val opaque = BufferedImage(100, 200, BufferedImage.TYPE_INT_RGB).also { it.createGraphics().apply { drawImage(image, 0, 0, null); dispose() } }
    val webp = WebpEncoder.encode(opaque, quality = 0.8f)

    val read = read(ReadScreenImageTrailblazeTool(TrailblazeNode.Bounds(12, 22, 28, 58)), webp, 100, 200)

    assertEquals("webp", read.result.source.encoding)
    assertFalse(read.result.lossless)
    assertTrue(
      read.pixels.all { it.red > 200 && it.green < 50 && it.blue < 50 },
      "inside the fill every pixel is red, give or take the lossy encoder",
    )
  }

  @Test
  fun `a screen with no screenshot fails with a clear reason`() {
    val error = assertIs<TrailblazeToolResult.Error.InvalidToolCall>(run(ReadScreenImageTrailblazeTool(), ByteArray(0), 10, 10))
    assertContains(error.errorMessage, "no screenshot")
  }

  @Test
  fun `reads the screenshot capture when the driver's dispatch capture has none`() {
    // The ANDROID_TEST driver dispatches tools with a screenshot-free capture and hands pixel
    // reads a separate one that includes the screenshot.
    val png = screen(10, 10, background = red).encode("png")
    val context = ctx(ByteArray(0), 10, 10, screenshotState = state(png, 10, 10))

    val success = assertIs<TrailblazeToolResult.Success>(runBlocking { ReadScreenImageTrailblazeTool().execute(context) })

    val read = Read(TrailblazeJsonInstance.decodeFromJsonElement(ReadScreenImageResult.serializer(), success.structuredContent!!))
    assertTrue(read.pixels.all { it == red })
  }

  @Test
  fun `tells lossless WebP from lossy by its chunks`() {
    fun webp(vararg chunks: String): ByteArray {
      val body = chunks.flatMap { fourCc ->
        // Each chunk: FourCC, a little-endian size of 2, and 2 bytes of payload.
        fourCc.encodeToByteArray().toList() + listOf<Byte>(2, 0, 0, 0, 0, 0)
      }
      return "RIFF".encodeToByteArray() + ByteArray(4) + "WEBP".encodeToByteArray() + body.toByteArray()
    }
    assertEquals(ScreenshotEncoding.WEBP_LOSSLESS, ScreenshotEncoding.of(webp("VP8L")))
    assertEquals(ScreenshotEncoding.WEBP_LOSSY, ScreenshotEncoding.of(webp("VP8 ")))
    assertEquals(ScreenshotEncoding.WEBP_LOSSY, ScreenshotEncoding.of(webp("VP8X", "ALPH", "VP8 ")))
    assertEquals(ScreenshotEncoding.WEBP_LOSSLESS, ScreenshotEncoding.of(webp("VP8X", "VP8L")))
    assertEquals(ScreenshotEncoding.UNKNOWN, ScreenshotEncoding.of(byteArrayOf(1, 2, 3)))
  }
}
