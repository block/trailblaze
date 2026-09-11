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
   * flows); it is deliberately not the full registered RPC surface. Every other endpoint the daemon
   * registers is absent for one reason — **scope**: no TypeScript consumer needs it yet, and callers
   * can use the untyped `rpcCall` until one does. That covers the flat `GetToolCatalogRequest` /
   * `RunTrailYamlRequest` and the sealed-carrying `DeviceInteractionRequest` alike.
   *
   * Sealed types are not a codegen limitation. [SerialDescriptorTsCodegen] renders a sealed
   * hierarchy as a TypeScript discriminated union (`renderSealedUnion`, covered by
   * `SerialDescriptorTsCodegenTest`), so a sealed-carrying endpoint needs no generator work first.
   *
   * Only an endpoint the daemon routes over **HTTP** belongs here. `rpcCall` always POSTs
   * `/rpc/<Name>` and has no WebSocket path, so an entry `DeviceApiEndpoint` does not register on
   * the HTTP transport compiles fine and 404s at runtime. Two ways to trip on that:
   *  - `GetScreenStateRequest` is on the **on-device** RPC surface, which the daemon calls as a
   *    client rather than serves, so exporting it needs a daemon-side route or proxy first.
   *  - `SubscribeFramesRequest` / `UnsubscribeFramesRequest` are WS-only by design (they push
   *    frames), so a generated POST binding for either would 404 even though the daemon "routes"
   *    them.
   *
   * `DeviceInteractionRequest` is neither: `DeviceApiEndpoint` registers it on both transports, so
   * it stays a genuine one-line add whenever a TypeScript consumer wants it.
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
