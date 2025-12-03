package xyz.block.trailblaze.util

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import xyz.block.trailblaze.http.TrailblazeHttpClientFactory
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.model.DeviceConnectionStatus
import xyz.block.trailblaze.model.TrailblazeOnDeviceInstrumentationTarget
import xyz.block.trailblaze.util.AndroidHostAdbUtils.adbPortForward
import xyz.block.trailblaze.util.AndroidHostAdbUtils.adbPortReverse
import xyz.block.trailblaze.util.AndroidHostAdbUtils.createAdbCommandProcessBuilder
import xyz.block.trailblaze.util.AndroidHostAdbUtils.hasAlreadyBeenBuiltAndInstalledThisSession
import xyz.block.trailblaze.util.AndroidHostAdbUtils.isAppInstalled
import xyz.block.trailblaze.util.TrailblazeProcessBuilderUtils.runProcess
import java.io.File

object HostAndroidDeviceConnectUtils {

  val MAESTRO_APP_ID = "dev.mobile.maestro"
  val MAESTRO_TEST_APP_ID = "dev.mobile.maestro.test"

  val ioScope = CoroutineScope(Dispatchers.IO)

  suspend fun uninstallAllAndroidInstrumentationProcesses(
    trailblazeOnDeviceInstrumentationTargetTestApps: Set<TrailblazeOnDeviceInstrumentationTarget>,
    deviceId: String?,
  ) {
    val testAppIds: List<String> =
      trailblazeOnDeviceInstrumentationTargetTestApps.map { it.testAppId } + listOf(
        HostAndroidDeviceConnectUtils.MAESTRO_APP_ID,
        HostAndroidDeviceConnectUtils.MAESTRO_TEST_APP_ID,
      ).distinct()
    println("Ensure All Android Instrumentation Processes are Uninstalled.  IDs: $testAppIds")
    testAppIds.forEach { appId ->
      AndroidHostAdbUtils.forceStopApp(
        deviceId = deviceId,
        appId = appId,
      )
    }
  }

  private suspend fun buildWithGradleAndInstallTrailblazeOnDeviceInstrumentationTargetTestApp(
    trailblazeOnDeviceInstrumentationTarget: TrailblazeOnDeviceInstrumentationTarget,
    sendProgressMessage: (String) -> Unit,
  ): Boolean {
    val gitRoot = File(GitUtils.getGitRootViaCommand() ?: ".")
    sendProgressMessage("Building and Installing On-Device Trailblaze with Gradle (this may take a while)")
    // Start Gradle process
    val gradleProcess = ProcessBuilder(
      "./gradlew",
      trailblazeOnDeviceInstrumentationTarget.gradleInstallAndroidTestCommand,
    ).directory(gitRoot).redirectErrorStream(true).start()

    // Log Gradle output
    val gradleSystemOutListenerThread = Thread {
      try {
        val reader = gradleProcess.inputStream.bufferedReader()
        var line: String?
        while (reader.readLine().also { line = it } != null) {
          println("Gradle output: $line")
        }
      } catch (e: Exception) {
        println("Error reading Gradle output: ${e.message}")
      }
    }
    gradleSystemOutListenerThread.start()

    // Wait for Gradle to complete
    val gradleExit = gradleProcess.waitFor()
    gradleSystemOutListenerThread.interrupt() // Kill system out listening
    val gradleSuccessful = gradleExit == 0
    if (gradleSuccessful) {
      sendProgressMessage("Gradle test app setup successful")
    } else {
      sendProgressMessage("Gradle install failed with exit code $gradleExit")
    }
    return gradleSuccessful
  }

