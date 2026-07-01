package xyz.block.trailblaze.device

import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.mobile.tools.ListInstalledAppsDetailedResult
import xyz.block.trailblaze.mobile.tools.ListInstalledAppsDetailedTrailblazeTool
import xyz.block.trailblaze.mobile.tools.ListInstalledAppsResult
import xyz.block.trailblaze.mobile.tools.filterInstalledApps
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [parseInstalledAppsFromDumpsys] — the pure parser behind the host-JVM (adb) path
 * of the `mobile_listInstalledAppsDetailed` primitive. Lives in commonMain so the
 * `dumpsys package packages` parsing is exercised without a device, mirroring
 * [AndroidDeviceCommandExecutorRunAsValidationTest].
 *
 * Also pins both tools' wire shapes: the lean [ListInstalledAppsResult] (`{"appIds":[...]}`) and the
 * deep [ListInstalledAppsDetailedResult] (`{"apps":[{…}]}`).
 */
class InstalledAppParsingTest {

  // Trimmed but faithful `adb shell dumpsys package packages` output: a system app and a
  // user-installed app, each with the package-level codePath / versionCode / versionName / flags we
  // read, plus the per-user sub-block we ignore.
  private val dumpsysSample = """
    Packages:
      Package [com.android.settings] (1a2b3c):
        codePath=/system/priv-app/Settings
        primaryCpuAbi=arm64-v8a
        versionCode=35 minSdk=24 targetSdk=35
        versionName=15
        flags=[ SYSTEM HAS_CODE ALLOW_CLEAR_USER_DATA ]
        pkgFlags=[ SYSTEM HAS_CODE ALLOW_CLEAR_USER_DATA ]
        User 0: ceDataInode=111 installed=true hidden=false stopped=false enabled=0
          dataDir=/data/user/0/com.android.settings
          firstInstallTime=2026-06-29 13:09:21
      Package [com.example.userapp] (4d5e6f):
        codePath=/data/app/~~abc==/com.example.userapp-xyz==/base.apk
        primaryCpuAbi=arm64-v8a
        versionCode=6940515 minSdk=26 targetSdk=34
        versionName=6.94
        flags=[ HAS_CODE ALLOW_CLEAR_USER_DATA ALLOW_BACKUP ]
        pkgFlags=[ HAS_CODE ALLOW_CLEAR_USER_DATA ALLOW_BACKUP ]
        User 0: ceDataInode=222 installed=true hidden=false stopped=false enabled=0
          dataDir=/data/user/0/com.example.userapp
  """.trimIndent()

  @Test
  fun `parseInstalledAppsFromDumpsys extracts isSystemApp, version, buildNumber and installPath`() {
    val apps = parseInstalledAppsFromDumpsys(dumpsysSample)

    assertEquals(
      listOf("com.android.settings", "com.example.userapp"),
      apps.map { it.appId },
      "every Package [..] block becomes an entry, sorted by id",
    )

    val settings = apps.first { it.appId == "com.android.settings" }
    assertEquals(true, settings.isSystemApp, "the SYSTEM flag token marks a system app")
    assertEquals("15", settings.version)
    assertEquals("35", settings.buildNumber, "buildNumber is versionCode")
    assertEquals("/system/priv-app/Settings", settings.installPath, "installPath is codePath")

    val user = apps.first { it.appId == "com.example.userapp" }
    assertEquals(false, user.isSystemApp, "no SYSTEM token in flags → user app")
    assertEquals("6.94", user.version)
    assertEquals("6940515", user.buildNumber)
    assertEquals("/data/app/~~abc==/com.example.userapp-xyz==/base.apk", user.installPath)
  }

  @Test
  fun `parseInstalledAppsFromDumpsys leaves label null (dumpsys has no display name)`() {
    // The one field the host/adb path can't supply — it needs resource resolution (on-device).
    assertTrue(parseInstalledAppsFromDumpsys(dumpsysSample).all { it.label == null })
  }

  @Test
  fun `parseInstalledAppsFromDumpsys returns empty for output with no Package blocks`() {
    assertTrue(parseInstalledAppsFromDumpsys("Packages:\n  (nothing here)\n").isEmpty())
  }

