package xyz.block.trailblaze.toolcalls

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.junit.Test
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.mobile.tools.ListInstalledAppsTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.PasteClipboardTrailblazeTool

/**
 * The "this tool can't run on this device" wording: it must name the device the call landed on and
 * where the tool does run, so a call routed to the wrong device reads as a routing mistake.
 */
class ToolAvailabilityMessagesTest {

  private val webDevice = TrailblazeDeviceId("playwright-native", TrailblazeDevicePlatform.WEB)

  @Test
  fun `a mobile primitive run on a web device names the device and the platforms it supports`() {
    val result = runBlocking {
      ListInstalledAppsTrailblazeTool.execute(context(TrailblazeDriverType.PLAYWRIGHT_NATIVE, webDevice))
    }

    assertThat(result).isInstanceOf(TrailblazeToolResult.Error.ExceptionThrown::class)
    assertThat((result as TrailblazeToolResult.Error.ExceptionThrown).errorMessage).isEqualTo(
      "mobile_listInstalledApps runs on Android and iOS only, and this device is " +
        "web/playwright-native (Web Browser).",
    )
  }

  @Test
  fun `a tool that needs a mobile agent still names the web device it landed on`() {
    // Browser sessions have no Maestro agent, so the platform check must come before that one.
    val result = runBlocking {
      PasteClipboardTrailblazeTool.execute(context(TrailblazeDriverType.PLAYWRIGHT_NATIVE, webDevice))
    }

    assertThat((result as TrailblazeToolResult.Error.ExceptionThrown).errorMessage).isEqualTo(
      "mobile_pasteClipboard runs on Android and iOS only, and this device is " +
        "web/playwright-native (Web Browser).",
    )
  }

  @Test
  fun `the shipped toolsets restrict mobile_listInstalledApps to Android and iOS`() {
    val message = ToolAvailabilityMessages.whereAvailable(
      toolName = "mobile_listInstalledApps",
      entries = TrailblazeToolSetCatalog.defaultEntries(),
      currentDriver = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
      currentDeviceId = webDevice,
    )

    assertThat(message).isEqualTo(
      "mobile_listInstalledApps runs on Android and iOS only, and this device is " +
        "web/playwright-native (Web Browser).",
    )
  }

  @Test
  fun `drivers are named only when the device's platform is supported but its driver is not`() {
    val entries = listOf(
      entry(TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY, TrailblazeDriverType.IOS_HOST),
    )
    val androidDevice = TrailblazeDeviceId("emulator-5554", TrailblazeDevicePlatform.ANDROID)

    val onWrongDriver = ToolAvailabilityMessages.whereAvailable(
      toolName = TOOL,
      entries = entries,
      currentDriver = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
      currentDeviceId = androidDevice,
    )
    assertThat(onWrongDriver).isEqualTo(
      "$TOOL runs on Android only with the android-ondevice-accessibility driver, and " +
        "android/emulator-5554 (Android) uses android-ondevice-instrumentation. " +
        "Switch with `trailblaze config android-driver accessibility`, then reconnect the device.",
    )

    val onWrongPlatform = ToolAvailabilityMessages.whereAvailable(
      toolName = TOOL,
      entries = entries,
      currentDriver = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
      currentDeviceId = webDevice,
    )
    assertThat(onWrongPlatform).isNotNull().doesNotContain("driver")
  }

  @Test
  fun `a tool any driver can run gets no device explanation`() {
    val entries = listOf(entry(TrailblazeDriverType.IOS_HOST), entry())

    assertThat(
      ToolAvailabilityMessages.whereAvailable(TOOL, entries, TrailblazeDriverType.PLAYWRIGHT_NATIVE, webDevice),
    ).isNull()
    assertThat(
      ToolAvailabilityMessages.whereAvailable("someOtherTool", entries, TrailblazeDriverType.PLAYWRIGHT_NATIVE, webDevice),
    ).isNull()
  }

  @Test
  fun `a tool the device's driver supports gets no device explanation`() {
    // Rejected by the target (e.g. `excluded_tools:`), not the device: blaming the device misleads.
    val entries = listOf(entry(TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY, TrailblazeDriverType.IOS_HOST))

    assertThat(
      ToolAvailabilityMessages.whereAvailable(
        toolName = TOOL,
        entries = entries,
        currentDriver = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
        currentDeviceId = TrailblazeDeviceId("emulator-5554", TrailblazeDevicePlatform.ANDROID),
      ),
    ).isNull()
  }

  @Test
  fun `without a bound device the message still says where the tool runs`() {
    val message = ToolAvailabilityMessages.whereAvailable(
      toolName = TOOL,
      entries = listOf(entry(TrailblazeDriverType.IOS_HOST, TrailblazeDriverType.IOS_AXE)),
      currentDriver = null,
      currentDeviceId = null,
    )

    assertThat(message).isEqualTo("$TOOL runs on iOS only.")
  }

  @Test
  fun `platform lists read as plain English`() {
    assertThat(ToolAvailabilityMessages.platformList(listOf(TrailblazeDevicePlatform.IOS))).isEqualTo("iOS")
    assertThat(
      ToolAvailabilityMessages.platformList(
        listOf(TrailblazeDevicePlatform.WEB, TrailblazeDevicePlatform.ANDROID, TrailblazeDevicePlatform.IOS),
      ),
    ).contains("Android, iOS and Web Browser")
  }

  private fun entry(vararg drivers: TrailblazeDriverType) = ToolSetCatalogEntry(
    id = "test_${drivers.joinToString("_") { it.yamlKey }}",
    description = "test",
    toolClasses = emptySet(),
    scriptedToolNames = setOf(ToolName(TOOL)),
    compatibleDriverTypes = drivers.toSet(),
  )

  private fun context(driverType: TrailblazeDriverType, deviceId: TrailblazeDeviceId) =
    TrailblazeToolExecutionContext(
      screenState = null,
      traceId = null,
      trailblazeDeviceInfo = TrailblazeDeviceInfo(
        trailblazeDeviceId = deviceId,
        trailblazeDriverType = driverType,
        widthPixels = 1080,
        heightPixels = 1920,
      ),
      sessionProvider = TrailblazeSessionProvider {
        TrailblazeSession(sessionId = SessionId("test-session"), startTime = Clock.System.now())
      },
      trailblazeLogger = TrailblazeLogger.createNoOp(),
      memory = AgentMemory(),
    )

  private companion object {
    const val TOOL = "demo_tool"
  }
}
