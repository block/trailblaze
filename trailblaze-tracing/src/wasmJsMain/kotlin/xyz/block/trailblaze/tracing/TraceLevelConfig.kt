package xyz.block.trailblaze.tracing

/**
 * The browser has no environment to read, and nothing in the report viewer records spans — it only
 * displays ones a run already recorded. The default is the only reachable answer.
 */
internal actual fun configuredTraceLevel(): TraceLevel = TraceLevel.NORMAL
