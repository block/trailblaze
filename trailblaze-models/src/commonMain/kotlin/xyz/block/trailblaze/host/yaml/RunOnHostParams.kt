package xyz.block.trailblaze.host.yaml

import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDevicePort
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.llm.TrailblazeReferrer
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.model.TrailblazeHostAppTarget

class RunOnHostParams(
  val targetTestApp: TrailblazeHostAppTarget?,
  val runYamlRequest: RunYamlRequest,
  val device: TrailblazeConnectedDeviceSummary,
  val forceStopTargetApp: Boolean,
  val additionalInstrumentationArgs: () -> Map<String, String>,
  val onProgressMessage: (String) -> Unit,
  /**
   * Fired with the session id the moment the session is created, BEFORE any trail steps run.
   * Lets the caller start session-scoped capture that must already be live during execution
   * (e.g. the iOS Simulator log stream). No-op by default; the Maestro host path invokes it so
   * the daemon's capture coordinator starts during the run rather than after it finishes.
   */
  val onSessionStarted: (SessionId) -> Unit = {},
  /** RPC port for Compose driver connections. */
  val composeRpcPort: Int = TrailblazeDevicePort.COMPOSE_DEFAULT_RPC_PORT,
  /**
   * The source/context from which this run was initiated.
   * Used for analytics and to determine behavior (e.g., MCP keeps drivers alive between calls).
   */
  val referrer: TrailblazeReferrer,
  /** When true, uses a no-op logger so no session files are written to disk. */
  val noLogging: Boolean = false,
  /**
   * Resolved per-run session-video toggle (CLI `--capture-video` > `TRAILBLAZE_CAPTURE_VIDEO` >
   * `trailblaze config capture-video` > default off). The web / Electron rules self-instrument
   * their own video capture (the capture coordinator skips WEB), so this is how the user's opt-in
   * reaches them — they gate their `ensure*VideoCaptureStarted` on it. Defaults to false: video is
   * opt-in (large files, expensive sprite extraction).
   */
  val captureVideo: Boolean = false,
  /**
   * Reference to a PREVIOUS run to diff this run's `takeSnapshot` captures against — an http(s)
   * URL to a session logs zip (e.g. the CI artifact store's `latest_success.zip`), a local zip,
   * or an extracted session directory. Null (the default) falls back to the
   * `TRAILBLAZE_SNAPSHOT_BASELINE` env var in the executing process; comparison is skipped when
   * neither is set. The goldens-free alternative to checked-in `*.golden.png` files.
   */
  val snapshotBaselineRef: String? = null,
  /**
   * Pass threshold for the baseline comparison: a snapshot passes when its pixel diff percentage
   * is <= this value. Null inherits `TRAILBLAZE_SNAPSHOT_BASELINE_THRESHOLD`, else the built-in
   * default (2.0).
   */
  val snapshotBaselineThresholdPercent: Double? = null,
) {

  val trailblazeDevicePlatform: TrailblazeDevicePlatform = device.platform

  val trailblazeDriverType: TrailblazeDriverType = device.trailblazeDriverType
}