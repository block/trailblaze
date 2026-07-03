package xyz.block.trailblaze.host

import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * What a host-side YAML run hands back to [xyz.block.trailblaze.host.yaml.DesktopYamlRunner]:
 * the [sessionId] it produced (null when a branch returned no session without throwing) plus
 * [lastToolResult] — the last successfully-executed tool's [TrailblazeToolResult.Success].
 *
 * The tool result is threaded up so `trailblaze tool <read-tool>` on a host/Maestro device
 * (iOS_HOST, Android HOST) can surface the tool's real return value (its `message` /
 * `structuredContent`) instead of a generic "Executed …" acknowledgement — matching what the
 * on-device-RPC, host-local web, and iOS-AXE dispatch branches already do. It's null for
 * action-style tools (tap/swipe) that produce no payload, and for the web/Compose/Revyl runners
 * whose payloads reach the CLI through their own dispatch branches rather than this path.
 *
 * [sessionId] is non-null for every run that opened a session (success, failure, cancellation);
 * it's null only when a branch returned without starting one (e.g. a skipped trail).
 */
data class HostYamlRunResult(
  val sessionId: SessionId?,
  val lastToolResult: TrailblazeToolResult.Success? = null,
)
