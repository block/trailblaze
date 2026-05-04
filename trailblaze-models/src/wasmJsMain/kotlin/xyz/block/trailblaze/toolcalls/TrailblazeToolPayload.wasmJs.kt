package xyz.block.trailblaze.toolcalls

/**
 * Wasm/JS implementation: `KClass.annotations` isn't on the wasm stdlib surface. The wasm UI
 * is decode-only (logs always materialize as `OtherTrailblazeTool`, never round-tripped
 * through this encoder) so we degrade gracefully — the caller falls back to `class.simpleName`,
 * which is sufficient for any future wasm-side encoding paths and keeps logs decodable.
 */
internal actual fun TrailblazeTool.findTrailblazeToolClassAnnotation(): TrailblazeToolClass? = null