  private fun instrumentationProcessBuilder(
    testAppId: String,
    fqTestName: String,
    sendProgressMessage: (String) -> Unit,
    deviceId: String?,
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
    deviceId: String?,
    additionalInstrumentationArgs: Map<String, String> = emptyMap(),
    sendProgressMessage: (String) -> Unit,
  ): DeviceConnectionStatus {
    val trailblazeOnDeviceInstrumentationTarget = trailblazeOnDeviceInstrumentationTarget
    val completableDeferred: CompletableDeferred<DeviceConnectionStatus> = CompletableDeferred()
    var hasCallbackBeenCalled = false

    HostAndroidDeviceConnectUtils.uninstallAllAndroidInstrumentationProcesses(
      trailblazeOnDeviceInstrumentationTargetTestApps = setOf(trailblazeOnDeviceInstrumentationTarget),
      deviceId = deviceId,
    )
    buildWithGradleAndInstallTrailblazeOnDeviceInstrumentationTargetTestApp(
      trailblazeOnDeviceInstrumentationTarget = trailblazeOnDeviceInstrumentationTarget,
      sendProgressMessage = sendProgressMessage,
    )

    adbPortForward(
      deviceId = deviceId,
      localPort = 52526,
    )

    // Log instrumentation output
    ioScope.launch {
      try {
        val instrProcess = instrumentationProcessBuilder(
          testAppId = trailblazeOnDeviceInstrumentationTarget.testAppId,
          fqTestName = trailblazeOnDeviceInstrumentationTarget.fqTestName,
          sendProgressMessage = sendProgressMessage,
          deviceId = deviceId,
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
              val delayAmount = 2000L

              while (attempts < maxAttempts) {
                delay(delayAmount) // Wait between checks
                instrumentationRunning = AndroidHostAdbUtils.isAppRunning(
                  deviceId = deviceId,
                  appId = trailblazeOnDeviceInstrumentationTarget.testAppId,
                )
                attempts++

                if (instrumentationRunning) {
                  sendProgressMessage("Instrumentation process verified as running!")
                  completableDeferred.complete(
                    DeviceConnectionStatus.TrailblazeInstrumentationRunning(
                      deviceId = deviceId,
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
                  DeviceConnectionStatus.ConnectionFailure(
                    errorMessage = errorMessage,
                    deviceId = deviceId,
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
              DeviceConnectionStatus.ConnectionFailure(
                errorMessage = errorMessage,
                deviceId = deviceId,
              ),
            )
          }
        }
      } catch (e: Exception) {
        val errorMessage = "Error connecting Trailblaze On-Device. ${e.message}"
        sendProgressMessage(errorMessage)
        completableDeferred.complete(
          DeviceConnectionStatus.ConnectionFailure(
            errorMessage = errorMessage,
            deviceId = deviceId,
          ),
        )
      }
    }
    return completableDeferred.await()
  }

  // Function to get devices from adb
  suspend fun getAdbDevices(): List<AdbDevice> = try {
    val processBuilder = createAdbCommandProcessBuilder(args = listOf("devices"), deviceId = null)

    val processResult = processBuilder.runProcess(
      outputLineCallback = {},
    )

    processResult.outputLines.drop(1)
      .filter { it.isNotBlank() && it.contains("\tdevice") }
      .map { line ->
        val id = line.substringBefore("\t")
        AdbDevice(id, getDeviceModelName(id))
      }
  } catch (e: Exception) {
    emptyList()
  }

  suspend fun sentRequestStartTestWithYaml(request: RunYamlRequest): String {
    val client = TrailblazeHttpClientFactory.createDefaultHttpClient(30L)
    val json = Json.encodeToString(request)

    val response = client.post("http://localhost:52526/run") {
      contentType(ContentType.Application.Json)
      setBody(json)
    }

    val responseText = response.bodyAsText()
    client.close()
    return responseText
  }

  // Function to get the device model name from adb
  fun getDeviceModelName(deviceId: String): String = try {
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
    processResult.outputLines.firstOrNull() ?: deviceId
  } catch (e: Exception) {
    deviceId
  }

  suspend fun connectToInstrumentationAndInstallAppIfNotAvailable(
    sendProgressMessage: (String) -> Unit,
    deviceId: String?,
    port: Int = 52526,
    trailblazeOnDeviceInstrumentationTarget: TrailblazeOnDeviceInstrumentationTarget,
    additionalInstrumentationArgs: Map<String, String> = emptyMap(),
  ): DeviceConnectionStatus {
    adbPortForward(deviceId, port)
    adbPortReverse(deviceId, 8443)

    if (!hasAlreadyBeenBuiltAndInstalledThisSession(trailblazeOnDeviceInstrumentationTarget) ||
      !isAppInstalled(
        trailblazeOnDeviceInstrumentationTarget.testAppId,
        deviceId,
      )
    ) {
      val installSuccessful = buildWithGradleAndInstallTrailblazeOnDeviceInstrumentationTargetTestApp(
        trailblazeOnDeviceInstrumentationTarget = trailblazeOnDeviceInstrumentationTarget,
        sendProgressMessage = sendProgressMessage,
      )
      if (!installSuccessful) {
        return DeviceConnectionStatus.ConnectionFailure(
          errorMessage = "Gradle install failed",
          deviceId = deviceId,
        )
      } else {
        delay(1000)
      }
    }
    sendProgressMessage("On-Device Trailblaze Installed. Connecting to Trailblaze...")

    return HostAndroidDeviceConnectUtils.connectToInstrumentation(
      trailblazeOnDeviceInstrumentationTarget = trailblazeOnDeviceInstrumentationTarget,
      deviceId = deviceId,
      sendProgressMessage = sendProgressMessage,
      additionalInstrumentationArgs = additionalInstrumentationArgs,
    )
  }
}
