package xyz.block.trailblaze.toolcalls

import kotlin.reflect.KClass

/**
 * Wasm/JS: annotation reflection isn't on the wasm stdlib surface, and class-backed tools
 * are never registered there — same degradation as `findTrailblazeToolClassAnnotation`
 * (TrailblazeToolPayload.wasmJs.kt).
 */
internal actual fun KClass<out TrailblazeTool>.findTrailblazeToolClassAnnotationOrNull(): TrailblazeToolClass? = null
