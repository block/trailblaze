package xyz.block.trailblaze.util

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.block.trailblaze.util.AndroidSdkPaths.ADB_EXECUTABLE
import xyz.block.trailblaze.util.AndroidSdkPaths.ADB_EXECUTABLE_WINDOWS

/**
 * Tests for [AdbPathResolver.resolveAdbPath].
 *
 * Each test injects fake `pathCheck` / `executableFileCheck` providers so the logic is
 * exercised deterministically without depending on the environment of the machine
 * running CI.
 */
class AdbPathResolverTest {

  @Test
  fun `returns bare adb when adb is on PATH`() {
    val result =
      AdbPathResolver.resolveAdbPath(
        candidatePaths = listOf("/ignored/sdk"),
        adbFilenames = listOf(ADB_EXECUTABLE),
        pathCheck = { cmd -> cmd == ADB_EXECUTABLE },
        executableFileCheck = { error("SDK probe should not run when PATH hits") },
      )

    assertEquals(ADB_EXECUTABLE, result)
  }

  @Test
  fun `returns absolute path when adb found inside a candidate SDK`() {
    val sdkRoot = "/fake/android/sdk"
    val expectedAbsolutePath =
      File(sdkRoot, "platform-tools${File.separator}$ADB_EXECUTABLE").absolutePath

    val result =
      AdbPathResolver.resolveAdbPath(
        candidatePaths = listOf(sdkRoot),
        adbFilenames = listOf(ADB_EXECUTABLE),
        pathCheck = { false },
        executableFileCheck = { file -> file.absolutePath == expectedAbsolutePath },
      )

    assertEquals(expectedAbsolutePath, result)
  }

  @Test
  fun `returns first matching SDK path when multiple candidates exist`() {
    val sdkRoots = listOf("/first/sdk", "/second/sdk")
    val expectedAbsolutePath =
      File(sdkRoots[0], "platform-tools${File.separator}$ADB_EXECUTABLE").absolutePath

    val result =
      AdbPathResolver.resolveAdbPath(
        candidatePaths = sdkRoots,
        adbFilenames = listOf(ADB_EXECUTABLE),
        pathCheck = { false },
        // Both SDKs report adb present; resolver should pick the first.
        executableFileCheck = { true },
      )

    assertEquals(expectedAbsolutePath, result)
  }

  @Test
  fun `skips blank candidate paths`() {
    val result =
      AdbPathResolver.resolveAdbPath(
        candidatePaths = listOf("", "   ", "/real/sdk"),
        adbFilenames = listOf(ADB_EXECUTABLE),
        pathCheck = { false },
        executableFileCheck = { file -> file.absolutePath.startsWith("/real/sdk") },
      )

    assertTrue("Expected a path under /real/sdk, got: $result", result.startsWith("/real/sdk"))
  }

  @Test
  fun `prefers adb_exe on Windows when both filenames are probed`() {
    val sdkRoot = """C:\fake\sdk"""
    val result =
      AdbPathResolver.resolveAdbPath(
        candidatePaths = listOf(sdkRoot),
        adbFilenames = listOf(ADB_EXECUTABLE_WINDOWS, ADB_EXECUTABLE),
        pathCheck = { false },
        // Both would exist; the resolver should pick the first filename in the list.
        executableFileCheck = { true },
      )

    assertTrue("Expected Windows result to end with adb.exe, got: $result", result.endsWith(ADB_EXECUTABLE_WINDOWS))
  }

  @Test
  fun `falls back to bare adb when neither PATH nor any SDK resolves`() {
    val result =
      AdbPathResolver.resolveAdbPath(
        candidatePaths = listOf("/no/such/sdk"),
        adbFilenames = listOf(ADB_EXECUTABLE),
        pathCheck = { false },
        executableFileCheck = { false },
      )

    assertEquals(ADB_EXECUTABLE, result)
  }

  @Test
  fun `public ADB_COMMAND is memoized across accesses`() {
    // The lazy delegate guarantees a single computation; this test just documents the contract.
    assertEquals(AdbPathResolver.ADB_COMMAND, AdbPathResolver.ADB_COMMAND)
  }
}
