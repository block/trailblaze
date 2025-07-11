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


  fun isAiEnabled(): Boolean {
    val aiEnabled = instrumentationArguments.getString("trailblaze.ai.enabled", "false").toBoolean()
    return aiEnabled
  }
}
