package xyz.block.trailblaze.logs.model

import kotlinx.datetime.Clock
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

  companion object {
    /**
     * Generates a new unique session ID using the current timestamp.
     *
     * Format: "session_{timestamp}" for readability and uniqueness.
     */
    fun generate(): SessionId = SessionId("session_${Clock.System.now().toEpochMilliseconds()}")
  }
}
