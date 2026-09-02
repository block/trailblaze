package xyz.block.trailblaze.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import xyz.block.trailblaze.android.tools.shellEscape
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDevicePort
import xyz.block.trailblaze.devices.TrailblazeDevicePort.getTrailblazeOnDeviceSpecificPort
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.model.DeviceConnectionStatus
import xyz.block.trailblaze.model.TrailblazeOnDeviceInstrumentationTarget
import xyz.block.trailblaze.util.AndroidHostAdbUtils.adbPortForward
import xyz.block.trailblaze.util.AndroidHostAdbUtils.adbPortReverse
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

object HostAndroidDeviceConnectUtils {

  const val MAESTRO_APP_ID = "dev.mobile.maestro"
  const val MAESTRO_TEST_APP_ID = "dev.mobile.maestro.test"
  private const val INSTRUMENTATION_START_TIMEOUT_MS = 15_000L
  private const val INSTRUMENTATION_PROCESS_VERIFY_ATTEMPTS = 5
  private const val INSTRUMENTATION_PROCESS_VERIFY_DELAY_MS = 1_000L
  private const val INSTRUMENTATION_OUTPUT_TAIL_LINES = 20

  val ioScope = CoroutineScope(Dispatchers.IO)

  // Both ids per target, because they differ exactly when it matters: an in-process harness
  // runs inside the app under test, so force-stopping only the test package leaves the old
  // instrumentation attached to a live app process and the next `am instrument` fails with
  // "already running". For a self-instrumenting bundled runner the two are the same string.
  internal fun forceStopAppIds(
    trailblazeOnDeviceInstrumentationTargetTestApps: Set<TrailblazeOnDeviceInstrumentationTarget>,
  ): List<String> = (
    trailblazeOnDeviceInstrumentationTargetTestApps
      .flatMap { listOf(it.testAppId, it.instrumentationProcessAppId) } +
      listOf(MAESTRO_APP_ID, MAESTRO_TEST_APP_ID)
    ).distinct()

  fun forceStopAllAndroidInstrumentationProcesses(
    trailblazeOnDeviceInstrumentationTargetTestApps: Set<TrailblazeOnDeviceInstrumentationTarget>,
    deviceId: TrailblazeDeviceId,
  ) {
    val testAppIds: List<String> = forceStopAppIds(trailblazeOnDeviceInstrumentationTargetTestApps)

    // Disable accessibility services before force-stopping. A registered accessibility service
    // causes Android to restart the process immediately after force-stop, preventing clean shutdown.
    testAppIds.forEach { appId ->
      AccessibilityServiceSetupUtils.disableAccessibilityService(deviceId, appId)
    }

    Console.log("Force stopping all Android instrumentation processes. IDs: $testAppIds")
    testAppIds.forEach { appId ->
      AndroidHostAdbUtils.forceStopApp(
        deviceId = deviceId,
        appId = appId,
      )
    }
  }

  private suspend fun installPrecompiledTrailblazeOnDeviceInstrumentationTargetTestApp(
    trailblazeOnDeviceInstrumentationTarget: TrailblazeOnDeviceInstrumentationTarget,
    trailblazeDeviceId: TrailblazeDeviceId,
    sendProgressMessage: (String) -> Unit,
  ): Boolean {
    // Use pre-compiled APK bundled with the application
    if (!PrecompiledApkInstaller.hasPrecompiledApk(trailblazeOnDeviceInstrumentationTarget)) {
      val errorMessage = "Pre-compiled APK not found for ${trailblazeOnDeviceInstrumentationTarget.testAppId}. " +
          "This indicates a build configuration issue. The APK should be bundled during the desktop app build process."
      sendProgressMessage(errorMessage)
      Console.log(errorMessage)
      return false
    }

    sendProgressMessage("Installing pre-compiled test APK...")

    val installSuccess = PrecompiledApkInstaller.extractAndInstallPrecompiledApk(
      trailblazeDeviceId = trailblazeDeviceId,
      sendProgressMessage = sendProgressMessage,
    )

    if (installSuccess) {
      sendProgressMessage("Pre-compiled test APK installed successfully")
    } else {
      sendProgressMessage("Failed to install pre-compiled test APK")
    }

    return installSuccess
  }

