package xyz.block.trailblaze.adb

/**
 * Wasm/JS implementation of [TrailblazeAdb].
 * 
 * ADB operations are not supported in the browser environment.
 * All methods throw UnsupportedOperationException.
 */
actual object TrailblazeAdb {
  
  private fun unsupported(): Nothing {
    throw UnsupportedOperationException(
      "TrailblazeAdb is not supported in Wasm/JS environment. " +
      "ADB operations can only be performed on Android devices or from a JVM host."
    )
  }

  actual fun shell(command: String): String = unsupported()

  actual fun isAppRunning(packageName: String): Boolean = unsupported()

  actual fun forceStopApp(packageName: String): Unit = unsupported()

  actual fun clearAppData(packageName: String): Unit = unsupported()

  actual fun grantPermission(packageName: String, permission: String): Unit = unsupported()

  actual fun listInstalledPackages(): List<String> = unsupported()

  actual fun getSerialNumber(): String = unsupported()
}
