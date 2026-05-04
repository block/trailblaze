package xyz.block.trailblaze.util

import java.io.File
import xyz.block.trailblaze.util.AndroidSdkPaths.ADB_EXECUTABLE
import xyz.block.trailblaze.util.TrailblazeProcessBuilderUtils.isCommandAvailable

/**
 * Resolves the path to the `adb` binary once at startup.
 *
 * Now used only by [AndroidHostAdbUtils] as a private fallback for the few host operations that
 * dadb does not cover (currently `adb reverse` and `adb forward tcp:X localabstract:Y`); most
 * shell/install/push/pull/forward operations go directly over the adb wire protocol via dadb.
 *
 * The candidate SDK directories and executable filenames live in [AndroidSdkPaths]
 * so the desktop `ToolAvailabilityChecker` can share them without drift.
 */
object AdbPathResolver {

  /**
   * The command to use when spawning adb processes — i.e. what callers pass to
   * `ProcessBuilder`.
   *
   * Computed once on first access: returns bare `"adb"` when it's already on PATH,
   * or the absolute path to the adb binary found via ANDROID_HOME / ANDROID_SDK_ROOT /
   * well-known SDK install locations.
   */
  val ADB_COMMAND: String by lazy { resolveAdbPath() }

  /**
   * Pure resolver used by [ADB_COMMAND] and by unit tests.
   *
   * Exposed `internal` with injectable providers so tests can exercise each branch
   * (PATH hit, env-var hit, platform-default hit, total miss) without depending on the
   * environment of the machine running the test.
   */
  internal fun resolveAdbPath(
    candidatePaths: List<String> = AndroidSdkPaths.CANDIDATE_PATHS,
    adbFilenames: List<String> = AndroidSdkPaths.ADB_FILENAMES,
    pathCheck: (String) -> Boolean = { isCommandAvailable(it) },
    executableFileCheck: (File) -> Boolean = { it.exists() && it.canExecute() },
  ): String {
    // Already on PATH — nothing to do. (On Windows, `where` handles the `.exe` lookup.)
    if (pathCheck(ADB_EXECUTABLE)) {
      return ADB_EXECUTABLE
    }

    for (sdkPath in candidatePaths) {
      if (sdkPath.isBlank()) continue
      for (adbFilename in adbFilenames) {
        val adbFile = File(sdkPath, "platform-tools${File.separator}$adbFilename")
        if (executableFileCheck(adbFile)) {
          Console.log("[AdbPathResolver] adb not on PATH, found at ${adbFile.absolutePath}")
          return adbFile.absolutePath
        }
      }
    }

    // Total miss — surface the full candidate list so the user can see what was tried.
    Console.log(
      buildString {
        appendLine("[AdbPathResolver] adb not found on PATH or in any known SDK location. Tried:")
        candidatePaths.forEach { appendLine("  - $it") }
      }
    )
    return ADB_EXECUTABLE
  }
}
