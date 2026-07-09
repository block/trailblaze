package xyz.block.trailblaze.trailrunner

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Coverage for [AndroidVectorIconRasterizer] — the VectorDrawable-subset renderer behind Android
 * app icons that ship no raster mipmap (vector adaptive icons only). Fixtures are trimmed
 * `aapt2 dump xmltree` output captured from a real-world app's APK, so the parser is pinned to the
 * exact dump format the extraction pipeline feeds it.
 */
class AndroidVectorIconRasterizerTest {

  private val appForegroundXmlTree = """
    N: android=http://schemas.android.com/apk/res/android (line=5)
      E: vector (line=5)
        A: http://schemas.android.com/apk/res/android:height(0x01010155)=108.000000dp
        A: http://schemas.android.com/apk/res/android:width(0x01010159)=108.000000dp
        A: http://schemas.android.com/apk/res/android:viewportWidth(0x01010402)=93
        A: http://schemas.android.com/apk/res/android:viewportHeight(0x01010403)=128
          E: group (line=9)
            A: http://schemas.android.com/apk/res/android:scaleX(0x01010324)=0.326953
            A: http://schemas.android.com/apk/res/android:scaleY(0x01010325)=0.45
            A: http://schemas.android.com/apk/res/android:translateX(0x0101045a)=31.2967
            A: http://schemas.android.com/apk/res/android:translateY(0x0101045b)=35.2
              E: path (line=12)
                A: http://schemas.android.com/apk/res/android:fillColor(0x01010404)=#ffffffff
                A: http://schemas.android.com/apk/res/android:pathData(0x01010405)="M10,10L80,10L80,120L10,120Z" (Raw: "M10,10L80,10L80,120L10,120Z")
  """.trimIndent().lines()

  private val appBackgroundXmlTree = """
    N: android=http://schemas.android.com/apk/res/android (line=1)
      N: aapt=http://schemas.android.com/aapt (line=1)
        E: vector (line=1)
          A: http://schemas.android.com/apk/res/android:height(0x01010155)=108.000000dp
          A: http://schemas.android.com/apk/res/android:width(0x01010159)=108.000000dp
          A: http://schemas.android.com/apk/res/android:viewportWidth(0x01010402)=108
          A: http://schemas.android.com/apk/res/android:viewportHeight(0x01010403)=108
            E: path (line=2)
              A: http://schemas.android.com/apk/res/android:fillColor(0x01010404)=@0x7f080031
              A: http://schemas.android.com/apk/res/android:pathData(0x01010405)="M0 0h108v108H0z" (Raw: "M0 0h108v108H0z")
              A: http://schemas.android.com/apk/res/android:strokeWidth(0x01010407)=1
              A: http://schemas.android.com/apk/res/android:fillType(0x0101051e)=1
  """.trimIndent().lines()

  private val appGradientXmlTree = """
    N: android=http://schemas.android.com/apk/res/android (line=0)
      N: aapt=http://schemas.android.com/aapt (line=0)
        E: gradient (line=4)
          A: http://schemas.android.com/apk/res/android:angle(0x010101a0)=0.000000e+00
          A: http://schemas.android.com/apk/res/android:type(0x010101a1)=0
          A: http://schemas.android.com/apk/res/android:startX(0x01010510)=54
          A: http://schemas.android.com/apk/res/android:startY(0x01010511)=0.000000e+00
          A: http://schemas.android.com/apk/res/android:endX(0x01010512)=54
          A: http://schemas.android.com/apk/res/android:endY(0x01010513)=108
            E: item (line=6)
              A: http://schemas.android.com/apk/res/android:color(0x010101a5)=#fffed25d
              A: http://schemas.android.com/apk/res/android:offset(0x01010514)=0.000000e+00
            E: item (line=7)
              A: http://schemas.android.com/apk/res/android:color(0x010101a5)=#fffece50
              A: http://schemas.android.com/apk/res/android:offset(0x01010514)=1
  """.trimIndent().lines()

