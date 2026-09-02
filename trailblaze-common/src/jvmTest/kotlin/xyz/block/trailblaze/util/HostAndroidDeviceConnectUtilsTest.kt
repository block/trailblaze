package xyz.block.trailblaze.util

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.doesNotContain
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.Collections
import xyz.block.trailblaze.android.tools.shellEscape
import xyz.block.trailblaze.config.AppTargetYamlLoader
import xyz.block.trailblaze.config.ToolNameResolver
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDevicePort
import xyz.block.trailblaze.devices.TrailblazeDevicePort.getTrailblazeOnDeviceSpecificPort
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.model.DeviceConnectionStatus
import xyz.block.trailblaze.model.TrailblazeOnDeviceInstrumentationTarget

class HostAndroidDeviceConnectUtilsTest {

  private val deviceId = TrailblazeDeviceId(
    instanceId = "emulator-5554",
    trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
  )

  @Test
  fun instrumentationCommandArgsShellEscapeDynamicValues() {
    val args = HostAndroidDeviceConnectUtils.instrumentationAdbShellCommandArgs(
      trailblazeOnDeviceInstrumentationTarget =
        TrailblazeOnDeviceInstrumentationTarget.DEFAULT_ANDROID_ON_DEVICE,
      deviceId = deviceId,
      additionalInstrumentationArgs = linkedMapOf(
        "trailblaze.llm.auth.token.test" to "token with spaces & symbols \$(echo nope)",
        "trailblaze.llm.provider.base_url" to "https://example.com/v1/chat?x=1&y=2",
        "trailblaze.llm.default_model" to "openai/gpt 4.1",
      ),
    )

    assertThat(args).containsExactly(
      "am",
      "instrument",
      "-w",
      "-r",
      "-e",
      "class",
      "xyz.block.trailblaze.AndroidStandaloneServerTest".shellEscape(),
      "-e",
      "trailblaze.reverseProxy".shellEscape(),
      "true".shellEscape(),
      "-e",
      TrailblazeDevicePort.INSTRUMENTATION_ARG_KEY.shellEscape(),
      deviceId.getTrailblazeOnDeviceSpecificPort().toString().shellEscape(),
      "-e",
      "trailblaze.llm.auth.token.test".shellEscape(),
      "token with spaces & symbols \$(echo nope)".shellEscape(),
      "-e",
      "trailblaze.llm.provider.base_url".shellEscape(),
      "https://example.com/v1/chat?x=1&y=2".shellEscape(),
      "-e",
      "trailblaze.llm.default_model".shellEscape(),
      "openai/gpt 4.1".shellEscape(),
      "xyz.block.trailblaze.runner/androidx.test.runner.AndroidJUnitRunner".shellEscape(),
    )
  }

  // ── httpsPort threading ────────────────────────────────────────────────────
  // connectToInstrumentationAndInstallAppIfNotAvailable reverse-forwards its httpsPort param and
  // injects the same value as the device's `trailblaze.httpsPort` instrumentation arg. Both come
  // from this one merge helper's input, so pinning the merge pins the invariant: the port the
  // device POSTs logs to is always the port the host reversed. When they diverge (daemon on a
  // non-default port, arg left to its 52526 default), every log POST lands in a dead socket and
  // the host times out with "no progress" while the run executes fine on-device.

  @Test
  fun httpsPortArgMatchesReversedPort() {
    val args = HostAndroidDeviceConnectUtils.instrumentationArgsWithHttpsPort(
      additionalInstrumentationArgs = mapOf("trailblaze.llm.default_model" to "some-model"),
      httpsPort = 52522,
    )
    assertThat(args).isEqualTo(
      mapOf(
        "trailblaze.llm.default_model" to "some-model",
        TrailblazeDevicePort.HTTPS_PORT_INSTRUMENTATION_ARG_KEY to "52522",
      ),
    )
  }

  @Test
  fun httpsPortArgOverridesCallerSuppliedValue() {
    // A caller-supplied trailblaze.httpsPort pointing anywhere but the reversed port recreates
    // the dead-socket failure, so the port parameter must win.
    val args = HostAndroidDeviceConnectUtils.instrumentationArgsWithHttpsPort(
      additionalInstrumentationArgs =
        mapOf(TrailblazeDevicePort.HTTPS_PORT_INSTRUMENTATION_ARG_KEY to "52526"),
      httpsPort = 52522,
    )
    assertThat(args[TrailblazeDevicePort.HTTPS_PORT_INSTRUMENTATION_ARG_KEY]).isEqualTo("52522")
  }

