package xyz.block.trailblaze.events

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Pins the [AttachmentRef] wire shape and the marker-based detection contract. */
class AttachmentRefTest {

  private val ref = AttachmentRef(
    path = "attachments/3f9c.wav",
    mimeType = "audio/wav",
    sizeBytes = 88_244,
    label = "synthesized speech",
  )

  @Test
  fun `serialization round-trips`() {
    val json = Json.encodeToString(AttachmentRef.serializer(), ref)
    assertEquals(ref, Json.decodeFromString(AttachmentRef.serializer(), json))
  }

  @Test
  fun `marker is always encoded, even with encodeDefaults off`() {
    val strictJson = Json { encodeDefaults = false }
    val obj = strictJson.encodeToJsonElement(AttachmentRef.serializer(), ref).jsonObject
    assertEquals(JsonPrimitive(true), obj[AttachmentRef.MARKER_FIELD])
  }

  @Test
  fun `label is optional`() {
    val noLabel = """{"${'$'}attachment":true,"path":"attachments/a.png","mimeType":"image/png","sizeBytes":10}"""
    val decoded = Json.decodeFromString(AttachmentRef.serializer(), noLabel)
    assertNull(decoded.label)
  }

  @Test
  fun `detection accepts a marked object and tolerates unknown keys`() {
    val obj = buildJsonObject {
      put(AttachmentRef.MARKER_FIELD, true)
      put("path", "attachments/a.wav")
      put("mimeType", "audio/wav")
      put("sizeBytes", 5)
      put("someProducerExtra", "ignored")
    }
    val detected = AttachmentRef.fromJsonObjectOrNull(obj)
    assertEquals(AttachmentRef(path = "attachments/a.wav", mimeType = "audio/wav", sizeBytes = 5), detected)
  }

  @Test
  fun `detection is exact-dispatch, not duck-typed`() {
    // path+mimeType+sizeBytes without the marker is NOT an attachment.
    val duck = buildJsonObject {
      put("path", "attachments/a.wav")
      put("mimeType", "audio/wav")
      put("sizeBytes", 5)
    }
    assertNull(AttachmentRef.fromJsonObjectOrNull(duck))
  }

  @Test
  fun `detection requires the marker to be literally true`() {
    fun marked(marker: JsonPrimitive): JsonObject = buildJsonObject {
      put(AttachmentRef.MARKER_FIELD, marker)
      put("path", "attachments/a.wav")
      put("mimeType", "audio/wav")
      put("sizeBytes", 5)
    }
    assertNull(AttachmentRef.fromJsonObjectOrNull(marked(JsonPrimitive(false))))
    assertNull(AttachmentRef.fromJsonObjectOrNull(marked(JsonPrimitive("true"))))
    assertNull(AttachmentRef.fromJsonObjectOrNull(marked(JsonPrimitive(1))))
  }

  @Test
  fun `detection rejects a marked object with missing or mistyped required fields`() {
    val missingPath = buildJsonObject {
      put(AttachmentRef.MARKER_FIELD, true)
      put("mimeType", "audio/wav")
      put("sizeBytes", 5)
    }
    assertNull(AttachmentRef.fromJsonObjectOrNull(missingPath))

    val stringSize = buildJsonObject {
      put(AttachmentRef.MARKER_FIELD, true)
      put("path", "attachments/a.wav")
      put("mimeType", "audio/wav")
      put("sizeBytes", "big")
    }
    assertNull(AttachmentRef.fromJsonObjectOrNull(stringSize))
  }

  @Test
  fun `a false marker is refused at construction, on copy, and on decode`() {
    // All three reach the same init block. A ref that encoded "$attachment": false would be
    // rejected by every detector on both sides, so it must never come into existence.
    assertFailsWith<IllegalArgumentException> {
      AttachmentRef(path = "a.wav", mimeType = "audio/wav", sizeBytes = 1, marker = false)
    }
    assertFailsWith<IllegalArgumentException> { ref.copy(marker = false) }
    val falseMarker = """{"${'$'}attachment":false,"path":"a.wav","mimeType":"audio/wav","sizeBytes":1}"""
    assertFails { Json.decodeFromString(AttachmentRef.serializer(), falseMarker) }
  }

  @Test
  fun `sizeBytes reads integer literals exactly, past the range a double can carry`() {
    // 2^53 + 1 — the smallest integer a Double cannot represent. Parsing via Double would
    // silently report 9007199254740992 for a file whose size the wire stated exactly.
    val obj = buildJsonObject {
      put(AttachmentRef.MARKER_FIELD, true)
      put("path", "attachments/huge.bin")
      put("mimeType", "application/octet-stream")
      put("sizeBytes", 9_007_199_254_740_993L)
    }
    assertEquals(9_007_199_254_740_993L, AttachmentRef.fromJsonObjectOrNull(obj)?.sizeBytes)
  }

  @Test
  fun `sizeBytes truncates a fractional literal`() {
    assertEquals(5L, AttachmentRef.fromJsonObjectOrNull(sized(JsonPrimitive(5.9)))?.sizeBytes)
  }

  @Test
  fun `sizeBytes rejects negative, non-finite and out-of-range values instead of clamping`() {
    // A size that cannot be represented is bad input, not a very large file — so these are `null`
    // (the whole object is not an attachment) rather than Long.MAX_VALUE or a negative row.
    assertNull(AttachmentRef.fromJsonObjectOrNull(sized(JsonPrimitive(-1))))
    assertNull(AttachmentRef.fromJsonObjectOrNull(sized(JsonPrimitive(-0.5))))
    assertNull(AttachmentRef.fromJsonObjectOrNull(sized(JsonPrimitive(Double.NaN))))
    assertNull(AttachmentRef.fromJsonObjectOrNull(sized(JsonPrimitive(Double.POSITIVE_INFINITY))))
    assertNull(AttachmentRef.fromJsonObjectOrNull(sized(JsonPrimitive(1e30))))
  }

  private fun sized(sizeBytes: JsonPrimitive): JsonObject = buildJsonObject {
    put(AttachmentRef.MARKER_FIELD, true)
    put("path", "attachments/a.wav")
    put("mimeType", "audio/wav")
    put("sizeBytes", sizeBytes)
  }

  @Test
  fun `findAll sweeps refs at any depth under any field name, in document order`() {
    val payload = buildJsonObject {
      put("text", "Can I see the full menu please?")
      putJsonObject("audio") {
        put(AttachmentRef.MARKER_FIELD, true)
        put("path", "attachments/first.wav")
        put("mimeType", "audio/wav")
        put("sizeBytes", 1)
      }
      putJsonArray("extras") {
        add(
          buildJsonObject {
            putJsonObject("nested") {
              put(AttachmentRef.MARKER_FIELD, true)
              put("path", "attachments/second.png")
              put("mimeType", "image/png")
              put("sizeBytes", 2)
              put("label", "screenshot")
            }
          },
        )
      }
    }
    val found = AttachmentRef.findAll(payload)
    assertEquals(listOf("attachments/first.wav", "attachments/second.png"), found.map { it.path })
    assertEquals("screenshot", found[1].label)
  }

  @Test
  fun `findAll on a payload with no refs is empty`() {
    val payload = buildJsonObject {
      put("name", "tap")
      putJsonObject("details") { put("x", 1) }
    }
    assertTrue(AttachmentRef.findAll(payload).isEmpty())
  }
}
