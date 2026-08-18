package xyz.block.trailblaze.toolcalls

import kotlin.reflect.KClass

/**
 * Extracts the [TrailblazeToolClass] annotation from a [TrailblazeTool] class.
 */
fun KClass<out TrailblazeTool>.trailblazeToolClassAnnotation(): TrailblazeToolClass =
  this.findTrailblazeToolClassAnnotationOrNull()
    ?: error("Please add @TrailblazeToolClass to $this")

/**
 * Platform-specific class-level [TrailblazeToolClass] annotation lookup — the [KClass]
 * counterpart of [findTrailblazeToolClassAnnotation] (instance-level, TrailblazeToolPayload.kt),
 * split the same way because annotation reflection isn't on the commonMain stdlib surface:
 * - JVM/Android: `kotlin.reflect.full.findAnnotation`.
 * - Wasm/JS: always `null` — class-backed tools are never registered or dispatched there,
 *   so [trailblazeToolClassAnnotation]'s missing-annotation error is the correct outcome
 *   should a wasm code path ever reach it.
 */
internal expect fun KClass<out TrailblazeTool>.findTrailblazeToolClassAnnotationOrNull(): TrailblazeToolClass?

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
 * is itself the verify verdict (e.g. `assertVisible`, `web_verifyTextVisible`). Used by
 * `blaze(hint=VERIFY)` to gate which LLM-recommended tools may execute.
 */
fun KClass<out TrailblazeTool>.isVerification(): Boolean = this.trailblazeToolClassAnnotation().isVerification
