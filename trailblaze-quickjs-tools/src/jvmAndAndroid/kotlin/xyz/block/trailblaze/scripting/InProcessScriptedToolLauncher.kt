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
 *
 * The catalog discover → resolve-config → in-process-filter step is factored into
 * [resolveInProcessScriptedTools] so the host launch path, the host [describe] advertise path, and
 * the **on-device** launch path (`AndroidTrailblazeRule.launchToolsetScriptedToolBundles`) all
 * resolve catalog scripted tools the same way. Keeping that one resolver is the guard against the
 * host and device paths drifting — which is exactly the bug class #3845 fixed (the on-device path
 * had its own copy of "YAML descriptor → what the LLM sees" and dropped the descriptor).
 */
object InProcessScriptedToolLauncher {

  /**
   * A catalog scripted tool resolved for the in-process runtime: its [config] (carrying the
   * description / inputSchema / `_meta`), and the classpath/asset [bundleResourcePath] of its
   * pre-compiled `.bundle.js`. Emitted by [resolveInProcessScriptedTools].
   */
  data class ResolvedInProcessScriptedTool(
    val name: ToolName,
    val config: InlineScriptToolConfig,
    val bundleResourcePath: String,
  )

  /**
   * Discover + resolve + in-process-filter [toolNames] against the catalog YAML descriptors. The
   * single source of truth for "which catalog scripted tools run in-process and where their bundle
   * lives" — shared by [launch], [describe], and the on-device launcher so they can't drift.
   *
   * Skips (with a logged breadcrumb) any name that: has no discovered descriptor, has no matching
   * tool config in that descriptor, or opts into a non-[ScriptedToolRuntime.IN_PROCESS] runtime
   * (those dispatch through the subprocess path, never in-process). [skipNames] are dropped up
   * front — e.g. names already registered as target-declared tools, which win on collision.
   *
   * Connects no QuickJS engine; pure resolution over the on-disk/classpath descriptors.
   */
  fun resolveInProcessScriptedTools(
    toolNames: Set<ToolName>,
    skipNames: Set<ToolName> = emptySet(),
    logPrefix: String = "[InProcessScriptedToolLauncher]",
  ): List<ResolvedInProcessScriptedTool> {
    val newNames = toolNames - skipNames
    if (newNames.isEmpty()) return emptyList()
    val descriptorsByName = ScriptedToolNameDiscoverer.discoverDescriptorsByName()
    return newNames.mapNotNull { name ->
      val discovered = descriptorsByName[name]
      if (discovered == null) {
        Console.log(
          "$logPrefix Scripted tool '${name.toolName}' has no matching descriptor — skipping. " +
            "Ensure a descriptor YAML with an explicit `name: ${name.toolName}` exists under a " +
            "trailmap's `tools/` directory; on-device, also confirm that descriptor (and its " +
            "`.bundle.js`) is packaged into the runtime's resources/assets.",
        )
        return@mapNotNull null
      }
      val config = discovered.descriptor.toInlineScriptToolConfigs().firstOrNull { ToolName(it.name) == name }
      if (config == null) {
        Console.log(
          "$logPrefix descriptor for '${name.toolName}' produced no matching tool config — skipping.",
        )
        return@mapNotNull null
      }
      // Subprocess-opted tools never run through the embedded QuickJS engine; only in-process
      // tools (the default, #3819) do. They dispatch via the host bun subprocess where one is
      // available — which means they're simply not dispatchable in an on-device session.
      if (ScriptedToolRuntime.resolve(config.runtime) != ScriptedToolRuntime.IN_PROCESS) {
        Console.log(
          "$logPrefix Scripted tool '${name.toolName}' declares runtime '${config.runtime}' " +
            "(not in-process) — skipping in-process resolution. It runs only on the host bun " +
            "subprocess path, so it is not available in an on-device session.",
        )
        return@mapNotNull null
      }
      // Shared with the on-device path so the descriptor -> bundle naming rule can't drift.
      ResolvedInProcessScriptedTool(
        name = name,
        config = config,
        bundleResourcePath = ScriptedToolNameDiscoverer.bundleResourcePath(discovered),
      )
    }
  }

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
    // Idempotent launch: skip tools already on the repo, not just the caller-supplied [skipNames].
    // A host session can reach this launcher twice against the same repo — e.g. an iOS-host tool
    // dispatch ensures the session's scripted-tool runtime (registering catalog tools like
    // `openUrl`), then re-runs the resolved tool via `runYaml`, which launches catalog tools again.
    // Without this guard the second pass hits `addDynamicTools`'s duplicate-name check and throws
    // ("Dynamic tool 'openUrl' is already registered by another dynamic source"), crashing the whole
    // session over a tool that's already present and working. Re-registering an identical catalog
    // tool is a no-op, so skipping it is strictly safer than crashing.
    val alreadyRegistered = toolRepo.getRegisteredDynamicTools().keys
    val resolved = resolveInProcessScriptedTools(toolNames, skipNames + alreadyRegistered, logPrefix)
    if (resolved.isEmpty()) return emptyList()

    val accumulated = mutableListOf<LazyYamlScriptedToolRegistration>()

    try {
      for (tool in resolved) {
        val bundleJs = classLoader?.getResourceAsStream(tool.bundleResourcePath)
          ?.bufferedReader()
          ?.use { it.readText() }
        if (bundleJs == null) {
          Console.log(
            "$logPrefix Scripted tool '${tool.name.toolName}': pre-compiled bundle not found on " +
              "classpath at '${tool.bundleResourcePath}' — skipping. Run " +
              "`./gradlew :trailblaze-common:bundleFrameworkScriptedTools` to regenerate.",
          )
          continue
        }

        val bundleDir = File(sessionDir, "in-process-scripted-tools")
        bundleDir.mkdirs()
        val bundleFile = File(bundleDir, "${tool.name.toolName}.bundle.js")
        bundleFile.writeText(bundleJs)

        accumulated += LazyYamlScriptedToolRegistration.create(
          toolConfig = tool.config,
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
  fun describe(toolNames: Set<ToolName>): List<TrailblazeToolDescriptor> =
    resolveInProcessScriptedTools(toolNames).map {
      LazyYamlScriptedToolRegistration.buildScriptedToolDescriptor(it.config)
    }
}
