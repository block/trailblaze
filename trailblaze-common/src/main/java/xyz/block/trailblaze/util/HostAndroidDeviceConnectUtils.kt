package xyz.block.trailblaze.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDevicePort
import xyz.block.trailblaze.devices.TrailblazeDevicePort.getTrailblazeOnDeviceSpecificPort
import xyz.block.trailblaze.model.DeviceConnectionStatus
import xyz.block.trailblaze.model.TrailblazeOnDeviceInstrumentationTarget
import xyz.block.trailblaze.util.AndroidHostAdbUtils.adbPortForward
import xyz.block.trailblaze.util.AndroidHostAdbUtils.adbPortReverse
import xyz.block.trailblaze.util.AndroidHostAdbUtils.createAdbCommandProcessBuilder
import xyz.block.trailblaze.util.TrailblazeProcessBuilderUtils.runProcess

object HostAndroidDeviceConnectUtils {

  const val MAESTRO_APP_ID = "dev.mobile.maestro"
  const val MAESTRO_TEST_APP_ID = "dev.mobile.maestro.test"

  val ioScope = CoroutineScope(Dispatchers.IO)

  fun forceStopAllAndroidInstrumentationProcesses(
    trailblazeOnDeviceInstrumentationTargetTestApps: Set<TrailblazeOnDeviceInstrumentationTarget>,
    deviceId: TrailblazeDeviceId,
  ) {
    val testAppIds: List<String> =
      trailblazeOnDeviceInstrumentationTargetTestApps.map { it.testAppId } + listOf(
        HostAndroidDeviceConnectUtils.MAESTRO_APP_ID,
        HostAndroidDeviceConnectUtils.MAESTRO_TEST_APP_ID,
      ).distinct()
    println("Force stopping all Android instrumentation processes. IDs: $testAppIds")
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
      println(errorMessage)
      return false
    }

    sendProgressMessage("Installing pre-compiled test APK...")

    val installSuccess = PrecompiledApkInstaller.extractAndInstallPrecompiledApk(
      resourcePath = PrecompiledApkInstaller.PRECOMPILED_APK_RESOURCE_PATH,
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

  private fun instrumentationProcessBuilder(
    testAppId: String,
    fqTestName: String,
    sendProgressMessage: (String) -> Unit,
    deviceId: TrailblazeDeviceId,
    additionalInstrumentationArgs: Map<String, String> = emptyMap(),
  ): ProcessBuilder {
    sendProgressMessage(
      "Connecting to Android Test Instrumentation.",
    )
    // Start instrumentation
    val processBuilder = createAdbCommandProcessBuilder(
      deviceId = deviceId,
      args = buildList {
        addAll(
          listOf(
            "shell",
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
            fqTestName,
          ),
        )

        // Use Reverse Proxy because we are connected
        addAll(
          listOf(
            "-e",
            "trailblaze.reverseProxy",
            "true",
          ),
        )

        // Use Reverse Proxy because we are connected
        addAll(
          listOf(
            "-e",
            TrailblazeDevicePort.INSTRUMENTATION_ARG_KEY,
            deviceId.getTrailblazeOnDeviceSpecificPort().toString(),
          ),
        )

        listOf(
          "OPENAI_API_KEY",
          "DATABRICKS_TOKEN",
        ).forEach { envVar ->
          System.getenv(envVar)?.let {
            addAll(listOf("-e", envVar, it))
          }
        }

        additionalInstrumentationArgs.forEach { (key, value) ->
          addAll(listOf("-e", key, value))
        }

        add("$testAppId/androidx.test.runner.AndroidJUnitRunner")
      },
    )

    // Get the environment map
    System.getenv().keys.forEach { envVar ->
      val value = System.getenv(envVar)
      if (value != null) {
        processBuilder.environment()[envVar] = value
      } else {
        println("Warning: $envVar is not set in the environment")
      }
    }

    return processBuilder
  }

  suspend fun connectToInstrumentation(
    trailblazeOnDeviceInstrumentationTarget: TrailblazeOnDeviceInstrumentationTarget,
    trailblazeDeviceId: TrailblazeDeviceId,
    additionalInstrumentationArgs: Map<String, String> = emptyMap(),
    sendProgressMessage: (String) -> Unit,
  ): DeviceConnectionStatus {
    val trailblazeOnDeviceInstrumentationTarget = trailblazeOnDeviceInstrumentationTarget
    val completableDeferred: CompletableDeferred<DeviceConnectionStatus> = CompletableDeferred()
    var hasCallbackBeenCalled = false

    // Always force stop and reinstall to ensure a clean slate
    forceStopAllAndroidInstrumentationProcesses(
      trailblazeOnDeviceInstrumentationTargetTestApps = setOf(trailblazeOnDeviceInstrumentationTarget),
      deviceId = trailblazeDeviceId,
    )
    installPrecompiledTrailblazeOnDeviceInstrumentationTargetTestApp(
      trailblazeOnDeviceInstrumentationTarget = trailblazeOnDeviceInstrumentationTarget,
      trailblazeDeviceId = trailblazeDeviceId,
      sendProgressMessage = sendProgressMessage,
    )

    adbPortForward(
      deviceId = trailblazeDeviceId,
      localPort = trailblazeDeviceId.getTrailblazeOnDeviceSpecificPort(),
    )

    // Log instrumentation output
    ioScope.launch {
      try {
        val instrProcess = instrumentationProcessBuilder(
          testAppId = trailblazeOnDeviceInstrumentationTarget.testAppId,
          fqTestName = trailblazeOnDeviceInstrumentationTarget.fqTestName,
          sendProgressMessage = sendProgressMessage,
          deviceId = trailblazeDeviceId,
          additionalInstrumentationArgs = additionalInstrumentationArgs,
        )

        instrProcess.runProcess { line ->
          println("Instrumentation output: $line")
          // Update status based on output
          if (!hasCallbackBeenCalled && line.contains("INSTRUMENTATION_STATUS_CODE:")) {
            sendProgressMessage("Trailblaze On-Device Connected Successfully!")
            println("INSTRUMENTATION_STATUS_CODE found in output: $line")

            // Poll for up to 5 seconds to verify instrumentation is actually running
            ioScope.launch {
              var attempts = 0
              val maxAttempts = 5
              var instrumentationRunning = false
              val delayAmount = 1000L

              while (attempts < maxAttempts) {
                delay(delayAmount) // Wait between checks
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
                } else if (attempts < maxAttempts) {
                  sendProgressMessage("Verifying instrumentation process... (${attempts * delayAmount}ms)")
                }
              }

              if (!instrumentationRunning) {
                val errorMessage = "Could not validate instrumentation process started after 5 seconds"
                sendProgressMessage(errorMessage)
                completableDeferred.complete(
                  DeviceConnectionStatus.DeviceConnectionError.ConnectionFailure(
                    errorMessage = errorMessage,
                  ),
                )
              }
            }

            hasCallbackBeenCalled = true
          } else if (line.contains("INSTRUMENTATION_CODE: 0")) {
            val errorMessage = "Error occurred during instrumentation process."
            sendProgressMessage(errorMessage)
            println(errorMessage)
            completableDeferred.complete(
              DeviceConnectionStatus.DeviceConnectionError.ConnectionFailure(
                errorMessage = errorMessage,
              ),
            )
          }
        }
      } catch (e: Exception) {
        val errorMessage = "Error connecting Trailblaze On-Device. ${e.message}"
        sendProgressMessage(errorMessage)
        completableDeferred.complete(
          DeviceConnectionStatus.DeviceConnectionError.ConnectionFailure(
            errorMessage = errorMessage,
          ),
        )
      }
    }
    return completableDeferred.await()
  }

