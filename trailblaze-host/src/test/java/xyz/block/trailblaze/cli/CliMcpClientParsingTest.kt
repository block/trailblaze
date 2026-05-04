package xyz.block.trailblaze.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform

/** Unit tests for [CliMcpClient] parsing helpers. */
class CliMcpClientParsingTest {

  // ---------------------------------------------------------------------------
  // parseDeviceList
  // ---------------------------------------------------------------------------

  @Test
  fun `parseDeviceList parses single Android device`() {
    val content = "  - emulator-5554 (Android) - Google Pixel 6"
    val entries = CliMcpClient.parseDeviceList(content)

    assertEquals(1, entries.size)
    assertEquals("emulator-5554", entries[0].instanceId)
    assertEquals(TrailblazeDevicePlatform.ANDROID, entries[0].platform)
    assertEquals("Google Pixel 6", entries[0].description)
    assertEquals("android/emulator-5554", entries[0].spec)
  }

  @Test
  fun `parseDeviceList captures null description when omitted`() {
    // The daemon omits the trailing ` - <desc>` segment for entries it doesn't have a
    // human-readable name for (e.g. virtual web devices like `playwright-native`).
    val content = "  - playwright-native (Web Browser)"
    val entries = CliMcpClient.parseDeviceList(content)

    assertEquals(1, entries.size)
    assertEquals("playwright-native", entries[0].instanceId)
    assertEquals(null, entries[0].description)
  }

  @Test
  fun `parseDeviceList parses single iOS device`() {
    val content = "  - 1A2B3C4D-5E6F (iOS) - iPhone 15 Pro"
    val entries = CliMcpClient.parseDeviceList(content)

    assertEquals(1, entries.size)
    assertEquals("1A2B3C4D-5E6F", entries[0].instanceId)
    assertEquals(TrailblazeDevicePlatform.IOS, entries[0].platform)
    assertEquals("ios/1A2B3C4D-5E6F", entries[0].spec)
  }

  @Test
  fun `parseDeviceList parses Web Browser device`() {
    val content = "  - localhost:3000 (Web Browser)"
    val entries = CliMcpClient.parseDeviceList(content)

    assertEquals(1, entries.size)
    assertEquals("localhost:3000", entries[0].instanceId)
    assertEquals(TrailblazeDevicePlatform.WEB, entries[0].platform)
  }

  @Test
  fun `parseDeviceList parses multiple devices`() {
    val content = """
      - emulator-5554 (Android) - Google Pixel 6
      - emulator-5556 (Android) - Google Pixel 7
      - 1A2B3C4D-5E6F (iOS) - iPhone 15 Pro
      - localhost:3000 (Web Browser)
    """.trimIndent()
    val entries = CliMcpClient.parseDeviceList(content)

    assertEquals(4, entries.size)
    assertEquals("emulator-5554", entries[0].instanceId)
    assertEquals(TrailblazeDevicePlatform.ANDROID, entries[0].platform)
    assertEquals("emulator-5556", entries[1].instanceId)
    assertEquals(TrailblazeDevicePlatform.ANDROID, entries[1].platform)
    assertEquals("1A2B3C4D-5E6F", entries[2].instanceId)
    assertEquals(TrailblazeDevicePlatform.IOS, entries[2].platform)
    assertEquals("localhost:3000", entries[3].instanceId)
    assertEquals(TrailblazeDevicePlatform.WEB, entries[3].platform)
  }

  @Test
  fun `parseDeviceList ignores lines without platform markers`() {
    val content = """
      Available devices:
      - emulator-5554 (Android) - Google Pixel 6
      No other devices found.
    """.trimIndent()
    val entries = CliMcpClient.parseDeviceList(content)

    assertEquals(1, entries.size)
    assertEquals("emulator-5554", entries[0].instanceId)
  }

  @Test
  fun `parseDeviceList returns empty for no devices`() {
    assertEquals(emptyList(), CliMcpClient.parseDeviceList(""))
    assertEquals(emptyList(), CliMcpClient.parseDeviceList("No devices found."))
  }

  @Test
  fun `parseDeviceList handles leading whitespace and bullet prefix`() {
    // The real MCP response uses "  - " prefix
    val content = "  - emulator-5554 (Android) - desc"
    val entries = CliMcpClient.parseDeviceList(content)

    assertEquals(1, entries.size)
    assertEquals("emulator-5554", entries[0].instanceId)
  }

