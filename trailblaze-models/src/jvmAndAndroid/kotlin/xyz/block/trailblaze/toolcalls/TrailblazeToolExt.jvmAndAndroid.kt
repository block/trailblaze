package xyz.block.trailblaze.toolcalls

import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation

/**
 * JVM/Android: read the class-level annotation via full Kotlin reflection. No exception
 * swallowing — a reflection failure here is a programming error surfaced at registration
 * time, unlike the encoder hot path in TrailblazeToolPayload.
 */
internal actual fun KClass<out TrailblazeTool>.findTrailblazeToolClassAnnotationOrNull(): TrailblazeToolClass? =
  this.findAnnotation<TrailblazeToolClass>()
