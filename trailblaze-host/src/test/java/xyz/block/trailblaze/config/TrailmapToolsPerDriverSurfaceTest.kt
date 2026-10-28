package xyz.block.trailblaze.config

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.block.trailblaze.config.project.ScriptedToolEnrichment
import xyz.block.trailblaze.config.project.TrailblazeProjectConfigLoader
import xyz.block.trailblaze.scripting.mcp.TrailblazeToolMeta
import xyz.block.trailblaze.model.toSessionToolRepo
import xyz.block.trailblaze.toolcalls.DynamicTrailblazeToolRegistration
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.ToolName

/**
 * The per-driver tool surface a session gets from a trailmap whose `.ts` tools are on its target
 * unlisted: the loader puts every tool on the target, then each driver registers a tool only where
 * its own `supportedPlatforms` / `supportedDrivers` allow (the runtime's [TrailblazeToolMeta.shouldRegister]
 * gate), and the session hides from the LLM what `excluded_tools:` opted out of. Nothing here
 * lists a tool per platform or driver.
 */
class TrailmapToolsPerDriverSurfaceTest {

  private val workspace = createTempDirectory("trailmap-tools-surface").toFile()

  @AfterTest
  fun cleanup() {
    workspace.deleteRecursively()
  }

  @Test
  fun `unlisted ts tools register per driver by their own spec and excluded_tools`() {
    val trailmapDir = File(workspace, "trailmaps/myapp").apply { mkdirs() }
    File(trailmapDir, "trailmap.yaml").writeText(
      """
      id: myapp
      target:
        display_name: MyApp
        platforms:
          android:
            drivers: [android]
            excluded_tools: [excludedTool, androidOnlyExcluded]
          ios:
            drivers: [ios-host]
            excluded_tools: [excludedTool, partlyExcluded]
      """.trimIndent(),
    )
    // Bare `.ts` tools with no descriptor: the name `ToolNameResolver` could discover them by
    // doesn't exist, so `excluded_tools:` has to match them through the target itself.
    val gatesByTool = mapOf(
      "unlistedTool" to emptyMap(),
      "excludedTool" to emptyMap(),
      "partlyExcluded" to emptyMap(),
      "iosOnlyTool" to mapOf("trailblaze/supportedPlatforms" to listOf("ios")),
      "androidOnlyExcluded" to mapOf("trailblaze/supportedPlatforms" to listOf("android")),
      "webOnlyTool" to mapOf("trailblaze/supportedPlatforms" to listOf("web")),
      // `drivers: [android]` is a shorthand; the tool still lands on the driver it expands to.
      "accessibilityOnlyTool" to mapOf("trailblaze/supportedDrivers" to listOf("android-ondevice-accessibility")),
      // iOS declares only `ios-host`, so a session never runs this one.
      "axeOnlyTool" to mapOf("trailblaze/supportedDrivers" to listOf("ios-axe")),
    )
    File(trailmapDir, "tools").mkdirs()
    gatesByTool.keys.forEach { name ->
      File(trailmapDir, "tools/$name.ts").writeText("export const $name = trailblaze.tool(async () => \"ok\");\n")
    }
    val enrichment = object : ScriptedToolEnrichment {
      override fun enrich(
        trailmapId: String,
        trailmapDir: File,
        trailmapToolsDir: File,
        deferredDescriptors: List<ScriptedToolEnrichment.DeferredDescriptor>,
      ): List<ScriptedToolEnrichment.EnrichmentResult> = deferredDescriptors.map { d ->
        val name = d.relativePath.substringAfterLast('/').removeSuffix(".ts")
        ScriptedToolEnrichment.EnrichmentResult.Resolved(
          relativePath = d.relativePath,
          configs = listOf(
            InlineScriptToolConfig(
              script = File(trailmapDir, d.relativePath).absolutePath,
              name = name,
              meta = JsonObject(
                gatesByTool.getValue(name).mapValues { (_, values) -> JsonArray(values.map(::JsonPrimitive)) },
              ),
            ),
          ),
        )
      }
    }
    val configFile = File(workspace, "trailblaze.yaml").apply { writeText("targets:\n  - myapp\n") }

    val targetConfig = TrailblazeProjectConfigLoader.resolveRuntime(
      TrailblazeProjectConfigLoader.load(configFile)!!,
      includeClasspathTrailmaps = false,
      scriptedToolEnrichment = enrichment,
    ).targets.single()
    val target = YamlBackedHostAppTarget(targetConfig, ToolNameResolver(knownTools = emptyMap()))

    // What the LLM is offered per driver: the host launcher registers every tool that passes
    // `shouldRegister`, and the session repo hides the ones `excluded_tools:` opted out of.
    val toolNames = targetConfig.tools.orEmpty().map { it.name }.toSet()
    val surfaceByDriver = targetConfig.platforms.orEmpty()
      .flatMap { (platformKey, platform) -> platform.resolveDriverTypes(platformKey) }
      .associate { driver ->
        val repo = target.toSessionToolRepo(driver)
        repo.addDynamicTools(
          targetConfig.tools.orEmpty()
            .filter { TrailblazeToolMeta.fromJsonObject(it.meta ?: JsonObject(emptyMap())).shouldRegister(driver, preferHostAgent = true) }
            .map { stubRegistration(it.name) },
        )
        driver.yamlKey to repo.getCurrentToolDescriptors().map { it.name }.filter { it in toolNames }.sorted()
      }

    assertEquals(
      mapOf(
        "android-ondevice-accessibility" to listOf("accessibilityOnlyTool", "partlyExcluded", "unlistedTool"),
        "android-ondevice-instrumentation" to listOf("partlyExcluded", "unlistedTool"),
        "ios-host" to listOf("iosOnlyTool", "unlistedTool"),
      ),
      surfaceByDriver,
    )
  }

  private fun stubRegistration(toolName: String): DynamicTrailblazeToolRegistration =
    object : DynamicTrailblazeToolRegistration {
      override val name: ToolName = ToolName(toolName)
      override val trailblazeDescriptor = TrailblazeToolDescriptor(name = toolName)

      override fun buildKoogTool(
        trailblazeToolContextProvider: () -> TrailblazeToolExecutionContext,
      ): TrailblazeKoogTool<out TrailblazeTool> = error("not built in this test")

      override fun decodeToolCall(argumentsJson: String): TrailblazeTool = error("not dispatched in this test")
    }
}
