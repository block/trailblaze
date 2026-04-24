package xyz.block.trailblaze.scripting.subprocess

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.scripting.mcp.TrailblazeContextEnvelope
import kotlin.test.Test

class TrailblazeContextEnvelopeTest {

  private val deviceInfo = TrailblazeDeviceInfo(
    trailblazeDeviceId = TrailblazeDeviceId("emulator-5554", TrailblazeDevicePlatform.ANDROID),
    trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
    widthPixels = 1080,
    heightPixels = 2400,
  )

  @Test fun `envelope has exactly memory and device keys`() {
    val envelope = TrailblazeContextEnvelope.buildLegacyArgEnvelope(AgentMemory(), deviceInfo)
    assertThat(envelope.keys).isEqualTo(setOf("memory", "device"))
  }

  @Test fun `device block carries platform driver and dimensions`() {
    val envelope = TrailblazeContextEnvelope.buildLegacyArgEnvelope(AgentMemory(), deviceInfo)
    val device = envelope["device"]!!.jsonObject
    assertThat(device["platform"]!!.jsonPrimitive.content).isEqualTo("android")
    assertThat(device["driverType"]!!.jsonPrimitive.content).isEqualTo("android-ondevice-accessibility")
    assertThat(device["widthPixels"]!!.jsonPrimitive.int).isEqualTo(1080)
    assertThat(device["heightPixels"]!!.jsonPrimitive.int).isEqualTo(2400)
  }

  @Test fun `memory block mirrors AgentMemory variables as JSON string primitives`() {
    val memory = AgentMemory().apply {
      remember("email", "user@example.com")
      remember("session", "abc123")
    }
    val envelope = TrailblazeContextEnvelope.buildLegacyArgEnvelope(memory, deviceInfo)
    val memBlock = envelope["memory"]!!.jsonObject
    assertThat(memBlock["email"]).isEqualTo(JsonPrimitive("user@example.com"))
    assertThat(memBlock["session"]).isEqualTo(JsonPrimitive("abc123"))
  }

  @Test fun `memory block is empty object when AgentMemory is empty`() {
    val envelope = TrailblazeContextEnvelope.buildLegacyArgEnvelope(AgentMemory(), deviceInfo)
    assertThat(envelope["memory"]!!.jsonObject).isEqualTo(JsonObject(emptyMap()))
  }

  @Test fun `reserved key is literal _trailblazeContext`() {
    // Guards against accidental rename — this string is public contract with author-side TS.
    assertThat(TrailblazeContextEnvelope.RESERVED_KEY).isEqualTo("_trailblazeContext")
  }

  @Test fun `meta envelope carries baseUrl sessionId invocationId and device block`() {
    // The `_meta.trailblaze` envelope adds the transport fields the SDK reads
    // (baseUrl/sessionId/invocationId) on top of the legacy memory/device shape. Device block
    // must be structurally identical to the legacy arg envelope — SDK consumers and legacy
    // raw-SDK consumers need the same device shape so docs don't drift.
    val envelope = TrailblazeContextEnvelope.buildMetaTrailblaze(
      memory = AgentMemory(),
      device = deviceInfo,
      baseUrl = "http://localhost:52525",
      sessionId = SessionId("session-abc"),
      invocationId = "inv-123",
    )
    assertThat(envelope.keys).contains("baseUrl")
    assertThat(envelope.keys).contains("sessionId")
    assertThat(envelope.keys).contains("invocationId")
    assertThat(envelope.keys).contains("device")
    assertThat(envelope.keys).contains("memory")

    assertThat(envelope["baseUrl"]!!.jsonPrimitive.content).isEqualTo("http://localhost:52525")
    assertThat(envelope["sessionId"]!!.jsonPrimitive.content).isEqualTo("session-abc")
    assertThat(envelope["invocationId"]!!.jsonPrimitive.content).isEqualTo("inv-123")

    val device = envelope["device"]!!.jsonObject
    assertThat(device["platform"]!!.jsonPrimitive.content).isEqualTo("android")
    assertThat(device["driverType"]!!.jsonPrimitive.content).isEqualTo("android-ondevice-accessibility")
    assertThat(device["widthPixels"]!!.jsonPrimitive.int).isEqualTo(1080)
    assertThat(device["heightPixels"]!!.jsonPrimitive.int).isEqualTo(2400)
  }

  @Test fun `meta key is literal trailblaze`() {
    // Locks the top-level `_meta` bucket name — subprocess authors read `_meta.trailblaze`,
    // so renaming this breaks every SDK consumer silently.
    assertThat(TrailblazeContextEnvelope.META_KEY).isEqualTo("trailblaze")
  }
}
