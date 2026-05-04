package xyz.block.trailblaze.toolcalls

import kotlin.coroutines.cancellation.CancellationException
import kotlin.reflect.full.findAnnotation

/**
 * JVM/Android implementation: read [TrailblazeToolClass] via full Kotlin reflection.
 * Reflection errors are swallowed (returning `null`) so a tool whose class lacks the
 * annotation — or whose class-loading reflection trips an unrelated runtime issue — degrades
 * gracefully instead of throwing into encoder hot paths.
 *
 * Cooperative cancellation is preserved: `findAnnotation` triggers class-loading, which can
 * run user-side static initializers and (in pathological cases) participate in coroutine
 * cancellation. We re-throw [CancellationException] explicitly rather than silencing it —
 * `runCatching {…}.getOrNull()` here would otherwise swallow it and break the suspend chain
 * the same way the broad-`Exception` catches elsewhere in this PR were fixed.
 */
internal actual fun TrailblazeTool.findTrailblazeToolClassAnnotation(): TrailblazeToolClass? =
  try {
    this::class.findAnnotation<TrailblazeToolClass>()
  } catch (e: CancellationException) {
    throw e
  } catch (_: Exception) {
    null
  }
