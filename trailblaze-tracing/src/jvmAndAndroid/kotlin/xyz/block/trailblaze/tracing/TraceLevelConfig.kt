package xyz.block.trailblaze.tracing

internal actual fun configuredTraceLevel(): TraceLevel = resolveTraceLevel(
  property = System.getProperty(TRACE_LEVEL_PROPERTY),
  // Guarded: a restrictive SecurityManager turns an environment lookup into a throw, and failing to
  // start because nobody asked for tracing would be absurd.
  env = runCatching { System.getenv(TRACE_LEVEL_ENV) }.getOrNull(),
  // Printed rather than logged: this runs before any logging is configured.
  warn = ::println,
)
