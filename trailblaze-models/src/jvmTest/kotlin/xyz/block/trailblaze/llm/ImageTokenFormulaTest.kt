package xyz.block.trailblaze.llm

import kotlin.test.Test
import kotlin.test.assertEquals

class ImageTokenFormulaTest {

  // -- Anthropic: tokens = (w * h) / 750 --

  @Test
  fun `anthropic formula for default screenshot 1536x768`() {
    // (1536 * 768) / 750 = 1572
    assertEquals(1572, ImageTokenFormula.ANTHROPIC.estimateTokens(1536, 768))
  }

  @Test
  fun `anthropic formula scales down images over 1568px`() {
    // 3000x2000: longEdge=3000 > 1568, scale=1568/3000=0.5227
    // scaled: 1567x1045, tokens = (1567 * 1045) / 750 = 2183
    val tokens = ImageTokenFormula.ANTHROPIC.estimateTokens(3000, 2000)
    assertEquals(2183, tokens)
  }

  @Test
  fun `anthropic formula for small image`() {
    // 200x200: (200*200)/750 = 53
    assertEquals(53, ImageTokenFormula.ANTHROPIC.estimateTokens(200, 200))
  }

  // -- OpenAI tile-based: ceil(w/512) * ceil(h/512) * 170 + 85 --

  @Test
  fun `openai tile formula for default screenshot 1536x768`() {
    // ceil(1536/512)=3, ceil(768/512)=2, 3*2*170+85 = 1105
    assertEquals(1105, ImageTokenFormula.OPENAI_TILE.estimateTokens(1536, 768))
  }

  @Test
  fun `openai tile formula for small image`() {
    // 256x256: ceil(256/512)=1, ceil(256/512)=1, 1*1*170+85 = 255
    assertEquals(255, ImageTokenFormula.OPENAI_TILE.estimateTokens(256, 256))
  }

  @Test
  fun `openai tile formula scales down oversized images`() {
    // 4096x2048: shortSide=2048>768, scale=768/2048=0.375
    // scaled: 1536x768, same as default
    assertEquals(1105, ImageTokenFormula.OPENAI_TILE.estimateTokens(4096, 2048))
  }

  // -- Google tile-based: crop_unit = floor(min(w,h)/1.5), tiles * 258 --

  @Test
  fun `google tile formula for default screenshot 1536x768`() {
    // cropUnit = floor(768/1.5) = 512
    // tiles = ceil(1536/512) * ceil(768/512) = 3 * 2 = 6
    // tokens = 6 * 258 = 1548
    assertEquals(1548, ImageTokenFormula.GOOGLE_TILE.estimateTokens(1536, 768))
  }

  @Test
  fun `google tile formula for small image under 384px`() {
    // Both dims <= 384: flat 258
    assertEquals(258, ImageTokenFormula.GOOGLE_TILE.estimateTokens(384, 384))
    assertEquals(258, ImageTokenFormula.GOOGLE_TILE.estimateTokens(100, 200))
  }

  @Test
  fun `google tile formula example from docs 960x540`() {
    // cropUnit = floor(540/1.5) = 360
    // tiles = ceil(960/360) * ceil(540/360) = 3 * 2 = 6
    // tokens = 6 * 258 = 1548
    assertEquals(1548, ImageTokenFormula.GOOGLE_TILE.estimateTokens(960, 540))
  }

  // -- DEFAULT --

  @Test
  fun `default formula returns flat 765`() {
    assertEquals(765, ImageTokenFormula.DEFAULT.estimateTokens(1536, 768))
    assertEquals(765, ImageTokenFormula.DEFAULT.estimateTokens(100, 100))
  }

  // -- Edge cases --

  @Test
  fun `zero dimensions return default estimate`() {
    assertEquals(765, ImageTokenFormula.ANTHROPIC.estimateTokens(0, 768))
    assertEquals(765, ImageTokenFormula.OPENAI_TILE.estimateTokens(1536, 0))
    assertEquals(765, ImageTokenFormula.GOOGLE_TILE.estimateTokens(0, 0))
  }
}
