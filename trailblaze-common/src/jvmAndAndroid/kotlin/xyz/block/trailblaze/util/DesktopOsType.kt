package xyz.block.trailblaze.util

/**
 * Represents the operating system type for the desktop application.
 * Used for platform-specific behavior throughout the app.
 */
enum class DesktopOsType(val displayName: String) {
  MAC_OS("macOS"),
  WINDOWS("Windows"),
  LINUX("Linux"),
  ;

  companion object {
    /**
     * Cached current OS type, lazily evaluated once.
     */
    private val current: DesktopOsType by lazy {
      val osName = System.getProperty("os.name")?.lowercase() ?: ""
      when {
        osName.contains("mac") -> MAC_OS
        osName.contains("win") -> WINDOWS
        else -> LINUX // Default to Linux for other Unix-like systems
      }
    }

    /**
     * Returns the current operating system type.
     */
    fun current(): DesktopOsType = current
  }
}

/**
 * Returns true if the current OS is macOS.
 */
fun isMacOs(): Boolean = DesktopOsType.current() == DesktopOsType.MAC_OS

/**
 * Returns true if the current OS is Windows.
 */
fun isWindows(): Boolean = DesktopOsType.current() == DesktopOsType.WINDOWS

/**
 * Returns true if the current OS is Linux.
 */
fun isLinux(): Boolean = DesktopOsType.current() == DesktopOsType.LINUX

/**
 * Returns true if the current CPU architecture is ARM (aarch64 / arm64).
 */
fun isArm(): Boolean {
  val arch = System.getProperty("os.arch").lowercase()
  return arch.contains("aarch64") || arch.contains("arm64")
}

/** Set to `true` to open the desktop window on Linux; see [canRunDesktopGui]. */
const val DESKTOP_GUI_ON_LINUX_ENV = "TRAILBLAZE_DESKTOP_GUI"

/**
 * Returns true if the desktop GUI (Compose Desktop) can run on this platform.
 *
 * macOS with a display always can. Linux only when [DESKTOP_GUI_ON_LINUX_ENV] opts in and a display
 * server is reachable ([hasDisplay]): without the opt-in a Linux host runs the daemon headless even
 * with `$DISPLAY` set, because a workstation that exports one for an emulator does not want a window.
 */
fun canRunDesktopGui(): Boolean =
  canRunDesktopGui(
    os = DesktopOsType.current(),
    headless = java.awt.GraphicsEnvironment.isHeadless(),
    linuxOptIn = System.getenv(DESKTOP_GUI_ON_LINUX_ENV),
    hasDisplay = ::hasDisplay,
  )

internal fun canRunDesktopGui(
  os: DesktopOsType,
  headless: Boolean,
  linuxOptIn: String?,
  hasDisplay: () -> Boolean,
): Boolean =
  when {
    headless -> false
    os == DesktopOsType.MAC_OS -> true
    os == DesktopOsType.LINUX -> linuxOptIn?.trim()?.lowercase() in setOf("1", "true") && hasDisplay()
    else -> false
  }

/**
 * Returns true if a graphical display is available for launching visible (non-headless) UI.
 * On Linux, checks for an X11 (`$DISPLAY`) or Wayland (`$WAYLAND_DISPLAY`) display server.
 * On macOS/Windows, defers to the JVM's headless detection.
 *
 * Tests can pin the result deterministically by setting the `trailblaze.test.hasDisplay`
 * system property to `"true"` or `"false"` — same seam pattern as `trailblaze.appdata.dir`.
 */
fun hasDisplay(): Boolean {
  System.getProperty("trailblaze.test.hasDisplay")?.let { return it.toBoolean() }
  if (java.awt.GraphicsEnvironment.isHeadless()) return false
  if (isLinux()) {
    return !System.getenv("DISPLAY").isNullOrBlank() ||
      !System.getenv("WAYLAND_DISPLAY").isNullOrBlank()
  }
  return true
}
