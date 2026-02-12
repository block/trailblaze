package xyz.block.trailblaze.tracing

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
expect object PlatformIds {
  fun pid(): Long
  fun tid(): Long
  fun threadName(): String? // optional, used to emit metadata
  fun processName(): String? // optional, used to emit metadata
}
