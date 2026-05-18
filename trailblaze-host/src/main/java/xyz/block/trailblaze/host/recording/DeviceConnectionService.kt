package xyz.block.trailblaze.host.recording

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.devices.WebInstanceIds
import xyz.block.trailblaze.host.devices.MaestroConnectedDevice
import xyz.block.trailblaze.host.devices.TrailblazeDeviceService
import xyz.block.trailblaze.host.devices.WebBrowserState
import xyz.block.trailblaze.host.rules.BasePlaywrightNativeTest
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.llm.TrailblazeReferrer
import xyz.block.trailblaze.logs.client.TrailblazeSessionManager
import xyz.block.trailblaze.mcp.android.ondevice.rpc.OnDeviceRpcClient
import xyz.block.trailblaze.model.TrailblazeConfig
import xyz.block.trailblaze.playwright.recording.PlaywrightDeviceScreenStream
import xyz.block.trailblaze.playwright.recording.PlaywrightInteractionToolFactory
import xyz.block.trailblaze.ui.TrailblazeDeviceManager
import xyz.block.trailblaze.ui.recording.ConnectionState
import xyz.block.trailblaze.ui.recording.RecordingDeviceConnection
import xyz.block.trailblaze.ui.recording.formatDeviceLabel
import xyz.block.trailblaze.util.AccessibilityServiceSetupUtils
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.HostAndroidDeviceConnectUtils
import java.io.IOException

/**
 * Establishes live connections to devices for the recording surface. Shared between the
 * desktop [xyz.block.trailblaze.ui.tabs.recording.RecordingTabComposable] and the HTTP
 * [xyz.block.trailblaze.host.recording.rpc.DeviceApiEndpoint] so both surfaces use
 * identical connection logic.
 *
 * Thread-safe: [connectToDevice] is a suspend function and all state mutations inside it
 * are confined to IO.
 */
class DeviceConnectionService(private val deviceManager: TrailblazeDeviceManager) {

  /**
   * Connects to [device] and returns the appropriate screen stream + tool factory wrapped
   * in a [ConnectionState]. Runs device creation on the IO dispatcher.
   *
   * Idempotent on the Playwright-native path (reuses a running browser). Android and iOS
   * paths are not idempotent — callers must ensure only one connection is live per device
   * at a time.
   */
  suspend fun connectToDevice(
    device: TrailblazeConnectedDeviceSummary,
  ): ConnectionState = try {
    when (device.platform) {
      TrailblazeDevicePlatform.WEB -> connectWeb(device)
      TrailblazeDevicePlatform.ANDROID -> connectAndroid(device)
      TrailblazeDevicePlatform.IOS -> connectIos(device)
      TrailblazeDevicePlatform.DESKTOP -> ConnectionState.Error(
        "Recording is not wired up for the Compose desktop driver yet. " +
          "Use the hidden `trailblaze desktop snapshot` command for one-shot captures.",
      )
    }
  } catch (e: Exception) {
    val msg = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
    ConnectionState.Error("Connection failed: $msg")
  }

  private suspend fun connectWeb(device: TrailblazeConnectedDeviceSummary): ConnectionState {
    val instanceId = device.instanceId
    val pageManager = deviceManager.webBrowserManager.getPageManager(instanceId)
      ?: run {
        val stateFlow = deviceManager.webBrowserManager.browserStateFlow(instanceId)
        deviceManager.webBrowserManager.launchBrowser(instanceId = instanceId, headless = true)
        val terminal = withContext(Dispatchers.IO) {
          stateFlow.first { it is WebBrowserState.Running || it is WebBrowserState.Error }
        }
        if (terminal is WebBrowserState.Error) {
          return ConnectionState.Error("Failed to launch browser '$instanceId': ${terminal.message}")
        }
        deviceManager.webBrowserManager.getPageManager(instanceId)
          ?: return ConnectionState.Error("Failed to launch browser '$instanceId'")
      }

    val stream = PlaywrightDeviceScreenStream(pageManager)
    val toolFactory = PlaywrightInteractionToolFactory(stream)

    val targetTestApp = deviceManager.getCurrentSelectedTargetApp()
    val customToolClasses =
      targetTestApp?.getCustomToolsForDriver(device.trailblazeDriverType) ?: emptySet()
    val playwrightTest = BasePlaywrightNativeTest(
      trailblazeLlmModel = deviceManager.currentTrailblazeLlmModelProvider(),
      customToolClasses = customToolClasses,
      appTarget = targetTestApp,
      trailblazeDeviceId = device.trailblazeDeviceId,
      existingBrowserManager = pageManager,
    )
    deviceManager.setActivePlaywrightNativeTest(device.trailblazeDeviceId, playwrightTest)

    return ConnectionState.Connected(
      RecordingDeviceConnection(
        stream = stream,
        toolFactory = toolFactory,
        deviceLabel = formatDeviceLabel(device),
        trailblazeDeviceId = device.trailblazeDeviceId,
        trailblazeDriverType = device.trailblazeDriverType,
      ),
    )
  }

