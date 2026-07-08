package xyz.block.trailblaze.playwright.tools

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.Test
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance

/**
 * LLM-facing enum tool parameters must decode case-insensitively — models regularly emit
 * lowercase values regardless of the schema's casing. Decodes through the real tool
 * serializers so the @Serializable(with = ...) wiring is covered.
 */
class PlaywrightToolEnumCasingTest {

  @Test
  fun `web_scroll decodes lowercase direction`() {
    val tool = TrailblazeJsonInstance.decodeFromString(
      PlaywrightNativeScrollTool.serializer(),
      """{"direction":"down"}""",
    )
    assertThat(tool.direction).isEqualTo(PlaywrightNativeScrollTool.ScrollDirection.DOWN)
  }

  @Test
  fun `web_verifyValue decodes lowercase type`() {
    val tool = TrailblazeJsonInstance.decodeFromString(
      PlaywrightNativeVerifyValueTool.serializer(),
      """{"expected":"hello","type":"attribute"}""",
    )
    assertThat(tool.type).isEqualTo(PlaywrightNativeVerifyValueTool.VerifyValueType.ATTRIBUTE)
  }

  @Test
  fun `web_navigate decodes lowercase action`() {
    val tool = TrailblazeJsonInstance.decodeFromString(
      PlaywrightNativeNavigateTool.serializer(),
      """{"action":"goto","url":"https://example.com"}""",
    )
    assertThat(tool.action).isEqualTo(PlaywrightNativeNavigateTool.NavigationAction.GOTO)
  }
}
