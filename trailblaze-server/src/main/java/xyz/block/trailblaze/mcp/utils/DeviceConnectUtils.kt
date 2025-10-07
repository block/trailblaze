package xyz.block.trailblaze.mcp.utils

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
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import xyz.block.trailblaze.http.TrailblazeHttpClientFactory
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.mcp.models.AdbDevice
import xyz.block.trailblaze.mcp.models.DeviceConnectionStatus
import xyz.block.trailblaze.mcp.utils.HostAdbCommandUtils.runProcess
import xyz.block.trailblaze.model.TargetTestApp
import java.io.File

object DeviceConnectUtils {

  val ioScope = CoroutineScope(Dispatchers.IO)

  // Helper to get git root directory
  private fun getGitRoot(): File? = try {
    val process = ProcessBuilder("git", "rev-parse", "--show-toplevel")
      .redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText().trim()
    val exit = process.waitFor()
    if (exit == 0 && output.isNotBlank()) File(output) else null
  } catch (e: Exception) {
    null
  }

  suspend fun isAppInstalled(appId: String, deviceId: String?): Boolean = listInstalledPackages(deviceId).any { it == appId }

  /**
   * Keep track on whether we've installed this test app this session
   */
  private val hasInstalledThisSessionMap = mutableMapOf<TargetTestApp, Boolean>()

  fun hasAlreadyBeenBuiltAndInstalledThisSession(targetTestApp: TargetTestApp): Boolean {
    val alreadyBeenInstalledThisSession: Boolean? = hasInstalledThisSessionMap[targetTestApp]
    hasInstalledThisSessionMap[targetTestApp] = true
    return alreadyBeenInstalledThisSession ?: false
  }

  suspend fun connectToInstrumentationAndInstallAppIfNotAvailable(
    sendProgressMessage: (String) -> Unit,
    deviceId: String? = null,
    port: Int = 52526,
    targetTestApp: TargetTestApp,
  ): DeviceConnectionStatus {
    adbPortForward(deviceId, port)
    adbPortReverse(deviceId, 8443)

    if (!hasAlreadyBeenBuiltAndInstalledThisSession(targetTestApp) ||
      !isAppInstalled(
        targetTestApp.testAppId,
        deviceId,
      )
    ) {
      val installSuccessful = buildWithGradleAndInstallTestApp(
        targetTestApp = targetTestApp,
        sendProgressMessage = sendProgressMessage,
      )
      if (!installSuccessful) {
        return DeviceConnectionStatus.ConnectionFailure("Gradle install failed")
      } else {
        delay(1000)
      }
    }
    sendProgressMessage("On-Device Trailblaze Installed. Connecting to Trailblaze...")

    return connectToInstrumentation(
      targetTestApp = targetTestApp,
      deviceId = deviceId,
      sendProgressMessage = sendProgressMessage,
    )
  }

  fun execShellCommand(deviceId: String?, shellArgs: List<String>): String {
    println("adb shell ${shellArgs.joinToString(" ")}")
    return HostAdbCommandUtils.createAdbCommandProcessBuilder(
      deviceId = deviceId,
      args = listOf(
        "shell",
      ) + shellArgs,
    ).runProcess {}.fullOutput
  }

  fun isAppRunning(deviceId: String?, appId: String): Boolean {
    val output = execShellCommand(deviceId, listOf("pidof", "$appId"))
    println("pidof $appId: $output")
    val isRunning = output.trim().isNotEmpty()
    return isRunning
  }

  /**
   * @return true if the condition was met within the timeout, false otherwise
   */
  fun tryUntilSuccessOrTimeout(
    maxWaitMs: Long,
    intervalMs: Long,
    conditionDescription: String,
    condition: () -> Boolean,
  ): Boolean {
    val startTime = Clock.System.now()
    var elapsedTime = 0L
    while (elapsedTime < maxWaitMs) {
      val conditionResult: Boolean = try {
        condition()
      } catch (e: Exception) {
        println("Ignored Exception while computing Condition [$conditionDescription], Exception [${e.message}]")
        false
      }
      if (conditionResult) {
        println("Condition [$conditionDescription] met after ${elapsedTime}ms")
        return true
      } else {
        println("Condition [$conditionDescription] not yet met after ${elapsedTime}ms with timeout of ${maxWaitMs}ms")
        Thread.sleep(intervalMs)
        elapsedTime = Clock.System.now().toEpochMilliseconds() - startTime.toEpochMilliseconds()
      }
    }
    println("Timed out (${maxWaitMs}ms limit) met [$conditionDescription] after ${elapsedTime}ms")
    return false
  }

