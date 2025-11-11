package xyz.block.trailblaze.model

import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import kotlin.reflect.KClass

abstract class TrailblazeHostAppTarget(
  val name: String,
) {
  abstract fun getPossibleAppIdsForPlatform(platform: TrailblazeDevicePlatform): List<String>?

  protected abstract fun internalGetCustomToolsForDriver(driverType: TrailblazeDriverType): Set<KClass<out TrailblazeTool>>
  fun getCustomToolsForDriver(driverType: TrailblazeDriverType): Set<KClass<out TrailblazeTool>> = internalGetCustomToolsForDriver(driverType)

  fun getAllCustomToolClassesForSerialization(): Set<KClass<out TrailblazeTool>> = TrailblazeDriverType.entries.flatMap { trailblazeDriverType ->
    getCustomToolsForDriver(trailblazeDriverType)
  }.toSet()

  protected abstract fun internalGetAndroidOnDeviceTarget(): TrailblazeOnDeviceInstrumentationTarget?
  fun getTrailblazeOnDeviceInstrumentationTarget(): TrailblazeOnDeviceInstrumentationTarget = internalGetAndroidOnDeviceTarget() ?: TrailblazeOnDeviceInstrumentationTarget.DEFAULT_ANDROID_ON_DEVICE

  /**
   * Returns comprehensive information about this app target as formatted text including:
   * - Driver types with their platforms and custom tool counts
   * - Installed app IDs for all platforms
   */
  fun getAppInfoText(supportedDrivers: Set<TrailblazeDriverType>): String = buildString {
    // Print installed app information
    appendLine("Apps Ids by Platform:")
    appendLine("-".repeat(40))

    TrailblazeDevicePlatform.entries.forEach { platform ->
      val appIds = getPossibleAppIdsForPlatform(platform)
      if (!appIds.isNullOrEmpty()) {
        appendLine("• ${platform.displayName}: ${appIds.joinToString(",")}")
      }
    }

    // Print Android on-device target information
    appendLine("\nAndroid On-Device Target:")
    appendLine("-".repeat(40))
    val androidTarget = getTrailblazeOnDeviceInstrumentationTarget()
    appendLine("• Test App ID: ${androidTarget.testAppId}")
    appendLine("• Test Class: ${androidTarget.fqTestName}")
    appendLine("• Gradle Command: ${androidTarget.gradleInstallAndroidTestCommand}")
  }

  object DefaultTrailblazeHostAppTarget : TrailblazeHostAppTarget(
    "None",
  ) {
    override fun getPossibleAppIdsForPlatform(platform: TrailblazeDevicePlatform): List<String>? = null

    override fun internalGetCustomToolsForDriver(driverType: TrailblazeDriverType): Set<KClass<out TrailblazeTool>> = setOf()

    override fun internalGetAndroidOnDeviceTarget(): TrailblazeOnDeviceInstrumentationTarget = TrailblazeOnDeviceInstrumentationTarget.DEFAULT_ANDROID_ON_DEVICE
  }
}
