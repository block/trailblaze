package xyz.block.trailblaze.logs.server.endpoints

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.llm.RunYamlRequest

/**
 * Request from CLI to run a trail file.
 *
 * Supports two modes:
 * 1. **Fully resolved** — provide [runYamlRequest] with device, LLM, and YAML already resolved.
 * 2. **Raw CLI params** — provide [yamlContent] (or [trailFilePath]) plus optional CLI flags.
 *    The daemon resolves the device and LLM from its own state.
 */
@Serializable
data class CliRunRequest(
  /** Fully resolved request (used for direct execution). */
  val runYamlRequest: RunYamlRequest? = null,
  /** Whether to force stop the target app before running the trail. */
  val forceStopTargetApp: Boolean = false,

  // --- Raw CLI parameter mode (daemon resolves device/LLM) ---

  /** YAML content to execute. */
  val yamlContent: String? = null,
  /** Path to the trail file (for metadata/test naming). */
  val trailFilePath: String? = null,
  /** Override test name. */
  val testName: String? = null,
  /** Driver type override (e.g., "ANDROID_ONDEVICE_INSTRUMENTATION"). */
  val driverType: String? = null,
  /** Target device ID override. */
  val deviceId: String? = null,
  /** LLM provider override (e.g., "openai", "anthropic"). */
  val llmProvider: String? = null,
  /** LLM model override (e.g., "gpt-4.1"). */
  val llmModel: String? = null,
  /** Use recorded tool sequences instead of LLM inference. */
  val useRecordedSteps: Boolean = false,
  /** Show the browser window (for web trails). */
  val showBrowser: Boolean = false,
  /** When true, uses a no-op logger so no session files are written to disk. */
  val noLogging: Boolean = false,
  /** Agent implementation override (e.g., "MULTI_AGENT_V3"). */
  val agentImplementation: String? = null,
  /**
   * Override the persisted `trailblaze config self-heal` setting for this run.
   * `null` = inherit the saved config; `true`/`false` = explicit CLI override
   * (from `--self-heal` / `--no-self-heal`).
   */
  val selfHeal: Boolean? = null,
  /** Override capture video setting (null = use app config default). */
  val captureVideo: Boolean? = null,
  /** Override capture Android logcat setting (null = use app config default). */
  val captureLogcat: Boolean? = null,
  /** Override capture iOS Simulator system logs setting (null = use app config default). */
  val captureIosLogs: Boolean? = null,
  /**
   * When true, the daemon auto-starts framework network capture for the run.
   * Mirrors the desktop-app "Capture Network Traffic" toggle. From the CLI,
   * set via `--capture-network` or `--capture-all`. `false` (default) inherits
   * whatever the daemon's saved app config says.
   */
  val captureNetworkTraffic: Boolean = false,
) {
  /**
   * Validates that at least one execution mode is specified:
   * either [runYamlRequest] (fully resolved) or [yamlContent]/[trailFilePath] (raw CLI params).
   */
  fun validate() {
    require(runYamlRequest != null || yamlContent != null || trailFilePath != null) {
      "CliRunRequest must specify either runYamlRequest, yamlContent, or trailFilePath"
    }
  }
}

/**
 * Response from the run endpoint.
 */
@Serializable
data class CliRunResponse(
  /** Whether the run completed successfully */
  val success: Boolean,
  /** Session ID for tracking the run */
  val sessionId: String? = null,
  /** Error message if failed */
  val error: String? = null,
  /** Device classifiers from the session (e.g., ["android"], ["ios", "iphone"]) for recording filename. */
  val deviceClassifiers: List<String> = emptyList(),
)