  @Test
  fun `parseVector reads viewport, group transforms, and a solid path`() {
    val vector = AndroidVectorIconRasterizer.parseVector(appForegroundXmlTree) { null }!!
    assertEquals(93f, vector.viewportWidth)
    assertEquals(128f, vector.viewportHeight)
    val group = vector.children.single() as AndroidVectorIconRasterizer.VdGroup
    assertEquals(0.326953f, group.scaleX)
    assertEquals(31.2967f, group.translateX)
    val path = group.children.single() as AndroidVectorIconRasterizer.VdPath
    assertEquals("M10,10L80,10L80,120L10,120Z", path.pathData)
    assertEquals(AndroidVectorIconRasterizer.VdFill.Solid(0xFFFFFFFF.toInt()), path.fill)
  }

  @Test
  fun `parseVector resolves a fillColor resource ref through the gradient resolver`() {
    val vector = AndroidVectorIconRasterizer.parseVector(appBackgroundXmlTree) { ref ->
      if (ref == "0x7f080031") appGradientXmlTree else null
    }!!
    val path = vector.children.single() as AndroidVectorIconRasterizer.VdPath
    val fill = path.fill as AndroidVectorIconRasterizer.VdFill.Linear
    assertEquals(listOf(0f to 0xFFFED25D.toInt(), 1f to 0xFFFECE50.toInt()), fill.stops)
    assertEquals(108f, fill.endY)
    assertTrue(path.evenOdd)
  }

  @Test
  fun `parseVector rejects unsupported elements rather than rendering them wrong`() {
    val withClipPath = """
      E: vector (line=1)
        A: x:viewportWidth(0x01010402)=24
        A: x:viewportHeight(0x01010403)=24
          E: clip-path (line=2)
            A: x:pathData(0x01010405)="M0,0h24v24h-24z"
    """.trimIndent().lines()
    assertNull(AndroidVectorIconRasterizer.parseVector(withClipPath) { null })
  }

  @Test
  fun `parseSvgPath handles absolute, shorthand, and close commands`() {
    val bounds = AndroidVectorIconRasterizer.parseSvgPath("M0 0h108v108H0z").bounds2D
    assertEquals(0.0, bounds.minX, 0.001)
    assertEquals(108.0, bounds.maxX, 0.001)
    assertEquals(108.0, bounds.maxY, 0.001)
    // Curves + relative commands parse without error and stay in the ballpark.
    val curvy = AndroidVectorIconRasterizer.parseSvgPath("M10,10c5,0 10,5 10,10s-5,10 -10,10q-5,0 -5,-10t0,-10a5,5 0 1 1 5,0z").bounds2D
    assertTrue(curvy.width > 0 && curvy.height > 0)
  }

  @Test
  fun `renderAdaptiveIcon composes gradient background and white glyph in the safe-zone crop`() {
    val background = AndroidVectorIconRasterizer.VdLayer.Vector(
      AndroidVectorIconRasterizer.parseVector(appBackgroundXmlTree) { appGradientXmlTree }!!,
    )
    val foreground = AndroidVectorIconRasterizer.VdLayer.Vector(
      AndroidVectorIconRasterizer.parseVector(appForegroundXmlTree) { null }!!,
    )
    val image = AndroidVectorIconRasterizer.renderAdaptiveIcon(background, foreground, sizePx = 96)
    assertEquals(96, image.width)
    // A corner pixel of the crop is pure background: the gold gradient, fully opaque.
    val corner = image.getRGB(2, 2)
    assertEquals(0xFF, (corner ushr 24) and 0xFF)
    assertTrue((corner ushr 16) and 0xFF > 0xE0, "expected yellow-ish red channel, got ${Integer.toHexString(corner)}")
    assertTrue(corner and 0xFF < 0x80, "expected yellow-ish blue channel, got ${Integer.toHexString(corner)}")
    // The center carries the white foreground glyph.
    assertEquals(0xFFFFFFFF.toInt(), image.getRGB(48, 48))
  }
}
