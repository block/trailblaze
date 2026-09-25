package xyz.block.trailblaze.mcp

import xyz.block.trailblaze.devices.TrailblazeDriverType

/**
 * The message a failed host-local tool carries to whoever called it.
 *
 * A scripted tool that throws reports `Error: <message>` followed by its JS stack. When that tool
 * was itself called from a scripted tool, the SDK rethrows the failure as
 * `trailblaze.client.callTool("<inner>") tool failed: <inner message>` and the outer tool's
 * envelope appends its own stack. Every level of nesting therefore prepended one more wrapper and
 * appended one more stack: three tools deep, the cause sat 400 characters into a single line and
 * the stack was printed three times.
 *
 * [describe] leads with the innermost cause, lists the nested tools the failure passed through on
 * one `via` line, and keeps only the first JS stack frame, which is where the throw happened. A
 * failure that did not pass through a nested call is returned as-is, minus the generic `Error:`
 * header, so a directly called tool's full stack still reaches the session log.
 *
 * Lives here rather than in the host module because both places a host-local failure reaches a
 * caller have to use it: the bridge's nested dispatches and the daemon's inline path for a
 * first-class MCP `tools/call`.
 */
object HostLocalToolFailure {
  /**
   * The wrappers Trailblaze itself adds around a tool failure: the host-local and on-device
   * dispatches, and the per-driver one that names a [TrailblazeDriverType]. Enumerated rather
   * than matched as "any word", because any word also matches a tool's own message — a tool
   * reporting `Payment tool execution failed: declined` would be cut down to `declined`.
   */
  private val WRAPPER_PREFIX = Regex(
    (listOf("Host-local", "On-device") + TrailblazeDriverType.entries.map { it.name })
      .joinToString(separator = "|", prefix = "^(?:", postfix = ") tool execution failed: ") { Regex.escape(it) },
  )

  /** The SDK's rethrow of a nested tool's failure; group 1 is the nested tool's name. */
  private val NESTED_CALL = Regex("""^trailblaze\.client\.callTool\("([^"]+)"\) tool failed: """)

  /** The envelope header of a plain `Error`; typed errors (`TypeError: …`) keep their name. */
  private const val GENERIC_HEADER = "Error: "

  /**
   * A JS stack frame, in both shapes Bun writes: `at <name> (<file>:<line>:<column>)` and — for a
   * frame with no function name, typically the module's top level — the bare `at
   * <file>:<line>:<column>`.
   *
   * The line:column pair is what makes this the appended JS stack and not a line of a tool's own
   * output: a tool that runs a command (`exec`) puts that command's unfiltered output in the
   * failure message, and a JVM subprocess's `at com.example.Thing.method(Thing.kt:42)` frames are
   * diagnostic content, not a wrapper stack. They have no column, so they do not match here and
   * survive as message lines.
   *
   * The bare shape additionally requires a `.` or `/` in the file part, so a message line that
   * happens to end in a clock time (`at 12:30:45`) is not read as a frame.
   */
  private val STACK_FRAME = Regex("""^\s+at\s+(?:.*\(.*:\d+:\d+\)|\S*[./]\S*:\d+:\d+)$""")
  private const val STACK_TRUNCATED = "...[stack truncated]"

  private const val VIA_PREFIX = "  via "
  private const val VIA_SEPARATOR = " → "
  private const val FRAME_INDENT = "    "

  fun describe(errorMessage: String): String {
    val via = mutableListOf<String>()
    var rest = errorMessage
    while (true) {
      rest = stripWrappers(rest)
      val nested = NESTED_CALL.find(rest) ?: break
      via += nested.groupValues[1]
      rest = rest.substring(nested.range.last + 1)
    }
    if (via.isEmpty()) return rest

    val message = mutableListOf<String>()
    var throwSite: String? = null
    for (line in rest.lines()) {
      when {
        line.startsWith(VIA_PREFIX) -> via += line.removePrefix(VIA_PREFIX).split(VIA_SEPARATOR)
        STACK_FRAME.matches(line) -> if (throwSite == null) throwSite = line.trim()
        line == STACK_TRUNCATED -> Unit
        else -> message += line
      }
    }
    return buildString {
      append(message.joinToString("\n").trimEnd())
      append('\n').append(VIA_PREFIX).append(via.joinToString(VIA_SEPARATOR))
      if (throwSite != null) append('\n').append(FRAME_INDENT).append(throwSite)
    }
  }

  private fun stripWrappers(text: String): String {
    var rest = text
    while (true) {
      val before = rest
      rest = WRAPPER_PREFIX.replaceFirst(rest, "").removePrefix(GENERIC_HEADER)
      if (rest == before) return rest
    }
  }
}
