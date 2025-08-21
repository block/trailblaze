package xyz.block.trailblaze.tracing

expect object PlatformIds {
  fun pid(): Long
  fun tid(): Long
  fun threadName(): String? // optional, used to emit metadata
  fun processName(): String? // optional, used to emit metadata
}
