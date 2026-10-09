package xyz.block.trailblaze.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which hosts the startup gate lets run. Each supported row is a host the uber JAR ships WebP and
 * QuickJS natives for; each rejected row is one it does not, where the first screenshot would fail
 * deep in a JNI loader instead of at startup. Values are the raw `os.name` / `os.arch` HotSpot
 * reports on that host.
 */
class SupportedPlatformTest {

  @Test
  fun `supported hosts start`() {
    listOf(
      "Mac OS X" to "aarch64",
      "Linux" to "amd64",
      "Linux" to "aarch64",
      "Windows 11" to "amd64",
      "Windows 10" to "amd64",
      "Windows Server 2022" to "amd64",
    ).forEach { (os, arch) ->
      assertTrue(TrailblazeDesktopUtil.isSupportedPlatform(os, arch), "$os ($arch) should start")
    }
  }

  @Test
  fun `hosts with no shipped natives are rejected`() {
    listOf(
      // Intel Mac: the JAR leaves out its WebP natives.
      "Mac OS X" to "x86_64",
      // Windows on Arm with an Arm JDK: neither WebP nor QuickJS publishes an arm64 Windows build.
      "Windows 11" to "aarch64",
      // 32-bit Windows JDK.
      "Windows 10" to "x86",
      "FreeBSD" to "amd64",
      "Linux" to "ppc64le",
    ).forEach { (os, arch) ->
      assertFalse(TrailblazeDesktopUtil.isSupportedPlatform(os, arch), "$os ($arch) should be rejected")
    }
  }
}
