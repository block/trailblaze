package xyz.block.trailblaze.util

import java.io.File

/**
 * Resolves the path to the `adb` binary at startup.
 *
 * The Desktop app UI ([ToolAvailabilityChecker]) already has smart detection that
 * checks ANDROID_HOME, ANDROID_SDK_ROOT, and well-known SDK install locations.
 * However, the CLI (`trailblaze run`) and MCP server (`trailblaze mcp`) invoke adb
 * via bare `ProcessBuilder("adb", ...)` calls, which fail when adb isn't on PATH —
 * even when ANDROID_HOME is set correctly.
 *
 * This resolver runs the same detection logic once at startup. All code that spawns
 * adb processes should use [adbCommand] instead of a hard-coded `"adb"` string.
 *
 * Call [resolve] early in the CLI / MCP entry point so that [adbCommand] is ready
 * before any device operations begin.
 */
object AdbPathResolver {

  /**
   * The command to use when spawning adb processes.
   *
   * After [resolve] runs this is either the bare `"adb"` (when it's already on PATH)
   * or the absolute path to the adb binary found inside a known SDK location.
   */
  @Volatile
  var adbCommand: String = "adb"
    private set

  /**
   * Well-known locations where the Android SDK may be installed.
   * Checked in order; the first match wins.
   */
  private val CANDIDATE_SDK_PATHS: List<String> by lazy {
    val home = System.getProperty("user.home") ?: ""
    val localAppData = System.getenv("LOCALAPPDATA") ?: ""
    listOfNotNull(
      System.getenv("ANDROID_HOME"),
      System.getenv("ANDROID_SDK_ROOT"),
      "$home/Library/Android/sdk",          // Android Studio default on macOS
      "$home/Android/Sdk",                  // Android Studio default on Linux
      "/usr/local/share/android-sdk",       // Homebrew cask location
      "$home/.android/sdk",
      // Windows locations
      "$localAppData\\Android\\Sdk".takeIf { localAppData.isNotEmpty() },
      "$home\\AppData\\Local\\Android\\Sdk",
    )
  }

  /** ADB executable filenames to probe inside SDK directories. */
  private val ADB_FILENAMES: List<String> by lazy {
    if (isWindows()) listOf("adb.exe", "adb") else listOf("adb")
  }

  /**
   * Checks whether [command] exists as an executable file in any directory on the
   * system PATH. Uses a pure filesystem lookup (no process spawn).
   */
  private fun isCommandOnPath(command: String): Boolean {
    val pathEnv = System.getenv("PATH") ?: return false
    val extensions = if (isWindows()) {
      val pathExt = System.getenv("PATHEXT") ?: ".COM;.EXE;.BAT;.CMD"
      listOf("") + pathExt.split(";").filter { it.isNotEmpty() }.map { it.lowercase() }
    } else {
      listOf("")
    }
    return pathEnv.split(File.pathSeparatorChar).any { dir ->
      extensions.any { ext ->
        val file = File(dir, command + ext)
        file.exists() && file.canExecute()
      }
    }
  }

  /**
   * Resolves the adb binary path. Should be called once during CLI / MCP startup.
   *
   * If adb is already on PATH, this is a no-op. Otherwise it searches the
   * candidate SDK paths and, if found, sets [adbCommand] to the absolute path.
   *
   * @return `true` if adb was found (either on PATH or in an SDK), `false` otherwise.
   */
  fun resolve(): Boolean {
    // Already on PATH — nothing to do.
    if (isCommandOnPath("adb")) {
      Console.log("[AdbPathResolver] adb found on PATH")
      return true
    }

    // Search well-known SDK locations.
    for (sdkPath in CANDIDATE_SDK_PATHS) {
      if (sdkPath.isNullOrBlank()) continue
      for (adbName in ADB_FILENAMES) {
        val adbFile = File(sdkPath, "platform-tools${File.separator}$adbName")
        if (adbFile.exists() && adbFile.canExecute()) {
          adbCommand = adbFile.absolutePath
          Console.log("[AdbPathResolver] adb not on PATH but found at $adbCommand")
          return true
        }
      }
    }

    Console.log("[AdbPathResolver] adb not found on PATH or in any known SDK location")
    return false
  }
}