  /**
   * @return true if the condition was met within the timeout, false otherwise
   */
  fun tryUntilSuccessOrThrowException(
    maxWaitMs: Long,
    intervalMs: Long,
    conditionDescription: String,
    condition: () -> Boolean,
  ) {
    val successful = tryUntilSuccessOrTimeout(
      maxWaitMs = maxWaitMs,
      intervalMs = intervalMs,
      conditionDescription = conditionDescription,
      condition = condition,
    )
    if (successful == false) {
      error("Timed out (${maxWaitMs}ms limit) met [$conditionDescription]")
    }
  }

  fun forceStopApp(
    deviceId: String?,
    appId: String,
  ) {
    if (isAppRunning(deviceId = deviceId, appId)) {
      execShellCommand(
        deviceId,
        listOf("am", "force-stop", appId),
      )
      tryUntilSuccessOrThrowException(
        maxWaitMs = 30_000,
        intervalMs = 200,
        "App $appId should be force stopped",
      ) {
        execShellCommand(
          deviceId = deviceId,
          shellArgs = listOf("dumpsys", "package", appId, "|", "grep", "stopped=true"),
        ).contains("stopped=true")
      }
    } else {
      println("App $appId does not have an active process, no need to force stop")
    }
  }

  val MAESTRO_APP_ID = "dev.mobile.maestro"
  val MAESTRO_TEST_APP_ID = "dev.mobile.maestro.test"

  suspend fun uninstallAllAndroidInstrumentationProcesses(targetTestApps: List<TargetTestApp>, deviceId: String?) {
    val testAppIds: List<String> = targetTestApps.map { it.testAppId } + listOf(
      MAESTRO_APP_ID,
      MAESTRO_TEST_APP_ID,
    ).distinct()
    println("Ensure All Android Instrumentation Processes are Uninstalled.  IDs: $testAppIds")
    testAppIds.forEach { appId ->
      forceStopApp(
        deviceId = deviceId,
        appId = appId,
      )
    }
  }