  // ── reuse vs. stale routing ────────────────────────────────────────────────
  // Reusing a live runner returns before `am instrument` consumes the new args, so a runner
  // launched against a different HTTPS port would keep POSTing there — the same dead socket,
  // reached from the other side. These pin when reuse is allowed.

  @Test
  fun reuseIsAllowedWhenTheRunningPortMatches() {
    assertThat(
      HostAndroidDeviceConnectUtils.requiresRelaunchForHttpsPort(
        assumedRunningHttpsPort = 52522,
        requestedHttpsPort = 52522,
      ),
    ).isEqualTo(false)
  }

  @Test
  fun relaunchIsForcedWhenTheRunningPortDiffers() {
    assertThat(
      HostAndroidDeviceConnectUtils.requiresRelaunchForHttpsPort(
        assumedRunningHttpsPort = TrailblazeDevicePort.TRAILBLAZE_DEFAULT_HTTPS_PORT,
        requestedHttpsPort = 52522,
      ),
    ).isEqualTo(true)
  }

  @Test
  fun unknownLauncherReusesOnTheDefaultPortAndRelaunchesOtherwise() {
    // A runner this daemon did not launch is assumed to be on the device-side default, which is
    // what every host path produced before the port was threaded. So the default-port case keeps
    // reusing exactly as before, and only a non-default daemon pays for a relaunch.
    assertThat(
      HostAndroidDeviceConnectUtils.requiresRelaunchForHttpsPort(
        assumedRunningHttpsPort = null,
        requestedHttpsPort = TrailblazeDevicePort.TRAILBLAZE_DEFAULT_HTTPS_PORT,
      ),
    ).isEqualTo(false)
    assertThat(
      HostAndroidDeviceConnectUtils.requiresRelaunchForHttpsPort(
        assumedRunningHttpsPort = null,
        requestedHttpsPort = 52522,
      ),
    ).isEqualTo(true)
  }

  // ── connect failures name their cause ─────────────────────────────────────
  // `am instrument -w -r` reports an abnormal end as a bare `INSTRUMENTATION_CODE: 0`; the reason
  // is only ever on the line before it. A failure that reaches the operator without that line is
  // undiagnosable — the CI job log's summary is all that survives when the run died before its
  // session existed.

  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun aProcessDeathFailureCarriesTheActivityManagerReason() {
    // Verbatim `am instrument -w -r` output from a runner whose process was killed while starting.
    val output = listOf(
      "INSTRUMENTATION_RESULT: shortMsg=Process crashed.",
      "INSTRUMENTATION_CODE: 0",
    )
    val progress = mutableListOf<String>()
    val outcome = CompletableDeferred<DeviceConnectionStatus>()
    output.forEach { line ->
      HostAndroidDeviceConnectUtils.onInstrumentationOutputLine(
        line = line,
        trailblazeDeviceId = deviceId,
        trailblazeOnDeviceInstrumentationTarget =
          TrailblazeOnDeviceInstrumentationTarget.DEFAULT_ANDROID_ON_DEVICE,
        sendProgressMessage = { progress.add(it) },
        hasCallbackBeenCalled = false,
        completableDeferred = outcome,
        outputLines = output,
        onStatusCodeHandled = {},
      )
    }

    val failure = outcome.getCompleted() as DeviceConnectionStatus.DeviceConnectionError.ConnectionFailure
    assertThat(failure.errorMessage).contains("shortMsg=Process crashed.")
    assertThat(failure.errorMessage).contains("xyz.block.trailblaze.runner")
    // The operator only ever sees the streamed progress, so the reason has to be in it too.
    assertThat(progress.last()).contains("shortMsg=Process crashed.")
  }

  @Test
  fun aFailureWithNoOutputSaysSoRatherThanLookingTruncated() {
    val message = HostAndroidDeviceConnectUtils.buildInstrumentationFailureMessage(
      prefix = "Error occurred during instrumentation process (xyz.block.trailblaze.runner).",
      outputLines = emptyList(),
    )
    assertThat(message).contains("No instrumentation output was received.")
  }

