package xyz.block.trailblaze.adb

/**
 * JVM implementation of [TrailblazeAdb] using a delegate pattern.
 * 
 * This runs on the host machine and delegates to a [TrailblazeAdbDelegate] that must be
 * initialized before use. The delegate is typically set up by the host application
 * (e.g., trailblaze-desktop) with a dadb-based implementation.
 * 
 * Usage:
 * ```kotlin
 * // In host app initialization
 * TrailblazeAdb.delegate = myDadbBasedDelegate
 * 
 * // Then use normally
 * TrailblazeAdb.shell("pm list packages")
 * ```
 */
actual object TrailblazeAdb {
  
  /**
   * Delegate that provides the actual ADB implementation on JVM.
   * Must be set before using any TrailblazeAdb methods.
   */
  var delegate: TrailblazeAdbDelegate? = null
  
  private fun requireDelegate(): TrailblazeAdbDelegate {
    return delegate ?: error(
      "TrailblazeAdb.delegate has not been initialized. " +
      "On JVM, you must set the delegate before using ADB operations. " +
      "Typically this is done in the host application startup."
    )
  }

  actual fun shell(command: String): String {
    return requireDelegate().shell(command)
  }

  actual fun isAppRunning(packageName: String): Boolean {
    return requireDelegate().isAppRunning(packageName)
  }

  actual fun forceStopApp(packageName: String) {
    requireDelegate().forceStopApp(packageName)
  }

  actual fun clearAppData(packageName: String) {
    requireDelegate().clearAppData(packageName)
  }

  actual fun grantPermission(packageName: String, permission: String) {
    requireDelegate().grantPermission(packageName, permission)
  }

  actual fun listInstalledPackages(): List<String> {
    return requireDelegate().listInstalledPackages()
  }

  actual fun getSerialNumber(): String {
    return requireDelegate().getSerialNumber()
  }
}

/**
 * Interface for JVM-side ADB implementations.
 * 
 * Implement this interface to provide ADB functionality on the JVM platform.
 * The implementation typically wraps dadb or another host-side ADB library.
 */
interface TrailblazeAdbDelegate {
  fun shell(command: String): String
  fun isAppRunning(packageName: String): Boolean
  fun forceStopApp(packageName: String)
  fun clearAppData(packageName: String)
  fun grantPermission(packageName: String, permission: String)
  fun listInstalledPackages(): List<String>
  fun getSerialNumber(): String
}
