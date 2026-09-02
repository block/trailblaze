package xyz.block.trailblaze.logs.server.endpoints

import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Wire contract for [CliStatusResponse.capabilities], the signal a CLI uses to decide whether a
 * running daemon will honor a per-request field or silently drop it.
 *
 * The default is the load-bearing part: an older daemon sends no `capabilities` key, and that must
 * decode as "honors nothing from the set" rather than as an absent-means-fine value. A caller reads
 * it to refuse delegating `--bind` / `--configuration` to a daemon that would resolve the device
 * set from its own environment instead.
 */
class CliStatusCapabilitiesTest {

  private val json = Json { ignoreUnknownKeys = true }

  @Test
  fun `capabilities round-trip through JSON`() {
    val decoded = json.decodeFromString(
      CliStatusResponse.serializer(),
      json.encodeToString(
        CliStatusResponse.serializer(),
        CliStatusResponse(
          running = true,
          port = 52525,
          connectedDevices = 1,
          uptimeSeconds = 5,
          capabilities = CliDaemonCapabilities.ALL,
        ),
      ),
    )

    assertEquals(CliDaemonCapabilities.ALL, decoded.capabilities)
  }

  @Test
  fun `a status payload from an older daemon decodes to no capabilities`() {
    val legacyPayload =
      """{"running":true,"port":52525,"connectedDevices":1,"uptimeSeconds":5}"""

    val decoded = json.decodeFromString(CliStatusResponse.serializer(), legacyPayload)

    assertEquals(emptySet(), decoded.capabilities)
  }

  @Test
  fun `this build advertises per-run device bindings`() {
    // The set is what a caller matches against; a capability defined but left out of ALL would
    // make every capable daemon look incapable.
    assertTrue(CliDaemonCapabilities.PER_RUN_DEVICE_BINDINGS in CliDaemonCapabilities.ALL)
  }
}
