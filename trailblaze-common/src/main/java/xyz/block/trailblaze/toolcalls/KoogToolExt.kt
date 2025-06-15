package xyz.block.trailblaze.toolcalls

import kotlinx.serialization.Serializable
import kotlin.reflect.KClass
import kotlin.reflect.full.hasAnnotation

object KoogToolExt {
  fun KClass<*>.hasSerializableAnnotation(): Boolean = this.hasAnnotation<Serializable>()

  fun Set<KClass<out TrailblazeTool>>.toKoogTools(
    trailblazeToolContextProvider: () -> TrailblazeToolExecutionContext,
  ): List<TrailblazeKoogTool<out TrailblazeTool>> = this.map { trailblazeToolClass ->
    trailblazeToolClass.toKoogTool(
      trailblazeToolContextProvider = trailblazeToolContextProvider,
    )
  }

  fun KClass<out TrailblazeTool>.toKoogTool(
    trailblazeToolContextProvider: () -> TrailblazeToolExecutionContext,
  ): TrailblazeKoogTool<out TrailblazeTool> = TrailblazeKoogTool(this) { args: TrailblazeTool ->
    val context = trailblazeToolContextProvider()
    val trailblazeToolResult: TrailblazeToolResult = if (args is ExecutableTrailblazeTool) {
      args.execute(context)
    } else {
      error("Tool $this does not implement ExecutableTrailblazeTool interface, cannot convert to Maestro commands")
    }
    buildString {
      append("Executed tool: ${args::class.simpleName} and result was trailblazeToolResult: $trailblazeToolResult")
    }
  }
}
