package xyz.block.trailblaze.host.ios

import org.junit.Test
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosHostUtilsTest {

  // region parseInstalledAppIdsFromListApps tests

  @Test
  fun `parseInstalledAppIdsFromListApps extracts bundle IDs from typical listapps output`() {
    // Sample output from `xcrun simctl listapps <device-id>`
    val outputLines = """
      {
          "com.apple.Preferences" =     {
              ApplicationType = System;
              Bundle = "file:///Applications/Preferences.app/";
              CFBundleDisplayName = Settings;
              CFBundleExecutable = Preferences;
              CFBundleIdentifier = "com.apple.Preferences";
              CFBundleName = Settings;
              CFBundleVersion = 1;
              Path = "/Applications/Preferences.app";
          };
          "com.squareup.square" =     {
              ApplicationType = User;
              Bundle = "file:///Users/test/Library/Developer/CoreSimulator/Devices/ABC123/data/Containers/Bundle/Application/DEF456/Square.app/";
              CFBundleDisplayName = Square;
              CFBundleIdentifier = "com.squareup.square";
              CFBundleVersion = 6940515;
              Path = "/Users/test/Library/Developer/CoreSimulator/Devices/ABC123/data/Containers/Bundle/Application/DEF456/Square.app";
          };
          "com.apple.mobilesafari" =     {
              ApplicationType = System;
              CFBundleIdentifier = "com.apple.mobilesafari";
              Path = "/Applications/MobileSafari.app";
          };
      }
    """.trimIndent().lines()

    val appIds = IosHostUtils.parseInstalledAppIdsFromListApps(outputLines)

    assertEquals(3, appIds.size)
    assertContains(appIds, "com.apple.Preferences")
    assertContains(appIds, "com.squareup.square")
    assertContains(appIds, "com.apple.mobilesafari")
  }

  @Test
  fun `parseInstalledAppIdsFromListApps filters out group identifiers`() {
    val outputLines = """
      {
          "com.apple.Preferences" =     {
              Path = "/Applications/Preferences.app";
          };
          "group.com.squareup.square" =     {
              Path = "/some/group/path";
          };
          "group.com.example.shared" =     {
              Path = "/another/group/path";
          };
          "com.example.app" =     {
              Path = "/Applications/Example.app";
          };
      }
    """.trimIndent().lines()

    val appIds = IosHostUtils.parseInstalledAppIdsFromListApps(outputLines)

    assertEquals(2, appIds.size)
    assertContains(appIds, "com.apple.Preferences")
    assertContains(appIds, "com.example.app")
    assertTrue(appIds.none { it.startsWith("group.") })
  }

  @Test
  fun `parseInstalledAppIdsFromListApps handles variable whitespace`() {
    // Test with different amounts of leading whitespace and spacing around equals
    val outputLines = listOf(
      "{",
      "    \"com.app.fourspaces\" =     {",      // 4 spaces, multiple spaces around =
      "\t\"com.app.tab\" =\t{",                   // tab, tab around =
      "  \"com.app.twospaces\" = {",              // 2 spaces, single space around =
      "      \"com.app.sixspaces\"={",            // 6 spaces, no spaces around =
      "}",
    )

    val appIds = IosHostUtils.parseInstalledAppIdsFromListApps(outputLines)

    assertEquals(4, appIds.size)
    assertContains(appIds, "com.app.fourspaces")
    assertContains(appIds, "com.app.tab")
    assertContains(appIds, "com.app.twospaces")
    assertContains(appIds, "com.app.sixspaces")
  }

  @Test
  fun `parseInstalledAppIdsFromListApps returns empty set for empty input`() {
    val appIds = IosHostUtils.parseInstalledAppIdsFromListApps(emptyList())
    assertTrue(appIds.isEmpty())
  }

  @Test
  fun `parseInstalledAppIdsFromListApps ignores lines without app identifiers`() {
    val outputLines = listOf(
      "{",
      "    ApplicationType = System;",
      "    CFBundleDisplayName = Settings;",
      "    Path = \"/Applications/Preferences.app\";",
      "};",
      "}",
    )

    val appIds = IosHostUtils.parseInstalledAppIdsFromListApps(outputLines)
    assertTrue(appIds.isEmpty())
  }

  // endregion

  // region parseAppPathFromListApps tests

  @Test
  fun `parseAppPathFromListApps extracts path for existing app`() {
    val output = """
      {
          "com.apple.Preferences" =     {
              ApplicationType = System;
              CFBundleIdentifier = "com.apple.Preferences";
              Path = "/Applications/Preferences.app";
          };
          "com.squareup.square" =     {
              ApplicationType = User;
              CFBundleIdentifier = "com.squareup.square";
              CFBundleVersion = 6940515;
              Path = "/Users/test/Library/Developer/CoreSimulator/Devices/ABC123/data/Containers/Bundle/Application/DEF456/Square.app";
          };
          "com.apple.mobilesafari" =     {
              ApplicationType = System;
              CFBundleIdentifier = "com.apple.mobilesafari";
              Path = "/Applications/MobileSafari.app";
          };
      }
    """.trimIndent()

    val squarePath = IosHostUtils.parseAppPathFromListApps(output, "com.squareup.square")
    assertEquals(
      "/Users/test/Library/Developer/CoreSimulator/Devices/ABC123/data/Containers/Bundle/Application/DEF456/Square.app",
      squarePath
    )

    val prefsPath = IosHostUtils.parseAppPathFromListApps(output, "com.apple.Preferences")
    assertEquals("/Applications/Preferences.app", prefsPath)

    val safariPath = IosHostUtils.parseAppPathFromListApps(output, "com.apple.mobilesafari")
    assertEquals("/Applications/MobileSafari.app", safariPath)
  }

  @Test
  fun `parseAppPathFromListApps returns null for non-existent app`() {
    val output = """
      {
          "com.apple.Preferences" =     {
              Path = "/Applications/Preferences.app";
          };
      }
    """.trimIndent()

    val path = IosHostUtils.parseAppPathFromListApps(output, "com.nonexistent.app")
    assertNull(path)
  }

  @Test
  fun `parseAppPathFromListApps handles paths with spaces`() {
    val output = """
      {
          "com.example.app" =     {
              Path = "/Users/John Doe/Library/My Apps/Example.app";
          };
      }
    """.trimIndent()

    val path = IosHostUtils.parseAppPathFromListApps(output, "com.example.app")
    assertEquals("/Users/John Doe/Library/My Apps/Example.app", path)
  }

  @Test
  fun `parseAppPathFromListApps handles variable whitespace around Path`() {
    // Test different spacing patterns around the Path key
    val output1 = """
      {
          "com.app.nospaces" =     {
              Path="/path/to/app1.app";
          };
      }
    """.trimIndent()

    val output2 = """
      {
          "com.app.extraspaces" =     {
              Path   =   "/path/to/app2.app";
          };
      }
    """.trimIndent()

    assertEquals("/path/to/app1.app", IosHostUtils.parseAppPathFromListApps(output1, "com.app.nospaces"))
    assertEquals("/path/to/app2.app", IosHostUtils.parseAppPathFromListApps(output2, "com.app.extraspaces"))
  }

  @Test
  fun `parseAppPathFromListApps does not match partial app IDs`() {
    val output = """
      {
          "com.squareup.square.debug" =     {
              Path = "/path/to/SquareDebug.app";
          };
          "com.squareup.square" =     {
              Path = "/path/to/Square.app";
          };
      }
    """.trimIndent()

    // Should match exact app ID, not partial
    val path = IosHostUtils.parseAppPathFromListApps(output, "com.squareup.square")
    assertEquals("/path/to/Square.app", path)
  }

  @Test
  fun `parseAppPathFromListApps returns null for empty output`() {
    val path = IosHostUtils.parseAppPathFromListApps("", "com.example.app")
    assertNull(path)
  }

  // endregion

  // region parseXmlPlistKey tests

  @Test
  fun `parseXmlPlistKey extracts string value from typical Info plist`() {
    val content = """
      <?xml version="1.0" encoding="UTF-8"?>
      <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
      <plist version="1.0">
      <dict>
          <key>CFBundleExecutable</key>
          <string>Square</string>
          <key>CFBundleIdentifier</key>
          <string>com.squareup.square</string>
          <key>CFBundleName</key>
          <string>Square</string>
          <key>CFBundleShortVersionString</key>
          <string>6.94</string>
          <key>CFBundleVersion</key>
          <string>6940515</string>
          <key>SQBuildNumber</key>
          <string>6515</string>
      </dict>
      </plist>
    """.trimIndent()

    assertEquals("6.94", IosHostUtils.parseXmlPlistKey(content, "CFBundleShortVersionString"))
    assertEquals("6940515", IosHostUtils.parseXmlPlistKey(content, "CFBundleVersion"))
    assertEquals("6515", IosHostUtils.parseXmlPlistKey(content, "SQBuildNumber"))
    assertEquals("com.squareup.square", IosHostUtils.parseXmlPlistKey(content, "CFBundleIdentifier"))
  }

  @Test
  fun `parseXmlPlistKey extracts integer value`() {
    val content = """
      <plist version="1.0">
      <dict>
          <key>CFBundleVersion</key>
          <integer>12345</integer>
          <key>MinimumOSVersion</key>
          <integer>15</integer>
      </dict>
      </plist>
    """.trimIndent()

    assertEquals("12345", IosHostUtils.parseXmlPlistKey(content, "CFBundleVersion"))
    assertEquals("15", IosHostUtils.parseXmlPlistKey(content, "MinimumOSVersion"))
  }

  @Test
  fun `parseXmlPlistKey extracts real value`() {
    val content = """
      <plist version="1.0">
      <dict>
          <key>AppVersion</key>
          <real>1.5</real>
      </dict>
      </plist>
    """.trimIndent()

    assertEquals("1.5", IosHostUtils.parseXmlPlistKey(content, "AppVersion"))
  }

  @Test
  fun `parseXmlPlistKey returns null for non-existent key`() {
    val content = """
      <plist version="1.0">
      <dict>
          <key>CFBundleVersion</key>
          <string>123</string>
      </dict>
      </plist>
    """.trimIndent()

    assertNull(IosHostUtils.parseXmlPlistKey(content, "NonExistentKey"))
  }

  @Test
  fun `parseXmlPlistKey handles whitespace in values`() {
    val content = """
      <plist version="1.0">
      <dict>
          <key>CFBundleVersion</key>
          <string>  6940515  </string>
          <key>CFBundleName</key>
          <string>
              Square Point of Sale
          </string>
      </dict>
      </plist>
    """.trimIndent()

    assertEquals("6940515", IosHostUtils.parseXmlPlistKey(content, "CFBundleVersion"))
    assertEquals("Square Point of Sale", IosHostUtils.parseXmlPlistKey(content, "CFBundleName"))
  }

  @Test
  fun `parseXmlPlistKey handles compact XML without newlines`() {
    val content = """<plist version="1.0"><dict><key>CFBundleVersion</key><string>123</string><key>CFBundleName</key><string>MyApp</string></dict></plist>"""

    assertEquals("123", IosHostUtils.parseXmlPlistKey(content, "CFBundleVersion"))
    assertEquals("MyApp", IosHostUtils.parseXmlPlistKey(content, "CFBundleName"))
  }

  @Test
  fun `parseXmlPlistKey returns null for empty content`() {
    assertNull(IosHostUtils.parseXmlPlistKey("", "CFBundleVersion"))
  }

  @Test
  fun `parseXmlPlistKey handles keys with special regex characters`() {
    val content = """
      <plist version="1.0">
      <dict>
          <key>Key.With.Dots</key>
          <string>value1</string>
          <key>Key[With]Brackets</key>
          <string>value2</string>
      </dict>
      </plist>
    """.trimIndent()

    assertEquals("value1", IosHostUtils.parseXmlPlistKey(content, "Key.With.Dots"))
    assertEquals("value2", IosHostUtils.parseXmlPlistKey(content, "Key[With]Brackets"))
  }

  // endregion

  // region Integration tests (require simulator)

  @Test
  fun `getAppVersionInfo returns version info for installed app`() {
    // This test requires a booted simulator with an app installed
    // Skip if no simulator is available
    val trailblazeDeviceId = getBootedSimulatorDeviceId() ?: run {
      println("Skipping test: No booted simulator found")
      return
    }

    // Test with a common system app that's always installed
    val versionInfo = IosHostUtils.getAppVersionInfo(trailblazeDeviceId, "com.apple.Preferences")

    if (versionInfo != null) {
      println("Retrieved version info:")
      println("  trailblazeDeviceId: ${versionInfo.trailblazeDeviceId}")
      println("  versionCode: ${versionInfo.versionCode}")
      println("  versionName: ${versionInfo.versionName}")
      // Note: buildNumber is app-specific (e.g., SQBuildNumber for Square apps)
      // and is populated by app-specific implementations, not the generic IosHostUtils

      assertNotNull(versionInfo.versionCode)
      assertNotNull(versionInfo.versionName)
      assertTrue(versionInfo.versionCode.isNotBlank())
    } else {
      println("App not installed on simulator ${trailblazeDeviceId.instanceId}")
    }
  }

  @Test
  fun `getAppVersionInfo returns null for non-existent app`() {
    val trailblazeDeviceId = getBootedSimulatorDeviceId() ?: run {
      println("Skipping test: No booted simulator found")
      return
    }

    val versionInfo = IosHostUtils.getAppVersionInfo(trailblazeDeviceId, "com.nonexistent.app")
    assertNull(versionInfo)
  }

  // endregion

  private fun getBootedSimulatorDeviceId(): TrailblazeDeviceId? {
    return try {
      val process = ProcessBuilder("xcrun", "simctl", "list", "devices", "booted", "-j")
        .redirectErrorStream(true)
        .start()
      val output = process.inputStream.bufferedReader().readText()
      process.waitFor()

      // Simple regex to find a device UUID
      val uuidRegex = Regex("[A-F0-9]{8}-[A-F0-9]{4}-[A-F0-9]{4}-[A-F0-9]{4}-[A-F0-9]{12}")
      uuidRegex.find(output)?.value?.let { instanceId ->
        TrailblazeDeviceId(
          instanceId = instanceId,
          trailblazeDevicePlatform = TrailblazeDevicePlatform.IOS,
        )
      }
    } catch (e: Exception) {
      null
    }
  }
}
