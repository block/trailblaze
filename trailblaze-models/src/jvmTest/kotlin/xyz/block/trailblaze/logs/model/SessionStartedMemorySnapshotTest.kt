package xyz.block.trailblaze.logs.model

import kotlinx.serialization.json.Json
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the wire shape of [SessionStatus.Started.resolvedInitialMemory] and
 * [SessionStatus.Started.sensitiveMemoryKeys]:
 *
 *  1. Both new fields default-empty, so a `Started` event built without naming them
 *     decodes to empty collections (the runner code path emits without them when no
 *     seeding occurred).
 *  2. Round-tripping a `Started` with non-empty values preserves them.
 *  3. A legacy JSON envelope written BEFORE these fields existed (no
 *     `resolvedInitialMemory` / `sensitiveMemoryKeys` keys present) still decodes —
 *     the defaults kick in. This is what makes the field addition backwards-compatible
 *     for session logs already on disk and in TestRail uploads.
 */
class SessionStartedMemorySnapshotTest {

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  private val deviceInfo = TrailblazeDeviceInfo(
    trailblazeDeviceId = TrailblazeDeviceId(
      instanceId = "pixel-7",
      trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
    ),
    trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
    widthPixels = 0,
    heightPixels = 0,
    classifiers = emptyList(),
  )

  @Test
  fun `default Started has empty memory snapshot fields`() {
    val started = SessionStatus.Started(
      trailConfig = null,
      trailFilePath = null,
      hasRecordedSteps = false,
      testMethodName = "test",
      testClassName = "Test",
      trailblazeDeviceInfo = deviceInfo,
    )
    assertTrue(started.resolvedInitialMemory.isEmpty())
    assertTrue(started.sensitiveMemoryKeys.isEmpty())
  }

  @Test
  fun `Started with non-empty memory snapshot round-trips through JSON`() {
    val original = SessionStatus.Started(
      trailConfig = null,
      trailFilePath = null,
      hasRecordedSteps = false,
      testMethodName = "test",
      testClassName = "Test",
      trailblazeDeviceInfo = deviceInfo,
      resolvedInitialMemory = mapOf("user" to "sam", "accountTier" to "PRO"),
      sensitiveMemoryKeys = setOf("password", "apiToken"),
    )

    val encoded = json.encodeToString(SessionStatus.serializer(), original)
    val decoded = json.decodeFromString(SessionStatus.serializer(), encoded)

    assertEquals(original, decoded)
  }

  @Test
  fun `legacy JSON without the new fields decodes to empty defaults`() {
    // Hand-crafted JSON missing both fields — what existing session-end.json artifacts
    // (written before this feature landed) look like. Must decode without throwing so
    // the desktop app's Sessions tab can still render historical sessions, and the
    // log-replay path doesn't need a migration step for old logs.
    val legacyJson = """
      {
        "type": "xyz.block.trailblaze.logs.model.SessionStatus.Started",
        "trailConfig": null,
        "trailFilePath": null,
        "hasRecordedSteps": false,
        "testMethodName": "test",
        "testClassName": "Test",
        "trailblazeDeviceInfo": {
          "trailblazeDeviceId": {
            "instanceId": "pixel-7",
            "trailblazeDevicePlatform": "ANDROID"
          },
          "trailblazeDriverType": "ANDROID_ONDEVICE_ACCESSIBILITY",
          "widthPixels": 0,
          "heightPixels": 0,
          "classifiers": []
        }
      }
    """.trimIndent()

    val decoded = json.decodeFromString(SessionStatus.serializer(), legacyJson)
    assertTrue(decoded is SessionStatus.Started)
    assertTrue((decoded as SessionStatus.Started).resolvedInitialMemory.isEmpty())
    assertTrue(decoded.sensitiveMemoryKeys.isEmpty())
  }

  @Test
  fun `legacy JSON with explicit null for the new fields surfaces a decode error not a silent empty`() {
    // Distinct from the missing-field case: explicit `"sensitiveMemoryKeys": null` and
    // `"resolvedInitialMemory": null` write a `null` JSON value into non-nullable
    // default-bearing fields. kotlinx-serialization rejects this (rather than silently
    // applying the default) — pin the loud-failure mode so a producer that drifts to
    // writing `null` instead of omitting the field doesn't sneak through. If the contract
    // ever needs to accept explicit nulls, this test will fail and force a deliberate
    // schema update.
    val legacyJsonWithNulls = """
      {
        "type": "xyz.block.trailblaze.logs.model.SessionStatus.Started",
        "trailConfig": null,
        "trailFilePath": null,
        "hasRecordedSteps": false,
        "testMethodName": "test",
        "testClassName": "Test",
        "trailblazeDeviceInfo": {
          "trailblazeDeviceId": {
            "instanceId": "pixel-7",
            "trailblazeDevicePlatform": "ANDROID"
          },
          "trailblazeDriverType": "ANDROID_ONDEVICE_ACCESSIBILITY",
          "widthPixels": 0,
          "heightPixels": 0,
          "classifiers": []
        },
        "resolvedInitialMemory": null,
        "sensitiveMemoryKeys": null
      }
    """.trimIndent()

    kotlin.test.assertFails {
      json.decodeFromString(SessionStatus.serializer(), legacyJsonWithNulls)
    }
  }
}
