package xyz.block.trailblaze.tracing

/**
 * How much of a run gets recorded.
 *
 * Detail is not free, so it is asked for. The spans at [NORMAL] are the ones worth having on every
 * run — tools, agent phases, LLM calls, HTTP — and each of them wraps work measured in tens of
 * milliseconds or more, so the recording cost is lost in the noise. [VERBOSE] opens up the layers
 * underneath: a driver's individual operations, a screen capture's internals, per-node selector
 * matching. Those can fire hundreds of times per step, and a span that costs more than the work it
 * measures does not just slow the run — it changes the shape of what you are trying to profile.
 *
 * The levels nest: [VERBOSE] records everything [NORMAL] does.
 */
enum class TraceLevel {
  /** Record nothing. `trace { }` calls its block and adds no events. */
  OFF,

  /** The default. Tools, agent phases, LLM calls and HTTP requests. */
  NORMAL,

  /** Everything, including the fine-grained spans `traceDetail { }` guards. */
  VERBOSE,
  ;

  companion object {
    /**
     * Reads a configured value, or null when [raw] is absent or unrecognized.
     *
     * Null rather than a fallback, so a caller can tell "not configured" from "configured wrong"
     * and warn about the second — a typo that silently means "default" is how a run ends up with no
     * detail and nobody knowing why.
     */
    fun parse(raw: String?): TraceLevel? {
      val value = raw?.trim()?.lowercase() ?: return null
      return when (value) {
        "off", "none", "false", "0" -> OFF
        "normal", "on", "true", "1", "default" -> NORMAL
        "verbose", "detail", "detailed", "all", "2" -> VERBOSE
        else -> null
      }
    }
  }
}

/**
 * The level this process starts at, from its environment.
 *
 * Read once at startup rather than per span: this sits inside `trace { }`, which is on the hot path
 * by construction, and an environment lookup there would cost more than the recording it gates.
 */
internal expect fun configuredTraceLevel(): TraceLevel

/** The name of the system property that sets the level, checked before [TRACE_LEVEL_ENV]. */
internal const val TRACE_LEVEL_PROPERTY: String = "trailblaze.trace.level"

internal const val TRACE_LEVEL_ENV: String = "TRAILBLAZE_TRACE_LEVEL"

/**
 * Picks the level from the two places it can be configured.
 *
 * Separate from [configuredTraceLevel] so the precedence and the warning are testable without an
 * environment: an `actual` that reads `System.getenv` directly can only be tested for whatever the
 * test JVM happened to inherit.
 *
 * [property] wins, so a single run can override the environment it inherits. A value neither of them
 * recognizes goes to [warn] and falls back to [TraceLevel.NORMAL] — an unrecognized value is a typo,
 * not a request for the default, and saying so is the difference between "I asked for verbose and got
 * nothing" taking a minute or an afternoon.
 */
internal fun resolveTraceLevel(
  property: String?,
  env: String?,
  warn: (String) -> Unit,
): TraceLevel {
  // Blank is treated as absent, not as a typo: an exported-but-empty variable is the shell's normal
  // way of saying nothing, and warning about it would cry wolf on every run in that shell.
  val raw = property?.takeIf { it.isNotBlank() } ?: env?.takeIf { it.isNotBlank() }
  return TraceLevel.parse(raw) ?: run {
    if (raw != null) {
      warn("[TrailblazeTracer] ignoring unrecognized trace level \"$raw\" — expected off, normal or verbose")
    }
    TraceLevel.NORMAL
  }
}
