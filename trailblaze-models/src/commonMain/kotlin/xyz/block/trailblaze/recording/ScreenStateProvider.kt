package xyz.block.trailblaze.recording

import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateResponse

/**
 * Platform-neutral source of [GetScreenStateResponse]. Adapts each platform's screen-capture
 * stack (on-device RPC for Android, Maestro driver for iOS, Playwright for Web) to a single
 * data shape so callers — the recording UI today, the snapshot CLI, and the planned WASM
 * client — hit one interface regardless of the underlying driver.
 *
 * **Why this exists.** Before this interface, each recording surface knew which platform it
 * was driving and called platform-specific APIs (Maestro `Driver.takeScreenshot`,
 * `Playwright.screenshot()`, `OnDeviceRpcClient.rpcCall(GetScreenStateRequest)`). That meant
 * three implementations of `getScreenshot` / `getViewHierarchy` / `getTrailblazeNodeTree`,
 * three subtly different shapes for the resulting data, and no clean place for a remote
 * client (browser-based recording surface, future MCP screen-state tool) to plug in.
 *
 * Now: every platform exposes the same response type. The recording surface holds a
 * [ScreenStateProvider] reference and stops caring which driver is on the other end. A WASM
 * client can hit one host HTTP endpoint per device and decode the same struct it would have
 * decoded for the native client.
 *
 * **Return contract.** Returns `null` on transient capture failures (a single dropped frame
 * during page navigation, an adb-forward blip on Android — `OnDeviceRpcClient` already heals
 * that under the hood, so a null here means the heal didn't catch in time). Callers in a
 * poll loop should treat null as "skip this tick" rather than fatal. Implementations throw
 * only for unrecoverable failures (driver shut down, instrumentation crashed) so the caller
 * gets a clear signal to tear down rather than spin.
 *
 * **`includeScreenshot` flag.** Defaults to true because most callers want the image; pass
 * false from internal `getViewHierarchy()` / `getTrailblazeNodeTree()` paths so the provider
 * doesn't pay screenshot encoding cost on calls that only need the tree. Annotated
 * (set-of-mark) screenshots are intentionally not part of this interface — they're an
 * LLM-oriented presentation concern, separate from the raw frame stream this contract feeds.
 */
interface ScreenStateProvider {
  suspend fun getScreenState(includeScreenshot: Boolean = true): GetScreenStateResponse?
}
