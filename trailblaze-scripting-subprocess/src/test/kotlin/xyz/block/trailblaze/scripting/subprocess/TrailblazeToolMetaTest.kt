package xyz.block.trailblaze.scripting.subprocess

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.scripting.mcp.TrailblazeToolMeta
import kotlin.test.Test

class TrailblazeToolMetaTest {

  @Test fun `empty meta yields defaults`() {
    val parsed = TrailblazeToolMeta.fromJsonObject(JsonObject(emptyMap()))
    assertThat(parsed).isEqualTo(TrailblazeToolMeta())
    assertThat(parsed.isForLlm).isTrue()
    assertThat(parsed.isRecordable).isTrue()
    assertThat(parsed.requiresHost).isFalse()
    assertThat(parsed.toolset).isNull()
  }

  @Test fun `reads every trailblaze key when present`() {
    val meta = buildJsonObject {
      put("trailblaze/isForLlm", false)
      put("trailblaze/isRecordable", false)
      put("trailblaze/requiresHost", true)
      put("trailblaze/requiresContext", true)
      put("trailblaze/toolset", "auth")
      put("trailblaze/supportedDrivers", buildJsonArray { add("android-ondevice-accessibility"); add("ios-host") })
      put("trailblaze/supportedPlatforms", buildJsonArray { add("ANDROID"); add("IOS") })
    }
    val parsed = TrailblazeToolMeta.fromJsonObject(meta)

    assertThat(parsed).isEqualTo(
      TrailblazeToolMeta(
        isForLlm = false,
        isRecordable = false,
        requiresHost = true,
        requiresContext = true,
        toolset = "auth",
        supportedDrivers = listOf("android-ondevice-accessibility", "ios-host"),
        supportedPlatforms = listOf("ANDROID", "IOS"),
      ),
    )
  }

  @Test fun `ignores unrecognized keys outside the trailblaze prefix`() {
    val meta = buildJsonObject {
      put("trailblaze/requiresHost", true)
      put("otherVendor/flag", true) // should not affect parsing
      put("progressToken", 123) // MCP-standard; not a Trailblaze key
    }
    val parsed = TrailblazeToolMeta.fromJsonObject(meta)
    assertThat(parsed.requiresHost).isTrue()
    assertThat(parsed.toolset).isNull()
  }

  @Test fun `gracefully handles wrong-shaped values as absent`() {
    val meta = buildJsonObject {
      put("trailblaze/requiresHost", "not-a-bool")
      put("trailblaze/supportedDrivers", "not-an-array")
      put("trailblaze/toolset", buildJsonArray { add("nope") })
    }
    val parsed = TrailblazeToolMeta.fromJsonObject(meta)
    assertThat(parsed.requiresHost).isFalse()
    assertThat(parsed.supportedDrivers).isEqualTo(emptyList<String>())
    assertThat(parsed.toolset).isNull()
  }

  @Test fun `shouldRegister - unrestricted meta registers for every session`() {
    val meta = TrailblazeToolMeta()
    assertThat(meta.shouldRegister(TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY, preferHostAgent = true)).isTrue()
    assertThat(meta.shouldRegister(TrailblazeDriverType.IOS_HOST, preferHostAgent = false)).isTrue()
    assertThat(meta.shouldRegister(TrailblazeDriverType.PLAYWRIGHT_NATIVE, preferHostAgent = true)).isTrue()
  }

  @Test fun `shouldRegister - supportedDrivers filters by session driver yamlKey`() {
    val meta = TrailblazeToolMeta(supportedDrivers = listOf("android-ondevice-accessibility"))
    assertThat(meta.shouldRegister(TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY, preferHostAgent = true)).isTrue()
    assertThat(meta.shouldRegister(TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION, preferHostAgent = true)).isFalse()
    assertThat(meta.shouldRegister(TrailblazeDriverType.IOS_HOST, preferHostAgent = true)).isFalse()
  }

  @Test fun `shouldRegister - supportedPlatforms filters by session platform`() {
    val meta = TrailblazeToolMeta(supportedPlatforms = listOf("ANDROID"))
    assertThat(meta.shouldRegister(TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY, preferHostAgent = true)).isTrue()
    assertThat(meta.shouldRegister(TrailblazeDriverType.REVYL_ANDROID, preferHostAgent = true)).isTrue()
    assertThat(meta.shouldRegister(TrailblazeDriverType.IOS_HOST, preferHostAgent = true)).isFalse()
    assertThat(meta.shouldRegister(TrailblazeDriverType.PLAYWRIGHT_NATIVE, preferHostAgent = true)).isFalse()
  }

  @Test fun `shouldRegister - requiresHost gates on preferHostAgent`() {
    val meta = TrailblazeToolMeta(requiresHost = true)
    assertThat(meta.shouldRegister(TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY, preferHostAgent = true)).isTrue()
    assertThat(meta.shouldRegister(TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY, preferHostAgent = false)).isFalse()
  }

  @Test fun `shouldRegister - driver check runs before platform check`() {
    // Driver specifies iOS; platform specifies Android. Conflicting; driver wins (checked first).
    val meta = TrailblazeToolMeta(
      supportedDrivers = listOf("ios-host"),
      supportedPlatforms = listOf("ANDROID"),
    )
    assertThat(meta.shouldRegister(TrailblazeDriverType.IOS_HOST, preferHostAgent = true)).isFalse()
    assertThat(meta.shouldRegister(TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY, preferHostAgent = true)).isFalse()
  }

  @Test fun `shouldRegister - all three filters combine - host-required ios-only on-device-agent session`() {
    val meta = TrailblazeToolMeta(
      supportedDrivers = listOf("ios-host"),
      requiresHost = true,
    )
    assertThat(meta.shouldRegister(TrailblazeDriverType.IOS_HOST, preferHostAgent = true)).isTrue()
    assertThat(meta.shouldRegister(TrailblazeDriverType.IOS_HOST, preferHostAgent = false)).isFalse()
    assertThat(meta.shouldRegister(TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY, preferHostAgent = true)).isFalse()
  }

  @Test fun `fromJsonObject normalizes supportedPlatforms to uppercase`() {
    // Authors can write `[web]`, `[WEB]`, or `[Web]` — all collapse to the canonical
    // uppercase form so the downstream `!in` check against TrailblazeDevicePlatform.name
    // (always uppercase) stays branch-free. supportedDrivers does NOT get normalized
    // because driver yamlKeys are conventionally lowercase already (e.g., "ios-host").
    val mixedCase = buildJsonObject {
      put("trailblaze/supportedPlatforms", buildJsonArray { add("web"); add("Ios"); add("ANDROID") })
    }
    val parsed = TrailblazeToolMeta.fromJsonObject(mixedCase)
    assertThat(parsed.supportedPlatforms).isEqualTo(listOf("WEB", "IOS", "ANDROID"))
    // Confirm the filter accepts a session that matches one of the normalized entries.
    assertThat(parsed.shouldRegister(TrailblazeDriverType.PLAYWRIGHT_NATIVE, preferHostAgent = true)).isTrue()
    assertThat(parsed.shouldRegister(TrailblazeDriverType.IOS_HOST, preferHostAgent = true)).isTrue()
    assertThat(parsed.shouldRegister(TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY, preferHostAgent = true)).isTrue()
  }
}
