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
  /** LLM model override (e.g., "gpt-5.6-terra"). */
  val llmModel: String? = null,
  /**
   * Tri-state replay-vs-AI control:
   *   - `true`  — force replay (use the trail's `recording:` tools verbatim).
   *   - `false` — force AI (ignore any recordings; LLM drives each step).
   *   - `null`  — daemon decides via `hasRecordedSteps(...)` auto-detect.
   *
   * Callers that previously sent `false` to mean "auto-detect" must now send
   * `null` to keep that behavior; `false` is the explicit force-AI signal.
   */
  val useRecordedSteps: Boolean? = null,
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
  /** Override capture video setting (null = default: video off, opt-in per run). */
  val captureVideo: Boolean? = null,
  /** Override capture Android logcat setting (null = use app config default). */
  val captureLogcat: Boolean? = null,
  /** Override capture iOS Simulator system logs setting (null = use app config default). */
  val captureIosLogs: Boolean? = null,
  /**
   * Override the daemon's framework network capture setting for this run. Mirrors the
   * desktop-app "Capture Network Traffic" toggle. From the CLI, set via
   * `--capture-network` / `--no-capture-network` (or `--capture-all`). `null` = inherit
   * whatever the daemon's saved app config says; `true`/`false` = explicit override.
   */
  val captureNetworkTraffic: Boolean? = null,
  /**
   * Per-objective cap on LLM calls for the legacy TRAILBLAZE_RUNNER agent. Forwarded from
   * the CLI's `--max-llm-calls` flag into [RunYamlRequest.maxLlmCalls]. Null = use the
   * runner's built-in default.
   */
  val maxLlmCalls: Int? = null,
  /**
   * CLI `--memory KEY=VAL` entries forwarded into [RunYamlRequest.initialMemorySeeds].
   * Applied AFTER the trail YAML's `config.memory:` block so CLI overrides YAML on the
   * same key. Empty by default. Values are logged in cleartext — use
   * [initialMemorySensitiveSeeds] for secrets.
   */
  val initialMemorySeeds: Map<String, String> = emptyMap(),
  /**
   * CLI `--secret KEY=VAL` entries forwarded into
   * [RunYamlRequest.initialMemorySensitiveSeeds]. Values are redacted in logs and excluded
   * from the session-start snapshot.
   */
  val initialMemorySensitiveSeeds: Map<String, String> = emptyMap(),
  /**
   * CLI `--arg KEY=VAL` / `--args-file` entries, already bound and typed against the trail's
   * `config.args:` declaration, forwarded into [RunYamlRequest.initialArgs]. Each value is a
   * JSON-encoded [kotlinx.serialization.json.JsonElement]. Empty for a non-parameterized trail.
   */
  val initialArgs: Map<String, String> = emptyMap(),
  /**
   * The run caller's absolute working directory, forwarded so the daemon anchors the workspace
   * `defaults.target` (rung 3) resolution at the caller's workspace instead of the daemon's own
   * frozen configured-trails-dir.
   *
   * Only matters when the trail declares no `config.target` and the run is dispatched to a daemon
   * launched from a different workspace than the caller's shell: without this, the daemon fell
   * back to its own `defaults.target`, so the target actually run could differ from the one
   * `trailblaze config get target` reports (which resolves from the caller's cwd). Null for older
   * CLI clients and for non-CLI submissions (MCP/HTTP), which keep the daemon-anchored behavior.
   */
  val callerWorkspaceDir: String? = null,
  /**
   * How much of this run the daemon should record — `off`, `normal` or `verbose`, as resolved by
   * the caller's `TRAILBLAZE_TRACE_LEVEL` / `trailblaze.trace.level`.
   *
   * A daemon outlives the run that started it, so without this the level is whatever the daemon
   * inherited at startup: `TRAILBLAZE_TRACE_LEVEL=verbose trailblaze run …` would silently record
   * at `normal`, and a daemon launched under `verbose` would keep recording every later run that
   * way. The daemon applies this for the run and restores its own level after.
   *
   * A string rather than the enum so an unrecognized value from a newer CLI leaves the daemon's
   * level alone instead of failing to decode the request. Null (an older CLI, or a non-CLI
   * submission over MCP/HTTP) also keeps the daemon's level.
   */
  val traceLevel: String? = null,
  /**
   * CLI `--configuration <name>`, forwarded into [RunYamlRequest.deviceConfiguration] — which of a
   * trail's `config.devices:` casts this run binds. Null leaves the daemon's
   * `TRAILBLAZE_DEVICE_CONFIGURATION` fallback in charge, as before.
   */
  val deviceConfiguration: String? = null,
  /**
   * CLI `--bind NAME=DEVICE_ID` entries, forwarded into [RunYamlRequest.deviceBindings] — the
   * selected cast's COMPANION devices (the start device is [deviceId]).
   *
   * Per-request rather than read from the daemon's environment, which is what lets two multi-device
   * runs bind different device sets on one daemon: `TRAILBLAZE_DEVICE_BINDINGS` is daemon-wide, so
   * two casts sharing a device name (two pairs both naming `buyer`) cannot both be expressed there.
   * Empty falls back to that env var.
   */
  val deviceBindings: Map<String, String> = emptyMap(),
  /**
   * CLI `--snapshot-baseline <ref>`: a previous run to diff this run's `takeSnapshot` captures
   * against — an http(s) URL to a session logs zip, a local zip, or an extracted session
   * directory. Null = no per-run baseline; the daemon then falls back to its own
   * `TRAILBLAZE_SNAPSHOT_BASELINE` env var, and skips comparison when that's unset too.
   *
   * Version-skew note: a daemon predating this field ignores it and runs no comparison — update
   * host and daemon together (or set the env var on the daemon) when relying on baseline diffs.
   */
  val snapshotBaseline: String? = null,
  /**
   * CLI `--snapshot-baseline-threshold <percent>`: a snapshot passes when its pixel diff
   * percentage is <= this value. Null inherits the daemon's
   * `TRAILBLAZE_SNAPSHOT_BASELINE_THRESHOLD`, else the built-in default (2.0).
   */
  val snapshotBaselineThresholdPercent: Double? = null,
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
  /**
   * The multi-device configuration the session selected (a `config.devices:` configuration
   * entry's name, e.g. `x2`), or null for a single-device run. When set, the CLI keys the
   * recording save-back by this name instead of [deviceClassifiers]. Null from older daemons —
   * absent degrades to classifier keying.
   */
  val selectedDeviceConfiguration: String? = null,
  /**
   * Machine-readable failure class, so callers that surface exit codes can distinguish a
   * request the daemon REJECTED as invalid ([ERROR_KIND_MISUSE], e.g. an unrecognized driver
   * name) from a run that was attempted and failed. Null for ordinary run failures — and for
   * responses from older daemons, so absent always means "attempted and failed" (exit code 1
   * on the CLI). Kept a nullable string rather than an enum so old/new client-daemon pairs
   * degrade gracefully in both directions.
   */
  val errorKind: String? = null,
  /**
   * Absolute path of the logs directory the daemon actually wrote this session into, so the CLI
   * reads the recording from where it landed rather than re-deriving it. The two can differ: the
   * daemon pins its logs repository at boot, and a client attached to a daemon started from
   * another checkout (or before a `logsDirectory` change) resolves a different directory from the
   * same settings. Null from older daemons — absent degrades to the client's own resolution.
   */
  val logsDir: String? = null,
) {
  companion object {
    /**
     * [errorKind] value for a request rejected as invalid before any run was attempted
     * (bad flags / malformed input). CLI callers map it to their misuse exit code (3).
     */
    const val ERROR_KIND_MISUSE = "misuse"
  }
}
