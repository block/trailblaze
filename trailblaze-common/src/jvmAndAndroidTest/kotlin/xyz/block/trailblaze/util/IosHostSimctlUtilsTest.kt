package xyz.block.trailblaze.util

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [IosHostSimctlUtils.parseInstalledAppIdsFromListApps], the canonical parser
 * shared by the cross-platform `mobile_listInstalledApps` tool and `IosHostUtils.getInstalledAppIds`.
 * (These cases moved here from `IosHostUtilsTest` when the parser was promoted to trailblaze-common.)
 */
class IosHostSimctlUtilsTest {

  @Test
  fun `clipboard command preserves Unicode and trailing whitespace without stderr contamination`() {
    val text = "usuario@example.com 日本語\n\n"
    assertEquals(
      text,
      IosHostSimctlUtils.runPasteboardCommand(
        listOf("/bin/sh", "-c", "cat; printf warning >&2"),
        stdin = text,
      ),
    )
  }

  /**
   * A simulator pasteboard. [dropsWrites] makes `simctl pbcopy` exit 0 and change nothing, as while
   * Xcode's DeviceHub.app syncs its pasteboard; [lagReads] pastes return the old value before the
   * write shows. Each command advances [clockNanos] by [commandMillis]. Any other command fails, so
   * a write that touched the Mac pasteboard would too.
   */
  private class FakePasteboard(
    val dropsWrites: Boolean = false,
    var lagReads: Int = 0,
    val commandMillis: Long = 0,
  ) {
    var device = ""
    var shown = ""
    var clockNanos = 0L
    val timeouts = mutableListOf<Long>()

    fun run(args: List<String>, stdin: String, timeoutMillis: Long): String {
      timeouts += timeoutMillis
      clockNanos += TimeUnit.MILLISECONDS.toNanos(commandMillis)
      return when (args.takeLast(2)) {
        listOf("pbcopy", UDID) -> "".also { if (!dropsWrites) device = stdin }
        listOf("pbpaste", UDID) -> if (lagReads-- > 0) shown else device.also { shown = it }
        else -> error("unexpected command $args")
      }
    }

    companion object {
      const val UDID = "TEST-SIMULATOR"
    }
  }

  private fun FakePasteboard.write(text: String, timeoutMillis: Long = 30_000) =
    IosHostSimctlUtils.writePasteboardVerified(
      FakePasteboard.UDID,
      text,
      timeoutMillis,
      runCommand = ::run,
      sleep = { clockNanos += TimeUnit.MILLISECONDS.toNanos(it) },
      nanoTime = { clockNanos },
    )

  @Test
  fun `setting the pasteboard returns once the simulator reads the text back`() {
    val pasteboard = FakePasteboard()

    pasteboard.write("ClipboardEvalText42 日本語\nsecond line\n")

    assertEquals("ClipboardEvalText42 日本語\nsecond line\n", pasteboard.device)
  }

  @Test
  fun `setting the pasteboard waits for a write that shows late`() {
    val pasteboard = FakePasteboard(lagReads = 3)

    pasteboard.write("ClipboardEvalText42")

    assertEquals("ClipboardEvalText42", pasteboard.device)
  }

  @Test
  fun `setting the pasteboard fails, naming DeviceHub, when the write is dropped`() {
    val pasteboard = FakePasteboard(dropsWrites = true)

    val error = assertFailsWith<IllegalStateException> { pasteboard.write("ClipboardEvalText42") }
    assertContains(error.message.orEmpty(), "DeviceHub")
    assertFalse("ClipboardEvalText42" in error.message.orEmpty(), "the text stays out of the error")
  }

  @Test
  fun `setting the pasteboard shares one deadline across every command`() {
    val pasteboard = FakePasteboard(dropsWrites = true, commandMillis = 400)

    assertFailsWith<IllegalStateException> { pasteboard.write("ClipboardEvalText42", timeoutMillis = 1_000) }
    assertTrue(TimeUnit.NANOSECONDS.toMillis(pasteboard.clockNanos) < 1_000 + 400, "ran past the deadline")
    assertEquals(pasteboard.timeouts.sortedDescending(), pasteboard.timeouts, "each command gets what is left")
  }

  @Test
  fun `clipboard timeout releases the lock even when stdin is blocked`() {
    lateinit var process: Process
    assertFailsWith<IllegalStateException> {
      IosHostSimctlUtils.runPasteboardCommand(
        listOf("/bin/sh", "-c", "exec sleep 30"),
        stdin = "x".repeat(1024 * 1024),
        timeoutMillis = 100,
        startProcess = { it.start().also { started -> process = started } },
      )
    }
    assertTrue(!process.isAlive, "Timed-out clipboard process must exit before the lock is released")
    val executor = Executors.newSingleThreadExecutor()
    try {
      assertEquals("available", executor.submit<String> {
        IosHostSimctlUtils.withPasteboardLock(1_000, { "unavailable" }, { "available" })
      }.get(60, TimeUnit.SECONDS))
    } finally {
      executor.shutdownNow()
    }
  }

