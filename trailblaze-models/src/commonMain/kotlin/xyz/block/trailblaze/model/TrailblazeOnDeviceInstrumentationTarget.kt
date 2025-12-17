package xyz.block.trailblaze.model

/**
 * The test app that will be used for running the tests.
 * It will start an instrumentation process and block the thread with a server running.
 */
data class TrailblazeOnDeviceInstrumentationTarget(
  val testAppId: String,
  val fqTestName: String,
) {
  /** Empty Companion object to allow extension values */
  companion object {
    val DEFAULT_ANDROID_ON_DEVICE = TrailblazeOnDeviceInstrumentationTarget(
      testAppId = "xyz.block.trailblaze.runner",
      fqTestName = "xyz.block.trailblaze.AndroidStandaloneServerTest",
    )

    val BlockOnDeviceInstrumentationTarget = TrailblazeOnDeviceInstrumentationTarget(
      testAppId = "xyz.block.trailblaze.runner",
      fqTestName = "xyz.block.trailblaze.block.BlockAndroidStandaloneServerTest",
    )
  }
}