  /**
   * Returns the tokens that, when joined with spaces, form the device-side shell command for
   * [AndroidHostAdbUtils.streamingShell]. Each dynamic value is `shellEscape`'d because the device's
   * `sh` will re-split the joined command on whitespace and interpret metacharacters (`;`, `$`,
   * backtick, etc.).
   *
   * Note: the leading `"shell"` token used to be present back when this argv was fed directly to
   * `adb shell` via `ProcessBuilder`. After the dadb migration the function returns the bare
   * `am instrument …` command — `streamingShell` opens its own shell stream over the wire
   * protocol, so a `shell` prefix would be doubled.
   *
   * Takes the whole [trailblazeOnDeviceInstrumentationTarget] rather than its two name fields so
   * that a harness's [TrailblazeOnDeviceInstrumentationTarget.forcedDriverType] is applied HERE,
   * where the command is emitted, instead of by each caller. This is the only place an
   * `am instrument` is built, so every connect path — CLI run, Sessions UI, MCP bridge, recording
   * — forwards the force identically and none of them can forget to.
   */
  internal fun instrumentationAdbShellCommandArgs(
    trailblazeOnDeviceInstrumentationTarget: TrailblazeOnDeviceInstrumentationTarget,
    deviceId: TrailblazeDeviceId,
    additionalInstrumentationArgs: Map<String, String> = emptyMap(),
  ): List<String> = buildList {
    val testAppId = trailblazeOnDeviceInstrumentationTarget.testAppId
    addAll(
      listOf(
        "am",
        "instrument",
        "-w",
        "-r",
      ),
    )
    addAll(
      listOf(
        "-e",
        "class",
        trailblazeOnDeviceInstrumentationTarget.fqTestName.shellEscape(),
      ),
    )
    addAll(
      listOf(
        "-e",
        "trailblaze.reverseProxy".shellEscape(),
        "true".shellEscape(),
      ),
    )
    addAll(
      listOf(
        "-e",
        TrailblazeDevicePort.INSTRUMENTATION_ARG_KEY.shellEscape(),
        deviceId.getTrailblazeOnDeviceSpecificPort().toString().shellEscape(),
      ),
    )

    // adb shell joins argv into one string and executes it via the device shell, so every
    // dynamic instrumentation key/value must be quoted explicitly.
    instrumentationArgsWithForcedDriver(
      additionalInstrumentationArgs = additionalInstrumentationArgs,
      trailblazeOnDeviceInstrumentationTarget = trailblazeOnDeviceInstrumentationTarget,
    ).forEach { (key, value) ->
      addAll(listOf("-e", key.shellEscape(), value.shellEscape()))
    }

    add("$testAppId/androidx.test.runner.AndroidJUnitRunner".shellEscape())
  }

  /**
   * What a fresh connect will force-stop, and why — [targets] to act on, [diagnostics] to report.
   * Returned together because the reason an extra harness was or was not stopped is only knowable
   * here, and both a silently-degraded union and a killed app-under-test are otherwise invisible.
   */
  internal data class ConnectForceStopPlan(
    val targets: Set<TrailblazeOnDeviceInstrumentationTarget>,
    val diagnostics: List<String> = emptyList(),
  )

  /**
   * The set a fresh connect must force-stop: the target being launched, plus each of the run
   * target's OTHER declared harnesses whose test APK is actually installed on this device.
   *
   * Stopping only [trailblazeOnDeviceInstrumentationTarget] leaves a PREVIOUS run's harness
   * attached — an in-process ANDROID_TEST harness lives inside the app under test and keeps
   * holding UiAutomation after its run, so the next driver's runner dies in startServer with
   * "UiAutomationService ... already registered!". The reciprocal case is intended too: starting
   * an in-process harness stops the bundled runner, because both bind this device's Trailblaze
   * port and both take UiAutomation, so they cannot coexist either way round.
   *
   * ### Why the install gate, and what the union still costs
   *
   * "Force-stopping a package that isn't running is a no-op" holds for a bundled runner but NOT
   * for an in-process harness: its [TrailblazeOnDeviceInstrumentationTarget.instrumentationProcessAppId]
   * is the app under test, which is running whenever anyone launched it. Adding it unconditionally
   * would make every fresh connect kill the app and discard whatever state the user had navigated
   * to.
   *
   * [installedPackages] is what bounds that. An in-process harness is built and installed by the
   * app's own build, so if its test APK is absent no in-process run ever happened here and there
   * is nothing to clean up — the overwhelmingly common case (any machine that never builds the
   * harness) pays nothing and behaves exactly as it did before this union existed.
   *
   * The residual cost, on a machine where the harness IS installed: a fresh connect for another
   * driver force-stops the app under test. That is not incidental — freeing UiAutomation requires
   * killing the process the instrumentation lives in, which is the app's. Reconnects to an
   * already-running server never reach this code.
   *
   * ### An unreadable package list is not an absent harness
   *
   * [AndroidHostAdbUtils.listInstalledPackages] swallows its exception and answers `emptyList()`,
   * and no live device has zero packages — so an empty [installedPackages] means the probe FAILED,
   * not that nothing is installed. Dropping the extras there would silently restore the crash this
   * union exists to prevent, so an unreadable list keeps every extra and says so. That costs
   * nothing when adb is genuinely broken (the force-stops fail too) and is correct if it recovers.
   */
  internal fun planConnectForceStop(
    trailblazeOnDeviceInstrumentationTarget: TrailblazeOnDeviceInstrumentationTarget,
    additionalForceStopTargets: Set<TrailblazeOnDeviceInstrumentationTarget>,
    installedPackages: Set<String>,
    deviceLabel: String,
  ): ConnectForceStopPlan {
    val connecting = setOf(trailblazeOnDeviceInstrumentationTarget)
    // Never the connecting target itself: it is what this connect is about to `am instrument`, so
    // it is stopped unconditionally above and must not be reported as a skipped extra.
    val extras = additionalForceStopTargets - trailblazeOnDeviceInstrumentationTarget
    if (extras.isEmpty()) return ConnectForceStopPlan(targets = connecting)

    if (installedPackages.isEmpty()) {
      return ConnectForceStopPlan(
        targets = connecting + extras,
        diagnostics = listOf(
          "Could not read installed packages on $deviceLabel — likely a silent adb failure " +
            "swallowed by AndroidHostAdbUtils.listInstalledPackages. Force stopping all " +
            "${extras.size} declared harness(es) unverified rather than risk leaving one " +
            "holding UiAutomation: ${extras.map { it.testAppId }}.",
        ),
      )
    }

    val (installed, absent) = extras.partition { it.testAppId in installedPackages }
    return ConnectForceStopPlan(
      targets = connecting + installed,
      diagnostics = buildList {
        installed.forEach {
          // The app-under-test kill is attributable only if the reason is stated next to it.
          add(
            "Also force stopping declared harness ${it.testAppId} and its host process " +
              "${it.instrumentationProcessAppId}: its test APK is installed on $deviceLabel, so a " +
              "previous run of it may still hold UiAutomation.",
          )
        }
        absent.forEach {
          add(
            "Not force stopping ${it.testAppId}: its test APK is not installed on $deviceLabel, " +
              "so it cannot be holding instrumentation here.",
          )
        }
      },
    )
  }

