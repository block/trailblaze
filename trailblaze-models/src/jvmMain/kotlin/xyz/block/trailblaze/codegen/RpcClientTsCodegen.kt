package xyz.block.trailblaze.codegen

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.serializer
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcRequest
import java.lang.reflect.ParameterizedType
import kotlin.reflect.KClass
import kotlin.reflect.full.starProjectedType

/**
 * Shared codegen for the typed daemon `/rpc/<Name>` TypeScript clients. Reflects each
 * `RpcRequest<TResponse>` into an endpoint (method name, `/rpc/<Name>` path, request/response types
 * via [SerialDescriptorTsCodegen]) and renders a `createXxxRpcClient` over the hand-written `rpcCall`
 * transport — nothing about the request→response pairing is maintained by hand.
 *
 * Both [HostRpcDtoTsBindings] (in `:trailblaze-models`) and the Trail Runner generator (in a
 * downstream module that can't import the host-rpc types but supplies its own `@Serializable`
 * DTO roots) call [generate]; they differ only in the endpoint allowlist, the extra DTO type-roots
 * to emit, and the client function's name + doc label.
 */
@OptIn(ExperimentalSerializationApi::class)
object RpcClientTsCodegen {

  /**
   * Assemble a full bindings file: [header] + the transport import + the walked TS types
   * ([extraTypeRoots] plus each endpoint's request/response descriptors, deduped by serial name) +
   * a `createXxxRpcClient` named [clientFunctionName]. [surfaceLabel] names the surface in the
   * client's doc comment (e.g. `"daemon's"`, `"Trail Runner"`).
   */
  fun generate(
    header: String,
    extraTypeRoots: List<SerialDescriptor>,
    requests: List<KClass<out RpcRequest<*>>>,
    clientFunctionName: String,
    surfaceLabel: String,
  ): String {
    require(requests.isNotEmpty()) {
      "RpcClientTsCodegen.generate requires at least one RpcRequest — a client with no endpoints " +
        "is meaningless (clientFunctionName=$clientFunctionName)."
    }
    val endpoints = requests.map(::endpointOf).sortedBy { it.methodName }
    // Each request must map to a UNIQUE client method — two requests that reduce to the same method
    // name (e.g. `FooRequest` + `Foo`) would silently emit duplicate keys in the client object
    // literal, where the last one wins. Fail the `generateDtoTs` build loudly instead.
    val collisions = endpoints.groupBy { it.methodName }.filter { it.value.size > 1 }
    require(collisions.isEmpty()) {
      "RpcClientTsCodegen.generate found requests that collide on the same client method name: " +
        collisions.entries.joinToString("; ") { (name, eps) -> "$name <- ${eps.map { it.endpointName }}" } +
        " (the method name is the request's simpleName minus the \"Request\" suffix)."
    }
    val rpcTypeRoots = endpoints.flatMap { listOf(it.requestDescriptor, it.responseDescriptor) }
    val typesBody = SerialDescriptorTsCodegen.generate(extraTypeRoots + rpcTypeRoots, header = "")
    return buildString {
      append(header)
      append(IMPORT_LINE)
      append(typesBody)
      append(renderClient(clientFunctionName, surfaceLabel, endpoints))
    }
  }

  private data class Endpoint(
    val methodName: String, // getConnectedDevices
    val endpointName: String, // GetConnectedDevicesRequest — the /rpc/<Name> segment
    val requestTsName: String,
    val responseTsName: String,
    val requestOptional: Boolean, // true when the request has no fields (e.g. a Kotlin `object`)
    val requestDescriptor: SerialDescriptor,
    val responseDescriptor: SerialDescriptor,
  )

  private fun endpointOf(requestClass: KClass<*>): Endpoint {
    val requestDescriptor = serializer(requestClass.starProjectedType).descriptor
    val responseDescriptor = serializer(requestClass.rpcResponseClass().starProjectedType).descriptor
    val endpointName = requestClass.simpleName ?: error("Anonymous RPC request class is not exportable.")
    return Endpoint(
      methodName = endpointName.removeSuffix("Request").replaceFirstChar { it.lowercase() },
      endpointName = endpointName,
      requestTsName = tsName(requestDescriptor),
      responseTsName = tsName(responseDescriptor),
      requestOptional = requestDescriptor.elementsCount == 0,
      requestDescriptor = requestDescriptor,
      responseDescriptor = responseDescriptor,
    )
  }

  /** The `TResponse` from a class's `RpcRequest<TResponse>` supertype — the request→response link. */
  private fun KClass<*>.rpcResponseClass(): KClass<*> {
    val parameterized = java.genericInterfaces
      .filterIsInstance<ParameterizedType>()
      .firstOrNull { (it.rawType as? Class<*>) == RpcRequest::class.java }
      ?: error("$simpleName must directly implement RpcRequest<TResponse> to be exported.")
    val responseType = parameterized.actualTypeArguments[0] as? Class<*>
      ?: error("$simpleName has a non-class RpcRequest response type, which isn't supported.")
    return responseType.kotlin
  }

  private fun tsName(d: SerialDescriptor): String =
    d.serialName.removeSuffix("?").substringAfterLast('.').substringAfterLast('$')

  private fun renderClient(
    functionName: String,
    surfaceLabel: String,
    endpoints: List<Endpoint>,
  ): String = buildString {
    // Pick a no-arg-callable endpoint (optional request) for the doc example so the snippet type-checks.
    val example = endpoints.firstOrNull { it.requestOptional } ?: endpoints.first()
    append("\n")
    append("/**\n")
    append(" * Typed client for the $surfaceLabel /rpc/<Name> endpoints — one method per RpcRequest<T>.\n")
    append(" *\n")
    append(" *   const rpc = $functionName({ baseUrl });\n")
    append(" *   const r = await rpc.${example.methodName}();   // RpcResult<${example.responseTsName}>\n")
    append(" */\n")
    append("export function $functionName(options: RpcCallOptions = {}) {\n")
    append("  return {\n")
    for (e in endpoints) {
      val param = if (e.requestOptional) "request: ${e.requestTsName} = {}" else "request: ${e.requestTsName}"
      append("    ${e.methodName}: ($param): Promise<RpcResult<${e.responseTsName}>> =>\n")
      append("      rpcCall<${e.requestTsName}, ${e.responseTsName}>(\"${e.endpointName}\", request, options),\n")
    }
    append("  };\n")
    append("}\n")
  }

  private const val IMPORT_LINE: String =
    "import { rpcCall, type RpcResult, type RpcCallOptions } from \"../rpc/client.js\";\n"
}
