package xyz.block.trailblaze.quickjs.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.block.trailblaze.devices.TrailblazeDriverType

/**
 * Direct coverage for [QuickJsToolMeta]. The launcher tests indirectly exercise the
 * `requiresHost` filter via real bundle fixtures; this suite pins `supportedDrivers`,
 * `supportedPlatforms` (with case normalization), the missing-`_meta` all-permissive
 * fallback, and malformed-`_meta` shape resilience.
 */
class QuickJsToolMetaTest {

  @Test
  fun `fromSpec defaults to all-permissive when meta is missing`() {
    val meta = QuickJsToolMeta.fromSpec(buildJsonObject { put("description", "no meta") })
    assertEquals(QuickJsToolMeta(), meta)
    assertTrue(meta.shouldRegister(TrailblazeDriverType.DEFAULT_ANDROID, preferHostAgent = false))
    assertTrue(meta.shouldRegister(TrailblazeDriverType.DEFAULT_ANDROID, preferHostAgent = true))
  }

  @Test
  fun `fromSpec defaults when meta is a JsonPrimitive instead of an object`() {
    // Author shouldn't write this, but a malformed `_meta` must not crash registration.
    val spec = buildJsonObject { put("_meta", "not-an-object") }
    val meta = QuickJsToolMeta.fromSpec(spec)
    assertEquals(QuickJsToolMeta(), meta)
  }

  @Test
  fun `supportedDrivers filters out current driver when not in list`() {
    val spec = buildJsonObject {
      put(
        "_meta",
        buildJsonObject {
          put("trailblaze/supportedDrivers", buildJsonArray { /* none */ })
        },
      )
    }
    val emptyList = QuickJsToolMeta.fromSpec(spec)
    // Empty list means unrestricted — must register.
    assertTrue(emptyList.shouldRegister(TrailblazeDriverType.DEFAULT_ANDROID, preferHostAgent = false))

    val onlyOther = QuickJsToolMeta.fromSpec(
      buildJsonObject {
        put(
          "_meta",
          buildJsonObject {
            put(
              "trailblaze/supportedDrivers",
              buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("not-the-current-driver")) },
            )
          },
        )
      },
    )
    assertFalse(
      onlyOther.shouldRegister(TrailblazeDriverType.DEFAULT_ANDROID, preferHostAgent = false),
      "tool with supportedDrivers=[not-the-current-driver] must not register on android-ondevice",
    )

    val includesCurrent = QuickJsToolMeta.fromSpec(
      buildJsonObject {
        put(
          "_meta",
          buildJsonObject {
            put(
              "trailblaze/supportedDrivers",
              buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive(TrailblazeDriverType.DEFAULT_ANDROID.yamlKey))
              },
            )
          },
        )
      },
    )
    assertTrue(includesCurrent.shouldRegister(TrailblazeDriverType.DEFAULT_ANDROID, preferHostAgent = false))
  }

  @Test
  fun `supportedPlatforms normalizes to uppercase before membership check`() {
    // Authors can write any casing; the case-normalize step in fromSpec keeps the !in
    // check against TrailblazeDevicePlatform.name (always uppercase) branch-free.
    val mixedCase = QuickJsToolMeta.fromSpec(
      buildJsonObject {
        put(
          "_meta",
          buildJsonObject {
            put(
              "trailblaze/supportedPlatforms",
              buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive("android"))
                add(kotlinx.serialization.json.JsonPrimitive("WEB"))
                add(kotlinx.serialization.json.JsonPrimitive("Ios"))
              },
            )
          },
        )
      },
    )
    assertEquals(listOf("ANDROID", "WEB", "IOS"), mixedCase.supportedPlatforms)
    assertTrue(mixedCase.shouldRegister(TrailblazeDriverType.DEFAULT_ANDROID, preferHostAgent = false))
  }

  @Test
  fun `requiresHost gates registration on preferHostAgent`() {
    val hostOnly = QuickJsToolMeta.fromSpec(
      buildJsonObject {
        put("_meta", buildJsonObject { put("trailblaze/requiresHost", true) })
      },
    )
    assertFalse(
      hostOnly.shouldRegister(TrailblazeDriverType.DEFAULT_ANDROID, preferHostAgent = false),
      "requiresHost=true must skip on-device sessions (preferHostAgent=false)",
    )
    assertTrue(
      hostOnly.shouldRegister(TrailblazeDriverType.DEFAULT_ANDROID, preferHostAgent = true),
      "requiresHost=true must register host sessions (preferHostAgent=true)",
    )
  }

  @Test
  fun `non-array supportedDrivers value is ignored rather than crashing`() {
    val malformed = QuickJsToolMeta.fromSpec(
      buildJsonObject {
        put(
          "_meta",
          buildJsonObject {
            put("trailblaze/supportedDrivers", "this-should-be-an-array")
          },
        )
      },
    )
    // `readStringList` returns empty for a non-array, which is the all-permissive default.
    assertTrue(malformed.supportedDrivers.isEmpty())
    assertTrue(malformed.shouldRegister(TrailblazeDriverType.DEFAULT_ANDROID, preferHostAgent = false))
  }

  @Test
  fun `meta is plain JsonObject`() {
    // Sanity: fromSpec doesn't require any specific JsonObject impl; works with the basic builder.
    val spec: JsonObject = buildJsonObject {
      put("description", "tool")
    }
    val meta = QuickJsToolMeta.fromSpec(spec)
    assertFalse(meta.requiresHost)
    assertEquals(emptyList(), meta.supportedDrivers)
    assertEquals(emptyList(), meta.supportedPlatforms)
  }
}
