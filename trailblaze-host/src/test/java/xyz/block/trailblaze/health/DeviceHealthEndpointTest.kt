package xyz.block.trailblaze.health

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform

/**
 * HTTP-level tests for [DeviceHealthEndpoint.register], driving `GET /health/device` through a real
 * Ktor test app with an injected device probe. Asserts the observable contract (the JSON body a
 * supervisor reads), not internal control flow.
 */
class DeviceHealthEndpointTest {

  private val json = Json { encodeDefaults = true }

  private val device =
    TrailblazeDeviceId(
      instanceId = "emulator-5554",
      trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
    )

  private fun healthApp(
    devices: List<TrailblazeDeviceId> = listOf(device),
    deviceProbe: (TrailblazeDeviceId, Long) -> String?,
    block: suspend ApplicationTestBuilder.() -> Unit,
  ) = testApplication {
    application {
      routing {
        DeviceHealthEndpoint.register(
          routing = this,
          connectedDevicesProvider = { devices },
          deviceProbe = deviceProbe,
        )
      }
    }
    block()
  }

  private fun decode(body: String): DeviceHealthResult =
    json.decodeFromString(DeviceHealthResult.serializer(), body)

  @Test
  fun `a completed getprop reports the device reachable`() = healthApp(
    deviceProbe = { _, _ -> "1" },
  ) {
    val result = decode(client.get(DeviceHealthEndpoint.PATH).bodyAsText())
    assertTrue(result.deviceReachable)
  }

  @Test
  fun `a completed-but-blank getprop still reports reachable (still-booting device)`() = healthApp(
    // A reachable emulator that is still booting answers sys.boot_completed with a blank line; the
    // transport is healthy, so this must NOT be reported as unreachable.
    deviceProbe = { _, _ -> "" },
  ) {
    val result = decode(client.get(DeviceHealthEndpoint.PATH).bodyAsText())
    assertTrue(result.deviceReachable, result.detail)
  }

  @Test
  fun `a null probe (timeout or failure) reports unreachable`() = healthApp(
    deviceProbe = { _, _ -> null },
  ) {
    val result = decode(client.get(DeviceHealthEndpoint.PATH).bodyAsText())
    assertTrue(!result.deviceReachable)
    assertTrue(result.detail.contains("did not answer"), result.detail)
  }

  @Test
  fun `no attached device reports unreachable without probing`() = healthApp(
    devices = emptyList(),
    deviceProbe = { _, _ -> error("probe must not run when no device is attached") },
  ) {
    val result = decode(client.get(DeviceHealthEndpoint.PATH).bodyAsText())
    assertTrue(!result.deviceReachable)
    assertTrue(result.detail.contains("no Android device"), result.detail)
  }

  @Test
  fun `the shell probe budget fits inside the health deadline so a retry cannot overrun it`() {
    var seenTimeout = -1L
    // The real adb call retries a timed-out attempt once at the full budget, so two attempts must
    // still fit inside the health deadline: the per-attempt budget must be at most half of it.
    healthApp(deviceProbe = { _, timeoutMs ->
      seenTimeout = timeoutMs
      "1"
    }) {
      client.get(DeviceHealthEndpoint.PATH).bodyAsText()
    }
    assertTrue(seenTimeout in 1..(DeviceHealthProbe.DEFAULT_TIMEOUT_MS / 2), "probe budget was $seenTimeout")
  }
}
