package xyz.block.trailblaze.codegen

import xyz.block.trailblaze.host.rpc.ConnectToDeviceRequest
import xyz.block.trailblaze.host.rpc.DisconnectDeviceRequest
import xyz.block.trailblaze.host.rpc.GetConnectedDevicesRequest
import xyz.block.trailblaze.host.rpc.GetTargetAppsRequest
import xyz.block.trailblaze.host.rpc.NavigateWebUrlRequest
import xyz.block.trailblaze.host.rpc.SetCurrentTargetAppRequest
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcRequest
import java.io.File
import kotlin.reflect.KClass

/**
 * Generates the daemon `/rpc/<Name>` TypeScript bindings — request/response **types** AND a typed
 * **client** — so a TypeScript UI calls `rpc.getConnectedDevices()` with the endpoint name, request
 * type, response type, and path all derived from Kotlin. The reflection + rendering is shared with
 * the Trail Runner generator via [RpcClientTsCodegen]; this object only owns the host-rpc endpoint
 * allowlist + the file header.
 *
 * Run via `./gradlew :trailblaze-models:generateDtoTs`; CI's `verifyDtoTs` byte-diffs the committed
 * `host-rpc.ts`. The transport primitive (`rpcCall` / `RpcResult`) is the small hand-written client
 * in `sdks/typescript/src/rpc/client.ts`; the generated methods wrap it with the types baked in.
 */
internal object HostRpcDtoTsBindings {

  /**
   * The explicit endpoint allowlist: `RpcRequest<TResponse>` implementors. Each one's response type
   * and `/rpc/<Name>` path are DERIVED (reflection), not hand-paired. Adding an endpoint is a
   * one-line edit here.
   *
   * This list tracks the endpoints the TypeScript UI actually consumes today (device + target-app
   * flows); it is deliberately not the full registered RPC surface. Other flat `@Serializable`
   * endpoints (e.g. `GetToolCatalogRequest`, `RunTrailYamlRequest`) are added here when a consumer
   * needs them — until then callers can still use the untyped `rpcCall`. Separately, endpoints that
   * carry on-the-wire **sealed** types are blocked on codegen, not scope: `DeviceInteractionRequest`
   * (sealed `DeviceInteraction`) and `GetScreenStateRequest` (sealed node-detail) — add them once
   * discriminated-union support lands in the walker.
   *
   * Typed as `KClass<out RpcRequest<*>>` so the allowlist is self-validating: a non-`RpcRequest`
   * entry fails to compile rather than throwing at reflection time.
   */
  private val REQUESTS: List<KClass<out RpcRequest<*>>> = listOf(
    GetConnectedDevicesRequest::class,
    ConnectToDeviceRequest::class,
    DisconnectDeviceRequest::class,
    GetTargetAppsRequest::class,
    SetCurrentTargetAppRequest::class,
    NavigateWebUrlRequest::class,
  )

  fun generate(): String = RpcClientTsCodegen.generate(
    header = HEADER,
    extraTypeRoots = emptyList(),
    requests = REQUESTS,
    clientFunctionName = "createRpcClient",
    surfaceLabel = "daemon's",
  )

  private const val HEADER: String =
    "// AUTO-GENERATED — do not edit by hand.\n" +
      "//\n" +
      "// Daemon /rpc/<Name> TypeScript bindings — request/response types AND a typed client —\n" +
      "// derived from the Kotlin @Serializable models and their RpcRequest<TResponse> declarations.\n" +
      "// Kotlin is canonical; this is the derived artifact.\n" +
      "//\n" +
      "// Regenerate with the `generateDtoTs` Gradle task; CI's `verifyDtoTs` byte-diffs this file\n" +
      "// against a fresh generation and fails the build on drift, so hand edits are reverted on\n" +
      "// the next CI run.\n"
}

/** Entry point for the `generateDtoTs` Gradle task. `args[0]` is the output file path. */
internal fun main(args: Array<String>) {
  val outPath = args.firstOrNull() ?: error("usage: HostRpcDtoTsBindingsKt <output-file.ts>")
  val outFile = File(outPath)
  outFile.parentFile?.mkdirs()
  outFile.writeText(HostRpcDtoTsBindings.generate(), Charsets.UTF_8)
  println("Wrote daemon RPC TypeScript bindings (types + client) to ${outFile.absolutePath}")
}
