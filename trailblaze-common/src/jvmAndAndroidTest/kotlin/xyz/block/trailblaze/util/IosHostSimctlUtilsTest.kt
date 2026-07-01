package xyz.block.trailblaze.util

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [IosHostSimctlUtils.parseInstalledAppIdsFromListApps], the canonical parser
 * shared by the cross-platform `mobile_listInstalledApps` tool and `IosHostUtils.getInstalledAppIds`.
 * (These cases moved here from `IosHostUtilsTest` when the parser was promoted to trailblaze-common.)
 */
class IosHostSimctlUtilsTest {

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
          "com.example.app" =     {
              ApplicationType = User;
              Bundle = "file:///Users/test/Library/Developer/CoreSimulator/Devices/ABC123/data/Containers/Bundle/Application/DEF456/ExampleApp.app/";
              CFBundleDisplayName = ExampleApp;
              CFBundleIdentifier = "com.example.app";
              CFBundleVersion = 6940515;
              Path = "/Users/test/Library/Developer/CoreSimulator/Devices/ABC123/data/Containers/Bundle/Application/DEF456/ExampleApp.app";
          };
          "com.apple.mobilesafari" =     {
              ApplicationType = System;
              CFBundleIdentifier = "com.apple.mobilesafari";
              Path = "/Applications/MobileSafari.app";
          };
      }
    """.trimIndent().lines()

    val appIds = IosHostSimctlUtils.parseInstalledAppIdsFromListApps(outputLines)

    assertEquals(3, appIds.size)
    assertContains(appIds, "com.apple.Preferences")
    assertContains(appIds, "com.example.app")
    assertContains(appIds, "com.apple.mobilesafari")
  }

  @Test
  fun `parseInstalledAppIdsFromListApps filters out group identifiers`() {
    val outputLines = """
      {
          "com.apple.Preferences" =     {
              Path = "/Applications/Preferences.app";
          };
          "group.com.example.app" =     {
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

    val appIds = IosHostSimctlUtils.parseInstalledAppIdsFromListApps(outputLines)

    assertEquals(2, appIds.size)
    assertContains(appIds, "com.apple.Preferences")
    assertContains(appIds, "com.example.app")
    assertTrue(appIds.none { it.startsWith("group.") })
  }

  @Test
  fun `parseInstalledAppIdsFromListApps handles variable whitespace`() {
    // Different amounts of leading whitespace and spacing around the equals sign.
    val outputLines = listOf(
      "{",
      "    \"com.app.fourspaces\" =     {", // 4 spaces, multiple spaces around =
      "\t\"com.app.tab\" =\t{", // tab, tab around =
      "  \"com.app.twospaces\" = {", // 2 spaces, single space around =
      "      \"com.app.sixspaces\"={", // 6 spaces, no spaces around =
      "}",
    )

    val appIds = IosHostSimctlUtils.parseInstalledAppIdsFromListApps(outputLines)

    assertEquals(4, appIds.size)
    assertContains(appIds, "com.app.fourspaces")
    assertContains(appIds, "com.app.tab")
    assertContains(appIds, "com.app.twospaces")
    assertContains(appIds, "com.app.sixspaces")
  }

  @Test
  fun `parseInstalledAppIdsFromListApps returns empty list for empty input`() {
    val appIds = IosHostSimctlUtils.parseInstalledAppIdsFromListApps(emptyList())
    assertTrue(appIds.isEmpty())
  }

  @Test
  fun `parseInstalledAppIdsFromListApps ignores nested GroupContainers entries`() {
    // Real `xcrun simctl listapps` output (Xcode 26.x) nests a `GroupContainers` dictionary
    // inside each app block. Its entries are `"&lt;id&gt;" = "file://…";` lines whose keys look like
    // bundle ids — including team-prefixed group ids that do NOT start with `group.`. Only the
    // block-opening header (`"&lt;bundleId&gt;" = {`) is a real installed app; the nested key-value
    // lines must be ignored. This pins the regression where the looser `= .*` regex mis-counted
    // `243LU875E5.groups.com.apple.podcasts` and similar as installed apps.
    val outputLines = """
      {
          "com.apple.Bridge" =     {
              ApplicationType = System;
              CFBundleIdentifier = "com.apple.Bridge";
              GroupContainers =         {
                  "243LU875E5.groups.com.apple.podcasts" = "file:///path/A/";
                  "group.com.apple.bridge" = "file:///path/B/";
              };
              Path = "/Applications/Bridge.app";
          };
          "com.example.app" =     {
              CFBundleIdentifier = "com.example.app";
              Path = "/Applications/Example.app";
          };
      }
    """.trimIndent().lines()

    val appIds = IosHostSimctlUtils.parseInstalledAppIdsFromListApps(outputLines)

    assertEquals(listOf("com.apple.Bridge", "com.example.app"), appIds)
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

    val appIds = IosHostSimctlUtils.parseInstalledAppIdsFromListApps(outputLines)
    assertTrue(appIds.isEmpty())
  }

  // --- Structured parser (parseInstalledAppsFromListApps) backing mobile_listInstalledApps detailed mode ---

  @Test
  fun `parseInstalledAppsFromListApps extracts isSystemApp, label, version, buildNumber and path`() {
    val outputLines = """
      {
          "com.apple.Preferences" =     {
              ApplicationType = System;
              CFBundleDisplayName = Settings;
              CFBundleName = Settings;
              CFBundleShortVersionString = "1.0";
              CFBundleVersion = 1;
              Path = "/Applications/Preferences.app";
          };
          "com.example.app" =     {
              ApplicationType = User;
              CFBundleDisplayName = ExampleApp;
              CFBundleShortVersionString = "6.94";
              CFBundleVersion = 6940515;
              Path = "/Users/test/ExampleApp.app";
          };
      }
    """.trimIndent().lines()

    val apps = IosHostSimctlUtils.parseInstalledAppsFromListApps(outputLines)

    assertEquals(2, apps.size)
    val prefs = apps.first { it.appId == "com.apple.Preferences" }
    assertEquals(true, prefs.isSystemApp)
    assertEquals("Settings", prefs.label)
    assertEquals("1.0", prefs.version) // user-visible CFBundleShortVersionString preferred
    assertEquals("1", prefs.buildNumber) // CFBundleVersion is the machine build number
    assertEquals("/Applications/Preferences.app", prefs.installPath)

    val example = apps.first { it.appId == "com.example.app" }
    assertEquals(false, example.isSystemApp)
    assertEquals("ExampleApp", example.label)
    assertEquals("6.94", example.version)
    assertEquals("6940515", example.buildNumber)
    assertEquals("/Users/test/ExampleApp.app", example.installPath)
  }

  @Test
  fun `parseInstalledAppsFromListApps falls back to CFBundleName and CFBundleVersion`() {
    // No CFBundleDisplayName and no CFBundleShortVersionString — must fall back to CFBundleName
    // (label) and CFBundleVersion (version).
    val outputLines = """
      {
          "com.apple.mobilesafari" =     {
              ApplicationType = System;
              CFBundleName = Safari;
              CFBundleVersion = 8617;
              Path = "/Applications/MobileSafari.app";
          };
      }
    """.trimIndent().lines()

    val apps = IosHostSimctlUtils.parseInstalledAppsFromListApps(outputLines)

    val safari = apps.single()
    assertEquals("Safari", safari.label)
    assertEquals("8617", safari.version)
    assertEquals(true, safari.isSystemApp)
  }

  @Test
  fun `parseInstalledAppsFromListApps ignores nested GroupContainers entries`() {
    // The GroupContainers dictionary nests `"<id>" = "file://…";` entries whose keys look like
    // bundle ids (including team-prefixed ones that don't start with `group.`). Only the
    // block-opening header is a real app — the nested key-value lines must NOT become apps, nor
    // should their values leak into the enclosing app's fields.
    val outputLines = """
      {
          "com.apple.Bridge" =     {
              ApplicationType = System;
              CFBundleDisplayName = Watch;
              GroupContainers =         {
                  "243LU875E5.groups.com.apple.podcasts" = "file:///path/A/";
                  "group.com.apple.bridge" = "file:///path/B/";
              };
              Path = "/Applications/Bridge.app";
          };
          "com.example.app" =     {
              ApplicationType = User;
              CFBundleDisplayName = ExampleApp;
              Path = "/Applications/Example.app";
          };
      }
    """.trimIndent().lines()

    val apps = IosHostSimctlUtils.parseInstalledAppsFromListApps(outputLines)

    assertEquals(listOf("com.apple.Bridge", "com.example.app"), apps.map { it.appId })
    assertEquals("Watch", apps.first { it.appId == "com.apple.Bridge" }.label)
  }

  @Test
  fun `parseInstalledAppsFromListApps treats a non-standard ApplicationType as non-system`() {
    val outputLines = """
      {
          "com.example.weird" =     {
              ApplicationType = Internal;
              Path = "/Applications/Weird.app";
          };
      }
    """.trimIndent().lines()

    val apps = IosHostSimctlUtils.parseInstalledAppsFromListApps(outputLines)

    val weird = apps.single()
    assertEquals(false, weird.isSystemApp, "an ApplicationType that's neither System nor User is not guessed into the system bucket")
    assertNull(weird.label)
    assertNull(weird.version)
  }

  @Test
  fun `parseInstalledAppsFromListApps filters group identifiers and empty input`() {
    assertTrue(IosHostSimctlUtils.parseInstalledAppsFromListApps(emptyList()).isEmpty())

    val outputLines = """
      {
          "group.com.example.shared" =     {
              ApplicationType = User;
              Path = "/some/group/path";
          };
          "com.example.app" =     {
              ApplicationType = User;
              Path = "/Applications/Example.app";
          };
      }
    """.trimIndent().lines()

    val apps = IosHostSimctlUtils.parseInstalledAppsFromListApps(outputLines)
    assertEquals(listOf("com.example.app"), apps.map { it.appId })
  }
}