  private val deviceConnectMutexes = ConcurrentHashMap<TrailblazeDeviceId, Mutex>()

  /**
   * One lock per device, held for the whole of [connectToInstrumentation].
   *
   * The connect sequence is destructive to whatever instrumentation is already on the device:
   * force-stop the runner package, reinstall its APK, then `am instrument`. Two overlapping
   * entrants therefore break each other rather than sharing — `am instrument` makes
   * ActivityManager force-stop any live process of the same package first, so the second entrant's
   * launch kills the first entrant's starting runner and the first entrant's output stream reports
   * `INSTRUMENTATION_RESULT: shortMsg=Process crashed.` / `INSTRUMENTATION_CODE: 0`, which is
   * indistinguishable from the runner genuinely failing to boot.
   *
   * Three independent callers reach this (a trail run, the MCP bridge, and the recording device
   * service) with nothing coordinating them, so serializing here is what makes the second entrant
   * safe: it waits, and then finds the runner the first entrant left live and takes the
   * already-running reuse path instead of tearing it down.
   */
  internal fun deviceConnectMutex(trailblazeDeviceId: TrailblazeDeviceId): Mutex =
    deviceConnectMutexes.computeIfAbsent(trailblazeDeviceId) { Mutex() }

  suspend fun connectToInstrumentation(
    trailblazeOnDeviceInstrumentationTarget: TrailblazeOnDeviceInstrumentationTarget,
    trailblazeDeviceId: TrailblazeDeviceId,
    additionalInstrumentationArgs: Map<String, String> = emptyMap(),
    sendProgressMessage: (String) -> Unit,
    forceRestart: Boolean = false,
    /**
     * The run target's other declared harnesses — see [planConnectForceStop]. Deliberately NOT
     * defaulted: an omitted set is the pre-fix force-stop scope, i.e. the "UiAutomationService
     * already registered!" crash, so a new caller has to state that it means it.
     */
    additionalForceStopTargets: Set<TrailblazeOnDeviceInstrumentationTarget>,
  ): DeviceConnectionStatus = deviceConnectMutex(trailblazeDeviceId).withLock {
    connectToInstrumentationExclusive(
      trailblazeOnDeviceInstrumentationTarget = trailblazeOnDeviceInstrumentationTarget,
      trailblazeDeviceId = trailblazeDeviceId,
      additionalInstrumentationArgs = additionalInstrumentationArgs,
      sendProgressMessage = sendProgressMessage,
      forceRestart = forceRestart,
      additionalForceStopTargets = additionalForceStopTargets,
    )
  }

