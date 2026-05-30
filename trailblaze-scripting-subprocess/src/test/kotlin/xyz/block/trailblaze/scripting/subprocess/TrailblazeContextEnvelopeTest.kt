package xyz.block.trailblaze.scripting.subprocess

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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

  @Test fun `meta envelope omits target block when no TargetSnapshot supplied`() {
    // Backward-compat shape — sessions without a target (web-only, scratch tools, test
    // fixtures) emit the envelope minus `target`, and the TS SDK's `fromMeta` reads that as
    // `ctx.target === undefined` (same path that older daemons take).
    val envelope = TrailblazeContextEnvelope.buildMetaTrailblaze(
      memory = AgentMemory(),
      device = deviceInfo,
      baseUrl = "http://localhost:52525",
      sessionId = SessionId("session-abc"),
      invocationId = "inv-123",
    )
    assertThat(envelope.keys).doesNotContain("target")
  }

  @Test fun `meta envelope emits target block when TargetSnapshot supplied`() {
    val envelope = TrailblazeContextEnvelope.buildMetaTrailblaze(
      memory = AgentMemory(),
      device = deviceInfo,
      baseUrl = "http://localhost:52525",
      sessionId = SessionId("session-abc"),
      invocationId = "inv-123",
      target = TrailblazeContextEnvelope.TargetSnapshot(
        id = "example",
        appIds = listOf("com.example.dev", "com.example.staging", "com.example"),
        appId = "com.example.dev",
      ),
    )
    val target = envelope["target"]!!.jsonObject
    assertThat(target["id"]!!.jsonPrimitive.content).isEqualTo("example")
    assertThat(target["appId"]!!.jsonPrimitive.content).isEqualTo("com.example.dev")
    val appIds = (target["appIds"] as JsonArray).map { it.jsonPrimitive.content }
    assertThat(appIds).containsExactly("com.example.dev", "com.example.staging", "com.example")
  }

  @Test fun `meta envelope target omits appId when null`() {
    // No declared candidate installed → appId is absent (vs. JSON null) so the TS SDK's
    // optional-chained reads on `ctx.target.appId` see `undefined` and authors fall
    // through to `appIds[0]` as documented.
    val envelope = TrailblazeContextEnvelope.buildMetaTrailblaze(
      memory = AgentMemory(),
      device = deviceInfo,
      baseUrl = "http://localhost:52525",
      sessionId = SessionId("session-abc"),
      invocationId = "inv-123",
      target = TrailblazeContextEnvelope.TargetSnapshot(
        id = "example",
        appIds = listOf("com.example.dev"),
        appId = null,
      ),
    )
    val target = envelope["target"]!!.jsonObject
    assertThat(target.keys).doesNotContain("appId")
    assertThat(target["id"]!!.jsonPrimitive.content).isEqualTo("example")
    assertThat((target["appIds"] as JsonArray).single().jsonPrimitive.content)
      .isEqualTo("com.example.dev")
  }

  @Test fun `applyResultMemoryDelta merges sets into AgentMemory`() {
    val memory = AgentMemory().apply { remember("existing", "old") }
    val resultMeta = buildJsonObject {
      put(
        TrailblazeContextEnvelope.META_KEY,
        buildJsonObject {
          put(
            "memoryDelta",
            buildJsonObject {
              put("user", "ada")
              put("existing", "new")
            },
          )
        },
      )
    }
    val applied = TrailblazeContextEnvelope.applyResultMemoryDelta(memory, resultMeta)
    assertThat(applied).isTrue()
    assertThat(memory.variables["user"]).isEqualTo("ada")
    assertThat(memory.variables["existing"]).isEqualTo("new")
  }

  @Test fun `applyResultMemoryDelta applies memoryDeletions`() {
    val memory = AgentMemory().apply {
      remember("keep", "k")
      remember("drop", "d")
    }
    val resultMeta = buildJsonObject {
      put(
        TrailblazeContextEnvelope.META_KEY,
        buildJsonObject {
          put("memoryDeletions", buildJsonArray { add(JsonPrimitive("drop")) })
        },
      )
    }
    TrailblazeContextEnvelope.applyResultMemoryDelta(memory, resultMeta)
    assertThat(memory.has("drop")).isFalse()
    assertThat(memory.variables["keep"]).isEqualTo("k")
  }

  @Test fun `applyResultMemoryDelta no-ops on null meta`() {
    val memory = AgentMemory().apply { remember("k", "v") }
    val applied = TrailblazeContextEnvelope.applyResultMemoryDelta(memory, null)
    assertThat(applied).isFalse()
    assertThat(memory.variables["k"]).isEqualTo("v")
  }

  @Test fun `applyResultMemoryDelta no-ops on meta without trailblaze envelope`() {
    val memory = AgentMemory()
    val applied = TrailblazeContextEnvelope.applyResultMemoryDelta(
      memory,
      buildJsonObject { put("other", "bucket") },
    )
    assertThat(applied).isFalse()
    assertThat(memory.variables).isEmpty()
  }

  @Test fun `applyResultMemoryDelta skips non-string set values`() {
    // Producer-side bug should surface as a missing entry, not a stringified-number value.
    val memory = AgentMemory()
    val resultMeta = buildJsonObject {
      put(
        TrailblazeContextEnvelope.META_KEY,
        buildJsonObject {
          put(
            "memoryDelta",
            buildJsonObject {
              put("good", "value")
              put("bad", JsonPrimitive(42))
            },
          )
        },
      )
    }
    TrailblazeContextEnvelope.applyResultMemoryDelta(memory, resultMeta)
    assertThat(memory.variables["good"]).isEqualTo("value")
    assertThat(memory.variables["bad"]).isNull()
  }

  @Test fun `applyResultMemoryDelta preserves sensitive marker on overwrite`() {
    // A key that was marked sensitive on the host must STAY sensitive after a TS-side
    // scripted tool writes a new value to it via the delta. Otherwise the `remember()`
    // log call would leak the new (still-sensitive) value in plain text. The apply
    // path detects existing sensitive marks and routes through `rememberSensitive`.
    val memory = AgentMemory().apply { rememberSensitive("pin", "old-pin") }
    val resultMeta = buildJsonObject {
      put(
        TrailblazeContextEnvelope.META_KEY,
        buildJsonObject {
          put("memoryDelta", buildJsonObject { put("pin", "new-pin") })
        },
      )
    }
    TrailblazeContextEnvelope.applyResultMemoryDelta(memory, resultMeta)
    assertThat(memory.variables["pin"]).isEqualTo("new-pin")
    assertThat(memory.sensitiveKeys.contains("pin")).isTrue()
  }

  @Test fun `applyResultMemoryDelta deletion of sensitive key drops value but preserves marker`() {
    // Symmetric to the sensitive-preserve guard on the set path. A TS scripted tool
    // can clear a sensitive value via `memoryDeletions`, but the host's sensitive
    // marker stays so a future write to the same key still redacts logs.
    val memory = AgentMemory().apply { rememberSensitive("pin", "1234") }
    val resultMeta = buildJsonObject {
      put(
        TrailblazeContextEnvelope.META_KEY,
        buildJsonObject {
          put("memoryDeletions", buildJsonArray { add(JsonPrimitive("pin")) })
        },
      )
    }
    TrailblazeContextEnvelope.applyResultMemoryDelta(memory, resultMeta)
    assertThat(memory.has("pin")).isFalse()
    assertThat(memory.sensitiveKeys.contains("pin")).isTrue()
  }

  @Test fun `applyResultMemoryDelta skips non-string deletion entries`() {
    // Mirror of the set-side guard. A producer that leaks a non-string into the deletions
    // array (e.g. an integer key) should not crash the apply loop — that entry is skipped
    // while sibling string entries still delete.
    val memory = AgentMemory().apply {
      remember("keep_int_skipped", "v")
      remember("really_delete", "v")
    }
    val resultMeta = buildJsonObject {
      put(
        TrailblazeContextEnvelope.META_KEY,
        buildJsonObject {
          put(
            "memoryDeletions",
            buildJsonArray {
              add(JsonPrimitive(42))
              add(JsonPrimitive("really_delete"))
            },
          )
        },
      )
    }
    TrailblazeContextEnvelope.applyResultMemoryDelta(memory, resultMeta)
    assertThat(memory.has("keep_int_skipped")).isTrue()
    assertThat(memory.has("really_delete")).isFalse()
  }

  @Test fun `applyResultMemoryDelta is end-to-end inverse of build memory snapshot`() {
    // Lock the round-trip: a handler reads `_meta.trailblaze.memory` (built by
    // buildMetaTrailblaze), writes back a `memoryDelta`, and the host applies it. The
    // host's memory after the apply mirrors the merged state.
    val hostMemory = AgentMemory().apply {
      remember("session_token", "abc123")
      remember("locale", "en-US")
    }
    val outbound = TrailblazeContextEnvelope.buildMetaTrailblaze(
      memory = hostMemory,
      device = deviceInfo,
      baseUrl = "http://localhost:52525",
      sessionId = SessionId("s"),
      invocationId = "i",
    )
    // The handler "received" outbound, set new keys, deleted one, returned a delta.
    val handlerDelta = buildJsonObject {
      put(
        TrailblazeContextEnvelope.META_KEY,
        buildJsonObject {
          put("memoryDelta", buildJsonObject { put("user_id", "42") })
          put("memoryDeletions", buildJsonArray { add(JsonPrimitive("locale")) })
        },
      )
    }
    TrailblazeContextEnvelope.applyResultMemoryDelta(hostMemory, handlerDelta)
    assertThat(hostMemory.variables.keys).isEqualTo(setOf("session_token", "user_id"))
    assertThat(hostMemory.variables["user_id"]).isEqualTo("42")
    // The inbound block still reflects pre-apply state — it's a snapshot, not a live view.
    assertThat(outbound["memory"]!!.jsonObject.keys).isEqualTo(setOf("session_token", "locale"))
  }

  @Test fun `meta envelope target omits optional displayName and baseUrls when unset`() {
    // The wire shape is "absent = unset"; emitting JSON nulls would force every consumer to
    // distinguish "no value" from "value is null", which the TS interface treats identically
    // anyway. Locking the omission semantics so a future writer change doesn't accidentally
    // start emitting nulls and break consumers that check `"displayName" in target`.
    val envelope = TrailblazeContextEnvelope.buildMetaTrailblaze(
      memory = AgentMemory(),
      device = deviceInfo,
      baseUrl = "http://localhost:52525",
      sessionId = SessionId("session-abc"),
      invocationId = "inv-123",
      target = TrailblazeContextEnvelope.TargetSnapshot(
        id = "example",
        appIds = listOf("com.example"),
        appId = "com.example",
      ),
    )
    val target = envelope["target"]!!.jsonObject
    assertThat(target.keys).doesNotContain("displayName")
    assertThat(target.keys).doesNotContain("baseUrls")
    assertThat(target.keys).doesNotContain("resolvedBaseUrl")
  }
}
