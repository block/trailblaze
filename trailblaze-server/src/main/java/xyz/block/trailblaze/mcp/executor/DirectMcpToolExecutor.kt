package xyz.block.trailblaze.mcp.executor

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.mcp.HostLocalToolDispatchingBridge
import xyz.block.trailblaze.mcp.TrailblazeMcpBridge
import xyz.block.trailblaze.mcp.toolsets.ToolSetCategory
import xyz.block.trailblaze.mcp.toolsets.ToolSetCategoryMapping
import xyz.block.trailblaze.scripting.InProcessScriptedToolLauncher
import xyz.block.trailblaze.scripting.LazyYamlScriptedToolRegistration
import xyz.block.trailblaze.toolcalls.HostLocalExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.KoogToolExt
import xyz.block.trailblaze.toolcalls.ToolName
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool.Companion.toTrailblazeToolDescriptor
import xyz.block.trailblaze.toolcalls.toKoogToolDescriptor
import xyz.block.trailblaze.toolcalls.toolName
import xyz.block.trailblaze.util.Console
import java.io.File
import kotlin.reflect.KClass

/**
 * Executes TrailblazeTools directly via the bridge, without MCP network round-trip.
 *
 * This is the optimized path for the self-connection pattern - the Koog agent
 * calls tools through this executor, which resolves and executes them in-process.
 *
 * @param mcpBridge Bridge to the device manager for tool execution
 * @param categories Tool categories to include (defaults to CORE_INTERACTION + NAVIGATION)
 */