  private suspend fun connectToInstrumentationExclusive(
    trailblazeOnDeviceInstrumentationTarget: TrailblazeOnDeviceInstrumentationTarget,
    trailblazeDeviceId: TrailblazeDeviceId,
    additionalInstrumentationArgs: Map<String, String>,
    sendProgressMessage: (String) -> Unit,
    forceRestart: Boolean,
    additionalForceStopTargets: Set<TrailblazeOnDeviceInstrumentationTarget>,
  ): DeviceConnectionStatus {
    val trailblazeOnDeviceInstrumentationTarget = trailblazeOnDeviceInstrumentationTarget
    val completableDeferred: CompletableDeferred<DeviceConnectionStatus> = CompletableDeferred()
    var hasCallbackBeenCalled = false

    // Check if the on-device server is already running. If so, reuse it instead of
    // force-stopping and reinstalling, which would clobber any active connections
    // (e.g., MCP screen-state queries, accessibility driver).
    //
    // Only where a live process actually proves instrumentation is attached — i.e. a
    // self-instrumenting bundled runner, whose process exists only because `am instrument` made
    // it. An in-process harness's process is the app under test, which is running whenever anyone
    // launched it, so reusing on that signal skips the install check and the launch and then binds
    // to whatever else answers the device port. Those runs get the full clean-slate path below;
    // the readiness probe, not `pidof`, is what says they are up.
    val alreadyRunning = !forceRestart &&
      trailblazeOnDeviceInstrumentationTarget.processLivenessProvesInstrumentationAttached &&
      AndroidHostAdbUtils.isAppRunning(
        deviceId = trailblazeDeviceId,
        appId = trailblazeOnDeviceInstrumentationTarget.instrumentationProcessAppId,
      )

    if (alreadyRunning) {
      // Even if running, verify the installed APK matches the bundled version.
      // This handles brew upgrades and local source rebuilds transparently.
      // Externally-installed harnesses have no bundle to compare against — the app's own build
      // owns their freshness — so a running server is always reusable.
      if (trailblazeOnDeviceInstrumentationTarget.installedExternally ||
        PrecompiledApkInstaller.isInstalledApkUpToDate(trailblazeDeviceId)
      ) {
        sendProgressMessage("On-device server already running — reusing existing connection.")
        Console.log("On-device server already running for ${trailblazeOnDeviceInstrumentationTarget.testAppId}, skipping force-stop/reinstall.")
        return DeviceConnectionStatus.WithTargetDevice.TrailblazeInstrumentationRunning(
          trailblazeDeviceId = trailblazeDeviceId,
        )
      } else {
        sendProgressMessage("On-device APK is outdated — reinstalling...")
        Console.log("APK SHA mismatch for ${trailblazeOnDeviceInstrumentationTarget.testAppId}, forcing reinstall.")
      }
    }

    // Server not running, force restart requested, or APK outdated — clean slate setup
    if (!alreadyRunning) {
      sendProgressMessage("On-device server not running — starting fresh...")
    }
    // One `pm list packages` read, shared by the force-stop gate below and the installedExternally
    // check further down — two reads of the same thing seconds apart can disagree, and the second
    // one's failure surfaces as a misleading "test APK is not installed" terminal error. Skipped
    // entirely when the caller passed no extras, since nothing would consult it.
    val installedPackages: Set<String> = if (additionalForceStopTargets.isEmpty()) {
      emptySet()
    } else {
      AndroidHostAdbUtils.listInstalledPackages(trailblazeDeviceId).toSet()
    }
    val forceStopPlan = planConnectForceStop(
      trailblazeOnDeviceInstrumentationTarget = trailblazeOnDeviceInstrumentationTarget,
      additionalForceStopTargets = additionalForceStopTargets,
      installedPackages = installedPackages,
      deviceLabel = trailblazeDeviceId.instanceId,
    )
    // Through sendProgressMessage as well as the log: on the paths a human is watching, a connect
    // that force-stops the app they had navigated to should say so where they can see it.
    forceStopPlan.diagnostics.forEach { diagnostic ->
      Console.log(diagnostic)
      sendProgressMessage(diagnostic)
    }
    forceStopAllAndroidInstrumentationProcesses(
      trailblazeOnDeviceInstrumentationTargetTestApps = forceStopPlan.targets,
      deviceId = trailblazeDeviceId,
    )
    if (trailblazeOnDeviceInstrumentationTarget.installedExternally) {
      // Instrument what's installed: the host bundles no APK for this harness. A missing install
      // is the one failure mode, and it has one fix — build and install the test APK.
      //
      // Reuses the list read above when there was one. An empty list there means the read failed
      // (see planConnectForceStop), which is not evidence of absence, so fall back to a fresh
      // probe rather than reporting this harness as uninstalled on an adb hiccup.
      val installed = if (installedPackages.isNotEmpty()) {
        trailblazeOnDeviceInstrumentationTarget.testAppId in installedPackages
      } else {
        AndroidHostAdbUtils.isAppInstalled(
          appId = trailblazeOnDeviceInstrumentationTarget.testAppId,
          deviceId = trailblazeDeviceId,
        )
      }
      if (!installed) {
        val errorMessage =
          "Test APK ${trailblazeOnDeviceInstrumentationTarget.testAppId} is not installed on " +
            "${trailblazeDeviceId.instanceId}. This harness is built by the app's own build, not " +
            "bundled with Trailblaze — install it (e.g. via the app repo's Gradle " +
            "installDebugAndroidTest task or farm artifact) and retry."
        sendProgressMessage(errorMessage)
        return DeviceConnectionStatus.DeviceConnectionError.ConnectionFailure(
          errorMessage = errorMessage,
        )
      }
    } else {
      val installSuccess = installPrecompiledTrailblazeOnDeviceInstrumentationTargetTestApp(
        trailblazeOnDeviceInstrumentationTarget = trailblazeOnDeviceInstrumentationTarget,
        trailblazeDeviceId = trailblazeDeviceId,
        sendProgressMessage = sendProgressMessage,
      )
      if (!installSuccess) {
        val errorMessage =
          "Failed to install the pre-compiled on-device runner for ${trailblazeOnDeviceInstrumentationTarget.testAppId}."
        sendProgressMessage(errorMessage)
        return DeviceConnectionStatus.DeviceConnectionError.ConnectionFailure(
          errorMessage = errorMessage,
        )
      }
    }

    adbPortForward(
      deviceId = trailblazeDeviceId,
      localPort = trailblazeDeviceId.getTrailblazeOnDeviceSpecificPort(),
    )

    val outputLines = Collections.synchronizedList(mutableListOf<String>())
    val instrumentationStreamRef = AtomicReference<AutoCloseable?>()

    sendProgressMessage("Connecting to Android Test Instrumentation.")
    try {
      val command = instrumentationAdbShellCommandArgs(
        trailblazeOnDeviceInstrumentationTarget = trailblazeOnDeviceInstrumentationTarget,
        deviceId = trailblazeDeviceId,
        additionalInstrumentationArgs = additionalInstrumentationArgs,
      ).joinToString(" ")
      val handle = AndroidHostAdbUtils.streamingShell(
        deviceId = trailblazeDeviceId,
        command = command,
        onLine = { outputLine ->
          if (completableDeferred.isCompleted) return@streamingShell
          appendOutputLine(outputLines, outputLine)
          onInstrumentationOutputLine(
            line = outputLine,
            trailblazeDeviceId = trailblazeDeviceId,
            trailblazeOnDeviceInstrumentationTarget = trailblazeOnDeviceInstrumentationTarget,
            sendProgressMessage = sendProgressMessage,
            hasCallbackBeenCalled = hasCallbackBeenCalled,
            completableDeferred = completableDeferred,
            outputLines = outputLines,
            onStatusCodeHandled = { hasCallbackBeenCalled = true },
          )
        },
        onExit = { exitCode ->
          if (!completableDeferred.isCompleted) {
            val errorMessage = buildInstrumentationFailureMessage(
              prefix =
                if (exitCode == 0) {
                  "Instrumentation exited before the on-device server reported ready."
                } else {
                  "Instrumentation process exited with code $exitCode before the on-device server reported ready."
                },
              outputLines = outputLines,
            )
            sendProgressMessage(errorMessage)
            completableDeferred.complete(
              DeviceConnectionStatus.DeviceConnectionError.ConnectionFailure(
                errorMessage = errorMessage,
              ),
            )
          }
        },
      )
      instrumentationStreamRef.set(handle)
    } catch (e: Exception) {
      if (!completableDeferred.isCompleted) {
        val errorMessage = buildInstrumentationFailureMessage(
          prefix = "Error connecting Trailblaze On-Device. ${e.message}",
          outputLines = outputLines,
        )
        sendProgressMessage(errorMessage)
        completableDeferred.complete(
          DeviceConnectionStatus.DeviceConnectionError.ConnectionFailure(
            errorMessage = errorMessage,
          ),
        )
      }
    }

    val result = withTimeoutOrNull(INSTRUMENTATION_START_TIMEOUT_MS) {
      completableDeferred.await()
    }
    if (result != null) {
      return result
    }

    val timeoutMessage = buildInstrumentationFailureMessage(
      prefix = "Timed out waiting ${INSTRUMENTATION_START_TIMEOUT_MS / 1000}s for Android instrumentation to start.",
      outputLines = outputLines,
    )
    val timeoutStatus = DeviceConnectionStatus.DeviceConnectionError.ConnectionFailure(
      errorMessage = timeoutMessage,
    )
    completableDeferred.complete(timeoutStatus)
    instrumentationStreamRef.get()?.let { runCatching { it.close() } }
    sendProgressMessage(timeoutMessage)
    return timeoutStatus
  }

