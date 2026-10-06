package xyz.block.trailblaze.logs.client

import kotlinx.serialization.Serializable

/**
 * Optional metadata that can be attached to a session.
 * Not required for basic logging, but useful for context.
 *
 * ## Usage
 * ```kotlin
 * val metadata = SessionMetadata(
 *     testClassName = "MyTest",
 *     testMethodName = "testLogin",
 *     trailFilePath = "trails/login.yaml"
 * )
 * ```
 */
@Serializable
data class SessionMetadata(
  val testClassName: String? = null,
  val testMethodName: String? = null,
  val trailFilePath: String? = null,
  val customProperties: Map<String, String> = emptyMap(),
  /**
   * The running trail's `config.id`: the same value on every run of that trail. Null when the
   * session is not running a trail, or the trail declares no id.
   */
  val trailId: String? = null,
)
