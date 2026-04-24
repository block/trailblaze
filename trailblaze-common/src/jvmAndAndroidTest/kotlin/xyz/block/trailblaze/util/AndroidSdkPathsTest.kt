package xyz.block.trailblaze.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [AndroidSdkPaths.computeCandidatePaths].
 *
 * Uses injected env / userHome / osType providers so each OS + env-var combination
 * is deterministic without mutating the real process environment.
 */
class AndroidSdkPathsTest {

  private fun envOf(vararg entries: Pair<String, String>): (String) -> String? {
    val map = entries.toMap()
    return { map[it] }
  }

  @Test
  fun `env vars come before platform defaults`() {
    val paths =
      AndroidSdkPaths.computeCandidatePaths(
        envProvider = envOf("ANDROID_HOME" to "/custom/sdk", "ANDROID_SDK_ROOT" to "/legacy/sdk"),
        userHome = "/home/user",
        osType = DesktopOsType.LINUX,
      )

    assertEquals("/custom/sdk", paths[0])
    assertEquals("/legacy/sdk", paths[1])
    assertEquals("/home/user/Android/Sdk", paths[2])
  }

  @Test
  fun `missing env vars are simply omitted`() {
    val paths =
      AndroidSdkPaths.computeCandidatePaths(
        envProvider = { null },
        userHome = "/home/user",
        osType = DesktopOsType.LINUX,
      )

    assertEquals(listOf("/home/user/Android/Sdk"), paths)
  }

  @Test
  fun `mac defaults include Android Studio and Homebrew cask paths`() {
    val paths =
      AndroidSdkPaths.computeCandidatePaths(
        envProvider = { null },
        userHome = "/Users/dev",
        osType = DesktopOsType.MAC_OS,
      )

    assertEquals(
      listOf("/Users/dev/Library/Android/sdk", "/usr/local/share/android-sdk"),
      paths,
    )
  }

  @Test
  fun `windows includes LOCALAPPDATA-derived path when env var is set`() {
    val paths =
      AndroidSdkPaths.computeCandidatePaths(
        envProvider = envOf("LOCALAPPDATA" to """C:\Users\dev\AppData\Local"""),
        userHome = """C:\Users\dev""",
        osType = DesktopOsType.WINDOWS,
      )

    assertTrue(
      "LOCALAPPDATA path should be present: $paths",
      paths.contains("""C:\Users\dev\AppData\Local\Android\Sdk"""),
    )
  }

  @Test
  fun `windows omits LOCALAPPDATA path when env var is missing`() {
    val paths =
      AndroidSdkPaths.computeCandidatePaths(
        envProvider = { null },
        userHome = """C:\Users\dev""",
        osType = DesktopOsType.WINDOWS,
      )

    assertEquals(listOf("""C:\Users\dev\AppData\Local\Android\Sdk"""), paths)
  }

  @Test
  fun `windows paths use single backslashes at runtime`() {
    val paths =
      AndroidSdkPaths.computeCandidatePaths(
        envProvider = envOf("LOCALAPPDATA" to """C:\x\y"""),
        userHome = """C:\Users\dev""",
        osType = DesktopOsType.WINDOWS,
      )

    // Defensive: guard against anyone reverting the triple-quoted strings back to
    // double-escaped literals — the runtime value must contain single backslashes.
    paths.forEach { path ->
      assertTrue("Path should not contain '\\\\': $path", !path.contains("\\\\"))
    }
  }
}