  internal fun onInstrumentationOutputLine(
    line: String,
    trailblazeDeviceId: TrailblazeDeviceId,
    trailblazeOnDeviceInstrumentationTarget: TrailblazeOnDeviceInstrumentationTarget,
    sendProgressMessage: (String) -> Unit,
    hasCallbackBeenCalled: Boolean,
    completableDeferred: CompletableDeferred<DeviceConnectionStatus>,
    outputLines: List<String>,
    onStatusCodeHandled: () -> Unit,
  ) {
    if (completableDeferred.isCompleted) return
    Console.log("Instrumentation output: $line")
    if (!hasCallbackBeenCalled && line.contains("INSTRUMENTATION_STATUS_CODE:")) {
      sendProgressMessage("Trailblaze On-Device Connected Successfully!")
      Console.log("INSTRUMENTATION_STATUS_CODE found in output: $line")

      ioScope.launch {
        var attempts = 0
        var instrumentationRunning = false

        while (attempts < INSTRUMENTATION_PROCESS_VERIFY_ATTEMPTS && !completableDeferred.isCompleted) {
          delay(INSTRUMENTATION_PROCESS_VERIFY_DELAY_MS)
          instrumentationRunning = AndroidHostAdbUtils.isAppRunning(
            deviceId = trailblazeDeviceId,
            appId = trailblazeOnDeviceInstrumentationTarget.instrumentationProcessAppId,
          )
          attempts++

          if (instrumentationRunning) {
            sendProgressMessage("Instrumentation process verified as running!")
            completableDeferred.complete(
              DeviceConnectionStatus.WithTargetDevice.TrailblazeInstrumentationRunning(
                trailblazeDeviceId = trailblazeDeviceId,
              ),
            )
            break
          } else if (attempts < INSTRUMENTATION_PROCESS_VERIFY_ATTEMPTS) {
            sendProgressMessage(
              "Verifying instrumentation process... (${attempts * INSTRUMENTATION_PROCESS_VERIFY_DELAY_MS}ms)",
            )
          }
        }

        if (!instrumentationRunning && !completableDeferred.isCompleted) {
          val errorMessage =
            "Could not validate instrumentation process started after " +
              "${INSTRUMENTATION_PROCESS_VERIFY_ATTEMPTS * INSTRUMENTATION_PROCESS_VERIFY_DELAY_MS / 1000}s"
          sendProgressMessage(errorMessage)
          completableDeferred.complete(
            DeviceConnectionStatus.DeviceConnectionError.ConnectionFailure(
              errorMessage = errorMessage,
            ),
          )
        }
      }

      onStatusCodeHandled()
    } else if (line.contains("INSTRUMENTATION_CODE: 0")) {
      // `INSTRUMENTATION_CODE: 0` is ActivityManager reporting an abnormal end, and the line
      // before it is the only place the reason appears — `INSTRUMENTATION_RESULT:
      // shortMsg=Process crashed.` when the runner's process died, an initialization stack when
      // it threw. Reaching here without a prior INSTRUMENTATION_STATUS_CODE means it never got
      // as far as starting the test, so the tail is all the diagnosis there is; dropping it left
      // this failure unexplainable from CI output alone.
      val errorMessage = buildInstrumentationFailureMessage(
        prefix = "Error occurred during instrumentation process " +
          "(${trailblazeOnDeviceInstrumentationTarget.testAppId}).",
        outputLines = outputLines,
      )
      sendProgressMessage(errorMessage)
      Console.log(errorMessage)
      if (!completableDeferred.isCompleted) {
        completableDeferred.complete(
          DeviceConnectionStatus.DeviceConnectionError.ConnectionFailure(
            errorMessage = errorMessage,
          ),
        )
      }
    }
  }

