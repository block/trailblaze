package xyz.block.trailblaze.toolcalls

import ai.koog.agents.core.tools.ToolDescriptor
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.config.ToolYamlConfig
import xyz.block.trailblaze.config.YamlDefinedToolSerializer
import xyz.block.trailblaze.config.YamlDefinedTrailblazeTool
import xyz.block.trailblaze.config.toTrailblazeToolDescriptor
import xyz.block.trailblaze.logs.client.TrailblazeSerializationInitializer
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool.Companion.toKoogToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool.Companion.toTrailblazeToolDescriptor
import xyz.block.trailblaze.util.Console
import kotlin.reflect.KClass
import kotlin.reflect.full.hasAnnotation

object KoogToolExt {
  fun KClass<*>.hasSerializableAnnotation(): Boolean = this.hasAnnotation<Serializable>()

  fun Set<KClass<out TrailblazeTool>>.toKoogTools(
    trailblazeToolContextProvider: () -> TrailblazeToolExecutionContext,
  ): List<TrailblazeKoogTool<out TrailblazeTool>> = this
    .filter { it.trailblazeToolClassAnnotation().surfaceToLlm }
    .map { trailblazeToolClass ->
      trailblazeToolClass.toKoogTool(
        trailblazeToolContextProvider = trailblazeToolContextProvider,
      )
    }

  fun KClass<out TrailblazeTool>.toKoogTool(
    trailblazeToolContextProvider: () -> TrailblazeToolExecutionContext,
  ): TrailblazeKoogTool<out TrailblazeTool> = TrailblazeKoogTool(this) { args: TrailblazeTool ->
    val executionContext = trailblazeToolContextProvider()
    val trailblazeToolResult: TrailblazeToolResult = if (args is ExecutableTrailblazeTool) {
      args.execute(executionContext)
    } else {
      error("Tool $this does not implement ExecutableTrailblazeTool interface, cannot convert to Maestro commands")
    }
    buildString {
      append("Executed tool: ${args::class.simpleName} and result was trailblazeToolResult: $trailblazeToolResult")
    }
  }

  /**
   * Executor-routed variant of [toKoogTools]. Instead of calling `tool.execute(context)`
   * directly (which throws for driver-specific tools like [PlaywrightExecutableTool] /
   * `ComposeExecutableTool` whose `execute` is implemented only via their agent), each tool
   * call is dispatched through [toolDispatcher] — typically `agent.runTrailblazeTools(...)`.
   * That route is what gives driver-correct execution (Playwright thread affinity + settle,
   * node-selector enrichment) plus the side-effect `logToolExecution` / session logging the
   * in-process Koog-strategy-graph agent relies on.
   */
  fun Set<KClass<out TrailblazeTool>>.toKoogToolsWithExecutor(
    toolDispatcher: suspend (TrailblazeTool) -> String,
  ): List<TrailblazeKoogTool<out TrailblazeTool>> = this
    .filter { it.trailblazeToolClassAnnotation().surfaceToLlm }
    .map { trailblazeToolClass ->
      TrailblazeKoogTool(trailblazeToolClass) { args: TrailblazeTool ->
        // The dispatcher returns the full LLM-facing message — including the fresh post-action
        // screen — so the agent perceives the latest screen via Koog's native tool-result
        // channel. The prune node then keeps only this latest hierarchy in context.
        toolDispatcher(args)
      }
    }

