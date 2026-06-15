package xyz.block.trailblaze.toolcalls.commands

import kotlinx.serialization.Serializable

/**
 * How an assertVisible text check compares the captured [expectedText] against the live
 * element text at replay. [EXACT] is the default so trails without the field keep their
 * original strict-equality behavior.
 */
@Serializable
enum class TextMatchMode {
  EXACT,
  PREFIX,
  REGEX,
}
