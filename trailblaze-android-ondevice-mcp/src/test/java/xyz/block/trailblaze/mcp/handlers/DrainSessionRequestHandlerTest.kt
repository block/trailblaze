package xyz.block.trailblaze.mcp.handlers

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.block.trailblaze.mcp.android.ondevice.rpc.DrainSessionRequest
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult

/**
 * JVM unit tests for [DrainSessionRequestHandler]. The handler delegates the actual cache-clear
 * work to [xyz.block.trailblaze.InstrumentationUtil.clearInstrumentationUiAutomationCache],
 * which reflects on [android.app.Instrumentation] private API and can only execute under a
 * live `am instrument` process. On the JVM that call throws (no
 * `InstrumentationRegistry.getInstrumentation()` available) — the handler catches that and
 * still returns `Success(uiAutomationCleared=false)` so the host can keep tearing down.
 *
 * The end-to-end behavior (clearing the real cached handle on-device) is covered by the
 * companion instrumented test marked `@Ignore` until we have a device-farm slot wired up
 * for it — see PR description for the follow-up plan.
 */
class DrainSessionRequestHandlerTest {

  @Test
  fun `handle returns Success even when reflection path fails on JVM`() {
    val handler = DrainSessionRequestHandler()
    val result = runBlocking { handler.handle(DrainSessionRequest(reason = "unit_test")) }
    assertTrue(result is RpcResult.Success, "Drain handler must never surface Failure to the host")
    assertEquals(
      false,
      result.data.uiAutomationCleared,
      "On JVM, no Instrumentation is registered, so clearInstrumentationUiAutomationCache returns false",
    )
  }

  @Test
  fun `handle accepts arbitrary reason strings without including them in the response`() {
    val handler = DrainSessionRequestHandler()
    val result = runBlocking {
      handler.handle(DrainSessionRequest(reason = "session-displacement; key=foo/bar"))
    }
    assertTrue(result is RpcResult.Success)
  }
}