  // ── connect is exclusive per device ───────────────────────────────────────
  // The connect sequence force-stops the runner package, reinstalls it, then `am instrument`s it.
  // ActivityManager force-stops any live process of the package as part of starting
  // instrumentation, so a second overlapping entrant kills the first entrant's starting runner and
  // the first sees `shortMsg=Process crashed.`. Serializing per device is what lets the second
  // entrant reuse instead of clobber.

  @Test
  fun aSecondConnectToTheSameDeviceTouchesNothingUntilTheFirstFinishes() = runBlocking {
    val held = HostAndroidDeviceConnectUtils.deviceConnectMutex(deviceId)
    held.lock()
    val progress = Collections.synchronizedList(mutableListOf<String>())
    val secondEntrant = launch(Dispatchers.Default) {
      HostAndroidDeviceConnectUtils.connectToInstrumentation(
        trailblazeOnDeviceInstrumentationTarget =
          TrailblazeOnDeviceInstrumentationTarget.DEFAULT_ANDROID_ON_DEVICE,
        trailblazeDeviceId = deviceId,
        sendProgressMessage = { progress.add(it) },
        additionalForceStopTargets = emptySet(),
      )
    }
    try {
      delay(500)
      // Every step of the connect reports itself, so silence is the observable form of "has not
      // started". An unserialized entrant would already have force-stopped the runner package the
      // first entrant is mid-launch on.
      assertThat(progress.toList()).isEmpty()
    } finally {
      secondEntrant.cancel()
      held.unlock()
    }
  }

  @Test
  fun aConnectToOneDeviceDoesNotBlockAnother() = runBlocking {
    val otherDeviceId = deviceId.copy(instanceId = "emulator-5556")
    val held = HostAndroidDeviceConnectUtils.deviceConnectMutex(deviceId)
    held.lock()
    try {
      // A per-device lock; a global one would make the second device wait on the first forever.
      assertThat(
        HostAndroidDeviceConnectUtils.deviceConnectMutex(otherDeviceId).tryLock(),
      ).isEqualTo(true)
      HostAndroidDeviceConnectUtils.deviceConnectMutex(otherDeviceId).unlock()
    } finally {
      held.unlock()
    }
  }

  @Test
  fun aQueuedConnectSeesTheRouteTheHolderJustLaunched() = runBlocking {
    // Two first-time connects on a NON-DEFAULT https port, so both would read "route unknown" and
    // conclude a relaunch is needed. Deciding that before taking the lock is what let the waiter
    // force-stop the runner the holder had just launched; deciding it inside means the waiter sees
    // the recorded port and connects with forceRestart=false, i.e. eligible to reuse.
    val racedDevice = deviceId.copy(instanceId = "emulator-5590")
    val nonDefaultPort = TrailblazeDevicePort.TRAILBLAZE_DEFAULT_HTTPS_PORT + 7
    val handedForceRestart = Collections.synchronizedList(mutableListOf<Boolean>())
    val entrants = (1..2).map {
      launch(Dispatchers.Default) {
        HostAndroidDeviceConnectUtils.connectWithRoutePinnedForTest(
          deviceId = racedDevice,
          httpsPort = nonDefaultPort,
        ) { effectiveForceRestart ->
          handedForceRestart.add(effectiveForceRestart)
          delay(50)
          DeviceConnectionStatus.WithTargetDevice.TrailblazeInstrumentationRunning(racedDevice)
        }
      }
    }
    entrants.forEach { it.join() }

    assertThat(handedForceRestart.toList()).containsExactly(true, false)
  }

  @Test
  fun theSameDeviceAlwaysGetsTheSameLock() {
    // Two entrants that resolved different Mutex instances would both proceed, which is the
    // failure this guards against.
    assertThat(
      HostAndroidDeviceConnectUtils.deviceConnectMutex(deviceId) ===
        HostAndroidDeviceConnectUtils.deviceConnectMutex(deviceId.copy()),
    ).isEqualTo(true)
  }

