package xyz.block.trailblaze.codegen

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import xyz.block.trailblaze.host.rpc.ConnectToDeviceRequest
import xyz.block.trailblaze.host.rpc.ConnectToDeviceResponse
import xyz.block.trailblaze.host.rpc.DisconnectDeviceRequest
import xyz.block.trailblaze.host.rpc.DisconnectDeviceResponse
import xyz.block.trailblaze.host.rpc.GetConnectedDevicesRequest
import xyz.block.trailblaze.host.rpc.GetConnectedDevicesResponse
import xyz.block.trailblaze.host.rpc.GetTargetAppsRequest
import xyz.block.trailblaze.host.rpc.GetTargetAppsResponse
import xyz.block.trailblaze.host.rpc.NavigateWebUrlRequest
import xyz.block.trailblaze.host.rpc.NavigateWebUrlResponse
import xyz.block.trailblaze.host.rpc.SetCurrentTargetAppRequest
import xyz.block.trailblaze.host.rpc.SetCurrentTargetAppResponse
import java.io.File

/**
 * Generates the TypeScript bindings for the daemon's `/rpc/<Name>` request/response types, so a
 * TypeScript UI can call the same typed RPC the Kotlin/Wasm UI uses today (via `HostRpcClient`),
 * with Kotlin as the single source of truth.
 *
 * Scope (this slice): the flat, UI-facing device + target-app endpoints. Deliberately excluded for
 * now because they carry on-the-wire **sealed** types the descriptor walker doesn't support yet:
 * `DeviceInteractionRequest` (sealed `DeviceInteraction`) and `GetScreenStateResponse` (sealed
 * node-detail in the view-hierarchy tree). Add them once `SerialDescriptorTsCodegen` grows
 * discriminated-union support.
 *
 * Only the request/response **data** types are generated. The `RpcResult` envelope is NOT a wire
 * type (the server unwraps it to the raw response on 200, or a flat error on non-2xx), so it lives
 * in the hand-written TypeScript client, not here.
 *
 * Run via `./gradlew :trailblaze-models:generateDtoTs`; CI's `verifyDtoTs` byte-diffs the committed
 * output.
 */
@OptIn(ExperimentalSerializationApi::class)
internal object HostRpcDtoTsBindings {

  /** Endpoint request/response roots; nested + referenced types are pulled in transitively. */
  private val ROOTS: List<SerialDescriptor> = listOf(
    GetConnectedDevicesRequest.serializer().descriptor,
    GetConnectedDevicesResponse.serializer().descriptor,
    ConnectToDeviceRequest.serializer().descriptor,
    ConnectToDeviceResponse.serializer().descriptor,
    DisconnectDeviceRequest.serializer().descriptor,
    DisconnectDeviceResponse.serializer().descriptor,
    GetTargetAppsRequest.serializer().descriptor,
    GetTargetAppsResponse.serializer().descriptor,
    SetCurrentTargetAppRequest.serializer().descriptor,
    SetCurrentTargetAppResponse.serializer().descriptor,
    NavigateWebUrlRequest.serializer().descriptor,
    NavigateWebUrlResponse.serializer().descriptor,
  )

  private const val HEADER: String =
    "// AUTO-GENERATED — do not edit by hand.\n" +
      "//\n" +
      "// TypeScript bindings for the daemon's /rpc/<Name> request/response types, derived from the\n" +
      "// Kotlin @Serializable models. Kotlin is canonical; this is the derived artifact. Pair these\n" +
      "// with the rpcCall() client in ../rpc/client.ts.\n" +
      "//\n" +
      "// Regenerate with the `generateDtoTs` Gradle task; CI's `verifyDtoTs` byte-diffs this file\n" +
      "// against a fresh generation and fails the build on drift, so hand edits are reverted on\n" +
      "// the next CI run.\n"

  fun generate(): String = SerialDescriptorTsCodegen.generate(ROOTS, HEADER)
}

/** Entry point for the `generateDtoTs` Gradle task. `args[0]` is the output file path. */
internal fun main(args: Array<String>) {
  val outPath = args.firstOrNull()
    ?: error("usage: HostRpcDtoTsBindingsKt <output-file.ts>")
  val outFile = File(outPath)
  outFile.parentFile?.mkdirs()
  outFile.writeText(HostRpcDtoTsBindings.generate(), Charsets.UTF_8)
  println("Wrote daemon RPC TypeScript bindings to ${outFile.absolutePath}")
}
