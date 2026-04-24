package xyz.block.trailblaze.util

/**
 * Single source of truth for locating the Android SDK on the host machine.
 *
 * Both [AdbPathResolver] (CLI / MCP adb resolution) and `ToolAvailabilityChecker`
 * (desktop UI status panel) use this helper, so the two never drift on which paths
 * are considered "well-known".
 */
object AndroidSdkPaths {

  /** Raw `adb` executable filename on macOS and Linux; also the fallback command name. */
  const val ADB_EXECUTABLE = "adb"

  /** Raw `adb.exe` executable filename on Windows. */
  const val ADB_EXECUTABLE_WINDOWS = "adb.exe"

  /**
   * Filenames to probe inside an SDK's `platform-tools/` directory when looking for adb.
   *
   * On Windows both `adb.exe` and `adb` are possible (Windows filesystem ignores case in the
   * extension, but some SDK distributions ship both); elsewhere only `adb`.
   */
  val ADB_FILENAMES: List<String> by lazy {
    if (isWindows()) listOf(ADB_EXECUTABLE_WINDOWS, ADB_EXECUTABLE) else listOf(ADB_EXECUTABLE)
  }

  /**
   * Candidate SDK directories, ordered by priority.
   *
   * Environment variables come first (most reliable), then platform-specific defaults
   * mirroring Android Studio's default SDK install location on each OS:
   *
   * - macOS:   `~/Library/Android/sdk` (Android Studio default);
   *            `/usr/local/share/android-sdk` (Homebrew `android-sdk` cask).
   * - Linux:   `~/Android/Sdk` (Android Studio default).
   * - Windows: `%LOCALAPPDATA%\Android\Sdk` (Android Studio default; typically
   *            `$home\AppData\Local\Android\Sdk`).
   *
   * Env-var contract: `ANDROID_HOME` is the current variable; `ANDROID_SDK_ROOT` is the
   * deprecated legacy name, still honored by the Android command-line tools.
   *
   * Lazy because the values cannot change after JVM start, so we compute them once.
   */
  val CANDIDATE_PATHS: List<String> by lazy { computeCandidatePaths() }

  /**
   * Pure function used by [CANDIDATE_PATHS] and unit tests. Exposed with injectable
   * providers so tests can exercise each OS / env-var combination without mutating
   * the real environment.
   */
  internal fun computeCandidatePaths(
    envProvider: (String) -> String? = { System.getenv(it) },
    userHome: String = System.getProperty("user.home") ?: "",
    osType: DesktopOsType = DesktopOsType.current(),
  ): List<String> {
    val fromEnv =
      listOfNotNull(envProvider("ANDROID_HOME"), envProvider("ANDROID_SDK_ROOT"))
    val platformDefaults =
      when (osType) {
        DesktopOsType.MAC_OS ->
          listOf(
            "$userHome/Library/Android/sdk",
            "/usr/local/share/android-sdk",
          )
        DesktopOsType.LINUX -> listOf("$userHome/Android/Sdk")
        DesktopOsType.WINDOWS -> {
          val localAppData = envProvider("LOCALAPPDATA") ?: ""
          listOfNotNull(
            """$localAppData\Android\Sdk""".takeIf { localAppData.isNotEmpty() },
            """$userHome\AppData\Local\Android\Sdk""",
          )
        }
      }
    return fromEnv + platformDefaults
  }
}
