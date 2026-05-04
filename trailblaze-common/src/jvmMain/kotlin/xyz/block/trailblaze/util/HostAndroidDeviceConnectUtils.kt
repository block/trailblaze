package xyz.block.trailblaze.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import xyz.block.trailblaze.android.tools.shellEscape
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDevicePort
import xyz.block.trailblaze.devices.TrailblazeDevicePort.getTrailblazeOnDeviceSpecificPort
import xyz.block.trailblaze.model.DeviceConnectionStatus
import xyz.block.trailblaze.model.TrailblazeOnDeviceInstrumentationTarget
import xyz.block.trailblaze.util.AndroidHostAdbUtils.adbPortForward
import xyz.block.trailblaze.util.AndroidHostAdbUtils.adbPortReverse
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference

object HostAndroidDeviceConnectUtils {

  const val MAESTRO_APP_ID = "dev.mobile.maestro"
  const val MAESTRO_TEST_APP_ID = "dev.mobile.maestro.test"
  private const val INSTRUMENTATION_START_TIMEOUT_MS = 15_000L
  private const val INSTRUMENTATION_PROCESS_VERIFY_ATTEMPTS = 5
  private const val INSTRUMENTATION_PROCESS_VERIFY_DELAY_MS = 1_000L
  private const val INSTRUMENTATION_OUTPUT_TAIL_LINES = 20

  val ioScope = CoroutineScope(Dispatchers.IO)

  fun forceStopAllAndroidInstrumentationProcesses(
    trailblazeOnDeviceInstrumentationTargetTestApps: Set<TrailblazeOnDeviceInstrumentationTarget>,
    deviceId: TrailblazeDeviceId,
  ) {
    val testAppIds: List<String> =
      trailblazeOnDeviceInstrumentationTargetTestApps.map { it.testAppId } + listOf(
        MAESTRO_APP_ID,
        MAESTRO_TEST_APP_ID,
      ).distinct()

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
   */
  internal fun instrumentationAdbShellCommandArgs(
    testAppId: String,
    fqTestName: String,
    deviceId: TrailblazeDeviceId,
    additionalInstrumentationArgs: Map<String, String> = emptyMap(),
  ): List<String> = buildList {
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
        fqTestName.shellEscape(),
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
    additionalInstrumentationArgs.forEach { (key, value) ->
      addAll(listOf("-e", key.shellEscape(), value.shellEscape()))
    }

    add("$testAppId/androidx.test.runner.AndroidJUnitRunner".shellEscape())
  }

  suspend fun connectToInstrumentation(
    trailblazeOnDeviceInstrumentationTarget: TrailblazeOnDeviceInstrumentationTarget,
    trailblazeDeviceId: TrailblazeDeviceId,
    additionalInstrumentationArgs: Map<String, String> = emptyMap(),
    sendProgressMessage: (String) -> Unit,
    forceRestart: Boolean = false,
  ): DeviceConnectionStatus {
    val trailblazeOnDeviceInstrumentationTarget = trailblazeOnDeviceInstrumentationTarget
    val completableDeferred: CompletableDeferred<DeviceConnectionStatus> = CompletableDeferred()
    var hasCallbackBeenCalled = false

    // Check if the on-device server is already running. If so, reuse it instead of
    // force-stopping and reinstalling, which would clobber any active connections
    // (e.g., MCP screen-state queries, accessibility driver).
    val alreadyRunning = !forceRestart && AndroidHostAdbUtils.isAppRunning(
      deviceId = trailblazeDeviceId,
      appId = trailblazeOnDeviceInstrumentationTarget.testAppId,
    )

    if (alreadyRunning) {
      // Even if running, verify the installed APK matches the bundled version.
      // This handles brew upgrades and local source rebuilds transparently.
      if (PrecompiledApkInstaller.isInstalledApkUpToDate(trailblazeDeviceId)) {
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
    forceStopAllAndroidInstrumentationProcesses(
      trailblazeOnDeviceInstrumentationTargetTestApps = setOf(trailblazeOnDeviceInstrumentationTarget),
      deviceId = trailblazeDeviceId,
    )
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

    adbPortForward(
      deviceId = trailblazeDeviceId,
      localPort = trailblazeDeviceId.getTrailblazeOnDeviceSpecificPort(),
    )

    val outputLines = Collections.synchronizedList(mutableListOf<String>())
    val instrumentationStreamRef = AtomicReference<AutoCloseable?>()

    sendProgressMessage("Connecting to Android Test Instrumentation.")
    try {
      val command = instrumentationAdbShellCommandArgs(
        testAppId = trailblazeOnDeviceInstrumentationTarget.testAppId,
        fqTestName = trailblazeOnDeviceInstrumentationTarget.fqTestName,
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

  private fun onInstrumentationOutputLine(
    line: String,
    trailblazeDeviceId: TrailblazeDeviceId,
    trailblazeOnDeviceInstrumentationTarget: TrailblazeOnDeviceInstrumentationTarget,
    sendProgressMessage: (String) -> Unit,
    hasCallbackBeenCalled: Boolean,
    completableDeferred: CompletableDeferred<DeviceConnectionStatus>,
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
            appId = trailblazeOnDeviceInstrumentationTarget.testAppId,
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
      val errorMessage = "Error occurred during instrumentation process."
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

  private fun buildInstrumentationFailureMessage(
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

  suspend fun connectToInstrumentationAndInstallAppIfNotAvailable(
    sendProgressMessage: (String) -> Unit,
    deviceId: TrailblazeDeviceId,
    trailblazeOnDeviceInstrumentationTarget: TrailblazeOnDeviceInstrumentationTarget,
    additionalInstrumentationArgs: Map<String, String> = emptyMap(),
    httpsPort: Int = TrailblazeDevicePort.TRAILBLAZE_DEFAULT_HTTPS_PORT,
    forceRestart: Boolean = false,
  ): DeviceConnectionStatus {
    val devicePort = deviceId.getTrailblazeOnDeviceSpecificPort()
    adbPortForward(deviceId, devicePort)
    adbPortReverse(deviceId, httpsPort)

    // Reuses existing on-device server if already running; only force-stops and
    // reinstalls when the server is not running (or forceRestart is true).
    return HostAndroidDeviceConnectUtils.connectToInstrumentation(
      trailblazeOnDeviceInstrumentationTarget = trailblazeOnDeviceInstrumentationTarget,
      trailblazeDeviceId = deviceId,
      sendProgressMessage = sendProgressMessage,
      additionalInstrumentationArgs = additionalInstrumentationArgs,
      forceRestart = forceRestart,
    )
  }
}