  private suspend fun connectAndroid(device: TrailblazeConnectedDeviceSummary): ConnectionState {
    val targetTestApp = deviceManager.getCurrentSelectedTargetApp()
      ?: return ConnectionState.Error(
        "No target app selected. Pick one in the Target dropdown before connecting.",
      )
    val instrumentationTarget = targetTestApp.getTrailblazeOnDeviceInstrumentationTarget()
    val needsAccessibility =
      device.trailblazeDriverType == TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY

    val rpcClient = OnDeviceRpcClient(
      trailblazeDeviceId = device.trailblazeDeviceId,
      sendProgressMessage = { Console.log("[DeviceConnectionService] $it") },
    )
    val screenStateProvider = OnDeviceRpcScreenStateProvider(
      rpc = rpcClient,
      requireAccessibilityService = needsAccessibility,
    )

    val (initialResponse, recordingSessionId) = withContext(Dispatchers.IO) {
      HostAndroidDeviceConnectUtils.connectToInstrumentationAndInstallAppIfNotAvailable(
        sendProgressMessage = { Console.log("[DeviceConnectionService] $it") },
        deviceId = device.trailblazeDeviceId,
        trailblazeOnDeviceInstrumentationTarget = instrumentationTarget,
        additionalInstrumentationArgs = deviceManager.onDeviceInstrumentationArgsProvider(),
      )
      if (needsAccessibility) {
        AccessibilityServiceSetupUtils.enableAccessibilityService(
          deviceId = device.trailblazeDeviceId,
          hostPackage = instrumentationTarget.testAppId,
          sendProgressMessage = { Console.log("[DeviceConnectionService] $it") },
        )
      }
      rpcClient.waitForReady(
        timeoutMs = 60_000L,
        requireAndroidAccessibilityService = needsAccessibility,
      )

      val response = screenStateProvider.getScreenState(includeScreenshot = false)
        ?: throw IOException("GetScreenState failed at connect")
      response to TrailblazeSessionManager.generateSessionId("recording")
    }

    val settingsState = deviceManager.settingsRepo.serverStateFlow.value
    val runYamlRequestTemplate = RunYamlRequest(
      yaml = "",
      testName = "recording",
      useRecordedSteps = false,
      trailblazeLlmModel = deviceManager.currentTrailblazeLlmModelProvider(),
      targetAppName = settingsState.appConfig.selectedTargetAppId,
      trailFilePath = null,
      config = TrailblazeConfig(
        overrideSessionId = recordingSessionId,
        sendSessionStartLog = false,
        sendSessionEndLog = false,
        browserHeadless = !settingsState.appConfig.showWebBrowser,
        preferHostAgent = settingsState.appConfig.preferHostAgent,
        captureNetworkTraffic = settingsState.appConfig.captureNetworkTraffic,
      ),
      trailblazeDeviceId = device.trailblazeDeviceId,
      driverType = device.trailblazeDriverType,
      referrer = TrailblazeReferrer.RECORDING_TAB_REPLAY,
    )

    val stream = OnDeviceRpcDeviceScreenStream(
      rpc = rpcClient,
      provider = screenStateProvider,
      runYamlRequestTemplate = runYamlRequestTemplate,
      initialDeviceWidth = initialResponse.deviceWidth,
      initialDeviceHeight = initialResponse.deviceHeight,
    )
    val toolFactory = MaestroInteractionToolFactory(
      deviceWidth = stream.deviceWidth,
      deviceHeight = stream.deviceHeight,
    )
    return ConnectionState.Connected(
      RecordingDeviceConnection(
        stream = stream,
        toolFactory = toolFactory,
        deviceLabel = formatDeviceLabel(device),
        trailblazeDeviceId = device.trailblazeDeviceId,
        trailblazeDriverType = device.trailblazeDriverType,
      ),
    )
  }

  private suspend fun connectIos(device: TrailblazeConnectedDeviceSummary): ConnectionState {
    val connectedDevice = withContext(Dispatchers.IO) {
      TrailblazeDeviceService.getConnectedDevice(device.trailblazeDeviceId, device.trailblazeDriverType)
    } ?: return ConnectionState.Error("Device not found: ${device.instanceId}")

    val maestroDevice = connectedDevice as? MaestroConnectedDevice
      ?: return ConnectionState.Error(
        "Recording currently requires a Maestro-backed device; got ${connectedDevice::class.simpleName}",
      )
    val driver = maestroDevice.getMaestroDriver()
    val stream = MaestroDeviceScreenStream(driver)
    val toolFactory = MaestroInteractionToolFactory(
      deviceWidth = stream.deviceWidth,
      deviceHeight = stream.deviceHeight,
    )
    return ConnectionState.Connected(
      RecordingDeviceConnection(
        stream = stream,
        toolFactory = toolFactory,
        deviceLabel = formatDeviceLabel(device),
        trailblazeDeviceId = device.trailblazeDeviceId,
        trailblazeDriverType = device.trailblazeDriverType,
      ),
    )
  }
}