  @Test
  fun httpsPortArgKeyIsTheOneTheDeviceReads() {
    // InstrumentationArgUtil.logsEndpoint and AndroidTestInstrumentation.logsEndpoint read this
    // exact string off the instrumentation Bundle; the literal here pins the wire contract.
    assertThat(TrailblazeDevicePort.HTTPS_PORT_INSTRUMENTATION_ARG_KEY)
      .isEqualTo("trailblaze.httpsPort")
  }
  /**
   * A target declaring an in-process `android_test:` harness, resolved through the real YAML
   * loader rather than a hand-built [TrailblazeOnDeviceInstrumentationTarget] — the expression the
   * connect path's callers pass for `additionalForceStopTargets` is
   * `<runTarget>.allInstrumentationTargets()`, so the loader's `hostProcessAppId` derivation is
   * part of what has to hold.
   */
  private fun inProcessHarnessTarget() = AppTargetYamlLoader.loadFromYaml(
    """
    id: example
    display_name: Example App
    platforms:
      android:
        app_ids:
          - com.example.app
    android_test:
      test_app_id: com.example.app.uitests
      fq_test_name: com.example.app.uitests.InProcessStandaloneServerTest
    """.trimIndent(),
    toolNameResolver = ToolNameResolver.fromBuiltInAndCustomTools(),
  )

  /** Every package a device with the in-process harness built and installed would report. */
  private val harnessInstalled = setOf("com.example.app", "com.example.app.uitests", "xyz.block.trailblaze.runner")

  /** The same device before anyone built the harness — the overwhelmingly common case. */
  private val harnessNotInstalled = setOf("com.example.app", "xyz.block.trailblaze.runner")

  private fun plan(
    connecting: TrailblazeOnDeviceInstrumentationTarget = TrailblazeOnDeviceInstrumentationTarget.DEFAULT_ANDROID_ON_DEVICE,
    additional: Set<TrailblazeOnDeviceInstrumentationTarget> =
      inProcessHarnessTarget().allInstrumentationTargets().toSet(),
    installedPackages: Set<String>,
  ) = HostAndroidDeviceConnectUtils.planConnectForceStop(
    trailblazeOnDeviceInstrumentationTarget = connecting,
    additionalForceStopTargets = additional,
    installedPackages = installedPackages,
    deviceLabel = "emulator-5556",
  )

  /**
   * A fresh connect must clear the run target's OWN declared harnesses, not just the target being
   * launched. Repro this pins: an in-process ANDROID_TEST run leaves its instrumentation attached
   * inside the app under test, still holding UiAutomation; a following
   * ANDROID_ONDEVICE_ACCESSIBILITY connect that force-stopped only the bundled runner (+ Maestro)
   * then died in the runner's startServer with "UiAutomationService ... already registered!".
   * Both the harness's test package AND its host app process must be in the force-stop id list —
   * the instrumentation lives in the app's process, so stopping the test package alone frees
   * nothing.
   */
  @Test
  fun connectForceStopSetIncludesTargetDeclaredInProcessHarnessWhenItsApkIsInstalled() {
    val forceStoppedAppIds =
      HostAndroidDeviceConnectUtils.forceStopAppIds(plan(installedPackages = harnessInstalled).targets)

    assertThat(forceStoppedAppIds).contains("com.example.app.uitests")
    // The whole point: the app under test, because that is the process the instrumentation — and
    // therefore the UiAutomation registration — lives in.
    assertThat(forceStoppedAppIds).contains("com.example.app")
    // The connecting target itself stays in the set unconditionally.
    assertThat(forceStoppedAppIds).contains("xyz.block.trailblaze.runner")
  }

  /**
   * The install gate, which is what keeps this union from costing every user their app state: an
   * in-process harness is built by the app's own build, so with its test APK absent no in-process
   * run ever happened on this device and there is nothing holding UiAutomation. The app under test
   * must NOT be force-stopped there — it is running whenever anyone launched it, and killing it
   * would discard whatever the user navigated to.
   */
  @Test
  fun connectForceStopSetOmitsHarnessesWhoseTestApkIsNotInstalled() {
    val forceStopPlan = plan(installedPackages = harnessNotInstalled)

    // Byte-identical to the pre-union behavior — no app-under-test kill, no harness kill.
    assertThat(HostAndroidDeviceConnectUtils.forceStopAppIds(forceStopPlan.targets)).containsExactly(
      "xyz.block.trailblaze.runner",
      HostAndroidDeviceConnectUtils.MAESTRO_APP_ID,
      HostAndroidDeviceConnectUtils.MAESTRO_TEST_APP_ID,
    )
    assertThat(forceStopPlan.diagnostics.single()).contains("Not force stopping com.example.app.uitests")
  }

