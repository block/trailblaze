package xyz.block.trailblaze.mcp.android.ondevice.rpc

import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertFalse
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance

class GetScreenStateRequestSerializationTest {

  /** The request as a runner built before the default flipped decodes it. */
  @Serializable
  private data class OlderRunnerRequest(val includeAnnotatedScreenshot: Boolean = true)

  /**
   * The JSON transport drops default-valued fields and the receiver fills them from its own
   * default. An omitted `false` would reach an older runner as `true`, and it would render and
   * ship an annotation nobody asked for.
   */
  @Test
  fun `an older runner reads a request that asks for no annotation as asking for none`() {
    val encoded = TrailblazeJsonInstance.encodeToString(GetScreenStateRequest())

    val decoded = TrailblazeJsonInstance.decodeFromString<OlderRunnerRequest>(encoded)

    assertFalse(decoded.includeAnnotatedScreenshot, encoded)
  }
}
