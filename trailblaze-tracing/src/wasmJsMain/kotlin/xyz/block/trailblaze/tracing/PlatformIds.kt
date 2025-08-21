package xyz.block.trailblaze.tracing

/**
 * Placeholder WASM implementation
 */
actual object PlatformIds {
  actual fun pid(): Long = 1L // numeric PID required by trace format
  actual fun tid(): Long = 1L // single thread
  actual fun threadName(): String? = "main"
  actual fun processName(): String? = "wasm"
}
