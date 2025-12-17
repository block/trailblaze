package xyz.block.trailblaze.logs.model

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * Type-safe wrapper for session identifiers.
 * Using an inline value class provides compile-time type safety with zero runtime overhead.
 */
@Serializable
@JvmInline
value class SessionId(val value: String) {
  init {
    require(value.isNotBlank()) { "SessionId cannot be blank" }
  }

  override fun toString(): String = value
}