  private fun appendOutputLine(
    outputLines: MutableList<String>,
    line: String,
  ) {
    synchronized(outputLines) {
      outputLines.add(line)
      while (outputLines.size > INSTRUMENTATION_OUTPUT_TAIL_LINES) {
        outputLines.removeAt(0)
      }
    }
  }

  internal fun buildInstrumentationFailureMessage(
    prefix: String,
    outputLines: List<String>,
  ): String {
    val outputTail =
      synchronized(outputLines) {
        outputLines.toList().takeLast(INSTRUMENTATION_OUTPUT_TAIL_LINES)
      }
    return if (outputTail.isEmpty()) {
      "$prefix No instrumentation output was received."
    } else {
      "$prefix Recent instrumentation output: ${outputTail.joinToString(" | ")}"
    }
  }

  // Function to get devices from adb
  suspend fun getAdbDevices(): List<TrailblazeDeviceId> =
    AndroidHostAdbUtils.listConnectedAdbDevices()

  // Function to get the device model name from adb
  fun getDeviceModelName(deviceId: TrailblazeDeviceId): String = try {
    AndroidHostAdbUtils.execAdbShellCommand(
      deviceId = deviceId,
      args = listOf("getprop", "ro.product.model"),
    ).lines().firstOrNull()?.takeIf { it.isNotBlank() } ?: deviceId.instanceId
  } catch (e: Exception) {
    deviceId.instanceId
  }

