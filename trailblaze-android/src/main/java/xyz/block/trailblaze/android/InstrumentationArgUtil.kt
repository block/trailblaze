package xyz.block.trailblaze.android

import androidx.test.platform.app.InstrumentationRegistry
import xyz.block.trailblaze.devices.TrailblazeDevicePort
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.util.Console

object InstrumentationArgUtil {

  private val instrumentationArguments by lazy {
    InstrumentationRegistry.getArguments().also { args ->
      buildString {
        val argKeys = args.keySet().sorted()
        argKeys.mapNotNull { key ->
          try {
            val argValue = args.getString(key)
            val maskedValue = argValue?.let {
              if (it.length > 4) {
                "*".repeat(it.length - 4) + it.takeLast(4)
              } else {
                it
              }
            }
            appendLine("Instrumentation argument key: $key value: $maskedValue")
          } catch (e: Exception) {
            appendLine("Unable to access instrumentation argument key: $key")
          }
        }
      }.also { Console.log(it) }
    }
  }

  fun getInstrumentationArg(key: String): String? = instrumentationArguments.getString(key)

  fun logsEndpoint(): String {
    val httpsPort = InstrumentationRegistry.getArguments().getString(
      "trailblaze.httpsPort",
      TrailblazeDevicePort.TRAILBLAZE_DEFAULT_HTTPS_PORT.toString(),
    )?.toIntOrNull() ?: TrailblazeDevicePort.TRAILBLAZE_DEFAULT_HTTPS_PORT

    val defaultLogsEndpoint = if (isReverseProxyEnabled()) {
      // adb reverse port forwarding
      "https://localhost:$httpsPort"
    } else {
      // Emulator
      "https://10.0.2.2:$httpsPort"
    }
    return InstrumentationRegistry.getArguments().getString(
      "trailblaze.logsEndpoint",
      defaultLogsEndpoint,
    )
  }

  fun isReverseProxyEnabled(): Boolean = instrumentationArguments.getString(
    "trailblaze.reverseProxy",
    "false",
  ).toBoolean()

  fun reverseProxyEndpoint(): String? = if (!isReverseProxyEnabled()) {
    null
  } else {
    "${logsEndpoint()}/reverse-proxy"
  }

  fun isAiEnabled(): Boolean {
    val aiEnabled = instrumentationArguments.getString("trailblaze.aiEnabled", "true").toBoolean()
    return aiEnabled
  }

  fun isAiFallbackEnabled(): Boolean? {
    // Returns null if not set, allowing config to be the default
    return instrumentationArguments.getString("trailblaze.aiFallbackEnabled")?.toBoolean()
  }

  /**
   * Returns the driver type from instrumentation args, or null if not set.
   *
   * Pass via: `-e trailblaze.driverType ANDROID_ONDEVICE_ACCESSIBILITY`
   */
  fun driverType(): TrailblazeDriverType? {
    val value = instrumentationArguments.getString("trailblaze.driverType") ?: return null
    return try {
      TrailblazeDriverType.valueOf(value)
    } catch (e: IllegalArgumentException) {
      Console.log("Unknown driver type: $value, falling back to default")
      null
    }
  }
}
