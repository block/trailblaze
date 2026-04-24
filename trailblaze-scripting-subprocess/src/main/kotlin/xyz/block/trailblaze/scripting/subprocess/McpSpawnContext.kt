package xyz.block.trailblaze.scripting.subprocess

import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.model.SessionId

/**
 * Session context required to spawn a subprocess MCP server.
 *
 * The [Decision 038 env-var contract][xyz.block.trailblaze.scripting.subprocess] is a public
 * API surface for authors — this is the typed Kotlin-side bundle that maps one-to-one to those
 * env vars at spawn. Populated from `TrailblazeToolExecutionContext` at session start; kept
 * narrow so tests and the on-device bundle path can construct it without dragging in device
 * machinery they don't need.
 *
 * See the scope devlog's § Environment contract:
 * `docs/devlog/2026-04-20-scripted-tools-a3-host-subprocess.md`.
 */
data class McpSpawnContext(
  val platform: TrailblazeDevicePlatform,
  val driver: TrailblazeDriverType,
  val widthPixels: Int,
  val heightPixels: Int,
  val sessionId: SessionId,
  /**
   * Base URL of the Trailblaze daemon HTTP server (e.g. `http://localhost:52525`). Surfaces to
   * subprocesses as both the `TRAILBLAZE_BASE_URL` env var and inside the
   * `_meta.trailblaze.baseUrl` field on every `tools/call`. Subprocesses use it to hit the
   * `/scripting/callback` endpoint when they compose other Trailblaze tools via
   * `@trailblaze/scripting`.
   *
   * Nullable so tests that don't exercise the callback path can keep constructing bare
   * [McpSpawnContext] values. When null, the env var and the `_meta` field are omitted.
   */
  val baseUrl: String? = null,
)
