package xyz.block.trailblaze.toolcalls

import ai.koog.agents.core.tools.ToolDescriptor
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool.Companion.toKoogToolDescriptor
import xyz.block.trailblaze.toolcalls.TrailblazeKoogTool.Companion.toTrailblazeToolDescriptor
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
}

fun KClass<out TrailblazeTool>.toTrailblazeToolDescriptorWithSource(): TrailblazeToolDescriptor? =
  toKoogToolDescriptor()?.toTrailblazeToolDescriptor()?.withKotlinSource(qualifiedName)

/**
 * Same as [toTrailblazeToolDescriptorWithSource], but describes tools the LLM is not shown.
 *
 * `@TrailblazeToolClass(surfaceToLlm = false)` means "don't let the model pick this", not "this
 * does not exist". Documenting a tool someone named explicitly — a `--help` lookup, or a step
 * read back out of a recording — is the second thing, so it must not go through the gate that
 * composes the agent's toolbox. Use [toTrailblazeToolDescriptorWithSource] for anything the LLM
 * chooses from.
 *
 * Selector params come back too ([withSelectorParamsRestored]). Both descriptor paths strip them,
 * which is right for a caller composing a toolbox and wrong for one describing a tool to a person:
 * `tapOn` without its `nodeSelector` reads as a tap with nothing to tap.
 *
 * And the result is reconciled against the tool's serializer ([withSerializedParameterNames]),
 * because what a person is shown has to be what a trail step decodes. The two differ only for a
 * tool with a hand-written serializer.
 */
fun KClass<out TrailblazeTool>.toTrailblazeToolDescriptorIgnoringLlmSurface(): TrailblazeToolDescriptor? =
  toScriptedToolDescriptor()
    ?.toTrailblazeToolDescriptor()
    ?.withSelectorParamsRestored(this)
    ?.withSerializedParameterNames(this)
    ?.withKotlinSource(qualifiedName)

private fun TrailblazeToolDescriptor.withKotlinSource(qualifiedName: String?): TrailblazeToolDescriptor =
  copy(
    source = TrailblazeToolSourceDescriptor(
      type = TrailblazeToolSourceType.KOTLIN,
      identifier = qualifiedName,
      className = qualifiedName,
    ),
  )