  @Test
  fun `parseInstalledAppsFromDumpsys normalizes literal null version to actual null`() {
    // Real-device finding: dumpsys prints `versionName=null` for an app with no version (e.g. a
    // test-runner APK). The parser must produce an actual null (omitted from JSON), not the string
    // "null". versionCode=0 is a real value and stays.
    val output = """
      Packages:
        Package [xyz.block.trailblaze.runner] (abc123):
          codePath=/data/app/~~r==/xyz.block.trailblaze.runner-q==/base.apk
          versionCode=0 minSdk=24 targetSdk=34
          versionName=null
          flags=[ HAS_CODE ALLOW_CLEAR_USER_DATA ]
    """.trimIndent()

    val app = parseInstalledAppsFromDumpsys(output).single()
    assertNull(app.version, "dumpsys 'versionName=null' must become an actual null, not the string \"null\"")
    assertEquals("0", app.buildNumber, "versionCode=0 is a real value")
    assertEquals(false, app.isSystemApp)
  }

  @Test
  fun `detailed tool defaults to include-everything`() {
    // The system-app filter defaults to "include everything" (parity with the historical behavior).
    // Guards the default from a silent flip.
    assertTrue(ListInstalledAppsDetailedTrailblazeTool().includeSystemApps)
  }

  @Test
  fun `filterInstalledApps drops system apps only when includeSystemApps is false, always sorted`() {
    val apps = listOf(
      InstalledApp(appId = "z.user", isSystemApp = false),
      InstalledApp(appId = "a.system", isSystemApp = true),
      InstalledApp(appId = "m.user", isSystemApp = false),
    )

    assertEquals(
      listOf("a.system", "m.user", "z.user"),
      filterInstalledApps(apps, includeSystemApps = true).map { it.appId },
      "include-everything keeps all apps, sorted by id",
    )
    assertEquals(
      listOf("m.user", "z.user"),
      filterInstalledApps(apps, includeSystemApps = false).map { it.appId },
      "includeSystemApps=false drops system apps, keeps user apps sorted by id",
    )
  }

  @Test
  fun `lean result serializes to the appIds array shape`() {
    val json = TrailblazeJsonInstance.encodeToString(
      ListInstalledAppsResult(appIds = listOf("com.example.userapp", "com.android.settings")),
    ).filterNot { it.isWhitespace() }

    assertEquals("""{"appIds":["com.example.userapp","com.android.settings"]}""", json)
  }

  @Test
  fun `detailed entry omits the metadata fields a platform can't supply`() {
    // With the shared Json's `encodeDefaults = false`, null metadata fields are dropped — so a
    // record with only appId + isSystemApp doesn't carry empty label/version/buildNumber/installPath
    // keys (which would burn LLM/log context).
    val json = TrailblazeJsonInstance.encodeToString(
      ListInstalledAppsDetailedResult(apps = listOf(InstalledApp(appId = "com.example.userapp", isSystemApp = false))),
    ).filterNot { it.isWhitespace() }

    assertEquals("""{"apps":[{"appId":"com.example.userapp","isSystemApp":false}]}""", json)
  }

  @Test
  fun `detailed result serializes with the full per-app record and round-trips`() {
    val result = ListInstalledAppsDetailedResult(
      apps = listOf(
        InstalledApp(
          appId = "com.example.userapp",
          isSystemApp = false,
          label = "Example",
          version = "1.2.3",
          buildNumber = "6940515",
          installPath = "/data/app/com.example.userapp/base.apk",
        ),
        InstalledApp(appId = "com.android.settings", isSystemApp = true),
      ),
    )

    // Strip insignificant whitespace so the pins don't depend on the shared Json's pretty-printing.
    val json = TrailblazeJsonInstance.encodeToString(result).filterNot { it.isWhitespace() }

    assertTrue(json.contains("\"apps\""), "top-level key must be 'apps'")
    assertTrue(json.contains("\"appId\":\"com.example.userapp\""))
    assertTrue(json.contains("\"isSystemApp\":false"))
    assertTrue(json.contains("\"isSystemApp\":true"))
    assertTrue(json.contains("\"label\":\"Example\""))
    assertTrue(json.contains("\"version\":\"1.2.3\""))
    assertTrue(json.contains("\"buildNumber\":\"6940515\""))
    assertTrue(json.contains("\"installPath\":\"/data/app/com.example.userapp/base.apk\""))

    // Round-trips back to the same structured value — the typesafe contract, not a hand-built string.
    val decoded = TrailblazeJsonInstance.decodeFromString<ListInstalledAppsDetailedResult>(json)
    assertEquals(result, decoded)
  }
}