  // Function to get devices from adb
  suspend fun getAdbDevices(): List<TrailblazeDeviceId> = try {
    val processBuilder = createAdbCommandProcessBuilder(
      args = listOf("devices"),
      deviceId = null
    )

    val processResult = processBuilder.runProcess(
      outputLineCallback = {},
    )

    processResult.outputLines.drop(1)
      .filter { it.isNotBlank() && it.contains("\tdevice") }
      .map { line ->
        val adbId = line.substringBefore("\t")
        TrailblazeDeviceId(
          trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
          instanceId = adbId,
        )
      }
  } catch (e: Exception) {
    emptyList()
  }

  // Function to get the device model name from adb
  fun getDeviceModelName(deviceId: TrailblazeDeviceId): String = try {
    val processBuilder = createAdbCommandProcessBuilder(
      deviceId = deviceId,
      args = listOf(
        "shell",
        "getprop",
        "ro.product.model",
      ),
    )
    val processResult = processBuilder.runProcess(
      outputLineCallback = {},
    )
    processResult.outputLines.firstOrNull() ?: deviceId.instanceId
  } catch (e: Exception) {
    deviceId.instanceId
  }

  suspend fun connectToInstrumentationAndInstallAppIfNotAvailable(
    sendProgressMessage: (String) -> Unit,
    deviceId: TrailblazeDeviceId,
    trailblazeOnDeviceInstrumentationTarget: TrailblazeOnDeviceInstrumentationTarget,
    additionalInstrumentationArgs: Map<String, String> = emptyMap(),
  ): DeviceConnectionStatus {
    val devicePort = deviceId.getTrailblazeOnDeviceSpecificPort()
    adbPortForward(deviceId, devicePort)
    adbPortReverse(deviceId, 8443)

    // connectToInstrumentation will handle uninstall and reinstall for a clean slate
    return HostAndroidDeviceConnectUtils.connectToInstrumentation(
      trailblazeOnDeviceInstrumentationTarget = trailblazeOnDeviceInstrumentationTarget,
      trailblazeDeviceId = deviceId,
      sendProgressMessage = sendProgressMessage,
      additionalInstrumentationArgs = additionalInstrumentationArgs,
    )
  }
}
