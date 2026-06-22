package xyz.block.trailblaze.quickjs.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.devices.TrailblazeDriverType

/**
 * Unit coverage for [QuickJsToolAdvertisement.fromInlineScriptToolConfig] — the single source of
 * truth for turning a catalog scripted tool's YAML config into "what the LLM sees" (descriptor)
 * plus "how it's gated on-device" (`_meta`). Both the on-device launch path
 * (`AndroidTrailblazeRule`) and any future caller build advertisements through this factory, so the
 * descriptor + `_meta`-wrap can't drift from the descriptor the host path derives. Previously the
 * device path open-coded this; pinning the factory keeps the behavior locked.
 */
class ScriptedToolAdvertisementFromConfigTest {

  /** JSON-Schema-shaped inputSchema with one required `url:string` property. */
  private fun urlInputSchema() = buildJsonObject {
    putJsonObject("properties") {
      putJsonObject("url") {
        put("type", "string")
        put("description", "The URL.")
      }
    }
    put("required", JsonArray(listOf(JsonPrimitive("url"))))
  }

  @Test
  fun `descriptor is built from the config's description and inputSchema`() {
    val config = InlineScriptToolConfig(
      script = "./openThing.ts",
      name = "openThing",
      description = "Opens the thing at the given url.",
      inputSchema = urlInputSchema(),
    )

    val advertisement = QuickJsToolAdvertisement.fromInlineScriptToolConfig(config)

    assertEquals("openThing", advertisement.descriptor.name)
    assertEquals("Opens the thing at the given url.", advertisement.descriptor.description)
    assertEquals(listOf("url"), advertisement.descriptor.requiredParameters.map { it.name })
    assertEquals("string", advertisement.descriptor.requiredParameters.single().type)
    assertTrue(advertisement.descriptor.optionalParameters.isEmpty())
  }

  @Test
  fun `supportedPlatforms _meta becomes the registration gate`() {
    val config = InlineScriptToolConfig(
      script = "./openThing.ts",
      name = "openThing",
      // Shape of `config.meta` after `toInlineScriptToolConfigs()` folds the top-level
      // `supportedPlatforms` shortcut into the namespaced `_meta` key.
      meta = buildJsonObject {
        put(
          "trailblaze/supportedPlatforms",
          JsonArray(listOf(JsonPrimitive("android"), JsonPrimitive("ios"))),
        )
      },
    )

    val advertisement = QuickJsToolAdvertisement.fromInlineScriptToolConfig(config)

    assertEquals(listOf("ANDROID", "IOS"), advertisement.meta.supportedPlatforms)
    assertTrue(
      advertisement.meta.shouldRegister(TrailblazeDriverType.DEFAULT_ANDROID, preferHostAgent = false),
    )
  }

  @Test
  fun `requiresHost _meta gates the tool off the on-device session`() {
    val config = InlineScriptToolConfig(
      script = "./hostThing.ts",
      name = "hostThing",
      meta = buildJsonObject { put("trailblaze/requiresHost", true) },
    )

    val advertisement = QuickJsToolAdvertisement.fromInlineScriptToolConfig(config)

    assertTrue(advertisement.meta.requiresHost)
    // On-device sessions pass preferHostAgent = false, so a requiresHost tool must not register.
    assertFalse(
      advertisement.meta.shouldRegister(TrailblazeDriverType.DEFAULT_ANDROID, preferHostAgent = false),
    )
  }
}
