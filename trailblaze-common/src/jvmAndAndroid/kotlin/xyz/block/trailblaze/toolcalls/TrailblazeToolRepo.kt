package xyz.block.trailblaze.toolcalls

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.message.Message
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.serializer
import xyz.block.trailblaze.config.YamlDefinedToolSerializer
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeSerializationInitializer
import xyz.block.trailblaze.toolcalls.KoogToolExt.hasSerializableAnnotation
import xyz.block.trailblaze.toolcalls.KoogToolExt.toKoogTools
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool.Companion.toKoogToolDescriptor
import xyz.block.trailblaze.toolcalls.commands.ObjectiveStatusTrailblazeTool
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.yaml.DirectionStep
import xyz.block.trailblaze.yaml.PromptStep
import xyz.block.trailblaze.yaml.VerificationStep
import kotlin.reflect.KClass

/**
 * Manual calls we register that are not related to Maestro
 */
class TrailblazeToolRepo(
  /**
   * The initial set of tools that are registered in this repository.
   */
  trailblazeToolSet: TrailblazeToolSet,
  /**
   * Optional catalog for dynamic toolset switching. When set, the LLM can call
   * `setActiveToolSets` to swap which tools are available.
   */
  val toolSetCatalog: List<ToolSetCatalogEntry>? = null,
) {
  val registeredTrailblazeToolClasses: MutableSet<KClass<out TrailblazeTool>> = trailblazeToolSet
    .asTools()
    .toMutableSet()

  /**
   * YAML-defined (`tools:` mode) tool names the LLM should see. These don't have a KClass —
   * they're identified by their `id:` and constructed at execute time via
   * [xyz.block.trailblaze.config.YamlDefinedTrailblazeTool]. Keyed by [ToolName] to stay
   * type-consistent with [registeredDynamicTools] and the rest of the resolver chain.
   */
  private val registeredYamlToolNames: MutableSet<ToolName> = trailblazeToolSet
    .asYamlToolNames()
    .toMutableSet()

  /**
   * Session-scoped dynamic tool registrations (subprocess MCP today; future bundled on-device
   * runtimes plug in here too). Keyed by [ToolName] so lookups can't be accidentally mixed
   * with raw-string identifiers from other sources. Participates in [asToolRegistry],
   * [getCurrentToolDescriptors], and [toolCallToTrailblazeTool] alongside the KClass and
   * YAML-name sets.
   */
  private val registeredDynamicTools: MutableMap<ToolName, DynamicTrailblazeToolRegistration> =
    mutableMapOf()

  /**
   * Tools that are not part of the catalog (e.g., app-specific custom tools).
   * These are preserved across [setActiveToolSets] calls.
   */
  private val extraToolClasses: Set<KClass<out TrailblazeTool>> by lazy {
    val catalogToolClasses = toolSetCatalog
      ?.flatMap { it.toolClasses }
      ?.toSet()
      ?: emptySet()
    registeredTrailblazeToolClasses.filter { it !in catalogToolClasses }.toSet()
  }

  /**
   * YAML-defined tool names that are not part of the catalog. Preserved across
   * [setActiveToolSets] calls alongside [extraToolClasses].
   */
  private val extraYamlToolNames: Set<ToolName> by lazy {
    val catalogYamlToolNames = toolSetCatalog
      ?.flatMap { it.yamlToolNames }
      ?.toSet()
      ?: emptySet()
    registeredYamlToolNames.filter { it !in catalogYamlToolNames }.toSet()
  }

  fun getRegisteredTrailblazeTools(): Set<KClass<out TrailblazeTool>> = synchronized(registeredTrailblazeToolClasses) {
    registeredTrailblazeToolClasses.toSet()
  }

  fun getRegisteredYamlToolNames(): Set<ToolName> = synchronized(registeredTrailblazeToolClasses) {
    registeredYamlToolNames.toSet()
  }

  fun getRegisteredDynamicTools(): Map<ToolName, DynamicTrailblazeToolRegistration> =
    synchronized(registeredTrailblazeToolClasses) { registeredDynamicTools.toMap() }

  /**
   * Point-in-time snapshot of class-backed, YAML-defined, and dynamic registered tools.
   * Read under a single lock so concurrent [setActiveToolSets] / [addDynamicTools] calls
   * can't yield a half-registered view.
   */
  private data class RegisteredToolsSnapshot(
    val toolClasses: Set<KClass<out TrailblazeTool>>,
    val yamlToolNames: Set<ToolName>,
    val dynamic: Map<ToolName, DynamicTrailblazeToolRegistration>,
  )

  private fun snapshotRegisteredTools(): RegisteredToolsSnapshot = synchronized(registeredTrailblazeToolClasses) {
    RegisteredToolsSnapshot(
      toolClasses = registeredTrailblazeToolClasses.toSet(),
      yamlToolNames = registeredYamlToolNames.toSet(),
      dynamic = registeredDynamicTools.toMap(),
    )
  }

  fun asToolRegistry(trailblazeToolContextProvider: () -> TrailblazeToolExecutionContext): ToolRegistry {
    // Always include verify tools so assertion tool calls from verification steps
    // can be resolved, even when dynamic toolsets limit registeredTrailblazeToolClasses.
    val snapshot = snapshotRegisteredTools()
    return ToolRegistry {
      tools((snapshot.toolClasses + verifyTools).toKoogTools(trailblazeToolContextProvider))
      if (snapshot.yamlToolNames.isNotEmpty()) {
        tools(KoogToolExt.buildKoogToolsForYamlDefined(snapshot.yamlToolNames, trailblazeToolContextProvider))
      }
      if (snapshot.dynamic.isNotEmpty()) {
        tools(snapshot.dynamic.values.map { it.buildKoogTool(trailblazeToolContextProvider) })
      }
    }
  }

  private fun addTrailblazeTools(vararg trailblazeTool: KClass<out TrailblazeTool>) = synchronized(registeredTrailblazeToolClasses) {
    trailblazeTool.forEach { tool ->
      if (!tool.hasSerializableAnnotation()) {
        throw IllegalArgumentException("Class ${tool.qualifiedName} is not serializable. Please add @Serializable from the Kotlin Serialization library.")
      }
      if (tool.toKoogToolDescriptor() != null) {
        registeredTrailblazeToolClasses.add(tool)
      } else {
        Console.log("Class ${tool.qualifiedName} (${tool.toolName().toolName}) cannot be used by the LLM.  It was not registered.")
      }
    }
  }

  fun addTrailblazeToolSet(trailblazeToolSet: TrailblazeToolSet) = synchronized(registeredTrailblazeToolClasses) {
    addTrailblazeTools(*trailblazeToolSet.asTools().toTypedArray())
    registeredYamlToolNames.addAll(trailblazeToolSet.asYamlToolNames())
  }

  /**
   * Register one or more session-scoped dynamic tool sources (e.g. subprocess MCP). Fails
   * fast on a name collision against any already-registered source — the error message
   * names both contributors so the author can resolve by renaming, per Decision 014 and the
   * scope devlog's tool-naming contract.
   *
   * **Atomic:** the entire batch is validated before anything is inserted. A collision on
   * registration N rolls back the whole call — registrations 0…N-1 never land in the repo —
   * so a partial failure can't leave the repo in an inconsistent state. Collision classes
   * checked: duplicate names within the batch, clashes against an existing Kotlin-backed
   * tool, a YAML-defined tool, or another dynamic registration.
   */
  fun addDynamicTools(registrations: Iterable<DynamicTrailblazeToolRegistration>) =
    synchronized(registeredTrailblazeToolClasses) {
      val batch = mutableMapOf<ToolName, DynamicTrailblazeToolRegistration>()
      registrations.forEach { registration ->
        val incoming: ToolName = registration.name
        val rawName: String = incoming.toolName
        require(incoming !in batch) {
          "Dynamic tool '$rawName' appears twice in the same addDynamicTools call"
        }
        val kotlinClash = registeredTrailblazeToolClasses.firstOrNull { it.toolName() == incoming }
        require(kotlinClash == null) {
          "Dynamic tool '$rawName' collides with Kotlin-registered tool ${kotlinClash?.qualifiedName}"
        }
        require(incoming !in registeredYamlToolNames) {
          "Dynamic tool '$rawName' collides with a YAML-defined tool of the same name"
        }
        require(incoming !in registeredDynamicTools) {
          "Dynamic tool '$rawName' is already registered by another dynamic source"
        }
        batch[incoming] = registration
      }
      // Validation passed — apply the whole batch at once.
      registeredDynamicTools.putAll(batch)
    }

  fun removeDynamicTool(name: ToolName) = synchronized(registeredTrailblazeToolClasses) {
    registeredDynamicTools.remove(name)
  }

  fun removeTrailblazeTools(vararg trailblazeToolArgs: KClass<out TrailblazeTool>) = synchronized(registeredTrailblazeToolClasses) {
    trailblazeToolArgs.forEach { tool ->
      if (registeredTrailblazeToolClasses.contains(tool)) {
        registeredTrailblazeToolClasses.remove(tool)
      }
    }
  }

  fun removeAllTrailblazeTools() = synchronized(registeredTrailblazeToolClasses) {
    registeredTrailblazeToolClasses.clear()
    registeredYamlToolNames.clear()
    registeredDynamicTools.clear()
  }

  /**
   * Replaces all registered tools with the resolved set from the given toolset IDs.
   * Requires [toolSetCatalog] to be set.
   *
   * **Dynamic tools are session-scoped and persist across switches.** [registeredDynamicTools]
   * is deliberately not rebuilt here: subprocess MCP / future on-device bundle tools are
   * registered once at session start (via [addDynamicTools]) and stay available regardless
   * of which catalog toolsets the LLM enables. The reported tool count + acknowledgement
   * string include them so the LLM's view of "tools available" matches reality.
   */
  fun setActiveToolSets(toolSetIds: List<String>): String {
    val catalog = toolSetCatalog
      ?: return "Dynamic toolsets not configured for this test."
    val validIds = catalog.map { it.id }.toSet()
    val invalidIds = toolSetIds.filter { it !in validIds }
    if (invalidIds.isNotEmpty()) {
      return "Unknown toolset IDs: $invalidIds. Valid IDs: ${validIds.filter { id -> !catalog.first { it.id == id }.alwaysEnabled }}"
    }
    val resolved = TrailblazeToolSetCatalog.resolve(toolSetIds, catalog)
    val newToolClasses = resolved.toolClasses + extraToolClasses
    val newYamlToolNames = resolved.yamlToolNames + extraYamlToolNames
    val dynamicToolCount = synchronized(registeredTrailblazeToolClasses) {
      registeredTrailblazeToolClasses.clear()
      registeredTrailblazeToolClasses.addAll(newToolClasses)
      registeredYamlToolNames.clear()
      registeredYamlToolNames.addAll(newYamlToolNames)
      registeredDynamicTools.size
    }
    val totalTools = newToolClasses.size + newYamlToolNames.size + dynamicToolCount
    Console.log("Active toolsets changed to: $toolSetIds ($totalTools tools)")
    return buildString {
      appendLine("Active tool sets updated.")
      appendLine("Enabled sets: ${(toolSetIds + "core").distinct()}")
      appendLine("Total tools available: $totalTools")
    }
  }

  fun toolCallToTrailblazeTool(toolMessage: Message.Tool): TrailblazeTool? = toolCallToTrailblazeTool(
    toolName = toolMessage.tool,
    toolContent = toolMessage.content,
  )

  fun toolCallToTrailblazeTool(
    toolName: String,
    /** The JSON string of the tool arguments. */
    toolContent: String,
  ): TrailblazeTool {
    val snapshot = snapshotRegisteredTools()

    // 1. Dynamic-source path — subprocess MCP, future in-process bundles, etc.
    //    Checked first so authors can override Kotlin-backed defaults if needed (the
    //    collision guard in [addDynamicTools] prevents accidental shadowing). Koog hands us
    //    the tool name as a raw String, so wrap it once for the typed map lookup.
    snapshot.dynamic[ToolName(toolName)]?.let { return it.decodeToolCall(toolContent) }

    // 2. Class-backed path — look up by KClass + decode via the class's Kotlin serializer.
    val trailblazeToolClass: KClass<out TrailblazeTool>? =
      snapshot.toolClasses.firstOrNull { toolKClass ->
        toolKClass.toKoogToolDescriptor()?.name == toolName
      }
    if (trailblazeToolClass != null) {
      @OptIn(InternalSerializationApi::class)
      return TrailblazeJsonInstance.decodeFromString(trailblazeToolClass.serializer(), toolContent)
    }

    // 3. YAML-defined path — resolve to the registered ToolYamlConfig + decode via that tool's
    //    pre-bound [YamlDefinedToolSerializer]. Mirrors what [asToolRegistry] does on the Koog
    //    side so planners that advertise YAML tools via [getCurrentToolDescriptors] can also
    //    have those tool calls deserialize cleanly on execution paths that route through here
    //    (e.g. AgentUiActionExecutor.mapToTrailblazeTool, HostAccessibilityRpcClient.execute).
    //    Koog hands us the name as a raw String; wrap it once for the typed-set membership test.
    val typedName = ToolName(toolName)
    if (typedName in snapshot.yamlToolNames) {
      val config = TrailblazeSerializationInitializer.buildYamlDefinedTools()[typedName]
      if (config != null) {
        return TrailblazeJsonInstance.decodeFromString(YamlDefinedToolSerializer(config), toolContent)
      }
    }

    error(
      buildString {
        appendLine("Could not find Trailblaze tool for name: $toolName.")
        appendLine("Registered class-backed tools: ${snapshot.toolClasses.map { it.simpleName }}")
        appendLine("Registered YAML-defined tools: ${snapshot.yamlToolNames.map { it.toolName }}")
        if (snapshot.dynamic.isNotEmpty()) {
          appendLine("Registered dynamic tools: ${snapshot.dynamic.keys.map { it.toolName }}")
        }
      },
    )
  }

  fun getCurrentToolDescriptors(): List<ToolDescriptor> {
    val snapshot = snapshotRegisteredTools()
    val classDescriptors = snapshot.toolClasses.mapNotNull { it.toKoogToolDescriptor() }
    val yamlDescriptors = KoogToolExt.buildDescriptorsForYamlDefined(snapshot.yamlToolNames)
    // Subprocess / other runtime-discovered tools use the lenient projection: an MCP server
    // can legitimately advertise an `array` / `object` parameter type that the LLM-tool
    // schema doesn't model today, and registration shouldn't crash on that.
    val dynamicDescriptors = snapshot.dynamic.values.map {
      it.trailblazeDescriptor.toKoogToolDescriptor(strict = false)
    }
    return classDescriptors + yamlDescriptors + dynamicDescriptors
  }

  companion object {
    /**
     * Creates a [TrailblazeToolRepo] with dynamic toolset support.
     *
     * Starts with only core tools (the catalog's `always_enabled` entries) plus any
     * [customToolClasses] / [customYamlToolNames]. The LLM can enable additional toolsets at
     * runtime via `setActiveToolSets`.
     *
     * When [driverType] is non-null, the core tool resolution runs through
     * [TrailblazeToolSetCatalog.resolveForDriver] so alwaysEnabled entries that declare
     * incompatible `drivers:` (e.g. `core_interaction.yaml` on Playwright) are filtered out
     * before being added to the initial surface. Leave null if the driver isn't known — the
     * catalog resolves all alwaysEnabled entries regardless of driver (pre-existing behavior).
     *
     * [customYamlToolNames] lets callers contribute YAML-defined tools (those without a
     * backing [KClass]) to the initial surface — symmetric with [customToolClasses] for
     * class-backed tools. Callers that only reference class-backed tools can leave it at
     * the default empty set.
     */
    fun withDynamicToolSets(
      customToolClasses: Set<KClass<out TrailblazeTool>> = emptySet(),
      customYamlToolNames: Set<ToolName> = emptySet(),
      excludedToolClasses: Set<KClass<out TrailblazeTool>> = emptySet(),
      catalog: List<ToolSetCatalogEntry> = TrailblazeToolSetCatalog.defaultEntries(),
      driverType: TrailblazeDriverType? = null,
    ): TrailblazeToolRepo {
      val coreTools = if (driverType != null) {
        TrailblazeToolSetCatalog.resolveForDriver(driverType, emptyList(), catalog)
      } else {
        TrailblazeToolSetCatalog.resolve(emptyList(), catalog)
      }
      return TrailblazeToolRepo(
        trailblazeToolSet = TrailblazeToolSet.DynamicTrailblazeToolSet(
          name = "Core Tool Set",
          toolClasses = coreTools.toolClasses + customToolClasses - excludedToolClasses,
          yamlToolNames = coreTools.yamlToolNames + customYamlToolNames,
        ),
        toolSetCatalog = catalog,
      )
    }
  }

  // When running - verify: only provide the assertion tools and the objective status tool.
  // If you don't provide the objective status tool then the agent cannot complete the step.
  // Resolves via this repo's configured [toolSetCatalog] when present so a test that wires up
  // a custom catalog (e.g. extra app-specific entries) gets those reflected here too; falls
  // back to the global [TrailblazeToolSetCatalog.defaultEntries] when no override was passed.
  // Uses [entryToolClasses] (not [resolve]) so Invariant 3's isolation semantics hold — no
  // alwaysEnabled meta/core_interaction tools leak into the VerificationStep surface.
  // Lazy so repos that never hit VerificationStep don't force catalog discovery.
  private val verifyTools: Set<KClass<out TrailblazeTool>> by lazy {
    val catalog = toolSetCatalog ?: TrailblazeToolSetCatalog.defaultEntries()
    TrailblazeToolSetCatalog.entryToolClasses("verification", catalog) +
      ObjectiveStatusTrailblazeTool::class
  }

  // This function returns different tool descriptors based on the type of prompt step passed in.
  // The DirectionStep returns all registered trailblaze tool classes, while the VerificationStep
  // will return a subset of the assert tool set.
  fun getToolDescriptorsForStep(promptStep: PromptStep): List<ToolDescriptor> = when (promptStep) {
    is DirectionStep -> getCurrentToolDescriptors()
    is VerificationStep -> verifyTools.mapNotNull { it.toKoogToolDescriptor() }
  }
}
