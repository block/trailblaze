package xyz.block.trailblaze.scripting

import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.config.ScriptedToolNameDiscoverer
import xyz.block.trailblaze.config.ScriptedToolRuntime
import xyz.block.trailblaze.config.project.toInlineScriptToolConfigs
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.util.Console
import java.io.File

/**
 * Loads pre-compiled QuickJS bundles for catalog/framework-delivered scripted tools and registers
 * them as in-process [LazyYamlScriptedToolRegistration]s into [toolRepo].
 *
 * This is the **one** in-process launch path shared by every host of the agent loop:
 *  - the host runner (`TrailblazeHostYamlRunner.registerToolsetScriptedToolBundles`)
 *  - the MCP daemon (`TrailblazeMcpServer.ensureSessionScriptToolRuntime` + `DirectMcpToolExecutor`)
 *
 * Before this consolidation the daemon synthesized a `bun` subprocess for every scripted tool while
 * the host ran them in-process — two implementations of the same dispatch. A scripted tool whose
 * resolved [ScriptedToolRuntime] is [ScriptedToolRuntime.IN_PROCESS] (the default since #3819) now
 * runs through the embedded QuickJS engine everywhere. Tools that explicitly opt into
 * [ScriptedToolRuntime.SUBPROCESS] are skipped here — the caller keeps routing those through the
 * subprocess synthesizer.
 *
 * Each registered tool advertises via [TrailblazeToolRepo.advertisedDynamic] and dispatches
 * host-local (its nested framework `client.callTool(...)` calls route back through the driver
 * agent), so the caller does not need a separate advertise step.
 *
 * Rolls back every registration made in this call if any single one throws, so a partial failure
 * never leaves half-registered tools in the session repo.
 */
object InProcessScriptedToolLauncher {

  /**
   * @param toolNames catalog scripted tool names to launch in-process.
   * @param skipNames names already handled elsewhere (target-declared inline tools, subprocess
   *   tools) — not re-registered here. The target-declared version wins on a name collision.
   * @return the registrations created, for session-end disposal via [LazyYamlScriptedToolRegistration.dispose].
   */
  suspend fun launch(
    toolRepo: TrailblazeToolRepo,
    sessionId: SessionId,
    sessionDir: File,
    toolNames: Set<ToolName>,
    skipNames: Set<ToolName> = emptySet(),
    classLoader: ClassLoader? = InProcessScriptedToolLauncher::class.java.classLoader,
    logPrefix: String = "[InProcessScriptedToolLauncher]",
  ): List<LazyYamlScriptedToolRegistration> {
    val newNames = toolNames - skipNames
    if (newNames.isEmpty()) return emptyList()

    val descriptorsByName = ScriptedToolNameDiscoverer.discoverDescriptorsByName()
    val accumulated = mutableListOf<LazyYamlScriptedToolRegistration>()

    try {
      for (name in newNames) {
        val discovered = descriptorsByName[name]
        if (discovered == null) {
          Console.log(
            "$logPrefix Scripted tool '${name.toolName}' has no matching descriptor — skipping. " +
              "Ensure a descriptor YAML with an explicit `name: ${name.toolName}` exists under a " +
              "trailmap's `tools/` directory.",
          )
          continue
        }

        val toolConfig: InlineScriptToolConfig =
          discovered.descriptor.toInlineScriptToolConfigs().firstOrNull { ToolName(it.name) == name }
            ?: continue

        // Subprocess-opted tools stay on the subprocess path; only in-process tools (the default,
        // #3819) launch through the embedded QuickJS engine here.
        if (ScriptedToolRuntime.resolve(toolConfig.runtime) != ScriptedToolRuntime.IN_PROCESS) {
          Console.log(
            "$logPrefix Scripted tool '${name.toolName}' opts into ${toolConfig.runtime} runtime " +
              "— skipping in-process launch (handled by the subprocess path).",
          )
          continue
        }

        // Shared with the on-device path so the descriptor -> bundle naming rule can't drift.
        val bundleResourcePath = ScriptedToolNameDiscoverer.bundleResourcePath(discovered)
        val bundleJs = classLoader?.getResourceAsStream(bundleResourcePath)
          ?.bufferedReader()
          ?.use { it.readText() }
        if (bundleJs == null) {
          Console.log(
            "$logPrefix Scripted tool '${name.toolName}': pre-compiled bundle not found on " +
              "classpath at '$bundleResourcePath' — skipping. Run " +
              "`./gradlew :trailblaze-common:bundleFrameworkScriptedTools` to regenerate.",
          )
          continue
        }

        val bundleDir = File(sessionDir, "in-process-scripted-tools")
        bundleDir.mkdirs()
        val bundleFile = File(bundleDir, "${name.toolName}.bundle.js")
        bundleFile.writeText(bundleJs)

        accumulated += LazyYamlScriptedToolRegistration.create(
          toolConfig = toolConfig,
          bundlePath = bundleFile,
          toolRepo = toolRepo,
          sessionId = sessionId,
        )
      }

      if (accumulated.isNotEmpty()) {
        toolRepo.addDynamicTools(accumulated)
        Console.log(
          "$logPrefix Registered ${accumulated.size} in-process scripted tool(s) from " +
            "pre-compiled bundles: ${accumulated.joinToString { it.name.toolName }}",
        )
      }
    } catch (e: Throwable) {
      Console.log(
        "$logPrefix Rolling back ${accumulated.size} in-process scripted-tool registration(s) " +
          "due to startup failure: ${e::class.simpleName}: ${e.message}",
      )
      for (reg in accumulated) {
        runCatching { reg.dispose() }
      }
      throw e
    }

    return accumulated
  }

  /**
   * Builds advertise-time [TrailblazeToolDescriptor]s for [toolNames] from the catalog YAML
   * WITHOUT connecting a QuickJS engine. For surfaces that advertise synchronously (e.g.
   * `DirectMcpToolExecutor.getAvailableTools()`) and launch lazily on first dispatch. Skips names
   * that have no descriptor and tools that opt into [ScriptedToolRuntime.SUBPROCESS] — those can't
   * dispatch through the in-process path so they must not be advertised on it.
   */
  fun describe(toolNames: Set<ToolName>): List<TrailblazeToolDescriptor> {
    if (toolNames.isEmpty()) return emptyList()
    val descriptorsByName = ScriptedToolNameDiscoverer.discoverDescriptorsByName()
    return toolNames.mapNotNull { name ->
      val discovered = descriptorsByName[name] ?: return@mapNotNull null
      val config: InlineScriptToolConfig =
        discovered.descriptor.toInlineScriptToolConfigs().firstOrNull { ToolName(it.name) == name }
          ?: return@mapNotNull null
      if (ScriptedToolRuntime.resolve(config.runtime) != ScriptedToolRuntime.IN_PROCESS) {
        return@mapNotNull null
      }
      LazyYamlScriptedToolRegistration.buildScriptedToolDescriptor(config)
    }
  }
}
