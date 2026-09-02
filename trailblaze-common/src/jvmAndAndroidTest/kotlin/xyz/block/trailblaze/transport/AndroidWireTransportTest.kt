package xyz.block.trailblaze.transport

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test
import xyz.block.trailblaze.devices.TrailblazeDriverType

/**
 * The driver pin is the only thing standing between an `ANDROID_TEST` run and a full readiness
 * timeout: `OnDeviceRpcProtoCodec.toProto` cannot encode that driver's `androidView` / `compose`
 * tree, so every tree-bearing response over the binary wire — the readiness probe included — comes
 * back an encode failure against a server that is up and answering. A hang, not an error, which is
 * why this is pinned by a test rather than left to be noticed.
 */
class AndroidWireTransportTest {

  @Test
  fun `drivers the binary codec cannot encode are pinned to JSON`() {
    TrailblazeDriverType.entries.filter { !it.protoWireSafe }.forEach { driverType ->
      assertThat(AndroidWireTransport.modeFor(driverType), "modeFor($driverType)")
        .isEqualTo(AndroidWireTransportMode.JSON)
    }
  }

  /**
   * Compared against [AndroidWireTransport.mode] rather than a literal so the test states the
   * contract — proto-safe drivers defer to the environment switch — instead of asserting whatever
   * `TRAILBLAZE_ANDROID_WIRE_TRANSPORT` happens to be in the runner's environment.
   */
  @Test
  fun `proto-safe drivers defer to the environment switch`() {
    TrailblazeDriverType.entries.filter { it.protoWireSafe }.forEach { driverType ->
      assertThat(AndroidWireTransport.modeFor(driverType), "modeFor($driverType)")
        .isEqualTo(AndroidWireTransport.mode)
    }
  }

  /** Callers that have not resolved a driver yet must not be silently downgraded to JSON. */
  @Test
  fun `an unknown driver defers to the environment switch`() {
    assertThat(AndroidWireTransport.modeFor(null)).isEqualTo(AndroidWireTransport.mode)
  }
}
