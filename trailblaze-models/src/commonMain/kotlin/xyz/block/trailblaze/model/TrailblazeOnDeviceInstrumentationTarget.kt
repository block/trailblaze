package xyz.block.trailblaze.model

import xyz.block.trailblaze.devices.TrailblazeDriverType

/**
 * The test app that will be used for running the tests.
 * It will start an instrumentation process and block the thread with a server running.
 */
data class TrailblazeOnDeviceInstrumentationTarget(
  val testAppId: String,
  val fqTestName: String,
  /**
   * When `true`, this test APK is built and installed by the app's own build (farm step or local
   * Gradle) rather than bundled inside the CLI binary — the in-process ANDROID_TEST harness is
   * the case. The host then instruments what's installed: no precompiled-APK install, no
   * bundled-SHA freshness check, and a missing install is an actionable error naming the APK
   * instead of an attempt to install a bundle that doesn't exist.
   */
  val installedExternally: Boolean = false,
  /**
   * The application id of the process the instrumentation actually runs **in**, when that is not
   * [testAppId].
   *
   * The bundled runner self-instruments — its manifest's `targetPackage` is its own package — so
   * a process named [testAppId] exists and `pidof` finds it. The in-process ANDROID_TEST harness
   * is a `com.android.test` module pointed at the app under test, so instrumentation loads into
   * the APP's process and nothing is ever named after the test package. Asking about [testAppId]
   * there answers "not running" for a server that is up and serving, which the connect path
   * reports as an infrastructure failure.
   *
   * Null keeps the self-instrumenting assumption, which is correct for every bundled runner.
   */
  val hostProcessAppId: String? = null,
  /**
   * The driver this harness exists to serve, forwarded to the device as
   * [TrailblazeDriverType.INSTRUMENTATION_ARG_KEY] when the host launches it.
   *
   * Set for a harness reachable ONLY by selecting that driver: the in-process ANDROID_TEST
   * harness resolves solely through
   * `TrailblazeHostAppTarget.getTrailblazeOnDeviceInstrumentationTargetForDriver(ANDROID_TEST)`,
   * so launching it already IS the operator's driver selection. Saying so on device is what lets
   * the runtime's pin gate tell a deliberate in-process run from a trail that merely arrived
   * pinned — without the arg, an accessibility-recorded estate trail is refused by
   * `AndroidTestTrailblazeRule.evaluateDriverPin` even though the operator named this driver.
   *
   * Null for the bundled runner, which serves whichever driver a run selects and must keep
   * honoring per-trail pins.
   */
  val forcedDriverType: TrailblazeDriverType? = null,
) {
  /**
   * The process to ask about when checking whether this instrumentation is running. Distinct from
   * [testAppId], which names the APK to install and the instrumentation to launch.
   *
   * Only meaningful when [processLivenessProvesInstrumentationAttached] — read that first.
   */
  val instrumentationProcessAppId: String get() = hostProcessAppId ?: testAppId

  /**
   * Whether a live process named [instrumentationProcessAppId] is proof that this instrumentation
   * is attached and serving.
   *
   * True for a self-instrumenting bundled runner: that process exists only because `am instrument`
   * created it, so `pidof` answering is proof.
   *
   * False for an in-process harness, where the process is the app under test. The app is running
   * whenever anyone launched it — from the launcher, from a previous trail, from another driver —
   * and none of that means instrumentation is attached. Taking it as proof skips both the
   * install check and the launch, and the run then binds to whatever else answers the device port.
   * Observed: with the app merely open and the bundled accessibility runner still resident, the
   * host reported ready in 276ms and dispatched an ANDROID_TEST trail to the accessibility
   * server, which hung until the run's no-progress timeout.
   */
  val processLivenessProvesInstrumentationAttached: Boolean get() = hostProcessAppId == null

  /** Empty Companion object to allow extension values */
  companion object {
    val DEFAULT_ANDROID_ON_DEVICE = TrailblazeOnDeviceInstrumentationTarget(
      testAppId = "xyz.block.trailblaze.runner",
      fqTestName = "xyz.block.trailblaze.AndroidStandaloneServerTest",
    )
  }
}
