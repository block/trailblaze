package xyz.block.trailblaze.host.devices

import dadb.Dadb
import maestro.Maestro
import maestro.drivers.AndroidDriver
import xyz.block.trailblaze.util.Console

internal object HostAndroidDriverFactory {

  private const val DEFAULT_MAESTRO_DRIVER_HOST_PORT = 7001

  // Singleton driver reuse within the same JVM session
  private var cachedMaestro: Maestro? = null
  private var cachedInstanceId: String? = null
  private var cachedDriverHostPort: Int? = null

  @Volatile
  private var hasPerformedInitialCleanup = false

  fun createAndroid(
    instanceId: String,
    openDriver: Boolean,
    driverHostPort: Int?,
  ): Maestro {
    val targetPort = driverHostPort ?: DEFAULT_MAESTRO_DRIVER_HOST_PORT

    // Check if we can reuse existing driver
    cachedMaestro?.let { cached ->
      if (cachedInstanceId == instanceId &&
        cachedDriverHostPort == targetPort &&
        !cached.isShutDown()
      ) {
        Console.log("Reusing existing Android driver for device $instanceId on port $targetPort")
        return cached
      }
    }

    // Only perform cleanup on first creation in this JVM (handles stale processes from previous runs)
    if (!hasPerformedInitialCleanup) {
      Console.log("Performing initial cleanup for fresh JVM - removing stale port forwards on port $targetPort")
      HostDriverPortUtils.removeStaleAdbPortForward(instanceId, targetPort)
      HostDriverPortUtils.killProcessesUsingPort(targetPort)
      // Give the system time to fully release the port after killing processes
      Thread.sleep(2000)
      hasPerformedInitialCleanup = true
    } else {
      Console.log("Skipping process cleanup - reusing connection within same JVM session")
      HostDriverPortUtils.waitForPortRelease(port = targetPort, timeoutMs = 5000)
    }

    // Retry logic: the Maestro driver install/start can be flaky after force-stop
    val maxRetries = 3
    var lastException: Exception? = null

    for (attempt in 1..maxRetries) {
      try {
        Console.log("Creating Android driver for device $instanceId on port $targetPort (attempt $attempt/$maxRetries)")

        val dadb = Dadb
          .list()
          .find { it.toString() == instanceId }
          ?: Dadb.discover()
          ?: error("Unable to find device with id $instanceId")

        val driver = AndroidDriver(
          dadb = dadb,
          hostPort = targetPort,
          emulatorName = instanceId,
        )

        val maestro = Maestro.android(
          driver = driver,
          openDriver = openDriver,
        )

        // Cache the driver for reuse
        cachedMaestro = maestro
        cachedInstanceId = instanceId
        cachedDriverHostPort = targetPort

        Console.log("Created new Android driver for device $instanceId on port $targetPort")
        return maestro
      } catch (e: Exception) {
        lastException = e
        Console.log("Android driver creation failed (attempt $attempt/$maxRetries): ${e::class.simpleName} - ${e.message}")

        if (attempt < maxRetries) {
          // Clean up before retrying
          HostDriverPortUtils.killProcessesUsingPort(targetPort)
          val backoffMs = 2000L * attempt
          Console.log("Waiting ${backoffMs}ms before retry...")
          Thread.sleep(backoffMs)
        }
      }
    }

    throw lastException ?: IllegalStateException("Failed to create Android driver after $maxRetries attempts")
  }
}
