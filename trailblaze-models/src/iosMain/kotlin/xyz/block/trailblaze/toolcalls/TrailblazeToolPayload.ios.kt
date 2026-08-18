package xyz.block.trailblaze.toolcalls

/**
 * iOS: no annotation reflection on Kotlin/Native, same as wasmJs. Callers fall back to
 * `class.simpleName`, which keeps logs decodable.
 */
internal actual fun TrailblazeTool.findTrailblazeToolClassAnnotation(): TrailblazeToolClass? = null
