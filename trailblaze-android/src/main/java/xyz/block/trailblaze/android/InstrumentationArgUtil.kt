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

  fun getApiKeyFromInstrumentationArg(): String = if (isAiEnabled()) {
    val openAiApiKey = instrumentationArguments.getString("OPENAI_API_KEY")
      ?: instrumentationArguments.getString("openAiApiKey")
    if (openAiApiKey.isNullOrBlank()) {
      throw IllegalStateException("OPENAI_API_KEY environment variable is not set")
    }
    openAiApiKey
  } else {
    "AI_DISABLED"
  }

  fun isAiEnabled(): Boolean {
    val aiEnabled = instrumentationArguments.getString("trailblaze.ai.enabled", "false").toBoolean()
    return aiEnabled
  }
}
