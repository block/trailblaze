package xyz.block.trailblaze.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.host.yaml.MultiDeviceConfigurationResolver

/**
 * Pins the contract of [TrailCommand.Companion.parseDeviceBinds] — the helper behind
 * `run --bind NAME=DEVICE_ID`, which names a multi-device trail's companion devices for one run.
 *
 * The behavior worth pinning is the divergence from `--memory`: a repeated NAME is an ERROR here,
 * not last-wins. The bindings travel as a map, so a dropped duplicate would be invisible to
 * `MultiDeviceConfigurationResolver` and the device the operator named first would go unbound with
 * nothing reported.
 */
class TrailCommandParseDeviceBindsTest {

  @Test
  fun `parses a single NAME=DEVICE_ID entry`() {
    assertEquals(
      mapOf("buyer" to "emulator-5562"),
      TrailCommand.parseDeviceBinds(listOf("buyer=emulator-5562")),
    )
  }

  @Test
  fun `preserves declared order across several entries`() {
    val binds = TrailCommand.parseDeviceBinds(
      listOf("buyer=emulator-5562", "kitchen=emulator-5564"),
    )
    assertEquals(listOf("buyer", "kitchen"), binds.keys.toList())
    assertEquals(listOf("emulator-5562", "emulator-5564"), binds.values.toList())
  }

  @Test
  fun `splits on the first equals so a device id may contain more equals`() {
    assertEquals(
      mapOf("buyer" to "udid=ABC-123"),
      TrailCommand.parseDeviceBinds(listOf("buyer=udid=ABC-123")),
    )
  }

  @Test
  fun `empty list yields empty map`() {
    assertEquals(emptyMap(), TrailCommand.parseDeviceBinds(emptyList()))
  }

  @Test
  fun `a repeated name is rejected instead of silently last-wins`() {
    val e = assertFailsWith<IllegalArgumentException> {
      TrailCommand.parseDeviceBinds(listOf("buyer=emulator-5562", "buyer=emulator-5564"))
    }
    val message = e.message ?: ""
    assertTrue("buyer" in message, "should name the duplicated bind name: $message")
    assertTrue(
      "emulator-5562" in message && "emulator-5564" in message,
      "should show both device ids so the operator can see which one they meant: $message",
    )
  }

  @Test
  fun `missing equals throws naming the offending entry`() {
    val e = assertFailsWith<IllegalArgumentException> {
      TrailCommand.parseDeviceBinds(listOf("emulator-5562"))
    }
    assertEquals(
      "Invalid --bind entry \"emulator-5562\" — expected NAME=DEVICE_ID with a non-empty NAME " +
        "(e.g. --bind buyer=emulator-5562).",
      e.message,
    )
  }

  @Test
  fun `empty name throws`() {
    val e = assertFailsWith<IllegalArgumentException> {
      TrailCommand.parseDeviceBinds(listOf("=emulator-5562"))
    }
    assertEquals(
      "Invalid --bind entry \"=emulator-5562\" — expected NAME=DEVICE_ID with a non-empty NAME " +
        "(e.g. --bind buyer=emulator-5562).",
      e.message,
    )
  }

  @Test
  fun `wholly empty entry throws`() {
    assertFailsWith<IllegalArgumentException> {
      TrailCommand.parseDeviceBinds(listOf(""))
    }
  }

  @Test
  fun `an empty device id is left for the resolver to reject`() {
    // Not the parser's job: MultiDeviceConfigurationResolver.validateRequestDeviceBindings phrases
    // a blank device id against the trail's own `config.devices:`, which this helper cannot see.
    assertEquals(
      mapOf("buyer" to ""),
      TrailCommand.parseDeviceBinds(listOf("buyer=")),
    )
  }

  // --- What the flags' help text promises, resolved end to end ---
  //
  // The two cases below carry CLI-shaped input through this parser and into the resolver that
  // consumes it, so the help text's claims are pinned by behavior rather than by wording. The
  // resolver's own contract is covered in MultiDeviceConfigurationResolverTest; what these add is
  // that what the CLI hands over is the shape the resolver accepts.

  private val pairTrail = """
    config:
      devices:
        pos-pair:
          devices:
            seller:
              classifier: android-tablet
            buyer:
              classifier: android-phone
    trail:
      - prompt: "tap checkout"
  """.trimIndent()

  private val singleDeviceTrail = """
    config:
      devices:
        android:
          driver: ANDROID_ONDEVICE_ACCESSIBILITY
    trail:
      - prompt: "tap checkout"
  """.trimIndent()

  private fun resolve(yaml: String, binds: List<String>, configuration: String?) =
    MultiDeviceConfigurationResolver.resolve(
      yaml = yaml,
      primaryDeviceId = TrailblazeDeviceId(
        instanceId = "emulator-5560",
        trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
      ),
      rawDeviceBindings = null,
      requestDeviceBindings = TrailCommand.parseDeviceBinds(binds),
      requestConfigurationName = configuration,
      sessionDriverType = null,
    )

  @Test
  fun `parsed binds and a named configuration resolve the declared cast`() {
    val resolved = resolve(pairTrail, listOf("buyer=emulator-5562"), configuration = "pos-pair")!!

    assertEquals("pos-pair", resolved.configurationName)
    assertEquals("seller", resolved.startDeviceName)
    assertEquals("emulator-5562", resolved.companionDeviceIds.getValue("buyer").instanceId)
  }

  @Test
  fun `naming a configuration a single-device trail does not declare is an error`() {
    // The `--configuration` help text's claim: this is an error, not a silent single-device run.
    // The flag is forwarded verbatim with no CLI-side check, so the promise is only kept if the
    // resolver rejects the selection rather than returning null past it.
    val message = assertFailsWith<TrailblazeException> {
      resolve(singleDeviceTrail, binds = emptyList(), configuration = "pos-pair")
    }.message.orEmpty()

    assertTrue(
      "declares no device configuration" in message,
      "must say the trail declares none rather than running single-device; got: $message",
    )
  }

  @Test
  fun `binding a name the selected configuration does not declare is an error`() {
    // The `--bind` help text's claim that the names come from the trail's configuration. A typo
    // must not degrade to a run with that companion quietly unbound.
    val message = assertFailsWith<TrailblazeException> {
      resolve(pairTrail, listOf("byer=emulator-5562"), configuration = "pos-pair")
    }.message.orEmpty()

    assertTrue("byer" in message, "must name the unrecognized bind; got: $message")
  }
}