  suspend fun connectToInstrumentation(
    targetTestApp: TargetTestApp,
    deviceId: String?,
    sendProgressMessage: (String) -> Unit,
  ): DeviceConnectionStatus {
    val completableDeferred: CompletableDeferred<DeviceConnectionStatus> = CompletableDeferred()
    var hasCallbackBeenCalled = false

    uninstallAllAndroidInstrumentationProcesses(
      targetTestApps = listOf(element = targetTestApp),
      deviceId = deviceId,
    )
    buildWithGradleAndInstallTestApp(targetTestApp, sendProgressMessage)

    adbPortForward(
      deviceId = deviceId,
      localPort = 52526,
    )

    // Log instrumentation output
    ioScope.launch {
      try {
        val instrProcess = instrumentationProcessBuilder(
          testAppId = targetTestApp.testAppId,
          fqTestName = targetTestApp.fqTestName,
          sendProgressMessage = sendProgressMessage,
          deviceId = deviceId,
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
                instrumentationRunning = isAppRunning(
                  deviceId = deviceId,
                  appId = targetTestApp.testAppId,
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
                  DeviceConnectionStatus.ConnectionFailure(errorMessage),
                )
              }
            }

            hasCallbackBeenCalled = true
          }
        }
      } catch (e: Exception) {
        val errorMessage = "Error connecting Trailblaze On-Device. ${e.message}"
        sendProgressMessage(errorMessage)
        completableDeferred.complete(
          DeviceConnectionStatus.ConnectionFailure(errorMessage),
        )
      }
    }
    return completableDeferred.await()
  }

  private fun instrumentationProcessBuilder(
    testAppId: String,
    fqTestName: String,
    sendProgressMessage: (String) -> Unit,
    deviceId: String?,
  ): ProcessBuilder {
    sendProgressMessage(
      "Connecting to Android Test Instrumentation.",
    )
    // Start instrumentation
    val processBuilder = HostAdbCommandUtils.createAdbCommandProcessBuilder(
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

  private fun uninstallApp(appPackageId: String, deviceId: String? = null) {
    HostAdbCommandUtils.createAdbCommandProcessBuilder(
      deviceId = deviceId,
      args = listOf(
        "uninstall",
        appPackageId,
      ),
    ).start().waitFor()
  }

  private suspend fun buildWithGradleAndInstallTestApp(
    targetTestApp: TargetTestApp,
    sendProgressMessage: (String) -> Unit,
  ): Boolean {
    val gitRoot = getGitRoot() ?: File(".")
    sendProgressMessage("Building and Installing On-Device Trailblaze (this may take a while)")
    // Start Gradle process
    val gradleProcess = ProcessBuilder(
      "./gradlew",
      targetTestApp.gradleInstallAndroidTestCommand,
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

  fun adbPortForward(
    deviceId: String?,
    localPort: Int,
    remotePort: Int = localPort,
  ): Process = try {
    // Check if forward already exists
    if (isPortForwardAlreadyActive(localPort, remotePort)) {
      println("Port forward tcp:$localPort -> tcp:$remotePort already exists")
      ProcessBuilder("echo", "Port forward already exists").start()
    } else {
      println("Setting up port forward tcp:$localPort -> tcp:$remotePort")
      HostAdbCommandUtils.createAdbCommandProcessBuilder(
        deviceId = deviceId,
        args = listOf("forward", "tcp:$localPort", "tcp:$remotePort"),
      ).start()
    }
  } catch (e: Exception) {
    throw RuntimeException("Failed to start port forwarding: ${e.message}")
  }

  fun adbPortReverse(
    deviceId: String?,
    localPort: Int,
    remotePort: Int = localPort,
  ): Process = try {
    // Check if forward already exists
    if (isPortReverseAlreadyActive(localPort, remotePort)) {
      println("Port reverse tcp:$localPort -> tcp:$remotePort already exists")
      ProcessBuilder("echo", "Port reverse already exists").start()
    } else {
      println("Setting up port forward tcp:$localPort -> tcp:$remotePort")
      HostAdbCommandUtils.createAdbCommandProcessBuilder(
        deviceId = deviceId,
        args = listOf("reverse", "tcp:$localPort", "tcp:$remotePort"),
      ).start()
    }
  } catch (e: Exception) {
    throw RuntimeException("Failed to start port forwarding: ${e.message}")
  }

  // Simplified helper to check if a port reverse already exists
  private fun isPortReverseAlreadyActive(localPort: Int, remotePort: Int): Boolean = try {
    val result = HostAdbCommandUtils.createAdbCommandProcessBuilder(
      deviceId = null,
      args = listOf("reverse", "--list"),
    ).runProcess({})

    result.outputLines.any { line ->
      line.contains("tcp:$localPort") && line.contains("tcp:$remotePort")
    }
  } catch (e: Exception) {
    false // If we can't check, assume it doesn't exist
  }

  // Simplified helper to check if a port forward already exists
  private fun isPortForwardAlreadyActive(localPort: Int, remotePort: Int): Boolean = try {
    val result = HostAdbCommandUtils.createAdbCommandProcessBuilder(
      deviceId = null,
      args = listOf("forward", "--list"),
    ).runProcess({})

    result.outputLines.any { line ->
      line.contains("tcp:$localPort") && line.contains("tcp:$remotePort")
    }
  } catch (e: Exception) {
    false // If we can't check, assume it doesn't exist
  }

  // Function to get the device model name from adb
  fun getDeviceModelName(deviceId: String): String = try {
    val processBuilder = HostAdbCommandUtils.createAdbCommandProcessBuilder(
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

  // Function to get devices from adb
  suspend fun getAdbDevices(): List<AdbDevice> = try {
    val processBuilder = HostAdbCommandUtils.createAdbCommandProcessBuilder(args = listOf("devices"), deviceId = null)

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

  // Function to list installed packages on device
  suspend fun listInstalledPackages(deviceId: String? = null): List<String> = try {
    val processBuilder = HostAdbCommandUtils.createAdbCommandProcessBuilder(
      deviceId = deviceId,
      args = listOf(
        "shell",
        "pm",
        "list",
        "packages",
      ),
    )

    val processResult = processBuilder.runProcess {}

    processResult.outputLines
      .filter { it.isNotBlank() && it.startsWith("package:") }
      .map { line ->
        line.substringAfter("package:")
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
}