class DirectMcpToolExecutor(
  private val mcpBridge: TrailblazeMcpBridge,
  private val categories: Set<ToolSetCategory> =
    setOf(
      ToolSetCategory.CORE_INTERACTION,
      ToolSetCategory.NAVIGATION,
    ),
) : McpToolExecutor, AutoCloseable {

  /**
   * Combined class-backed + YAML-defined + scripted tool surface for the configured categories.
   * Routed through [ToolSetCategoryMapping.resolve] so we can't accidentally advertise only one
   * slice — catalog entries like `navigation` include YAML-only tools (e.g. `pressBack`) and
   * scripted tools (e.g. `openUrl`) that MCP must handle alongside class-backed tools.
   */
  private val availableTools by lazy { ToolSetCategoryMapping.resolve(categories) }
  private val availableToolClasses: Set<KClass<out TrailblazeTool>> get() = availableTools.toolClasses
  private val availableYamlToolNames: Set<ToolName> get() = availableTools.yamlToolNames
  private val availableScriptedToolNames: Set<ToolName> get() = availableTools.scriptedToolNames
  private val availableScriptedToolNameStrings: Set<String> by lazy {
    availableScriptedToolNames.map { it.toolName }.toSet()
  }

  /** Map of tool name -> tool class for lookup */
  private val toolClassByName: Map<String, KClass<out TrailblazeTool>> by lazy {
    availableToolClasses.associateBy { it.toolName().toolName }
  }

  /**
   * Repo that resolves a (toolName, argsJson) pair to a concrete [TrailblazeTool]. Built
   * lazily from [availableTools] — [ToolSetCategoryMapping.resolve] can be expensive on first
   * access, so we share that work between [toolDescriptors] and this repo. Scripted tools are
   * added to this repo as dynamic registrations on first dispatch (see [ensureScriptedToolsLaunched]).
   */
  private val toolRepo: TrailblazeToolRepo by lazy {
    TrailblazeToolRepo(
      TrailblazeToolSet.DynamicTrailblazeToolSet(
        name = "DirectMcpToolExecutor",
        toolClasses = availableToolClasses,
        yamlToolNames = availableYamlToolNames,
      ),
    )
  }

  /** Cached tool descriptors (class-backed + YAML-defined + scripted). */
  private val toolDescriptors: List<TrailblazeToolDescriptor> by lazy {
    val classDescriptors = availableToolClasses.mapNotNull { toolClass ->
      toolClass.toKoogToolDescriptor()?.toTrailblazeToolDescriptor()
    }
    val yamlDescriptors = KoogToolExt.buildDescriptorsForYamlDefined(availableYamlToolNames)
      .map { it.toTrailblazeToolDescriptor() }
    // Catalog/framework scripted tools (e.g. openUrl). Advertised from YAML without launching a
    // QuickJS engine; the engine is connected lazily on first dispatch (ensureScriptedToolsLaunched).
    val scriptedDescriptors = InProcessScriptedToolLauncher.describe(availableScriptedToolNames)
    classDescriptors + yamlDescriptors + scriptedDescriptors
  }

  /**
   * All tool names the executor will accept. Derived from [toolDescriptors] so the
   * advertised surface and the acceptance gate are guaranteed to match — if
   * [KoogToolExt.buildDescriptorsForYamlDefined] skips a malformed YAML config (it logs a
   * warning and returns null), that name will also not pass the `ToolNotFound` check.
   */
  private val knownToolNames: Set<String> by lazy {
    toolDescriptors.map { it.name }.toSet()
  }

  // Scripted-tool QuickJS engines are connected lazily on first dispatch (advertisement needs no
  // engine). Held for disposal via [close].
  private val scriptedLaunchMutex = Mutex()
  @Volatile private var scriptedRegistrations: List<LazyYamlScriptedToolRegistration>? = null

  // Temp dir the launcher wrote bundle files into; deleted in [close] so a long-lived daemon doesn't
  // accumulate per-executor scratch dirs under java.io.tmpdir.
  @Volatile private var scriptedSessionDir: File? = null

  /**
   * Connects the in-process QuickJS engine for every advertised scripted tool the first time one
   * is dispatched, registering them into [toolRepo] so [deserializeTool] resolves a scripted name
   * to a host-local tool. Idempotent and concurrency-safe. Shares the SAME launcher the host runner
   * and the daemon's session runtime use — there is one in-process scripted-tool implementation.
   */
  private suspend fun ensureScriptedToolsLaunched() {
    if (availableScriptedToolNames.isEmpty() || scriptedRegistrations != null) return
    scriptedLaunchMutex.withLock {
      if (scriptedRegistrations != null) return
      val sessionId = SessionId.sanitized("direct-mcp-executor-scripted-tools")
      val sessionDir = File(System.getProperty("java.io.tmpdir"), "trailblaze-direct-mcp/${sessionId.value}")
      scriptedSessionDir = sessionDir
      scriptedRegistrations = InProcessScriptedToolLauncher.launch(
        toolRepo = toolRepo,
        sessionId = sessionId,
        sessionDir = sessionDir,
        toolNames = availableScriptedToolNames,
        logPrefix = "[DirectMcpToolExecutor]",
      )
    }
  }

  override suspend fun executeToolByName(
    toolName: String,
    args: JsonObject,
  ): ToolExecutionResult {
    if (toolName !in knownToolNames) {
      return ToolExecutionResult.ToolNotFound(
        requestedTool = toolName,
        availableTools = knownToolNames.toList(),
      )
    }

    // Connect scripted-tool engines on first scripted dispatch so the name resolves to a
    // host-local tool below.
    if (toolName in availableScriptedToolNameStrings) {
      ensureScriptedToolsLaunched()
    }

    return try {
      val tool = deserializeTool(toolName, args)
      // Host-local tools (scripted QuickJS tools) execute in-process; their nested framework
      // `client.callTool(...)` calls route back through [toolRepo]. Mirrors the primary inner-agent
      // dispatch in `BridgeUiActionExecutor`: try the host-local path, fall back to the device path
      // (Playwright is handled host-local; other drivers fall through to executeTrailblazeTool).
      val output = if (tool is HostLocalExecutableTrailblazeTool) {
        (mcpBridge as? HostLocalToolDispatchingBridge)?.executeHostLocalTool(tool, toolRepo, null)
          ?: mcpBridge.executeTrailblazeTool(tool)
      } else {
        mcpBridge.executeTrailblazeTool(tool)
      }
      ToolExecutionResult.Success(
        output = output,
        toolName = toolName,
      )
    } catch (e: Exception) {
      ToolExecutionResult.Failure(
        error = "Failed to execute '$toolName': ${e.message}",
        toolName = toolName,
      )
    }
  }

  override fun getAvailableTools(): List<TrailblazeToolDescriptor> = toolDescriptors

  /**
   * Deserializes a tool from (name, args) by routing through [toolRepo]. The repo
   * dispatches on toolName to the matching class-backed, YAML-defined, or (once launched)
   * scripted serializer, decoding the flat args object directly — no `toolName`/`raw` wrapping.
   */
  private fun deserializeTool(toolName: String, args: JsonObject): TrailblazeTool =
    toolRepo.toolCallToTrailblazeTool(toolName, args.toString())

  /** Frees any connected scripted-tool QuickJS engines + their scratch dir. Safe to call repeatedly. */
  override fun close() {
    val regs = scriptedRegistrations ?: return
    scriptedRegistrations = null
    runBlocking {
      for (reg in regs) {
        runCatching { reg.dispose() }
          .onFailure {
            Console.log(
              "[DirectMcpToolExecutor] Failed to dispose scripted tool '${reg.name.toolName}': ${it.message}",
            )
          }
      }
    }
    // Remove the per-executor bundle scratch dir so it doesn't accumulate under java.io.tmpdir.
    scriptedSessionDir?.let { dir -> runCatching { dir.deleteRecursively() } }
    scriptedSessionDir = null
  }
}
