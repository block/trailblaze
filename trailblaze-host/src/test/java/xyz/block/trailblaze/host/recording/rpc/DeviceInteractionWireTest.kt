package xyz.block.trailblaze.host.recording.rpc

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import org.junit.Test
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.host.rpc.DeviceInteraction
import xyz.block.trailblaze.host.rpc.DeviceInteractionRequest

/**
 * Pins the gesture body Trail Runner's Devices screen posts to `/rpc/DeviceInteractionRequest`
 * (built by `app/rpc/daemon.ts` and the generated `host-rpc.ts`) against the decoder the daemon
 * actually uses. The daemon server's ContentNegotiation is [McpJson] (`TrailblazeMcpServer`), whose
 * sealed types are keyed on `type`, so the generated bindings must key on `type` too. A client
 * test can't catch a wrong key: it only sees the body it built itself.
 */
class DeviceInteractionWireTest {

  @Test
  fun `the body the web client sends decodes to the gesture it names`() {
    val body = """
      {"trailblazeDeviceId":{"instanceId":"e","trailblazeDevicePlatform":"ANDROID"},
       "interaction":{"type":"tap","x":10,"y":20}}
    """.trimIndent()

    val request = McpJson.decodeFromString(DeviceInteractionRequest.serializer(), body)

    assertThat(request).isEqualTo(
      DeviceInteractionRequest(
        trailblazeDeviceId = TrailblazeDeviceId("e", TrailblazeDevicePlatform.ANDROID),
        interaction = DeviceInteraction.Tap(x = 10, y = 20),
      ),
    )
  }

  @Test
  fun `a gesture keyed on class, the log files' key, is refused`() {
    val body = """
      {"trailblazeDeviceId":{"instanceId":"e","trailblazeDevicePlatform":"ANDROID"},
       "interaction":{"class":"tap","x":10,"y":20}}
    """.trimIndent()

    assertFailure { McpJson.decodeFromString(DeviceInteractionRequest.serializer(), body) }
  }
}
