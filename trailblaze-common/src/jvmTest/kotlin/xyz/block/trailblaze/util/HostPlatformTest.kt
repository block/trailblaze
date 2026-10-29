package xyz.block.trailblaze.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HostPlatformTest {
  @Test
  fun `each JVM name for the same architecture reads as one name a tool can branch on`() {
    // macOS and Linux JVMs on Arm say aarch64; Windows and Linux on Intel say amd64, older ones x86_64.
    assertEquals(listOf("arm64", "arm64"), listOf("aarch64", "arm64").map { HostArch.fromOsArch(it)?.hostArchName })
    assertEquals(listOf("x64", "x64", "x64"), listOf("amd64", "x86_64", "X64").map { HostArch.fromOsArch(it)?.hostArchName })
  }

  @Test
  fun `an architecture Trailblaze doesn't name reads as null rather than a raw JVM string`() {
    assertNull(HostArch.fromOsArch("riscv64"))
  }

  @Test
  fun `the current host reads as the OS name the SDK accepts`() {
    val expected = when (DesktopOsType.current()) {
      DesktopOsType.MAC_OS -> "macos"
      DesktopOsType.WINDOWS -> "windows"
      DesktopOsType.LINUX -> "linux"
    }
    assertEquals(expected, HostPlatform.current().os.hostOsName)
  }
}
