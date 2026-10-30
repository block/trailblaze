package xyz.block.trailblaze.scripting

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.config.ScriptedToolRuntime
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import kotlin.reflect.KClass

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

  /** Excludes `fixture_anyPlatform_inProcess` on web drivers and `fixture_android_inProcess` on accessibility only. */
  private val excludingTarget = object : TrailblazeHostAppTarget(id = "fixtureapp", displayName = "Fixture App") {
    override fun getPossibleAppIdsForPlatform(platform: TrailblazeDevicePlatform): List<String>? = null

    override fun internalGetCustomToolsForDriver(
      driverType: TrailblazeDriverType,
    ): Set<KClass<out TrailblazeTool>> = emptySet()

    override fun getExcludedScriptedToolNamesForDriver(driverType: TrailblazeDriverType): Set<ToolName> = when {
      driverType.platform == TrailblazeDevicePlatform.WEB -> setOf(ToolName("fixture_anyPlatform_inProcess"))
      driverType == TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY -> setOf(ToolName("fixture_android_inProcess"))
      else -> emptySet()
    }
  }

  private fun hidden(vararg drivers: TrailblazeDriverType): Set<String> = HostScriptedToolLauncher.targetExcludedTools(
    target = excludingTarget,
    tools = tools,
    drivers = drivers.toList(),
    preferHostAgent = true,
  ).mapTo(mutableSetOf()) { it.toolName }

  @Test
  fun `a web companion's exclusion hides the tool from a web-only session`() {
    assertEquals(setOf("fixture_anyPlatform_inProcess"), hidden(TrailblazeDriverType.PLAYWRIGHT_NATIVE))
  }

  @Test
  fun `a tool excluded on one session driver but not another it supports stays advertised`() {
    // Instrumentation + web: the any-platform tool is excluded on web only, and the Android tool on accessibility only.
    assertEquals(
      emptySet(),
      hidden(TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION, TrailblazeDriverType.PLAYWRIGHT_NATIVE),
    )
  }

  @Test
  fun `a session driver the tool does not support has no say`() {
    // The Android tool can't run on web, so only the accessibility driver's exclusion counts.
    assertEquals(
      setOf("fixture_android_inProcess"),
      hidden(TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY, TrailblazeDriverType.PLAYWRIGHT_NATIVE),
    )
  }

  @Test
  fun `a tool two targets share stays offered if either permits it, in either launch order`() {
    // `excludingTarget` excludes the any-platform tool on web; `permittingTarget` excludes nothing.
    val permittingTarget = object : TrailblazeHostAppTarget(id = "otherapp", displayName = "Other App") {
      override fun getPossibleAppIdsForPlatform(platform: TrailblazeDevicePlatform): List<String>? = null

      override fun internalGetCustomToolsForDriver(
        driverType: TrailblazeDriverType,
      ): Set<KClass<out TrailblazeTool>> = emptySet()
    }
    val shared = tools.filter { it.name == "fixture_anyPlatform_inProcess" }

    // A launch whose target is in [failing] registers nothing, as when its bundle is missing.
    // A target in [hiddenBy] ships its copy of the tool with `surfaceToLlm: false`.
    fun hiddenAfter(
      vararg order: TrailblazeHostAppTarget,
      failing: Set<TrailblazeHostAppTarget> = emptySet(),
      hiddenBy: Set<TrailblazeHostAppTarget> = emptySet(),
    ): Set<String> {
      val registered = mutableSetOf<ToolName>()
      val hidden = mutableSetOf<ToolName>()
      order.forEach { target ->
        val registeredNow = if (target in failing) emptySet() else shared.map { ToolName(it.name) }.toSet() - registered
        val visibility = HostScriptedToolLauncher.targetToolVisibility(
          target = target,
          tools = if (target in hiddenBy) shared.map { it.copy(surfaceToLlm = false) } else shared,
          preRegistered = registered.toSet(),
          registeredNow = registeredNow,
          drivers = listOf(TrailblazeDriverType.PLAYWRIGHT_NATIVE),
          preferHostAgent = true,
        )
        hidden += visibility.hide
        hidden -= visibility.show
        registered += registeredNow
      }
      return hidden.mapTo(mutableSetOf()) { it.toolName }
    }

    assertEquals(setOf("fixture_anyPlatform_inProcess"), hiddenAfter(excludingTarget))
    assertEquals(emptySet(), hiddenAfter(excludingTarget, permittingTarget))
    assertEquals(emptySet(), hiddenAfter(permittingTarget, excludingTarget))
    // An excluding target whose tool never registered leaves no hide behind for the permitting one.
    assertEquals(emptySet(), hiddenAfter(excludingTarget, permittingTarget, failing = setOf(excludingTarget)))
    // A provider that hides its own copy is no permission to show the excluded one.
    assertEquals(
      setOf("fixture_anyPlatform_inProcess"),
      hiddenAfter(excludingTarget, permittingTarget, hiddenBy = setOf(permittingTarget)),
    )
  }
}