  // ---------------------------------------------------------------------------
  // parseConnectedInstanceId
  // ---------------------------------------------------------------------------

  @Test
  fun `parseConnectedInstanceId extracts from INFO format`() {
    val text = """
      Status: Connected
      Platform: Android
      Instance ID: emulator-5554
      Screen: 1080x2400
    """.trimIndent()
    assertEquals("emulator-5554", CliMcpClient.parseConnectedInstanceId(text))
  }

  @Test
  fun `parseConnectedInstanceId extracts from Connect format`() {
    assertEquals(
      "emulator-5554",
      CliMcpClient.parseConnectedInstanceId("Connected to emulator-5554 (Android)"),
    )
  }

  @Test
  fun `parseConnectedInstanceId extracts iOS UUID`() {
    assertEquals(
      "1A2B3C4D-5E6F-7890-ABCD-EF1234567890",
      CliMcpClient.parseConnectedInstanceId(
        "Connected to 1A2B3C4D-5E6F-7890-ABCD-EF1234567890 (iOS)",
      ),
    )
  }

  @Test
  fun `parseConnectedInstanceId returns null for unrecognized text`() {
    assertNull(CliMcpClient.parseConnectedInstanceId("No device connected"))
    assertNull(CliMcpClient.parseConnectedInstanceId(""))
  }

  // ---------------------------------------------------------------------------
  // parseDevicePlatform
  // ---------------------------------------------------------------------------

  @Test
  fun `parseDevicePlatform extracts from INFO format`() {
    val text = """
      Status: Connected
      Platform: Android
      Instance ID: emulator-5554
    """.trimIndent()
    assertEquals(TrailblazeDevicePlatform.ANDROID, CliMcpClient.parseDevicePlatform(text))
  }

  @Test
  fun `parseDevicePlatform extracts iOS from INFO format`() {
    assertEquals(
      TrailblazeDevicePlatform.IOS,
      CliMcpClient.parseDevicePlatform("Platform: iOS"),
    )
  }

  @Test
  fun `parseDevicePlatform extracts from Connect parenthetical format`() {
    assertEquals(
      TrailblazeDevicePlatform.ANDROID,
      CliMcpClient.parseDevicePlatform("Connected to emulator-5554 (Android)"),
    )
  }

  @Test
  fun `parseDevicePlatform is case insensitive`() {
    assertEquals(
      TrailblazeDevicePlatform.ANDROID,
      CliMcpClient.parseDevicePlatform("Platform: android"),
    )
  }

  @Test
  fun `parseDevicePlatform returns null for unrecognized text`() {
    assertNull(CliMcpClient.parseDevicePlatform("No device connected"))
    assertNull(CliMcpClient.parseDevicePlatform(""))
  }

  @Test
  fun `parseDevicePlatform returns null for unknown platform name`() {
    assertNull(CliMcpClient.parseDevicePlatform("Platform: Blackberry"))
  }

  // ---------------------------------------------------------------------------
  // parseEndedSessionInfo
  // ---------------------------------------------------------------------------

  @Test
  fun `parseEndedSessionInfo extracts session identifier`() {
    val text = "Connected to emulator-5554 (Android) (ended previous session: user@host)"
    assertEquals("user@host", CliMcpClient.parseEndedSessionInfo(text))
  }

  @Test
  fun `parseEndedSessionInfo returns null when no ended session`() {
    assertNull(CliMcpClient.parseEndedSessionInfo("Connected to emulator-5554 (Android)"))
    assertNull(CliMcpClient.parseEndedSessionInfo(""))
  }

  // ---------------------------------------------------------------------------
  // DeviceListEntry.spec
  // ---------------------------------------------------------------------------

  @Test
  fun `DeviceListEntry spec formats correctly`() {
    val entry = CliMcpClient.DeviceListEntry("emulator-5554", TrailblazeDevicePlatform.ANDROID)
    assertEquals("android/emulator-5554", entry.spec)

    val iosEntry = CliMcpClient.DeviceListEntry("ABC-123", TrailblazeDevicePlatform.IOS)
    assertEquals("ios/ABC-123", iosEntry.spec)

    val webEntry = CliMcpClient.DeviceListEntry("localhost:3000", TrailblazeDevicePlatform.WEB)
    assertEquals("web/localhost:3000", webEntry.spec)
  }
}
