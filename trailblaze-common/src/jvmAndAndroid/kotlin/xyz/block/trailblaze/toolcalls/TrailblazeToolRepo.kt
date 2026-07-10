package xyz.block.trailblaze.toolcalls

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.message.MessagePart
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import xyz.block.trailblaze.config.YamlDefinedToolSerializer
import xyz.block.trailblaze.config.toTrailblazeToolDescriptor
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeSerializationInitializer
import xyz.block.trailblaze.toolcalls.KoogToolExt.hasSerializableAnnotation
import xyz.block.trailblaze.toolcalls.KoogToolExt.toKoogTools
import xyz.block.trailblaze.toolcalls.KoogToolExt.toKoogToolsWithExecutor
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool.Companion.toKoogToolDescriptor
import xyz.block.trailblaze.toolcalls.commands.ObjectiveStatusTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.RequestDetailedViewHierarchyTrailblazeTool
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
   * Optional catalog used to resolve the initial tool surface and to scope a `verify:` step to
   * its driver-appropriate verification tools. The whole surface is advertised up front; there is
   * no runtime switching.
   */
  val toolSetCatalog: List<ToolSetCatalogEntry>? = null,
  /**
   * Optional driver type the repo is bound to. When set, [withDynamicToolSets] resolves the initial
   * surface through [TrailblazeToolSetCatalog.resolveForDriver] so catalog entries that declare
   * incompatible `drivers:` (e.g. `core_interaction.yaml` on a Playwright session) are filtered out.
   * Leave null in test fixtures and callers that don't know the driver yet; resolution then falls
   * back to the non-driver-aware catalog filter.
   */
  val driverType: TrailblazeDriverType? = null,
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
   * Scripted (`.ts` / `.js`) tool names the LLM should see. These are dispatched through
   * [registeredDynamicTools] (registered at session start by the bundling layer), but TRACKED
   * here so [advertisedDynamic] can hide a scripted tool that a target's `excluded_tools:` opted
   * out of. Symmetric with [registeredYamlToolNames] for the scripted-tool case.
   */
  private val registeredScriptedToolNames: MutableSet<ToolName> = trailblazeToolSet
    .asScriptedToolNames()
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

  fun getRegisteredTrailblazeTools(): Set<KClass<out TrailblazeTool>> = synchronized(registeredTrailblazeToolClasses) {
    registeredTrailblazeToolClasses.toSet()
  }

  fun getRegisteredYamlToolNames(): Set<ToolName> = synchronized(registeredTrailblazeToolClasses) {
    registeredYamlToolNames.toSet()
  }

  fun getRegisteredScriptedToolNames(): Set<ToolName> = synchronized(registeredTrailblazeToolClasses) {
    registeredScriptedToolNames.toSet()
  }

  /**
   * Every scripted tool name the catalog could deliver for this repo's driver — i.e. the union of
   * `scriptedToolNames` across all (driver-compatible) toolsets, not just the active ones.
   *
   * The bundling layer registers a dynamic tool for EACH of these at session start, so recorded
   * replays can dispatch a scripted tool even when it's hidden from the LLM. Advertisement is gated
   * by [advertisedDynamic] to the non-excluded set, so registering-but-not-advertising a scripted
   * tool that a target's `excluded_tools:` dropped is harmless.
   */
  val allCatalogScriptedToolNames: Set<ToolName> by lazy {
    val catalog = toolSetCatalog ?: return@lazy emptySet()
    if (driverType != null) {
      TrailblazeToolSetCatalog.defaultScriptedToolNamesForDriver(driverType, catalog)
    } else {
      catalog.flatMap { it.scriptedToolNames }.toSet()
    }
  }

  fun getRegisteredDynamicTools(): Map<ToolName, DynamicTrailblazeToolRegistration> =
    synchronized(registeredTrailblazeToolClasses) { registeredDynamicTools.toMap() }

  /**
   * Point-in-time snapshot of class-backed, YAML-defined, and dynamic registered tools.
   * Read under a single lock so concurrent [addDynamicTools] calls
   * can't yield a half-registered view.
   */
  private data class RegisteredToolsSnapshot(
    val toolClasses: Set<KClass<out TrailblazeTool>>,
    val yamlToolNames: Set<ToolName>,
    val dynamic: Map<ToolName, DynamicTrailblazeToolRegistration>,
    /** Scripted tool names that survived the target's `excluded_tools:` — i.e. the advertised set. */
    val advertisedScriptedToolNames: Set<ToolName>,
    /** Every scripted tool name the catalog could deliver (before exclusions). */
    val allCatalogScriptedToolNames: Set<ToolName>,
  ) {
    /**
     * Dynamic registrations visible to the LLM right now: every dynamic tool EXCEPT a catalog
     * scripted tool that a target's `excluded_tools:` dropped. A scripted tool is registered for
     * dispatch as soon as its bundle loads (so recorded replays + direct calls work via
     * [toolCallToTrailblazeTool]), but an excluded one is hidden from the LLM. Non-scripted dynamic
     * tools (subprocess MCP, target-declared scripted tools) are always advertised.
     *
     * A registration that declares `surfaceToLlm = false` (e.g. a scripted internal step composed
     * by a parent tool) is dropped regardless — it stays dispatchable by name and resolvable for
     * recorded replays, but never enters the LLM's tool menu. Defaults to `true`, so registrations
     * that don't model LLM-visibility (subprocess MCP, etc.) are unaffected.
     */
    fun advertisedDynamic(): List<DynamicTrailblazeToolRegistration> =
      dynamic.entries
        .filter { (name, reg) ->
          reg.surfaceToLlm &&
            (name !in allCatalogScriptedToolNames || name in advertisedScriptedToolNames)
        }
        .map { it.value }
  }

  private fun snapshotRegisteredTools(): RegisteredToolsSnapshot = synchronized(registeredTrailblazeToolClasses) {
    RegisteredToolsSnapshot(
      toolClasses = registeredTrailblazeToolClasses.toSet(),
      yamlToolNames = registeredYamlToolNames.toSet(),
      dynamic = registeredDynamicTools.toMap(),
      advertisedScriptedToolNames = registeredScriptedToolNames.toSet(),
      allCatalogScriptedToolNames = allCatalogScriptedToolNames,
    )
  }

  fun asToolRegistry(trailblazeToolContextProvider: () -> TrailblazeToolExecutionContext): ToolRegistry {
    // Always include verify tools so assertion tool calls from verification steps
    // can be resolved, even when dynamic toolsets limit registeredTrailblazeToolClasses.
    val snapshot = snapshotRegisteredTools()
    return ToolRegistry {
      tools((snapshot.toolClasses + verifyTools + koogInspectionTools).toKoogTools(trailblazeToolContextProvider))
      if (snapshot.yamlToolNames.isNotEmpty()) {
        tools(KoogToolExt.buildKoogToolsForYamlDefined(snapshot.yamlToolNames, trailblazeToolContextProvider))
      }
      val advertisedDynamic = snapshot.advertisedDynamic()
      if (advertisedDynamic.isNotEmpty()) {
        tools(advertisedDynamic.map { it.buildKoogTool(trailblazeToolContextProvider) })
      }
    }
  }

  /**
   * Executor-routed [ToolRegistry] for agent-driven, in-process Koog loops (e.g. the
   * [xyz.block.trailblaze.mcp.AgentImplementation.KOOG_STRATEGY_GRAPH] web path).
   *
   * Differs from [asToolRegistry] only in how class-backed and YAML-defined tool calls are
   * EXECUTED: instead of calling `tool.execute(context)` directly, each decoded tool is
   * dispatched through [toolDispatcher] — typically `agent.runTrailblazeTools(listOf(tool), …)`.
   * That route is mandatory for driver-specific tools (Playwright / Compose) whose own
   * `execute` throws and is only reachable via their agent. Routing through the agent also
   * preserves the side-effect `logToolExecution` / session logging the strategy-graph agent
   * relies on, so session logs happen exactly as they do on the legacy
   * [xyz.block.trailblaze.agent.TrailblazeRunner] path.
   *
   * Dynamic (subprocess-MCP) tools keep the context-provider path — their `execute` round-trips
   * through their own transport, not the driver agent, so [trailblazeToolContextProvider] (used
   * only for those) is still required.
   */
  fun asToolRegistry(
    toolDispatcher: suspend (TrailblazeTool) -> String,
    trailblazeToolContextProvider: () -> TrailblazeToolExecutionContext,
  ): ToolRegistry {
    val snapshot = snapshotRegisteredTools()
    return ToolRegistry {
      tools((snapshot.toolClasses + verifyTools + koogInspectionTools).toKoogToolsWithExecutor(toolDispatcher))
      if (snapshot.yamlToolNames.isNotEmpty()) {
        tools(KoogToolExt.buildKoogToolsForYamlDefinedWithExecutor(snapshot.yamlToolNames, toolDispatcher))
      }
      // Same advertisement gate as the context-provider overload above: filter through
      // `advertisedDynamic()` so a scripted internal step declaring `surfaceToLlm = false` (and a
      // scripted tool whose toolset isn't active) stays out of the LLM's menu on the
      // KOOG_STRATEGY_GRAPH (in-process) path too. It remains dispatchable by name via
      // `toolCallToTrailblazeTool` — this only governs what's advertised.
      val advertisedDynamic = snapshot.advertisedDynamic()
      if (advertisedDynamic.isNotEmpty()) {
        tools(advertisedDynamic.map { it.buildKoogTool(trailblazeToolContextProvider) })
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
    registeredScriptedToolNames.addAll(trailblazeToolSet.asScriptedToolNames())
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
    registeredScriptedToolNames.clear()
    registeredDynamicTools.clear()
  }

  fun toolCallToTrailblazeTool(toolCall: MessagePart.Tool.Call): TrailblazeTool? = toolCallToTrailblazeTool(
    toolName = toolCall.tool,
    toolContent = toolCall.args,
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
    snapshot.dynamic[ToolName(toolName)]?.let {
      return it.decodeToolCall(coerceScriptedArgsToDescriptor(toolContent, it.trailblazeDescriptor))
    }

    // 2. Class-backed path — look up by the @TrailblazeToolClass(name=...) value, not by the
    //    Koog descriptor name. Descriptor lookup misses any tool with surfaceToLlm = false (e.g.
    //    TapOnByElementSelector), since toKoogToolDescriptor() returns null for those — the
    //    annotation-name lookup keeps every class-backed tool reachable regardless of LLM
    //    visibility.
    val trailblazeToolClass: KClass<out TrailblazeTool>? =
      snapshot.toolClasses.firstOrNull { toolKClass ->
        toolKClass.toolName().toolName == toolName
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

  /**
   * Variant of [toolCallToTrailblazeTool] that bypasses the `surfaceToLlm` filter for YAML-defined
   * tools by falling back to the global [TrailblazeSerializationInitializer.buildYamlDefinedTools]
   * registry if the standard lookup misses.
   *
   * Used by inline scripted-tool composition (`SessionScopedHostBinding` in
   * `:trailblaze-quickjs-tools`) where author bundles can call into YAML-defined tools that
   * are intentionally hidden from the LLM (`surface_to_llm: false`). The standard
   * [toolCallToTrailblazeTool] already bypasses the filter for class-backed tools and for
   * YAML tools that are registered into [registeredYamlToolNames]; this method extends that
   * bypass to YAML tools that exist globally on the classpath but aren't in the session's
   * registered set.
   *
   * Returns null instead of throwing on a missing tool name — composition callers handle the
   * miss by returning a structured error envelope to JS rather than propagating an exception.
   */
  fun toolCallToTrailblazeToolUnfiltered(
    toolName: String,
    toolContent: String,
  ): TrailblazeTool? {
    return try {
      toolCallToTrailblazeTool(toolName, toolContent)
    } catch (_: IllegalStateException) {
      // Fall through to the unfiltered global lookups below — first global YAML, then
      // global class registry. Both extend the bypass to tools that exist on the classpath
      // but aren't in the session's registered set, which is the shape inline scripted-tool
      // composition needs: an author calling `client.callTool("runCommand", …)` from inside
      // a `.ts` body should reach the framework's `RunCommandTrailblazeTool` even when the
      // session's target hasn't explicitly listed it in its custom tool classes.
      val typedName = ToolName(toolName)
      val yamlConfig = TrailblazeSerializationInitializer.buildYamlDefinedTools()[typedName]
      if (yamlConfig != null) {
        @OptIn(InternalSerializationApi::class)
        return TrailblazeJsonInstance.decodeFromString(YamlDefinedToolSerializer(yamlConfig), toolContent)
      }
      // Global class-backed registry — any class annotated `@TrailblazeToolClass(name=...)`
      // that's been discovered at JVM init. The standard [toolCallToTrailblazeTool] only
      // searches the session's registered classes; this extends the search to every
      // class-backed tool the framework knows about. Required for scripted-tool composition
      // of host-side helpers like `runCommand` that aren't part of every target's toolset.
      val toolClass = TrailblazeSerializationInitializer.buildAllTools()[typedName] ?: return null
      @OptIn(InternalSerializationApi::class)
      TrailblazeJsonInstance.decodeFromString(toolClass.serializer(), toolContent)
    }
  }

  /**
   * Set of argument keys the runtime deserializer for [toolName] will recognize, or `null` if
   * no schema can be located. Wrappers that want to enforce the typed contract at dispatch time
   * (e.g. [xyz.block.trailblaze.scripting.callback.JsScriptingCallbackDispatcher],
   * [SessionScopedHostBinding]) use this to reject incoming `arguments_json` carrying keys the
   * tool doesn't accept — closing the loophole where `kotlinx.serialization`'s
   * `ignoreUnknownKeys = true` silently drops misspelled / LLM-scaffolding keys.
   *
   * **Paired with [requiredArgumentKeysFor]** — both methods walk the same four-tier resolution
   * chain (dynamic → session class → session YAML → global YAML → global class) and return
   * `null` for the same "schema can't be introspected" branch. Any future change that re-routes
   * one method's resolution chain MUST update the other in lockstep, or the validator's
   * unknown-key gate and missing-required gate will fall out of agreement.
   *
   * The returned set is the union of all keys the deserializer is willing to decode (not just
   * the LLM-visible parameters), so the message can list the canonical shape including fields
   * like `nodeSelector` that scripted callers may legitimately set even when the LLM descriptor
   * excludes them.
   *
   * Resolution mirrors [toolCallToTrailblazeTool] / [toolCallToTrailblazeToolUnfiltered]:
   *  1. Class-backed tool (session-registered, then global registry as a fallback for the
   *     unfiltered path).
   *  2. Dynamic tool registration (subprocess MCP, bundled on-device runtimes).
   *  3. YAML-defined tool (session-registered, then global classpath as a fallback).
   *
   * Returns `null` only when no schema can be located:
   *  - The tool name is unknown to every resolution tier, OR
   *  - A dynamic tool registration declares no parameters (subprocess MCP servers can legitimately
   *    advertise no schema; defaulting to "skip" avoids false rejections on tools whose schema
   *    simply isn't modelled in the descriptor).
   *
   * Returns an **empty set** when the tool's schema is author-controlled and exhaustively
   * declares zero parameters (class-backed `@TrailblazeToolClass` with no constructor args;
   * YAML tools with `parameters: []`). In that case every incoming key is unknown and the
   * validator MUST reject — leniency here would re-introduce the exact silent-drop behavior
   * this change closes (e.g. for `pressBack` and other no-arg YAML tools).
   */
  @OptIn(InternalSerializationApi::class)
  fun expectedArgumentKeysFor(toolName: String): Set<String>? {
    val snapshot = snapshotRegisteredTools()
    val typedName = ToolName(toolName)

    snapshot.dynamic[typedName]?.let { registration ->
      // Dynamic tools (subprocess MCP) can legitimately advertise no schema — fall through
      // to "skip" rather than rejecting every call to an unannotated subprocess tool.
      return registration.trailblazeDescriptor.parameterNames().ifEmpty { null }
    }

    val sessionClass = snapshot.toolClasses.firstOrNull { it.toolName() == typedName }
    if (sessionClass != null) {
      // Class-backed: empty `elementNames` means the Kotlin data class declares no
      // properties — strictly reject extra keys.
      return sessionClass.serializer().descriptor.elementNames.toSet()
    }

    if (typedName in snapshot.yamlToolNames) {
      val config = TrailblazeSerializationInitializer.buildYamlDefinedTools()[typedName]
      if (config != null) {
        // YAML tools: author-controlled exhaustive `parameters:` list. Empty means
        // "tool takes no args" — reject any incoming key (closes the `pressBack` /
        // no-arg YAML loophole flagged on #3213).
        return config.toTrailblazeToolDescriptor().parameterNames()
      }
    }

    // Unfiltered fallback — covers [toolCallToTrailblazeToolUnfiltered]'s lookup of global
    // YAML configs and class-backed tools that aren't in the session's registered set. Keeps
    // the unknown-key check live for scripted bundles that compose host-side helpers like
    // `runCommand` which intentionally aren't in every target's toolset.
    val globalYamlConfig = TrailblazeSerializationInitializer.buildYamlDefinedTools()[typedName]
    if (globalYamlConfig != null) {
      return globalYamlConfig.toTrailblazeToolDescriptor().parameterNames()
    }
    val globalToolClass = TrailblazeSerializationInitializer.buildAllTools()[typedName]
    if (globalToolClass != null) {
      return globalToolClass.serializer().descriptor.elementNames.toSet()
    }

    return null
  }

  private fun TrailblazeToolDescriptor.parameterNames(): Set<String> =
    (requiredParameters.map { it.name } + optionalParameters.map { it.name }).toSet()

  private fun TrailblazeToolDescriptor.requiredParameterNames(): Set<String> =
    requiredParameters.map { it.name }.toSet()

  /**
   * Set of argument keys the runtime considers REQUIRED for [toolName], or `null` if no schema
   * can be located. Pairs with [expectedArgumentKeysFor] — the unknown-key gate and the
   * missing-required-key gate run off the same four-tier resolution chain (dynamic → session
   * class → session YAML → global YAML → global class), so a tool whose schema is
   * introspectable for one check is introspectable for the other. Future changes to one
   * method's chain MUST update the other in lockstep.
   *
   * Returns an **empty set** when the tool's schema is known but declares no required
   * parameters — e.g., a scripted tool whose every input is `required: false`, a YAML tool
   * with `parameters: []`, or a class-backed tool whose every constructor argument has a
   * default. Returning empty (rather than null) means "I checked, nothing is required" so
   * callers reject empty payloads only when something actually must be present.
   *
   * Returns `null` only when the resolver doesn't recognize the tool at all — same skip
   * branch [expectedArgumentKeysFor] uses for "dynamic tool advertises no schema." Falling
   * through to null keeps the validator a no-op for tools whose schema can't be read.
   *
   * Class-backed tools introspect via `SerialDescriptor.isElementOptional` so a Kotlin
   * property with a default value (`val foo: String = "x"`) is treated as optional even
   * though `expectedArgumentKeysFor` lists it among the accepted keys. YAML and dynamic
   * tools read straight off the descriptor's `requiredParameters` split — the partition
   * that [LazyYamlScriptedToolRegistration] and [ToolYamlConfig.toTrailblazeToolDescriptor]
   * already populate from the author-declared `required:` annotation.
   */
  @OptIn(InternalSerializationApi::class)
  fun requiredArgumentKeysFor(toolName: String): Set<String>? {
    val snapshot = snapshotRegisteredTools()
    val typedName = ToolName(toolName)

    snapshot.dynamic[typedName]?.let { registration ->
      // Mirror [expectedArgumentKeysFor]'s "schema can be empty" skip: a dynamic tool that
      // advertises no parameters at all (subprocess MCP server without an explicit schema)
      // falls through rather than synthesizing a missing-required error on every empty call.
      if (registration.trailblazeDescriptor.parameterNames().isEmpty()) return null
      return registration.trailblazeDescriptor.requiredParameterNames()
    }

    val sessionClass = snapshot.toolClasses.firstOrNull { it.toolName() == typedName }
    if (sessionClass != null) {
      return classRequiredKeys(sessionClass)
    }

    if (typedName in snapshot.yamlToolNames) {
      val config = TrailblazeSerializationInitializer.buildYamlDefinedTools()[typedName]
      if (config != null) {
        return config.toTrailblazeToolDescriptor().requiredParameterNames()
      }
    }

    val globalYamlConfig = TrailblazeSerializationInitializer.buildYamlDefinedTools()[typedName]
    if (globalYamlConfig != null) {
      return globalYamlConfig.toTrailblazeToolDescriptor().requiredParameterNames()
    }
    val globalToolClass = TrailblazeSerializationInitializer.buildAllTools()[typedName]
    if (globalToolClass != null) {
      return classRequiredKeys(globalToolClass)
    }

    return null
  }

  @OptIn(InternalSerializationApi::class)
  private fun classRequiredKeys(klass: KClass<out TrailblazeTool>): Set<String> {
    val descriptor = klass.serializer().descriptor
    val out = LinkedHashSet<String>(descriptor.elementsCount)
    for (i in 0 until descriptor.elementsCount) {
      if (!descriptor.isElementOptional(i)) out += descriptor.getElementName(i)
    }
    return out
  }

  fun getCurrentToolDescriptors(): List<ToolDescriptor> {
    // Build from source types, not the string-typed TrailblazeToolDescriptor mirror, whose
    // round-trip collapses non-primitive params (e.g. List<String>) to String via parseKoogParameterType.
    val snapshot = snapshotRegisteredTools()
    val classDescriptors = snapshot.toolClasses.mapNotNull { it.toKoogToolDescriptor() }
    val yamlDescriptors = KoogToolExt.buildDescriptorsForYamlDefined(snapshot.yamlToolNames)
    val dynamicDescriptors = snapshot.advertisedDynamic().map {
      it.trailblazeDescriptor.toKoogToolDescriptor(strict = false)
    }
    return classDescriptors + yamlDescriptors + dynamicDescriptors
  }

  /**
   * Trailblaze-native descriptor view for catalog/debug surfaces. Unlike [getCurrentToolDescriptors],
   * this preserves source metadata so callers can classify tools as Kotlin, YAML, TypeScript,
   * JavaScript, or generic dynamic registrations without re-inferring provenance from names.
   */
  fun getCurrentTrailblazeToolDescriptors(): List<TrailblazeToolDescriptor> {
    val snapshot = snapshotRegisteredTools()
    val classDescriptors = snapshot.toolClasses.mapNotNull { it.toTrailblazeToolDescriptorWithSource() }
    val yamlDescriptors = KoogToolExt.buildTrailblazeDescriptorsForYamlDefined(snapshot.yamlToolNames)
    val dynamicDescriptors = snapshot.advertisedDynamic().map { it.trailblazeDescriptor }
    return classDescriptors + yamlDescriptors + dynamicDescriptors
  }

  companion object {
    /**
     * Drivers that get the [koogInspectionTools] surface (the `requestDetailedViewHierarchy` tool).
     * Same set as the `observation` toolset's `drivers:` — host (iOS) + on-device (Android) — which
     * expose a screen-inspection hierarchy and whose agents run a generic ExecutableTrailblazeTool.
     * Notably excludes Revyl (its agent can't execute this generic tool and it has its own capture).
     */
    private val KOOG_INSPECTION_DRIVERS: Set<TrailblazeDriverType> = setOf(
      TrailblazeDriverType.IOS_HOST,
      TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
      TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
    )

    /** Toolset id whose verify surface is the generic, driver-agnostic Android/iOS assertion set. */
    const val GENERIC_VERIFICATION_TOOLSET_ID = "verification"

    /**
     * A catalog entry is a "verification" toolset when it's the generic [GENERIC_VERIFICATION_TOOLSET_ID]
     * set or a driver-specific `*_verification` set (`web_verification`, `revyl_verification`,
     * `compose_verification`). This naming convention is the contract between the verify-toolset YAML
     * files and verify-step scoping ([getToolDescriptorsForStep] for a [VerificationStep]): a new
     * driver's verify toolset just has to follow it to be picked up.
     */
    fun isVerificationToolsetId(id: String): Boolean =
      id == GENERIC_VERIFICATION_TOOLSET_ID || id.endsWith("_verification")

    /**
     * Creates a [TrailblazeToolRepo] carrying every catalog tool the driver can run, plus any
     * [customToolClasses] / [customYamlToolNames] / [customScriptedToolNames]. The full surface is
     * advertised from the start — there is no runtime opt-in.
     *
     * When [driverType] is non-null, resolution runs through
     * [TrailblazeToolSetCatalog.resolveForDriver] so catalog entries that declare incompatible
     * `drivers:` (e.g. `core_interaction.yaml` on Playwright) are filtered out. Leave null if the
     * driver isn't known — the catalog then resolves every entry regardless of driver.
     *
     * [customYamlToolNames] lets callers contribute YAML-defined tools (those without a
     * backing [KClass]) to the initial surface — symmetric with [customToolClasses] for
     * class-backed tools. Callers that only reference class-backed tools can leave it at
     * the default empty set.
     *
     * [excludedToolClasses] / [excludedYamlToolNames] / [excludedScriptedToolNames] are the
     * target's `excluded_tools:` opt-outs, one set per backing — each subtracted from the matching
     * surface so an excluded tool (e.g. the scripted `openUrl`) isn't served to the LLM. Callers
     * should populate all three from `getExcludedToolSurfaceForDriver` rather than wiring them
     * individually, so a backing can't be excluded here but forgotten elsewhere.
     */
    fun withDynamicToolSets(
      customToolClasses: Set<KClass<out TrailblazeTool>> = emptySet(),
      customYamlToolNames: Set<ToolName> = emptySet(),
      customScriptedToolNames: Set<ToolName> = emptySet(),
      excludedToolClasses: Set<KClass<out TrailblazeTool>> = emptySet(),
      excludedYamlToolNames: Set<ToolName> = emptySet(),
      excludedScriptedToolNames: Set<ToolName> = emptySet(),
      catalog: List<ToolSetCatalogEntry> = TrailblazeToolSetCatalog.defaultEntries(),
      driverType: TrailblazeDriverType? = null,
    ): TrailblazeToolRepo {
      // Every tool the driver can run is advertised up front — there is no progressive
      // disclosure / opt-in narrowing anymore (the LLM sees the full surface and it works;
      // browsing a catalog only added complexity). Resolving with every catalog id selects
      // all entries, still filtered to the driver's compatible set when [driverType] is known.
      val allIds = catalog.map { it.id }
      val allTools = if (driverType != null) {
        TrailblazeToolSetCatalog.resolveForDriver(driverType, allIds, catalog)
      } else {
        TrailblazeToolSetCatalog.resolve(allIds, catalog)
      }
      return TrailblazeToolRepo(
        trailblazeToolSet = TrailblazeToolSet.DynamicTrailblazeToolSet(
          name = "All Tools",
          toolClasses = allTools.toolClasses + customToolClasses - excludedToolClasses,
          yamlToolNames = allTools.yamlToolNames + customYamlToolNames - excludedYamlToolNames,
          scriptedToolNames = allTools.scriptedToolNames + customScriptedToolNames - excludedScriptedToolNames,
        ),
        toolSetCatalog = catalog,
        driverType = driverType,
      )
    }
  }

  // The verification toolset(s) whose tools a `verify:` step may use, scoped to this repo's
  // [driverType]: the generic `verification` set for Android on-device + iOS host, the
  // driver-specific `web_verification` / `revyl_verification` / `compose_verification` for the
  // rest. When [driverType] is null (test fixtures / driver-agnostic construction) we can't tell
  // which driver-specific toolset applies, so we fall back to the historical generic `verification`
  // toolset — preserving behavior for callers that never set a driver. Resolves via this repo's
  // configured [toolSetCatalog] when present (so a custom test catalog is reflected) and otherwise
  // the global [TrailblazeToolSetCatalog.defaultEntries]. Shared by [verifyTools] (the executable
  // registry top-up) and [verifyStepToolDescriptors] (the advertised surface) so the two can't drift.
  private fun verificationToolsetEntries(): List<ToolSetCatalogEntry> {
    val catalog = toolSetCatalog ?: TrailblazeToolSetCatalog.defaultEntries()
    val capturedDriver = driverType
    return if (capturedDriver == null) {
      catalog.filter { it.id == GENERIC_VERIFICATION_TOOLSET_ID }
    } else {
      catalog.filter { isVerificationToolsetId(it.id) && it.isCompatibleWith(capturedDriver) }
    }
  }

  // When running - verify: only provide the assertion tools and the objective status tool.
  // If you don't provide the objective status tool then the agent cannot complete the step.
  // Lazy so repos that never hit VerificationStep don't force catalog discovery.
  //
  // This is the CLASS-BACKED verify surface — every driver-compatible verification toolset's
  // class-backed assertion tools (generic `assertVisible` / `assertNotVisibleWithText`, plus
  // driver-specific ones like Revyl's class-backed `revyl_assert`) plus objectiveStatus. It is
  // added to the executor-routed Koog registry (the `asToolRegistry` overloads) so those tools are
  // always DISPATCHABLE on a verify step. It MUST use the same driver-scoped entry selection as
  // [verifyStepToolDescriptors] (the advertised surface): otherwise a driver-specific class-backed
  // verify tool could be advertised but not registered, stranding the agent under
  // `ToolChoice.Required`. The driver-specific YAML verify tools (`web_verifyTextVisible`, …) have
  // no backing KClass, so they still reach the registry as dynamic registrations (`advertisedDynamic`).
  private val verifyTools: Set<KClass<out TrailblazeTool>> by lazy {
    verificationToolsetEntries().flatMap { it.toolClasses }.toSet() +
      ObjectiveStatusTrailblazeTool::class
  }

  /**
   * Advertised tool descriptors for a `verify:` step — the driver-appropriate assertion/observation
   * tools plus objectiveStatus.
   *
   * Driver-aware on purpose (see [verificationToolsetEntries]): each driver builds its verify surface
   * from a different verification toolset. Android on-device + iOS host use the generic `verification`
   * toolset (class-backed `assertVisible` / `assertNotVisibleWithText`); web uses `web_verification`,
   * Revyl uses `revyl_verification`, Compose uses `compose_verification`. Some contribute YAML-defined
   * tools with no backing KClass. Returning only the generic class-backed set (the historical
   * behavior) advertised Android assertion tools on a web/Revyl verify step and dropped that driver's
   * real verify tools, so we build descriptors from both the class-backed and YAML-defined tools of
   * every verification toolset compatible with this repo's [driverType].
   *
   * objectiveStatus is always included: without it the agent can't end a verify step.
   */
  private fun verifyStepToolDescriptors(): List<ToolDescriptor> {
    val verifyEntries = verificationToolsetEntries()
    val classDescriptors = verifyEntries
      .flatMap { it.toolClasses }
      .toSet()
      .mapNotNull { it.toKoogToolDescriptor() }
    val yamlDescriptors = KoogToolExt.buildDescriptorsForYamlDefined(
      verifyEntries.flatMap { it.yamlToolNames }.toSet(),
    )
    val objectiveStatusDescriptor = ObjectiveStatusTrailblazeTool::class.toKoogToolDescriptor()
    return (classDescriptors + yamlDescriptors + listOfNotNull(objectiveStatusDescriptor))
      .distinctBy { it.name }
  }

  // On-demand "full screen" inspection for the in-process Koog agent loops (the
  // [xyz.block.trailblaze.mcp.AgentImplementation.KOOG_STRATEGY_GRAPH] path). The screen view the
  // agent sees after each action is the compact, interactable-only element list; this tool lets it
  // request the full ref-annotated list (all visible elements) when it needs more — non-interactable
  // labels for context, or an element the compact filter omitted. Added only to the two
  // `asToolRegistry` overloads (the Koog registries), so the legacy runner's tool surface is
  // unchanged. Read-only, so it never appears in recordings.
  //
  // Scoped to the same drivers as the `observation` toolset: host (iOS) and on-device (Android),
  // which expose a screen-inspection hierarchy AND whose agents execute a generic
  // ExecutableTrailblazeTool via runTrailblazeTools. Revyl is deliberately excluded — its agent
  // only runs RevylExecutableTool/objectiveStatus and has its own screen capture, so advertising
  // this tool there would let the model call something that can never return a hierarchy. Other
  // drivers (web, Compose, iOS AXe) aren't validated for it, so they're left out too. A null
  // driver (test fixtures) keeps it for convenience.
  private val koogInspectionTools: Set<KClass<out TrailblazeTool>> =
    if (driverType == null || driverType in KOOG_INSPECTION_DRIVERS) {
      setOf(RequestDetailedViewHierarchyTrailblazeTool::class)
    } else {
      emptySet()
    }

  // This function returns different tool descriptors based on the type of prompt step passed in.
  // The DirectionStep returns all registered trailblaze tool classes, while the VerificationStep
  // returns the driver-appropriate verify surface (see [verifyStepToolDescriptors]).
  fun getToolDescriptorsForStep(promptStep: PromptStep): List<ToolDescriptor> = when (promptStep) {
    is DirectionStep -> getCurrentToolDescriptors()
    is VerificationStep -> verifyStepToolDescriptors()
  }
}

/**
 * Re-align a scripted tool-call's arguments to the types its [descriptor] declares before the
 * dynamic source decodes them. Runs on every dynamic dispatch (recorded replay AND live/agent
 * calls), but is a no-op — returns [argumentsJson] verbatim — whenever every value already matches
 * its declared type, which is the norm for freshly-generated agent calls. It exists for recorded
 * replay: the YAML→JSON decode of a `.trail.yaml` step guesses a scalar's type from its content
 * (kaml discards the source quote style), so a quoted passcode `'12345678'` or flag value `'true'`
 * arrives as a JSON number/boolean and the JS handler crashes on the first string op.
 * [coerceArgsToDescriptorTypes] is the single, tool-agnostic replacement for the per-tool
 * `String(x)` casts. Non-fatal by construction: any parse hiccup falls back to the raw argument
 * JSON unchanged.
 *
 * Set [SCRIPTED_ARG_TYPE_COERCION_DISABLE_ENV]=1 to bypass (returns [argumentsJson] verbatim).
 */
private fun coerceScriptedArgsToDescriptor(
  argumentsJson: String,
  descriptor: TrailblazeToolDescriptor,
): String {
  if (scriptedArgTypeCoercionDisabled()) return argumentsJson
  return try {
    val obj = TrailblazeJsonInstance.parseToJsonElement(argumentsJson) as? JsonObject ?: return argumentsJson
    val coerced = coerceArgsToDescriptorTypes(obj, descriptor)
    if (coerced === obj) argumentsJson else TrailblazeJsonInstance.encodeToString(JsonObject.serializer(), coerced)
  } catch (t: Throwable) {
    Console.log("[scripted-arg-coerce] skipped for ${descriptor.name}: ${t.message}")
    argumentsJson
  }
}

private const val SCRIPTED_ARG_TYPE_COERCION_DISABLE_ENV = "TRAILBLAZE_DISABLE_SCRIPTED_ARG_TYPE_COERCION"

private fun scriptedArgTypeCoercionDisabled(): Boolean =
  System.getenv(SCRIPTED_ARG_TYPE_COERCION_DISABLE_ENV)?.trim()?.lowercase().let { it == "1" || it == "true" }