  /**
   * The HTTPS port each device's currently-running instrumentation was launched with.
   *
   * Instrumentation args are fixed at `am instrument` time, so this is the only host-side record
   * of where a live on-device runner sends its logs. Written only after a connect that actually
   * (re)launched, and cleared when one fails — see [connectToInstrumentationAndInstallAppIfNotAvailable].
   */
  private val lastLaunchedHttpsPortByDevice = ConcurrentHashMap<TrailblazeDeviceId, Int>()

  /**
   * Whether instrumentation must be relaunched because its log routing cannot be proven to match
   * [requestedHttpsPort].
   *
   * `connectToInstrumentation` reuses a live runner by returning before `am instrument` runs, so a
   * reused process keeps the immutable arg bundle it was launched with. Reusing one that points at
   * a different port is the same dead-socket failure the port threading exists to prevent, just
   * reached from the other side.
   *
   * [assumedRunningHttpsPort] is null when this daemon did not launch the runner (a previous
   * daemon left it alive, or this process just started). An unknown launcher used the device-side
   * default, since that is what every host path produced before the port was threaded — so treat
   * null as the default. That keeps the common default-port case reusing exactly as before, and
   * forces a relaunch only when the daemon is on a non-default port, where reuse is broken anyway.
   */
  internal fun requiresRelaunchForHttpsPort(
    assumedRunningHttpsPort: Int?,
    requestedHttpsPort: Int,
  ): Boolean = (assumedRunningHttpsPort ?: TrailblazeDevicePort.TRAILBLAZE_DEFAULT_HTTPS_PORT) !=
    requestedHttpsPort

  /**
   * [httpsPort] must be the port the daemon's HTTPS server actually bound (resolved by
   * `TrailblazePortManager`), and deliberately has no default: with the daemon on a non-default
   * port, a defaulted value reverse-forwards `tcp:52526` to a host port nothing listens on, and
   * every on-device log POST dies in that socket — the run executes on-device while the host
   * times out with "no progress". The same value is injected into the instrumentation args
   * (see [instrumentationArgsWithHttpsPort]) so the device POSTs to the port being reversed, and
   * a live runner whose args point elsewhere is relaunched rather than reused (see
   * [requiresRelaunchForHttpsPort]).
   */
  suspend fun connectToInstrumentationAndInstallAppIfNotAvailable(
    sendProgressMessage: (String) -> Unit,
    deviceId: TrailblazeDeviceId,
    trailblazeOnDeviceInstrumentationTarget: TrailblazeOnDeviceInstrumentationTarget,
    httpsPort: Int,
    additionalInstrumentationArgs: Map<String, String> = emptyMap(),
    forceRestart: Boolean = false,
    /** See [planConnectForceStop]. Not defaulted, for the reason given on [connectToInstrumentation]. */
    additionalForceStopTargets: Set<TrailblazeOnDeviceInstrumentationTarget>,
  ): DeviceConnectionStatus {
    val devicePort = deviceId.getTrailblazeOnDeviceSpecificPort()
    adbPortForward(deviceId, devicePort)
    adbPortReverse(deviceId, httpsPort)

    // Calls [connectToInstrumentationExclusive], not [connectToInstrumentation]: the route pinning
    // below already holds this device's connect lock, and a coroutine Mutex is not reentrant.
    return withRoutePinnedUnderDeviceLock(
      deviceId = deviceId,
      httpsPort = httpsPort,
      forceRestart = forceRestart,
      sendProgressMessage = sendProgressMessage,
    ) { effectiveForceRestart ->
      // Reuses existing on-device server if already running; only force-stops and
      // reinstalls when the server is not running (or a restart is required).
      connectToInstrumentationExclusive(
        trailblazeOnDeviceInstrumentationTarget = trailblazeOnDeviceInstrumentationTarget,
        trailblazeDeviceId = deviceId,
        sendProgressMessage = sendProgressMessage,
        additionalInstrumentationArgs = instrumentationArgsWithHttpsPort(
          additionalInstrumentationArgs = additionalInstrumentationArgs,
          httpsPort = httpsPort,
        ),
        forceRestart = effectiveForceRestart,
        additionalForceStopTargets = additionalForceStopTargets,
      )
    }
  }

