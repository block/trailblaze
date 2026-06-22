package xyz.block.trailblaze.codegen

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcRequest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Unit coverage for [RpcClientTsCodegen] — the shared engine both [HostRpcDtoTsBindings] and the
 * downstream Trail Runner generator delegate to. Uses local sample `RpcRequest` types so
 * the test is independent of any real DTO/endpoint churn; the byte-exact end-to-end output is
 * separately locked by each module's `verifyDtoTs` byte-diff gate.
 */
class RpcClientTsCodegenTest {

  @Serializable data class GetWidgetsResponse(val count: Int)

  @Serializable data class SetWidgetResponse(val ok: Boolean)

  /** A no-field request (Kotlin `object`) — its client method should be callable with no args. */
  @Serializable object GetWidgetsRequest : RpcRequest<GetWidgetsResponse>

  /** A request that carries fields — its client method requires the request argument. */
  @Serializable data class SetWidgetRequest(val id: String) : RpcRequest<SetWidgetResponse>

  private fun generate(requests: List<kotlin.reflect.KClass<out RpcRequest<*>>>) =
    RpcClientTsCodegen.generate(
      header = "// HEADER\n",
      extraTypeRoots = emptyList(),
      requests = requests,
      clientFunctionName = "createSampleRpcClient",
      surfaceLabel = "sample",
    )

  @Test
  fun `renders one typed method per request with the right shape`() {
    val ts = generate(listOf(GetWidgetsRequest::class, SetWidgetRequest::class))

    // Header is prepended verbatim, and the transport import is emitted.
    assertContains(ts, "// HEADER")
    assertContains(ts, "import { rpcCall, type RpcResult, type RpcCallOptions } from \"../rpc/client.js\";")

    // Function named from clientFunctionName.
    assertContains(ts, "export function createSampleRpcClient(options: RpcCallOptions = {}) {")

    // No-field request → method callable with no args (defaulted request), endpoint = the class name.
    assertContains(
      ts,
      "getWidgets: (request: GetWidgetsRequest = {}): Promise<RpcResult<GetWidgetsResponse>> =>",
    )
    assertContains(ts, "rpcCall<GetWidgetsRequest, GetWidgetsResponse>(\"GetWidgetsRequest\", request, options),")

    // Field-carrying request → request argument is required (no `= {}`).
    assertContains(
      ts,
      "setWidget: (request: SetWidgetRequest): Promise<RpcResult<SetWidgetResponse>> =>",
    )
    assertContains(ts, "rpcCall<SetWidgetRequest, SetWidgetResponse>(\"SetWidgetRequest\", request, options),")

    // The doc-comment example prefers a no-arg-callable endpoint so the snippet type-checks.
    assertContains(ts, "const r = await rpc.getWidgets();")
  }

  @Test
  fun `an empty request list fails loud`() {
    val ex = assertFailsWith<IllegalArgumentException> { generate(emptyList()) }
    assertTrue(
      ex.message!!.contains("at least one RpcRequest"),
      "error should explain the empty endpoint list: ${ex.message}",
    )
  }

  @Serializable data class DupResponse(val v: Int)

  // Two distinct request classes whose method names both reduce to "widget" — exactly the silent
  // duplicate-key footgun the uniqueness guard must reject.
  @Serializable object WidgetRequest : RpcRequest<DupResponse>

  @Serializable data class Widget(val id: String) : RpcRequest<DupResponse>

  @Test
  fun `requests that collide on the same method name fail loud`() {
    val ex = assertFailsWith<IllegalArgumentException> {
      generate(listOf(WidgetRequest::class, Widget::class))
    }
    assertTrue(
      ex.message!!.contains("collide on the same client method name") && ex.message!!.contains("widget"),
      "error should name the colliding method: ${ex.message}",
    )
  }
}