  @Test
  fun `timed pasteboard lock returns timeout instead of waiting for an unbounded holder`() {
    val held = CountDownLatch(1)
    val release = CountDownLatch(1)
    val executor = Executors.newSingleThreadExecutor()
    val holder = executor.submit {
      IosHostSimctlUtils.withPasteboardLock {
        held.countDown()
        release.await(5, TimeUnit.SECONDS)
      }
    }

    assertTrue(held.await(5, TimeUnit.SECONDS))
    val result = IosHostSimctlUtils.withPasteboardLock(
      timeoutMillis = 50,
      onTimeout = { "timed-out" },
      action = { "acquired" },
    )
    release.countDown()
    holder.get(5, TimeUnit.SECONDS)
    executor.shutdownNow()

    assertEquals("timed-out", result)
  }

  @Test
  fun `parseAppProcessFromAppInfo reads the executable and install directory from appinfo output`() {
    // Shape of `xcrun simctl appinfo <device-id> com.example.wallet.dogfood`: the executable is
    // `Wallet`, not the bundle id's last component.
    val outputLines = """
      {
          ApplicationType = User;
          Bundle = "file:///Users/test/Library/Developer/CoreSimulator/Devices/ABC123/data/Containers/Bundle/Application/DEF456/Wallet.app/";
          CFBundleDisplayName = Wallet;
          CFBundleExecutable = Wallet;
          CFBundleIdentifier = "com.example.wallet.dogfood";
          CFBundleName = Wallet;
          GroupContainers =     {
              "group.com.example.wallet.dogfood" = "file:///Users/test/Library/Developer/CoreSimulator/Devices/ABC123/data/Containers/Shared/AppGroup/GHI789/";
          };
          Path = "/Users/test/Library/Developer/CoreSimulator/Devices/ABC123/data/Containers/Bundle/Application/DEF456/Wallet.app";
      }
    """.trimIndent().lines()

    assertEquals(
      IosAppProcess(executableName = "Wallet", installDir = "/DEF456/Wallet.app/"),
      IosHostSimctlUtils.parseAppProcessFromAppInfo(outputLines),
    )
  }

  @Test
  fun `parseAppProcessFromAppInfo keeps the executable when the path is not an app bundle`() {
    val outputLines = """
      {
          CFBundleExecutable = "Example App";
          Path = "/tmp/not-a-bundle";
      }
    """.trimIndent().lines()

    assertEquals(
      IosAppProcess(executableName = "Example App", installDir = null),
      IosHostSimctlUtils.parseAppProcessFromAppInfo(outputLines),
    )
  }

  @Test
  fun `parseAppProcessFromAppInfo returns null without an executable`() {
    assertNull(IosHostSimctlUtils.parseAppProcessFromAppInfo(listOf("{", "    ApplicationType = User;", "}")))
    assertNull(IosHostSimctlUtils.parseAppProcessFromAppInfo(emptyList()))
  }

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

  @Test
  fun `clearing a data container deletes a symlink, never what it points at`() {
    val host = Files.createTempDirectory("host-files")
    val hostFile = host.resolve("keep.txt").also { it.toFile().writeText("host data") }
    val container = Files.createTempDirectory("data-container")
    Files.createDirectories(container.resolve("Library/Caches"))
    container.resolve("Library/Caches/session.db").toFile().writeText("stale")
    Files.createSymbolicLink(container.resolve("Documents"), host)

    val undeleted = IosHostSimctlUtils.deleteChildrenWithoutFollowingLinks(container)

    assertEquals(emptyList(), undeleted)
    assertEquals(emptyList(), Files.list(container).use { it.toList() })
    assertEquals("host data", hostFile.toFile().readText())
  }

  @Test
  fun `an app counts as running only while launchd lists a pid for it`() {
    val running = listOf("45470\t0\tUIKitApplication:com.example.app[78e1][rb-legacy]")
    val exited = listOf("-\t0\tUIKitApplication:com.example.app[78e1][rb-legacy]")
    val otherApp = listOf("45470\t0\tUIKitApplication:com.example.app.widget[12be][rb-legacy]")

    assertTrue(IosHostSimctlUtils.isAppRunning(running, "com.example.app"))
    assertFalse(IosHostSimctlUtils.isAppRunning(exited, "com.example.app"))
    assertFalse(IosHostSimctlUtils.isAppRunning(otherApp, "com.example.app"))
  }

  @Test
  fun `only get_app_container's ENOENT means the app is not installed`() {
    // Captured from `xcrun simctl get_app_container <udid> <missing bundle id> data`.
    val notInstalled = "An error was encountered processing the command (domain=NSPOSIXErrorDomain, code=2):\n" +
      "The operation couldn\u2019t be completed. No such file or directory"
    val otherFailure = "An error was encountered processing the command (domain=NSPOSIXErrorDomain, code=60):\n" +
      "Operation timed out"

    assertTrue(IosHostSimctlUtils.isNotInstalledContainerOutput(notInstalled))
    assertFalse(IosHostSimctlUtils.isNotInstalledContainerOutput(otherFailure))
  }
}
