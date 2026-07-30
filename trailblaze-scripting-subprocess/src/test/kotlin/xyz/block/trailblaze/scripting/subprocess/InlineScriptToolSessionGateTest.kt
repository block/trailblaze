package xyz.block.trailblaze.scripting.subprocess

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.config.ScriptedToolRuntime
import xyz.block.trailblaze.devices.TrailblazeDriverType
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The pre-spawn half of the subprocess capability gate
 * ([SubprocessToolRegistrar.applicableInlineTools]).
 *
 * Modeled on the real bundled-trailmap descriptor that motivated it: a `runtime: subprocess`
 * tool declared `supportedPlatforms: [web]`, whose `.ts` ships inside the framework JAR rather
 * than the consuming workspace. Before the gate, an ANDROID session synthesized a wrapper for it
 * and forked bun — failing the whole session at `script:` resolution ("mcp_servers script not
 * found at <workspace>/…") or, where the file did exist, at the handshake. The tool was destined
 * to be discarded by the post-spawn filter either way.
 */
class InlineScriptToolSessionGateTest {

  private val tmpDir = Files.createTempDirectory("inline-script-tool-session-gate").toFile()

  @AfterTest fun cleanup() {
    tmpDir.deleteRecursively()
  }

  /**
   * The failing descriptor shape: web-only, subprocess runtime, and a `script:` that does not
   * exist relative to the session's workspace — exactly the field failure's input.
   */
  private val webOnlySignIn = InlineScriptToolConfig(
    script = "trails/config/trailmaps/shopapp/tools/host/shopapp_web_sign_in.ts",
    name = "shopapp_webSignIn",
    description = "Sign in to the web dashboard.",
    runtime = ScriptedToolRuntime.SUBPROCESS,
    meta = buildJsonObject {
      put("trailblaze/supportedPlatforms", buildJsonArray { add("web") })
    },
  )

  private val unrestricted = InlineScriptToolConfig(
    script = "trails/config/trailmaps/shopapp/tools/host/shopapp_notes.ts",
    name = "shopapp_writeNote",
    description = "Write a note.",
    runtime = ScriptedToolRuntime.SUBPROCESS,
  )

  @Test fun `web-only tool is not spawnable in an android session`() {
    val spawnable = SubprocessToolRegistrar.applicableInlineTools(
      tools = listOf(webOnlySignIn, unrestricted),
      driver = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
      preferHostAgent = true,
    )
    assertThat(spawnable.map { it.name }).containsExactly("shopapp_writeNote")
  }

  @Test fun `web-only tool is spawnable in a web session`() {
    val spawnable = SubprocessToolRegistrar.applicableInlineTools(
      tools = listOf(webOnlySignIn, unrestricted),
      driver = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
      preferHostAgent = true,
    )
    assertThat(spawnable.map { it.name }).containsExactly("shopapp_webSignIn", "shopapp_writeNote")
  }

  /**
   * Negative control for the two tests above: proves the android pass isn't vacuous.
   *
   * Same tool, same missing `script:`, only the session platform differs. Synthesizing the
   * android-gated set touches no path at all; synthesizing the web-gated set still reaches
   * resolution and reproduces the field failure verbatim. If the gate ever stopped filtering,
   * the android leg would raise that same error instead of producing nothing.
   */
  @Test fun `only the web session reaches script-path resolution for a web-only tool`() {
    val tools = listOf(webOnlySignIn)

    val androidGated = SubprocessToolRegistrar.applicableInlineTools(
      tools = tools,
      driver = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
      preferHostAgent = true,
    )
    assertThat(
      InlineScriptToolServerSynthesizer.synthesize(
        tools = androidGated,
        outputDir = File(tmpDir, "android"),
        pathAnchor = tmpDir,
      ),
    ).isEmpty()

    val webGated = SubprocessToolRegistrar.applicableInlineTools(
      tools = tools,
      driver = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
      preferHostAgent = true,
    )
    val failure = assertFailsWith<IllegalArgumentException> {
      InlineScriptToolServerSynthesizer.synthesize(
        tools = webGated,
        outputDir = File(tmpDir, "web"),
        pathAnchor = tmpDir,
      )
    }
    assertThat(failure.message!!).contains("mcp_servers script not found")
  }

  /**
   * `requiresHost` is a typed field that only reaches `_meta` when true, so the gate has to read
   * the effective advertisement rather than the authored `_meta` alone — otherwise an on-device
   * agent session would fork a host-only tool it then discards.
   */
  @Test fun `requiresHost tool is not spawnable under on-device-agent mode`() {
    val hostOnly = unrestricted.copy(name = "shopapp_hostOnly", requiresHost = true)

    val hostAgent = SubprocessToolRegistrar.applicableInlineTools(
      tools = listOf(hostOnly),
      driver = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
      preferHostAgent = true,
    )
    assertThat(hostAgent.map { it.name }).containsExactly("shopapp_hostOnly")

    val onDeviceAgent = SubprocessToolRegistrar.applicableInlineTools(
      tools = listOf(hostOnly),
      driver = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
      preferHostAgent = false,
    )
    assertThat(onDeviceAgent.map { it.name }).isEmpty()
  }

  /**
   * The pre-spawn gate must agree with the post-spawn one it front-runs: whatever the synthesized
   * wrapper would advertise as `_meta` is what the gate decides on. Asserting the agreement over
   * the same configs keeps the two halves from drifting into "spawned but never registered" (the
   * wasted fork) or "gated out but would have registered" (a silently missing tool). That the
   * generated wrapper really does emit this `_meta` is pinned by `InlineScriptToolServerSynthesizer
   * Test.wrapper script forwards inline tool meta into trailblaze tool registration`.
   */
  @Test fun `pre-spawn gate agrees with the post-spawn filter`() {
    val tools = listOf(
      webOnlySignIn,
      unrestricted,
      unrestricted.copy(name = "shopapp_hostOnly", requiresHost = true),
    )
    for (driver in TrailblazeDriverType.entries) {
      for (preferHostAgent in listOf(true, false)) {
        val preSpawn = SubprocessToolRegistrar.applicableInlineTools(tools, driver, preferHostAgent)
        val postSpawn = SubprocessToolRegistrar.filterAdvertisedTools(
          tools = tools.map { advertisedToolFor(it) },
          driver = driver,
          preferHostAgent = preferHostAgent,
        )
        assertThat(preSpawn.map { it.name }).containsExactly(
          *postSpawn.map { it.advertisedName.toolName }.toTypedArray(),
        )
      }
    }
  }

  /** The MCP `Tool` a synthesized wrapper advertises for [config] at `tools/list`. */
  private fun advertisedToolFor(config: InlineScriptToolConfig) = Tool(
    name = config.name,
    description = config.description,
    inputSchema = ToolSchema(properties = JsonObject(emptyMap()), required = emptyList()),
    meta = SubprocessToolRegistrar.advertisedMeta(config),
  )
}
