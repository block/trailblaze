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
      listOf(
        "xcrun", "simctl", "launch", "UDID-123", "com.example.app",
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
      listOf("xcrun", "simctl", "launch", "UDID-123", "com.example.app"),
      SimctlCli.launchCommand(
        udid = "UDID-123",
        bundleId = "com.example.app",
        launchArguments = emptyList(),
      ),
    )
  }
}