  /**
   * An unreadable package list is not an absent harness. `listInstalledPackages` swallows its
   * exception and answers empty, and no live device has zero packages — so treating empty as
   * "nothing installed" would drop every extra and silently restore the crash this union prevents.
   * The extras must survive, and the reason must be attributable to the failed probe rather than
   * reported as the (false) "test APK is not installed".
   */
  @Test
  fun connectForceStopSetKeepsExtrasWhenTheInstalledPackageProbeFailed() {
    val forceStopPlan = plan(installedPackages = emptySet())

    val forceStoppedAppIds = HostAndroidDeviceConnectUtils.forceStopAppIds(forceStopPlan.targets)
    assertThat(forceStoppedAppIds).contains("com.example.app.uitests")
    assertThat(forceStoppedAppIds).contains("com.example.app")

    val diagnostic = forceStopPlan.diagnostics.single()
    assertThat(diagnostic).contains("Could not read installed packages")
    // The wrong diagnosis is what sent the last debugger down the wrong path — assert it is absent.
    assertThat(diagnostic).doesNotContain("is not installed")
  }

  /**
   * The gate is asked only about the EXTRA harnesses. The target being connected is what this
   * connect is about to `am instrument`, so it is force-stopped whether or not the probe can see
   * it — and it must never be reported as a skipped extra, which is what deduping it out of the
   * extras before the partition guarantees.
   */
  @Test
  fun connectForceStopSetAlwaysIncludesTheConnectingTargetAndNeverReportsItAsSkipped() {
    val inProcessHarness = inProcessHarnessTarget().getAndroidTestInstrumentationTarget()!!
    val forceStopPlan = plan(
      connecting = inProcessHarness,
      installedPackages = harnessNotInstalled,
    )

    assertThat(HostAndroidDeviceConnectUtils.forceStopAppIds(forceStopPlan.targets)).containsExactly(
      "com.example.app.uitests",
      "com.example.app",
      "xyz.block.trailblaze.runner",
      HostAndroidDeviceConnectUtils.MAESTRO_APP_ID,
      HostAndroidDeviceConnectUtils.MAESTRO_TEST_APP_ID,
    )
    // The bundled runner is the only genuine extra here, and it IS installed, so it is stopped:
    // starting an in-process harness has to clear the runner for the same reason the reverse does.
    assertThat(forceStopPlan.diagnostics.single())
      .contains("Also force stopping declared harness xyz.block.trailblaze.runner")
  }

  /**
   * A run whose target declares no extra harnesses force-stops what it did before the union, and
   * — because the caller skips the `pm list packages` read in that case — must not consult the
   * package list at all. Pinned by passing a list that would change the answer if it were read.
   */
  @Test
  fun connectForceStopSetWithoutAdditionalTargetsIsJustTheConnectingTarget() {
    val forceStopPlan = plan(additional = emptySet(), installedPackages = emptySet())

    assertThat(HostAndroidDeviceConnectUtils.forceStopAppIds(forceStopPlan.targets)).containsExactly(
      "xyz.block.trailblaze.runner",
      HostAndroidDeviceConnectUtils.MAESTRO_APP_ID,
      HostAndroidDeviceConnectUtils.MAESTRO_TEST_APP_ID,
    )
    assertThat(forceStopPlan.diagnostics).isEmpty()
  }

  // ── forced-driver forwarding ───────────────────────────────────────────────
  // The in-process harness is reachable ONLY by selecting ANDROID_TEST, so launching it has to say
  // so on device. Without the arg, the on-device gate (AndroidTestTrailblazeRule.evaluateDriverPin)
  // sees a bare `config.driver:` pin and refuses — every accessibility-recorded estate trail dies
  // at ~1s on a CLI run that asked for this driver by name. The farm never hit it because a lane's
  // `deviceDriverTypes` already reaches the runner as this same arg.

