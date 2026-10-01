package xyz.block.trailblaze.scripting

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.config.ScriptedToolRuntime
import xyz.block.trailblaze.devices.TrailblazeDriverType

/**
 * A target's `tools:` list is not platform-scoped, so the launcher must drop the tools a session's
 * drivers can't use before registering them — for BOTH runtimes. In-process (QuickJS) tools used to
 * skip the gate, so every Android- and web-only tool of a multi-platform target reached an iOS
 * session's LLM tool list.
 */
class HostScriptedToolLauncherSessionGateTest {

  private fun tool(name: String, runtime: ScriptedToolRuntime?, vararg platforms: String) = InlineScriptToolConfig(
    script = "trails/config/trailmaps/fixtureapp/tools/$name.ts",
    name = name,
    description = name,
    runtime = runtime,
    meta = if (platforms.isEmpty()) {
      null
    } else {
      buildJsonObject { put("trailblaze/supportedPlatforms", buildJsonArray { platforms.forEach { add(it) } }) }
    },
  )

  private val tools = listOf(
    tool("fixture_android_inProcess", runtime = null, "android"),
    tool("fixture_web_inProcess", runtime = null, "web"),
    tool("fixture_ios_inProcess", runtime = null, "ios"),
    tool("fixture_anyPlatform_inProcess", runtime = null),
    tool("fixture_android_subprocess", ScriptedToolRuntime.SUBPROCESS, "android"),
    tool("fixture_ios_subprocess", ScriptedToolRuntime.SUBPROCESS, "ios"),
  )

  private fun partition(vararg drivers: TrailblazeDriverType): Pair<List<String>, List<String>> {
    val (subprocess, inProcess) = HostScriptedToolLauncher.partitionSessionInlineTools(
      tools = tools,
      drivers = drivers.toList(),
      preferHostAgent = true,
      logPrefix = "[test]",
    )
    return subprocess.map { it.name } to inProcess.map { it.name }
  }

  @Test
  fun `an iOS session keeps only the tools that apply to iOS, in both runtimes`() {
    val (subprocess, inProcess) = partition(TrailblazeDriverType.IOS_HOST)

    assertEquals(listOf("fixture_ios_subprocess"), subprocess)
    assertEquals(listOf("fixture_ios_inProcess", "fixture_anyPlatform_inProcess"), inProcess)
  }

  @Test
  fun `an Android session keeps the Android tools and drops the iOS and web ones`() {
    val (subprocess, inProcess) = partition(TrailblazeDriverType.DEFAULT_ANDROID)

    assertEquals(listOf("fixture_android_subprocess"), subprocess)
    assertEquals(listOf("fixture_android_inProcess", "fixture_anyPlatform_inProcess"), inProcess)
  }

  /**
   * `preferHostAgent` only picks where an on-device driver's agent runs. iOS tools always run on the
   * host, so turning the preference off (a global setting, often left off after an Android run)
   * must not drop host-only tools from an iOS session.
   */
  @Test
  fun `host-only tools stay on iOS when the host-agent preference is off`() {
    val hostOnly = InlineScriptToolConfig(
      script = "trails/config/trailmaps/fixtureapp/tools/fixture_hostOnly.ts",
      name = "fixture_hostOnly",
      description = "fixture_hostOnly",
      meta = buildJsonObject { put("trailblaze/requiresHost", JsonPrimitive(true)) },
    )
    fun kept(driver: TrailblazeDriverType) = HostScriptedToolLauncher.partitionSessionInlineTools(
      tools = listOf(hostOnly),
      drivers = listOf(driver),
      preferHostAgent = false,
      logPrefix = "[test]",
    ).let { (subprocess, inProcess) -> (subprocess + inProcess).map { it.name } }

    assertEquals(listOf("fixture_hostOnly"), kept(TrailblazeDriverType.IOS_HOST))
    assertEquals(emptyList(), kept(TrailblazeDriverType.DEFAULT_ANDROID))
  }

  @Test
  fun `a web companion on an iOS session also keeps the web tools`() {
    val (_, inProcess) = partition(TrailblazeDriverType.IOS_HOST, TrailblazeDriverType.PLAYWRIGHT_NATIVE)

    assertEquals(
      listOf("fixture_web_inProcess", "fixture_ios_inProcess", "fixture_anyPlatform_inProcess"),
      inProcess,
    )
  }
}
