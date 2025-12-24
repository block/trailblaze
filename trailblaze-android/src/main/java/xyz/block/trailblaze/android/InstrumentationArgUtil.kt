package xyz.block.trailblaze.android

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry

object InstrumentationArgUtil {

  private val instrumentationArguments by lazy {
    InstrumentationRegistry.getArguments().also { args ->
      println(args.toDebugString())
    }
  }

  fun Bundle.toDebugString(): String {
    val args = this
    return buildString {
      val argKeys = args.keySet().sorted()
      argKeys.mapNotNull { key ->
        try {
          val argValue = args.getString(key)
          appendLine("Instrumentation argument key: $key value: $argValue")
        } catch (e: Exception) {
          appendLine("Unable to access argument: $key")
        }
      }
    }
  }

  fun getInstrumentationArg(key: String): String? = instrumentationArguments.getString(key)

  fun logsEndpoint(): String {
    val defaultLogsEndpoint = if (isReverseProxyEnabled()) {
      // adb reverse port forwarding
      "https://localhost:8443"
    } else {
      // Emulator
      "https://10.0.2.2:8443"
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
}
