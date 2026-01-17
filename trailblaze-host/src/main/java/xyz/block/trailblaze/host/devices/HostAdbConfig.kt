package xyz.block.trailblaze.host.devices

import xyz.block.trailblaze.util.HostAdbExecutor

/**
 * Configuration for which ADB implementation to use on the host.
 * 
 * Two implementations are available:
 * - [AdbImplementation.ADB_BINARY]: Uses the `adb` binary via ProcessBuilder (traditional approach)
 * - [AdbImplementation.DADB]: Uses the dadb library for direct ADB protocol communication
 * 
 * Example usage:
 * ```kotlin
 * // Use adb binary (default, most compatible)
 * HostAdbConfig.initialize(AdbImplementation.ADB_BINARY)
 * 
 * // Or use dadb (faster, no process spawning)
 * HostAdbConfig.initialize(AdbImplementation.DADB)
 * ```
 */
object HostAdbConfig {
  
  /**
   * Available ADB implementations.
   */
  enum class AdbImplementation {
    /**
     * Use the `adb` binary via ProcessBuilder.
     * 
     * Pros:
     * - Maximum compatibility with all devices and emulators
     * - Supports all adb features
     * - Easier to debug (can see adb commands)
     * 
     * Cons:
     * - Spawns a process for each operation
     * - Slightly slower due to process overhead
     */
    ADB_BINARY,
    
    /**
     * Use the dadb library for direct ADB protocol communication.
     * 
     * Pros:
     * - No process spawning overhead
     * - Faster for many operations
     * - Direct socket communication
     * 
     * Cons:
     * - May have compatibility issues with some devices
     * - `reverse` still requires adb binary (dadb limitation)
     * - Connection can become stale (handled with auto-reconnect)
     */
    DADB,
  }
  
  private var currentImplementation: AdbImplementation? = null
  
  /**
   * Initialize the ADB executor with the specified implementation.
   * Call this early in application startup.
   * 
   * @param implementation Which ADB implementation to use
   */
  fun initialize(implementation: AdbImplementation = AdbImplementation.ADB_BINARY) {
    currentImplementation = implementation
    
    when (implementation) {
      AdbImplementation.ADB_BINARY -> {
        println("HostAdbConfig: Using adb binary implementation")
        AdbBinaryHostAdbExecutor.initialize()
      }
      AdbImplementation.DADB -> {
        println("HostAdbConfig: Using dadb implementation")
        DadbHostAdbExecutor.initialize()
      }
    }
  }
  
  /**
   * Get the current ADB implementation being used.
   * Returns null if not yet initialized.
   */
  fun getCurrentImplementation(): AdbImplementation? = currentImplementation
  
  /**
   * Check if ADB has been initialized.
   */
  fun isInitialized(): Boolean = HostAdbExecutor.factory != null
  
  /**
   * Clear any cached connections (only applicable for DADB implementation).
   */
  fun clearCache() {
    if (currentImplementation == AdbImplementation.DADB) {
      DadbHostAdbExecutor.clearCache()
    }
  }
}
