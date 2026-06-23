package xyz.block.trailblaze.llm

import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * Return shape of the `runTrailblazeYaml` callback the on-device
 * `xyz.block.trailblaze.mcp.handlers.RunYamlRequestHandler` invokes for `TRAILBLAZE_RUNNER`
 * dispatches.
 *
 * Carries:
 *  - [session] — the [TrailblazeSession] the handler should consider terminal for this RPC. Same
 *    instance as the one passed into the callback today; reserved as a slot for future callbacks
 *    that want to swap the session (no current consumer does).
 *  - [lastToolSuccess] — the last successfully-executed tool's
 *    [TrailblazeToolResult.Success] (carrying `message` + `structuredContent`). The on-device
 *    dispatch loop produces this for every tool; the handler mirrors `message` and
 *    `structuredContent` onto [RunYamlResponse.toolMessage] /
 *    [RunYamlResponse.toolStructuredContent] so a host-side scripted-tool author composing
 *    dual-mode primitives (`android_adbShell`, `android_sendBroadcast`,
 *    `mobile_listInstalledApps`) via `client.callTool(...)` receives the same payload they would
 *    get from the direct host-side actual.
 *
 * Null [lastToolSuccess] is the well-defined "no tool produced a Success" case (every tool errored,
 * the trail's only items were prompts that errored, the trail had no actionable steps, etc.).
 *
 * Lives in `commonMain` so the result type is visible from both the on-device callback
 * implementation site (`xyz.block.trailblaze.android.BaseAndroidStandaloneServerTest`, which is in
 * the `:trailblaze-android` module that the on-device-mcp module depends on) and the handler that
 * consumes it.
 */
data class RunYamlCallbackResult(
  val session: TrailblazeSession,
  val lastToolSuccess: TrailblazeToolResult.Success? = null,
  /**
   * How many [TrailblazeToolLog][xyz.block.trailblaze.logs.client.TrailblazeLog.TrailblazeToolLog]
   * entries the on-device dispatch emitted while running this request's tool(s). The handler
   * mirrors this onto [RunYamlResponse.onDeviceToolLogCount] so the host RPC agent can skip its
   * own catch-all tool-log emit when the device already logged the tool, fixing the on-device
   * double-logging in the session report (#3818). Defaults to `0` so callbacks that don't count
   * (and the no-tool-ran case) leave the host's catch-all emit in place.
   */
  val onDeviceToolLogCount: Int = 0,
)
