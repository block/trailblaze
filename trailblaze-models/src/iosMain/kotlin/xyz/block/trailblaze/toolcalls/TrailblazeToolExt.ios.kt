package xyz.block.trailblaze.toolcalls

import kotlin.reflect.KClass

/**
 * iOS: Kotlin/Native has no annotation reflection (`KClass.annotations` is JVM-only), so this
 * degrades to `null` exactly as the wasmJs actual does. Class-backed tools are a host-side
 * registration mechanism; an on-device agent receives tools already resolved.
 */
internal actual fun KClass<out TrailblazeTool>.findTrailblazeToolClassAnnotationOrNull(): TrailblazeToolClass? = null
