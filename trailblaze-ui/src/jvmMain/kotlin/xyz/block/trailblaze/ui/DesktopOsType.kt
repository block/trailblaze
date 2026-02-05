package xyz.block.trailblaze.ui

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
