package xyz.block.trailblaze.host.ios

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import xyz.block.trailblaze.util.SimctlCommand

/**
 * Unit tests for [SimctlCli]'s pure helpers: the permission-preserving bundle copy backing
 * `clearAppState`'s reinstall, the symlink-safe temp-bundle delete, the `launchctl list`
 * parse backing `ensureStopped`, and the `launch` argv build. The simctl-subprocess paths
 * need a real simulator and aren't covered here.
 */
class SimctlCliTest {

  /** POSIX-permission and symlink tests skip (not fail) on non-POSIX filesystems. */
  private fun assumePosixFileSystem() {
    assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))
  }

  // --- copyDirectoryPreservingAttributes ---

  @Test
  fun `copy preserves the execute bit on a nested binary`() {
    assumePosixFileSystem()
    val src = Files.createTempDirectory("simctl-copy-src-")
    val extension = Files.createDirectories(src.resolve("PlugIns/Widget.appex"))
    val binary = Files.createFile(extension.resolve("Widget"))
    Files.write(binary, byteArrayOf(0x7F, 0x45, 0x4C, 0x46))
    val exec = setOf(
      PosixFilePermission.OWNER_READ,
      PosixFilePermission.OWNER_WRITE,
      PosixFilePermission.OWNER_EXECUTE,
      PosixFilePermission.GROUP_READ,
      PosixFilePermission.GROUP_EXECUTE,
      PosixFilePermission.OTHERS_READ,
      PosixFilePermission.OTHERS_EXECUTE,
    )
    Files.setPosixFilePermissions(binary, exec)

    val dst = Files.createTempDirectory("simctl-copy-dst-").resolve("Widget.app")
    SimctlCli.copyDirectoryPreservingAttributes(src, dst)

    val copied = dst.resolve("PlugIns/Widget.appex/Widget")
    assertTrue(Files.isRegularFile(copied))
    assertEquals(exec, Files.getPosixFilePermissions(copied))
    assertEquals(Files.readAllBytes(binary).toList(), Files.readAllBytes(copied).toList())
  }

  @Test
  fun `copy keeps a non-executable file non-executable`() {
    assumePosixFileSystem()
    val src = Files.createTempDirectory("simctl-copy-src-")
    val plist = Files.createFile(src.resolve("Info.plist"))
    Files.setPosixFilePermissions(
      plist,
      setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.GROUP_READ,
        PosixFilePermission.OTHERS_READ,
      ),
    )

    val dst = Files.createTempDirectory("simctl-copy-dst-").resolve("Widget.app")
    SimctlCli.copyDirectoryPreservingAttributes(src, dst)

    val perms = Files.getPosixFilePermissions(dst.resolve("Info.plist"))
    assertFalse(PosixFilePermission.OWNER_EXECUTE in perms)
    assertFalse(PosixFilePermission.GROUP_EXECUTE in perms)
    assertFalse(PosixFilePermission.OTHERS_EXECUTE in perms)
  }

  @Test
  fun `copy replicates a symlink as a link without following it`() {
    assumePosixFileSystem()
    val src = Files.createTempDirectory("simctl-copy-src-")
    val real = Files.createFile(src.resolve("Current"))
    val link: Path = Files.createSymbolicLink(src.resolve("Latest"), real.fileName)

    val dst = Files.createTempDirectory("simctl-copy-dst-").resolve("Widget.app")
    SimctlCli.copyDirectoryPreservingAttributes(src, dst)

    val copiedLink = dst.resolve("Latest")
    assertTrue(Files.isSymbolicLink(copiedLink))
    assertEquals(Files.readSymbolicLink(link), Files.readSymbolicLink(copiedLink))
  }

  // --- deleteRecursivelyNoFollowLinks ---

  @Test
  fun `delete removes a symlink itself without deleting its external target`() {
    assumePosixFileSystem()
    val external = Files.createTempDirectory("simctl-delete-external-")
    val externalFile = Files.createFile(external.resolve("keep.txt"))

    val bundle = Files.createTempDirectory("simctl-delete-bundle-")
    Files.createFile(bundle.resolve("Info.plist"))
    Files.createSymbolicLink(bundle.resolve("Escape"), external)

    SimctlCli.deleteRecursivelyNoFollowLinks(bundle)

    assertFalse(Files.exists(bundle))
    assertTrue(Files.isDirectory(external))
    assertTrue(Files.isRegularFile(externalFile))
  }

  // --- isAppRunning ---

  private val header = "PID\tStatus\tLabel"

  @Test
  fun `app with a live pid reads as running`() {
    val output = """
      $header
      512	0	UIKitApplication:com.example.app[a1b2][rb-legacy]
      1	0	com.apple.SpringBoard
    """.trimIndent()
    assertTrue(SimctlCli.isAppRunning(output, "com.example.app"))
  }

  @Test
  fun `loaded-but-not-running job (dash pid) reads as stopped`() {
    val output = """
      $header
      -	0	UIKitApplication:com.example.app[a1b2][rb-legacy]
    """.trimIndent()
    assertFalse(SimctlCli.isAppRunning(output, "com.example.app"))
  }

  @Test
  fun `absent job reads as stopped`() {
    val output = """
      $header
      1	0	com.apple.SpringBoard
    """.trimIndent()
    assertFalse(SimctlCli.isAppRunning(output, "com.example.app"))
  }

  @Test
  fun `bundle id prefix does not match a longer bundle id`() {
    val output = """
      $header
      512	0	UIKitApplication:com.example.app2[a1b2][rb-legacy]
    """.trimIndent()
    assertFalse(SimctlCli.isAppRunning(output, "com.example.app"))
  }

  // --- launchCommand ---

  @Test
  fun `launchCommand appends launch arguments after the bundle id`() {
    assertEquals(
      SimctlCommand.argv(
        "launch", "UDID-123", "com.example.app",
        "-E2ETestLaunchSessionToken", "session-token-value",
      ),
      SimctlCli.launchCommand(
        udid = "UDID-123",
        bundleId = "com.example.app",
        launchArguments = listOf("-E2ETestLaunchSessionToken", "session-token-value"),
      ),
    )
  }

  @Test
  fun `launchCommand without launch arguments is the bare simctl launch command`() {
    assertEquals(
      SimctlCommand.argv("launch", "UDID-123", "com.example.app"),
      SimctlCli.launchCommand(
        udid = "UDID-123",
        bundleId = "com.example.app",
        launchArguments = emptyList(),
      ),
    )
  }

  @Test
  fun `the display name comes from appinfo, quoted or not, before the bundle name`() {
    // Shapes captured from `xcrun simctl appinfo`.
    val safari = "{\n    CFBundleDisplayName = Safari;\n    CFBundleIdentifier = \"com.apple.mobilesafari\";\n    CFBundleName = MobileSafari;\n}"
    val spaced = "{\n    CFBundleDisplayName = \"Point of Sale\";\n    CFBundleName = POS;\n}"
    val nameOnly = "{\n    CFBundleIdentifier = \"com.example.app\";\n    CFBundleName = Shop;\n}"

    assertEquals("Safari", SimctlCli.parseAppDisplayName(safari))
    assertEquals("Point of Sale", SimctlCli.parseAppDisplayName(spaced))
    assertEquals("Shop", SimctlCli.parseAppDisplayName(nameOnly))
  }

  private class FakeReboot(vararg states: String?, private val bootFailures: Int = 0) {
    private val states = states.toMutableList()
    val booted = mutableListOf<String>()
    var now = 0L

    fun state(): String? = if (states.size > 1) states.removeAt(0) else states.first()

    fun boot(): SimctlCli.Result {
      booted += "boot"
      return if (booted.size <= bootFailures) SimctlCli.Result(1, "", "Unable to boot") else SimctlCli.Result(0, "", "")
    }
  }

  private fun FakeReboot.run(shutdown: SimctlCli.Result?, timeoutSeconds: Long = 120) =
    SimctlCli.bootAfterShutdown(
      udid = "SIM",
      shutdown = shutdown,
      timeoutSeconds = timeoutSeconds,
      state = ::state,
      boot = ::boot,
      nanoTime = { now },
      sleep = { now += it * 1_000_000 },
    )

  @Test
  fun `a reboot waits out the shutdown before it boots`() {
    val fake = FakeReboot("Shutting Down", "Shutting Down", "Shutdown")

    assertTrue(fake.run(SimctlCli.Result(0, "", "")).success)
    assertEquals(listOf("boot"), fake.booted)
  }

  @Test
  fun `a shutdown that timed out but went through is still followed by a boot`() {
    val fake = FakeReboot("Shutting Down", "Shutdown")

    assertTrue(fake.run(SimctlCli.Result(-1, "", "simctl timed out after 120s")).success)
    assertEquals(listOf("boot"), fake.booted)
  }

  @Test
  fun `a device a failed shutdown left booted reports that failure and is not booted again`() {
    val fake = FakeReboot("Booted")

    val result = fake.run(SimctlCli.Result(1, "", "Unable to shutdown"))

    assertFalse(result.success)
    assertEquals("Unable to shutdown", result.stderr)
    assertEquals(emptyList(), fake.booted)
  }

  @Test
  fun `a boot that fails while the device stays shut down is tried again`() {
    val fake = FakeReboot("Shutdown", bootFailures = 1)

    assertTrue(fake.run(SimctlCli.Result(0, "", "")).success)
    assertEquals(listOf("boot", "boot"), fake.booted)
  }

  @Test
  fun `a device that never boots reports the last boot failure`() {
    val fake = FakeReboot("Shutdown", bootFailures = Int.MAX_VALUE)

    val result = fake.run(SimctlCli.Result(0, "", ""), timeoutSeconds = 2)

    assertFalse(result.success)
    assertEquals("Unable to boot", result.stderr)
  }

  @Test
  fun `a device that never finishes shutting down fails the reboot`() {
    val fake = FakeReboot("Shutting Down")

    val result = fake.run(SimctlCli.Result(0, "", ""), timeoutSeconds = 2)

    assertFalse(result.success)
    assertTrue(result.stderr.contains("still `Shutting Down`"))
    assertEquals(emptyList(), fake.booted)
  }

  @Test
  fun `reads one device's state out of the simctl listing`() {
    val listing = """
      -- iOS 26.5 --
          iPad (A16) (1822DE03-F863-4D4A-B826-F1E3E470482A) (Shutting Down)
          iPhone 17 Pro (E5BDD6FB-1C7C-46B7-A479-E8A772C3922D) (Booted)
    """.trimIndent()

    assertEquals("Shutting Down", SimctlCli.parseDeviceState(listing, "1822DE03-F863-4D4A-B826-F1E3E470482A"))
    assertEquals("Booted", SimctlCli.parseDeviceState(listing, "E5BDD6FB-1C7C-46B7-A479-E8A772C3922D"))
    assertEquals(null, SimctlCli.parseDeviceState(listing, "MISSING"))
  }

  @Test
  fun `reads a simulator's device type from the simctl device listing`() {
    val listing = """
      {
        "devices" : {
          "com.apple.CoreSimulator.SimRuntime.iOS-26-5" : [
            { "udid" : "E5BDD6FB-1C7C-46B7-A479-E8A772C3922D", "deviceTypeIdentifier" : "com.apple.CoreSimulator.SimDeviceType.iPhone-17-Pro", "state" : "Booted" },
            { "udid" : "19B2F674-DEA8-4C94-A2E8-4780CC7EE2DA", "deviceTypeIdentifier" : "com.apple.CoreSimulator.SimDeviceType.iPad-A16", "state" : "Booted" }
          ]
        }
      }
    """.trimIndent()

    assertEquals("com.apple.CoreSimulator.SimDeviceType.iPad-A16", SimctlCli.parseDeviceTypeIdentifier(listing, "19B2F674-DEA8-4C94-A2E8-4780CC7EE2DA"))
    assertEquals("com.apple.CoreSimulator.SimDeviceType.iPhone-17-Pro", SimctlCli.parseDeviceTypeIdentifier(listing, "E5BDD6FB-1C7C-46B7-A479-E8A772C3922D"))
    assertEquals(null, SimctlCli.parseDeviceTypeIdentifier(listing, "MISSING"))
    assertEquals(null, SimctlCli.parseDeviceTypeIdentifier("not json", "19B2F674-DEA8-4C94-A2E8-4780CC7EE2DA"))
  }
}