  @Test
  fun inProcessHarnessLaunchForwardsItsDriverAsTheForceArg() {
    val harness = inProcessHarnessTarget().getAndroidTestInstrumentationTarget()!!

    val args = HostAndroidDeviceConnectUtils.instrumentationArgsWithForcedDriver(
      additionalInstrumentationArgs = mapOf("trailblaze.llm.default_model" to "some-model"),
      trailblazeOnDeviceInstrumentationTarget = harness,
    )

    assertThat(args).isEqualTo(
      mapOf(
        "trailblaze.llm.default_model" to "some-model",
        "trailblaze.driverType" to "ANDROID_TEST",
      ),
    )
  }

  @Test
  fun bundledRunnerLaunchForwardsNoForceArg() {
    // The bundled runner serves whichever driver a run selects, and forcing one here would
    // silence the per-trail pin check on the drivers that legitimately honor it.
    val args = HostAndroidDeviceConnectUtils.instrumentationArgsWithForcedDriver(
      additionalInstrumentationArgs = mapOf("trailblaze.llm.default_model" to "some-model"),
      trailblazeOnDeviceInstrumentationTarget =
        TrailblazeOnDeviceInstrumentationTarget.DEFAULT_ANDROID_ON_DEVICE,
    )

    assertThat(args).isEqualTo(mapOf("trailblaze.llm.default_model" to "some-model"))
  }

  @Test
  fun theHarnessBeingLaunchedOverridesACallerSuppliedDriverArg() {
    // Same override-not-default rule as httpsPort: the harness actually being instrumented is the
    // ground truth, and a stale arg naming another driver describes some other run.
    val harness = inProcessHarnessTarget().getAndroidTestInstrumentationTarget()!!

    val args = HostAndroidDeviceConnectUtils.instrumentationArgsWithForcedDriver(
      additionalInstrumentationArgs =
        mapOf("trailblaze.driverType" to "ANDROID_ONDEVICE_ACCESSIBILITY"),
      trailblazeOnDeviceInstrumentationTarget = harness,
    )

    assertThat(args["trailblaze.driverType"]).isEqualTo("ANDROID_TEST")
  }

  /**
   * The bug was never in the merge rules — it was that nothing applied them when the command was
   * built. These two assert on the emitted argv, so dropping the
   * [HostAndroidDeviceConnectUtils.instrumentationArgsWithForcedDriver] call out of
   * [HostAndroidDeviceConnectUtils.instrumentationAdbShellCommandArgs] fails a test instead of
   * passing silently.
   */
  @Test
  fun theEmittedAmInstrumentCommandCarriesTheForceArgForTheInProcessHarness() {
    val harness = inProcessHarnessTarget().getAndroidTestInstrumentationTarget()!!

    val args = HostAndroidDeviceConnectUtils.instrumentationAdbShellCommandArgs(
      trailblazeOnDeviceInstrumentationTarget = harness,
      deviceId = deviceId,
    )

    // Adjacent triple, in `am instrument` order: the device reads `-e <key> <value>` positionally.
    assertThat(args.windowed(size = 3)).contains(
      listOf(
        "-e",
        "trailblaze.driverType".shellEscape(),
        "ANDROID_TEST".shellEscape(),
      ),
    )
  }

  @Test
  fun theEmittedAmInstrumentCommandCarriesNoForceArgForTheBundledRunner() {
    val args = HostAndroidDeviceConnectUtils.instrumentationAdbShellCommandArgs(
      trailblazeOnDeviceInstrumentationTarget =
        TrailblazeOnDeviceInstrumentationTarget.DEFAULT_ANDROID_ON_DEVICE,
      deviceId = deviceId,
    )

    assertThat(args).doesNotContain("trailblaze.driverType".shellEscape())
  }

  @Test
  fun forcedDriverArgKeyIsTheOneTheDeviceReads() {
    // InstrumentationArgUtil.driverType and AndroidTestTrailblazeRule read this exact string off
    // the instrumentation Bundle; the literal here pins the wire contract. The value is parsed
    // with `valueOf` on device, so only the enum NAME forces anything.
    assertThat(TrailblazeDriverType.INSTRUMENTATION_ARG_KEY).isEqualTo("trailblaze.driverType")
    assertThat(TrailblazeDriverType.ANDROID_TEST.name).isEqualTo("ANDROID_TEST")
  }
}