  /**
   * Executor-routed variant of [buildKoogToolsForYamlDefined]. Mirrors
   * [toKoogToolsWithExecutor]: each YAML-defined tool call is dispatched through
   * [toolDispatcher] (the agent) rather than executed inline against a context.
   */
  fun buildKoogToolsForYamlDefinedWithExecutor(
    yamlToolNames: Set<ToolName>,
    toolDispatcher: suspend (TrailblazeTool) -> String,
  ): List<TrailblazeKoogTool<out TrailblazeTool>> {
    if (yamlToolNames.isEmpty()) return emptyList()
    val configsByName = TrailblazeSerializationInitializer.buildYamlDefinedTools()
    return yamlToolNames.mapNotNull { name ->
      val config = configsByName[name]
      if (config == null) {
        Console.log(
          "buildKoogToolsForYamlDefinedWithExecutor: no YAML config registered for tool " +
            "'${name.toolName}' — skipping.",
        )
        return@mapNotNull null
      }
      if (config.surfaceToLlm == false) return@mapNotNull null
      val descriptor = config.toTrailblazeToolDescriptor().toKoogToolDescriptor(strict = true)
      val serializer = YamlDefinedToolSerializer(config)
      TrailblazeKoogTool(
        argsSerializer = serializer,
        descriptor = descriptor,
        executeTool = { args: YamlDefinedTrailblazeTool ->
          toolDispatcher(args)
        },
      )
    }
  }

  /**
   * Builds Koog tools for YAML-defined (`tools:` mode) tools referenced by name. Each tool's
   * descriptor and arg serializer come from the [ToolYamlConfig] loaded at startup; each tool
   * shares the [YamlDefinedTrailblazeTool] implementation class, differentiated at execute time
   * by the config captured in its serializer.
   *
   * Tools whose config sets `surface_to_llm: false` are filtered out — same rule the
   * class-backed path applies via `@TrailblazeToolClass(surfaceToLlm = false)` ([toKoogTools]
   * above). The `tools:` mode default is `null = treat as true`, mirroring the annotation
   * default.
   *
   * Names without a matching config (e.g. a typo in a toolset's `yamlToolNames`) log a warning
   * and are skipped rather than crashing registration.
   */
  fun buildKoogToolsForYamlDefined(
    yamlToolNames: Set<ToolName>,
    trailblazeToolContextProvider: () -> TrailblazeToolExecutionContext,
  ): List<TrailblazeKoogTool<out TrailblazeTool>> = buildKoogToolsForYamlDefined(
    yamlToolNames = yamlToolNames,
    configsByName = TrailblazeSerializationInitializer.buildYamlDefinedTools(),
    trailblazeToolContextProvider = trailblazeToolContextProvider,
  )

  /**
   * Test seam over [buildKoogToolsForYamlDefined] that lets callers inject a synthetic
   * [configsByName] map instead of going through the cached classpath scan in
   * [TrailblazeSerializationInitializer.buildYamlDefinedTools]. Production code should use
   * the public single-arg overload above.
   */
  internal fun buildKoogToolsForYamlDefined(
    yamlToolNames: Set<ToolName>,
    configsByName: Map<ToolName, ToolYamlConfig>,
    trailblazeToolContextProvider: () -> TrailblazeToolExecutionContext,
  ): List<TrailblazeKoogTool<out TrailblazeTool>> {
    if (yamlToolNames.isEmpty()) return emptyList()
    return yamlToolNames.mapNotNull { name ->
      val config = configsByName[name]
      if (config == null) {
        Console.log(
          "buildKoogToolsForYamlDefined: no YAML config registered for tool '${name.toolName}' — skipping. " +
            "Either the referencing toolset has a typo or the tool's YAML resource is missing " +
            "from the classpath.",
        )
        return@mapNotNull null
      }
      if (config.surfaceToLlm == false) return@mapNotNull null
      buildYamlDefinedKoogTool(config, trailblazeToolContextProvider)
    }
  }

  /**
   * Builds LLM-facing [ToolDescriptor]s for YAML-defined tools referenced by name — without
   * wiring up the execute side. Used when the LLM request needs to list YAML-defined tools
   * alongside class-backed ones (e.g. [TrailblazeToolRepo.getCurrentToolDescriptors]) and no
   * execution context is available yet.
   *
   * Same `surface_to_llm: false` filter as [buildKoogToolsForYamlDefined].
   *
   * Skips unknown names with a warning, same as [buildKoogToolsForYamlDefined].
   */
  fun buildDescriptorsForYamlDefined(yamlToolNames: Set<ToolName>): List<ToolDescriptor> =
    buildDescriptorsForYamlDefined(
      yamlToolNames = yamlToolNames,
      configsByName = TrailblazeSerializationInitializer.buildYamlDefinedTools(),
    )

