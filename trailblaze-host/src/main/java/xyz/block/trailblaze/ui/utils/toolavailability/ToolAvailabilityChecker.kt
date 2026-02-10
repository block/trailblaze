package xyz.block.trailblaze.ui.utils.toolavailability

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ToolAvailabilityChecker {

  /**
   * Well-known locations where the Android SDK may be installed. The list is checked in order; the
   * first match wins.
   */
  private val CANDIDATE_SDK_PATHS: List<String> by lazy {
    val home = System.getProperty("user.home") ?: ""
    val localAppData = System.getenv("LOCALAPPDATA") ?: ""
    listOfNotNull(
      System.getenv("ANDROID_HOME"),
      System.getenv("ANDROID_SDK_ROOT"),
      "$home/Library/Android/sdk", // Android Studio default on macOS
      "$home/Android/Sdk", // Android Studio default on Linux
      "/usr/local/share/android-sdk", // Homebrew cask location
      "$home/.android/sdk",
      // Windows locations
      "$localAppData\\Android\\Sdk".takeIf { localAppData.isNotEmpty() },
      "$home\\AppData\\Local\\Android\\Sdk",
    )
  }

  /** Well-known locations where Xcode.app may live. */
  private val CANDIDATE_XCODE_PATHS =
    listOf(
      "/Applications/Xcode.app",
      "/Applications/Xcode-beta.app",
    )

  suspend fun check(): ToolAvailability =
    withContext(Dispatchers.IO) {
      ToolAvailability(
        adbStatus = detectAdb(),
        iosStatus = detectIos(),
      )
    }

  // ---------------------------------------------------------------------------
  // ADB
  // ---------------------------------------------------------------------------

  /** ADB executable filenames to probe inside SDK directories. */
  private val ADB_FILENAMES: List<String> by lazy {
    if (IS_WINDOWS) listOf("adb.exe", "adb") else listOf("adb")
  }

  /** Tiered detection for ADB. */
  private fun detectAdb(): AdbStatus {
    // Tier 1: ADB is already on the shell PATH.
    if (isCommandAvailable("adb")) return AdbStatus.Available

    // Tier 2: Look for ADB inside known SDK locations.
    for (sdkPath in CANDIDATE_SDK_PATHS) {
      if (sdkPath.isBlank()) continue
      for (adbName in ADB_FILENAMES) {
        val adbFile = File(sdkPath, "platform-tools${File.separator}$adbName")
        if (adbFile.exists() && adbFile.canExecute()) {
          return AdbStatus.SdkFoundNotOnPath(
            sdkPath = sdkPath,
            adbPath = adbFile.absolutePath,
          )
        }
      }
    }

    // Tier 3: Not found anywhere.
    return AdbStatus.NotInstalled
  }

  // ---------------------------------------------------------------------------
  // iOS
  // ---------------------------------------------------------------------------

  /** Tiered detection for iOS tools (Xcode / Command Line Tools). */
  private fun detectIos(): IosStatus {
    // Tier 1: xcrun and xcodebuild are both available on PATH.
    if (isCommandAvailable("xcrun") && isCommandAvailable("xcodebuild")) {
      return IosStatus.Available
    }

    // Tier 2: Xcode.app bundle exists on disk but CLI tools aren't configured.
    for (path in CANDIDATE_XCODE_PATHS) {
      val xcodeApp = File(path)
      if (xcodeApp.exists() && xcodeApp.isDirectory) {
        return IosStatus.XcodeInstalledNotConfigured(xcodePath = path)
      }
    }

    // Tier 3: Xcode not found.
    return IosStatus.NotInstalled
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /** True when running on a Windows host. */
  private val IS_WINDOWS: Boolean by lazy {
    System.getProperty("os.name")?.lowercase()?.contains("win") == true
  }

  /**
   * Extensions to append when probing for an executable on Windows. Derived from the `PATHEXT`
   * environment variable (falling back to a sensible default). On non-Windows systems this list
   * contains only an empty string so the bare [command] name is checked as-is.
   */
  private val EXECUTABLE_EXTENSIONS: List<String> by lazy {
    if (IS_WINDOWS) {
      val pathExt = System.getenv("PATHEXT") ?: ".COM;.EXE;.BAT;.CMD"
      // Check the bare name first, then each PATHEXT extension (case-insensitive).
      listOf("") + pathExt.split(";").filter { it.isNotEmpty() }.map { it.lowercase() }
    } else {
      listOf("")
    }
  }

  /**
   * Checks whether [command] exists as an executable file in any directory on the system PATH.
   * Uses a pure filesystem lookup instead of spawning a process, which is faster and avoids
   * portability issues with `which` / `where`. On Windows, the `PATHEXT` environment variable is
   * consulted so that e.g. `adb` resolves to `adb.exe`.
   */
  private fun isCommandAvailable(command: String): Boolean {
    val pathEnv = System.getenv("PATH") ?: return false
    return pathEnv.split(File.pathSeparatorChar).any { dir ->
      EXECUTABLE_EXTENSIONS.any { ext ->
        val file = File(dir, command + ext)
        file.exists() && file.canExecute()
      }
    }
  }
}
