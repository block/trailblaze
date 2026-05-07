package xyz.block.trailblaze.android

import androidx.test.platform.app.InstrumentationRegistry
import xyz.block.trailblaze.devices.TrailblazeDevicePort
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.mcp.AgentImplementation
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

  /**
   * Returns the first [TrailblazeLlmModel] whose corresponding instrumentation arg key is set.
   *
   * Example:
   * ```
   * InstrumentationArgUtil.resolveTrailblazeLlmModel(
   *     LlmAuthResolver.resolve(TrailblazeLlmProvider.OPEN_ROUTER) to OpenRouterTrailblazeLlmModelList.GPT_OSS_120B_FREE,
   *     LlmAuthResolver.resolve(TrailblazeLlmProvider.OPENAI) to OpenAITrailblazeLlmModelList.OPENAI_GPT_4_1,
   * )
   * ```
   */
  fun resolveTrailblazeLlmModel(
    vararg candidates: Pair<String, TrailblazeLlmModel>,
  ): TrailblazeLlmModel {
    for ((key, model) in candidates) {
      if (getInstrumentationArg(key) != null) return model
    }
    val keys = candidates.joinToString(" or ") { it.first }
    error("Could not configure TrailblazeLlmModel — set $keys")
  }

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

  /**
   * When true, screen-state captures on the accessibility driver also dump the
   * UiAutomator view hierarchy (the legacy Maestro-driver tree) and use THAT as the
   * captured `viewHierarchy`, instead of the accessibility-derived projection that
   * the driver normally builds.
   *
   * Off by default — the dump is non-trivially expensive (≈ a few hundred ms per
   * snapshot, capped at 30s by the underlying `UiDevice.dumpWindowHierarchy` call),
   * roughly doubles per-step capture latency, and roughly doubles the on-disk size
   * of session log JSON. The only consumer today is the deterministic Maestro→
   * accessibility selector migration (`./trailblaze waypoint migrate-trail`), which
   * needs the true UiAutomator tree to resolve legacy Maestro selectors against the
   * exact tree the legacy runtime saw, not the accessibility-shape projection.
   *
   * Pass via: `-e trailblaze.captureSecondaryTree true`
   *
   * Same pattern as the other migration-only toggles (the future "we want both
   * trees so we can transition between accessibility versions" use case in the
   * 2026-05-05 conversation will reuse this hook with a different flag name).
   */
  fun shouldCaptureSecondaryTree(): Boolean {
    // Strict parse to match [isSelfHealEnabled]'s behavior: only "true"/"false"
    // (case-insensitive) are valid. A misspelled `-e trailblaze.captureSecondaryTree TRUE`
    // or `=1` should not silently default to `false` — log a warning so the operator can
    // fix it instead of wondering why migration captures aren't firing.
    val raw = instrumentationArguments.getString("trailblaze.captureSecondaryTree") ?: return false
    return raw.lowercase().toBooleanStrictOrNull() ?: run {
      Console.log(
        "[InstrumentationArgUtil] Invalid trailblaze.captureSecondaryTree value '$raw' " +
          "(expected 'true' or 'false'); defaulting to false",
      )
      false
    }
  }

  fun isSelfHealEnabled(): Boolean? {
    // Returns null if not set OR if set to a non-boolean value, allowing config to be the default.
    // Uses the same strict parser as the host/CLI resolvers (TrailCommand.resolveEffectiveSelfHeal
    // and BaseHostTrailblazeTest.resolveSelfHealFromEnvOrConfig) so the same input string behaves
    // the same way on every platform.
    return instrumentationArguments.getString("trailblaze.selfHeal")?.lowercase()?.toBooleanStrictOrNull()
  }

  /**
   * Returns the [AgentImplementation] from instrumentation args, or [AgentImplementation.DEFAULT].
   *
   * Pass via: `-e trailblaze.agent MULTI_AGENT_V3`
   * In CI: set `TRAILBLAZE_AGENT=MULTI_AGENT_V3` on the Buildkite step (mapped to the
   * instrumentation arg in root `build.gradle.kts`).
   */
  fun agentImplementation(): AgentImplementation {
    val value = instrumentationArguments.getString("trailblaze.agent") ?: return AgentImplementation.DEFAULT
    return try {
      AgentImplementation.valueOf(value)
    } catch (e: IllegalArgumentException) {
      Console.log("Unknown agent implementation: $value, falling back to ${AgentImplementation.DEFAULT}")
      AgentImplementation.DEFAULT
    }
  }

  /**
   * Returns the driver type from instrumentation args, or null if not set.
   *
   * When this returns non-null, it acts as a **force** override: the on-device runtime uses this
   * driver and skips the per-trail `config.driver` YAML peek entirely. Source can be either the
   * per-(config, device) pin from `TrailblazeCiConfig.deviceDriverTypes` or a build-level
   * `TRAILBLAZE_DRIVER_TYPE` env override — both flow into the same `trailblaze.driverType`
   * instrumentation arg, with build-env winning over code-config at pipeline-generation time.
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