  /** Test seam mirroring the seam on [buildKoogToolsForYamlDefined]. */
  internal fun buildDescriptorsForYamlDefined(
    yamlToolNames: Set<ToolName>,
    configsByName: Map<ToolName, ToolYamlConfig>,
  ): List<ToolDescriptor> {
    if (yamlToolNames.isEmpty()) return emptyList()
    return yamlToolNames.mapNotNull { name ->
      val config = configsByName[name]
      if (config == null) {
        Console.log(
          "buildDescriptorsForYamlDefined: no YAML config registered for tool '${name.toolName}' — skipping.",
        )
        return@mapNotNull null
      }
      if (config.surfaceToLlm == false) return@mapNotNull null
      // YAML-defined tools: author-controlled schema via `ToolYamlConfig.parameters[*].type`,
      // so unknown types are a config bug worth erroring on at registration.
      config.toTrailblazeToolDescriptor().toKoogToolDescriptor(strict = true)
    }
  }

  /**
   * Trailblaze-native descriptor variant of [buildDescriptorsForYamlDefined] that preserves
   * source metadata for catalog/debug surfaces. The Koog descriptor path intentionally drops
   * this metadata because execution does not depend on it.
   */
  fun buildTrailblazeDescriptorsForYamlDefined(yamlToolNames: Set<ToolName>): List<TrailblazeToolDescriptor> =
    buildTrailblazeDescriptorsForYamlDefined(
      yamlToolNames = yamlToolNames,
      configsByName = TrailblazeSerializationInitializer.buildYamlDefinedTools(),
    )

  internal fun buildTrailblazeDescriptorsForYamlDefined(
    yamlToolNames: Set<ToolName>,
    configsByName: Map<ToolName, ToolYamlConfig>,
  ): List<TrailblazeToolDescriptor> {
    if (yamlToolNames.isEmpty()) return emptyList()
    return yamlToolNames.mapNotNull { name ->
      val config = configsByName[name]
      if (config == null) {
        Console.log(
          "buildTrailblazeDescriptorsForYamlDefined: no YAML config registered for tool '${name.toolName}' — skipping.",
        )
        return@mapNotNull null
      }
      if (config.surfaceToLlm == false) return@mapNotNull null
      config.toTrailblazeToolDescriptor()
    }
  }

  private fun buildYamlDefinedKoogTool(
    config: ToolYamlConfig,
    trailblazeToolContextProvider: () -> TrailblazeToolExecutionContext,
  ): TrailblazeKoogTool<YamlDefinedTrailblazeTool> {
    val descriptor = config.toTrailblazeToolDescriptor().toKoogToolDescriptor(strict = true)
    val serializer = YamlDefinedToolSerializer(config)
    return TrailblazeKoogTool(
      argsSerializer = serializer,
      descriptor = descriptor,
      executeTool = { args: YamlDefinedTrailblazeTool ->
        val ctx = trailblazeToolContextProvider()
        val expanded = args.toExecutableTrailblazeTools(ctx)
        val results = expanded.map { it.execute(ctx) }
        buildString {
          append("Executed YAML-defined tool: ${config.id} — expanded to ")
          append("${expanded.size} primitive(s). Results: $results")
        }
      },
    )
  }
}

fun KClass<out TrailblazeTool>.toTrailblazeToolDescriptorWithSource(): TrailblazeToolDescriptor? =
  toKoogToolDescriptor()?.toTrailblazeToolDescriptor()?.copy(
    source = TrailblazeToolSourceDescriptor(
      type = TrailblazeToolSourceType.KOTLIN,
      identifier = qualifiedName,
      className = qualifiedName,
    ),
  )
