package xyz.block.trailblaze.mcp.android.ondevice.rpc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance

class GetScreenStateResponseSerializationTest {

  @Test
  fun `raw binary fields do not change the legacy JSON contract`() {
    val response = GetScreenStateResponse(
      viewHierarchy = ViewHierarchyTreeNode(text = "Home"),
      screenshotBase64 = "AQID",
      annotatedScreenshotBase64 = "BAUG",
      deviceWidth = 1080,
      deviceHeight = 1920,
    ).apply {
      screenshotBytes = byteArrayOf(1, 2, 3)
      annotatedScreenshotBytes = byteArrayOf(4, 5, 6)
    }

    val encoded = TrailblazeJsonInstance.encodeToString(response)
    val decoded = TrailblazeJsonInstance.decodeFromString<GetScreenStateResponse>(encoded)

    assertFalse(encoded.contains("screenshotBytes"))
    assertFalse(encoded.contains("annotatedScreenshotBytes"))
    assertEquals("AQID", decoded.screenshotBase64)
    assertEquals("BAUG", decoded.annotatedScreenshotBase64)
    assertNull(decoded.screenshotBytes)
    assertNull(decoded.annotatedScreenshotBytes)
  }
}
