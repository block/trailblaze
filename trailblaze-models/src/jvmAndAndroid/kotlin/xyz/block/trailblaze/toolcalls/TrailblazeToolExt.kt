package xyz.block.trailblaze.toolcalls

import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation

/**
 * Extracts the [TrailblazeToolClass] annotation from a [TrailblazeTool] class.
 */
fun KClass<out TrailblazeTool>.trailblazeToolClassAnnotation(): TrailblazeToolClass =
  this.findAnnotation<TrailblazeToolClass>()
    ?: error("Please add @TrailblazeToolClass to $this")

/**
 * Extracts tool name from a [TrailblazeTool] class.
 */
fun KClass<out TrailblazeTool>.toolName(): ToolName = ToolName(this.trailblazeToolClassAnnotation().name)

/**
 * Whether this tool requires host-side execution (e.g., ADB, USB hardware).
 */
fun KClass<out TrailblazeTool>.requiresHost(): Boolean = this.trailblazeToolClassAnnotation().requiresHost