  /**
   * Runs [connect] with the device's connect lock held, deciding stale routing and recording the
   * launched port inside that same lock.
   *
   * The whole read-decide-update of [lastLaunchedHttpsPortByDevice] has to be atomic per device,
   * not just the connect. Deciding stale routing before taking the lock lets a queued caller act
   * on a port entry that was only empty when it looked: two first-time connects on a non-default
   * HTTPS port both read "unknown", both conclude the route is stale, and the waiter then carries
   * that into [connect] as `forceRestart` — which bypasses the already-running reuse path and
   * force-stops the runner the first caller just launched and is already driving. Reading it under
   * the lock means the waiter sees the recorded port and reuses instead.
   */
  private suspend fun withRoutePinnedUnderDeviceLock(
    deviceId: TrailblazeDeviceId,
    httpsPort: Int,
    forceRestart: Boolean,
    sendProgressMessage: (String) -> Unit,
    connect: suspend (effectiveForceRestart: Boolean) -> DeviceConnectionStatus,
  ): DeviceConnectionStatus = deviceConnectMutex(deviceId).withLock {
    val staleRouting = requiresRelaunchForHttpsPort(
      assumedRunningHttpsPort = lastLaunchedHttpsPortByDevice[deviceId],
      requestedHttpsPort = httpsPort,
    )
    if (staleRouting && !forceRestart) {
      sendProgressMessage(
        "On-device runner may be routing logs to a different HTTPS port than $httpsPort — " +
          "relaunching instrumentation so its log endpoint matches this daemon.",
      )
    }

    val status = connect(forceRestart || staleRouting)

    // Record only on success: a failed launch may leave a process running with unknown args, and
    // forgetting is what makes the next connect relaunch instead of trusting a stale entry.
    if (status is DeviceConnectionStatus.DeviceConnectionError) {
      lastLaunchedHttpsPortByDevice.remove(deviceId)
    } else {
      lastLaunchedHttpsPortByDevice[deviceId] = httpsPort
    }
    status
  }

  /** Test seam for [withRoutePinnedUnderDeviceLock]. */
  internal suspend fun connectWithRoutePinnedForTest(
    deviceId: TrailblazeDeviceId,
    httpsPort: Int,
    forceRestart: Boolean = false,
    sendProgressMessage: (String) -> Unit = {},
    connect: suspend (effectiveForceRestart: Boolean) -> DeviceConnectionStatus,
  ): DeviceConnectionStatus = withRoutePinnedUnderDeviceLock(
    deviceId = deviceId,
    httpsPort = httpsPort,
    forceRestart = forceRestart,
    sendProgressMessage = sendProgressMessage,
    connect = connect,
  )

  /**
   * Overrides (never merely defaults) `trailblaze.httpsPort`: the value must be the one
   * `adbPortReverse` just installed, and a stale caller-supplied arg pointing anywhere else
   * recreates the dead-socket failure this function exists to prevent.
   */
  internal fun instrumentationArgsWithHttpsPort(
    additionalInstrumentationArgs: Map<String, String>,
    httpsPort: Int,
  ): Map<String, String> = additionalInstrumentationArgs +
    (TrailblazeDevicePort.HTTPS_PORT_INSTRUMENTATION_ARG_KEY to httpsPort.toString())

  /**
   * Forwards a harness's [TrailblazeOnDeviceInstrumentationTarget.forcedDriverType] as
   * `trailblaze.driverType`, so a driver the host resolved on this side is a driver the on-device
   * runtime knows was chosen. Called by [instrumentationAdbShellCommandArgs] rather than by its
   * callers, so the force is part of building an `am instrument` and no connect path can omit it.
   *
   * Overrides rather than defaults, for the same reason as [instrumentationArgsWithHttpsPort]: the
   * harness being launched is the ground truth about which driver is in play, and a caller-supplied
   * value naming a different one describes some other run.
   *
   * A null [TrailblazeOnDeviceInstrumentationTarget.forcedDriverType] adds nothing — the bundled
   * runner serves whichever driver a run selects, and injecting a force there would silence the
   * per-trail pin checks on the drivers that legitimately honor them.
   */
  internal fun instrumentationArgsWithForcedDriver(
    additionalInstrumentationArgs: Map<String, String>,
    trailblazeOnDeviceInstrumentationTarget: TrailblazeOnDeviceInstrumentationTarget,
  ): Map<String, String> {
    val forced = trailblazeOnDeviceInstrumentationTarget.forcedDriverType
      ?: return additionalInstrumentationArgs
    return additionalInstrumentationArgs +
      (TrailblazeDriverType.INSTRUMENTATION_ARG_KEY to forced.name)
  }
}
