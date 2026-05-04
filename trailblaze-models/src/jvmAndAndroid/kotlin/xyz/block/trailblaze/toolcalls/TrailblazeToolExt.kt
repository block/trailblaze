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

/**
 * Whether this tool is a read-only verification (assertion) tool whose successful execution
 * is itself the verify verdict (e.g. `assertVisible`, `web_verify_text_visible`). Used by
 * `blaze(hint=VERIFY)` to gate which LLM-recommended tools may execute.
 */
fun KClass<out TrailblazeTool>.isVerification(): Boolean = this.trailblazeToolClassAnnotation().isVerification
