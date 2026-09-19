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
  /**
   * Whether the process this instrumentation loads into belongs to Trailblaze rather than to an
   * app under test.
   *
   * True only for Trailblaze's own standalone runner APK, which self-instruments and contains no
   * product code. False for an in-process harness — including a retargeted in-process shell — whose
   * instrumented process IS the app being tested.
   *
   * What it gates: process-wide platform relaxations that are acceptable on Trailblaze's own
   * process but would change what the app under test is allowed to do. `am instrument
   * --no-hidden-api-checks` is the case — it lifts hidden-API enforcement for the whole
   * instrumented process, so applying it to an app under test would let that app call a blocklisted
   * hidden API under Trailblaze and crash in production, with Trailblaze silently weakening the
   * platform contract for the very thing it is testing.
   *
   * Defaults to false so that a harness added later has to claim this deliberately: the cost of a
   * wrong `false` is losing an optimization, and the cost of a wrong `true` is a test run that no
   * longer reflects production.
   */
  val instrumentationProcessIsTrailblazeOwned: Boolean = false,
) {
  /**
   * The pre-[instrumentationProcessIsTrailblazeOwned] constructor and `copy` descriptors, kept so
   * this module's published artifact stays binary-compatible — the same shim, for the same reason,
   * as `TrailRecordingResolution`'s and `ToolUsageResult`'s.
   *
   * Defaulting the new parameter does NOT preserve the old JVM signatures: it REPLACES
   * `<init>(String,String,Z,String,TrailblazeDriverType)V` and the five-argument
   * `copy`/`copy$default` with six-argument forms, so a consumer compiled against an earlier
   * artifact throws `NoSuchMethodError` on upgrade.
   *
   * [DeprecationLevel.HIDDEN] rather than plain overloads: it emits the bytecode while removing the
   * members from source resolution. As plain overloads, `copy(testAppId = other)` would be
   * applicable to both and Kotlin picks the SHORTER one, so ordinary source would silently drop the
   * ownership flag — and dropping it to `false` on the bundled runner is exactly the dead-recovery
   * bug this field exists to fix.
   *
   * `false` is the behavior-preserving value for the constructor: before this field existed the
   * relaxation reached nothing, so a caller that could not name it got no relaxation either way.
   *
   * The compat constructor **repeats the old parameter defaults**, and that is the load-bearing
   * part rather than tidiness. Restoring only the plain five-argument descriptor fixes just the
   * callers that passed all five explicitly; a compiled Kotlin caller that omitted any defaulted
   * argument does not call that descriptor at all — it calls the synthetic
   * `(…, int, DefaultConstructorMarker)` mask overload, which only exists if this declaration
   * carries defaults of its own. Both descriptors are in the API baseline; check for the
   * `DefaultConstructorMarker` one there before assuming this is redundant.
   *
   * Nothing in this repo can call them, so nothing tests them. The guard is the committed API
   * baseline: deleting either removes a line from `trailblaze-models.api` and fails `apiCheck`.
   */
  @Deprecated("Binary compatibility only", level = DeprecationLevel.HIDDEN)
  constructor(
    testAppId: String,
    fqTestName: String,
    installedExternally: Boolean = false,
    hostProcessAppId: String? = null,
    forcedDriverType: TrailblazeDriverType? = null,
  ) : this(testAppId, fqTestName, installedExternally, hostProcessAppId, forcedDriverType, false)

  /** Carries [instrumentationProcessIsTrailblazeOwned] through: a caller compiled against the old
   *  signature had no way to name it, so it cannot have meant to clear it. See the constructor. */
  @Deprecated("Binary compatibility only", level = DeprecationLevel.HIDDEN)
  fun copy(
    testAppId: String = this.testAppId,
    fqTestName: String = this.fqTestName,
    installedExternally: Boolean = this.installedExternally,
    hostProcessAppId: String? = this.hostProcessAppId,
    forcedDriverType: TrailblazeDriverType? = this.forcedDriverType,
  ): TrailblazeOnDeviceInstrumentationTarget = TrailblazeOnDeviceInstrumentationTarget(
    testAppId,
    fqTestName,
    installedExternally,
    hostProcessAppId,
    forcedDriverType,
    instrumentationProcessIsTrailblazeOwned,
  )

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
      // Trailblaze's own runner APK: it self-instruments, and no product code runs in it.
      instrumentationProcessIsTrailblazeOwned = true,
    )
  }
}
