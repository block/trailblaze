package xyz.block.trailblaze.mcp

import xyz.block.trailblaze.devices.TrailblazeDeviceId

/**
 * Thread-local device ID for HTTP multi-session mode.
 *
 * When multiple MCP sessions share one [TrailblazeMcpBridge], each session controls a different
 * device. This ThreadLocal ensures each tool call operates on the correct device without races
 * from concurrent sessions mutating shared state.
 *
 * Propagated across coroutine context switches via `asContextElement()` in [TrailblazeMcpServer]'s
 * tool execution handler.
 */
object McpDeviceContext {
  val currentDeviceId: ThreadLocal<TrailblazeDeviceId?> = ThreadLocal()
}
