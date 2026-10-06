package xyz.block.trailblaze.scripting.host

import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.tracing.TrailblazeTracer
import xyz.block.trailblaze.util.Console

/**
 * Runs one `ctx.host.<name>(args)` call. Both script transports — the in-process QuickJS binding
 * and the subprocess callback endpoint — route here, so they decode, fail and log identically.
 *
 * What a call does NOT do is the point: no tool lookup, no tool log in the session or report, no
 * recording, and no invalidation of the cached screen. It leaves a trace span and one daemon log
 * line with the function name, how long it took and whether it succeeded — never the arguments or
 * the result, which can carry credentials.
 */
object TrailblazeHostFunctionDispatcher {

  sealed interface Outcome {
    data class Success(val value: JsonElement) : Outcome

    data class Failure(val message: String) : Outcome
  }

  // Strict at every level: an argument the function doesn't declare — top-level or inside a nested
  // object — fails the call instead of being dropped by TrailblazeJsonInstance's
  // `ignoreUnknownKeys`. Everything else (leniency, serializers module) is inherited.
  private val strictJson = Json(TrailblazeJsonInstance) { ignoreUnknownKeys = false }

  suspend fun call(
    name: String,
    argsJson: String,
    context: TrailblazeToolExecutionContext,
    registry: TrailblazeHostFunctionRegistry = TrailblazeHostFunctionRegistry.bundled,
  ): Outcome {
    val started = TimeSource.Monotonic.markNow()
    val outcome = TrailblazeTracer.traceSuspend("host:$name", cat = "host-function") {
      dispatch(name, argsJson, context, registry)
    }
    val status = when (outcome) {
      is Outcome.Success -> "ok"
      is Outcome.Failure -> "failed"
    }
    Console.log("[TrailblazeHostFunction] $name $status in ${started.elapsedNow().inWholeMilliseconds}ms")
    return outcome
  }

  private suspend fun dispatch(
    name: String,
    argsJson: String,
    context: TrailblazeToolExecutionContext,
    registry: TrailblazeHostFunctionRegistry,
  ): Outcome {
    val entry = registry.resolve(name)
      ?: return Outcome.Failure(
        "No host function named '$name'. Registered: ${registry.all.keys.joinToString().ifEmpty { "(none)" }}.",
      )

    val argsSerializer = entry.argsSerializer
    unknownKeys(argsJson, argsSerializer)?.let { unknown ->
      val accepted = (0 until argsSerializer.descriptor.elementsCount)
        .map { argsSerializer.descriptor.getElementName(it) }
      return Outcome.Failure(
        "Host function '$name' does not accept ${unknown.joinToString { "'$it'" }}. " +
          "Accepted: ${accepted.joinToString().ifEmpty { "(no arguments)" }}.",
      )
    }

    // Failure messages here never carry the arguments or the result: either can hold credentials,
    // and the message reaches the script's error and from there the session log.
    val function = try {
      strictJson.decodeFromString(argsSerializer, argsJson.ifBlank { "{}" })
    } catch (e: SerializationException) {
      return Outcome.Failure("Host function '$name' could not decode its arguments: ${e.withoutInput()}")
    } catch (e: Exception) {
      return Outcome.Failure("Host function '$name' could not decode its arguments: ${e::class.simpleName}")
    }

    val result = try {
      function.invoke(context)
    } catch (e: CancellationException) {
      throw e
    } catch (e: TrailblazeHostFunctionException) {
      return Outcome.Failure(e.message.orEmpty())
    } catch (e: Exception) {
      return Outcome.Failure("Host function '$name' threw ${e::class.simpleName}: ${e.message}")
    }

    val resultClass = entry.annotation.result
    if (!resultClass.isInstance(result)) {
      return Outcome.Failure(
        "Host function '$name' returned ${result::class.simpleName}, but declares result ${resultClass.simpleName}.",
      )
    }
    return try {
      Outcome.Success(TrailblazeJsonInstance.encodeToJsonElement(entry.resultSerializer, result))
    } catch (e: Exception) {
      Outcome.Failure("Host function '$name' could not encode its result: ${e::class.simpleName}")
    }
  }

  /**
   * kotlinx's decode errors end with `JSON input: <the input>`; only the first line — what failed
   * and at which path — is safe to hand back.
   */
  private fun SerializationException.withoutInput(): String =
    message.orEmpty().lineSequence().first().ifBlank { this::class.simpleName.orEmpty() }

  /** Keys in [argsJson] the function's serializer doesn't declare, or null when there are none. */
  private fun unknownKeys(argsJson: String, serializer: KSerializer<*>): List<String>? {
    val args = try {
      strictJson.parseToJsonElement(argsJson.ifBlank { "{}" }) as? JsonObject
    } catch (_: SerializationException) {
      null
    } ?: return null
    val declared = (0 until serializer.descriptor.elementsCount)
      .map { serializer.descriptor.getElementName(it) }
      .toSet()
    return args.keys.filterNot { it in declared }.takeIf { it.isNotEmpty() }
  }

  /**
   * The envelope the QuickJS binding returns to the script: `{"ok":true,"value":…}` or
   * `{"ok":false,"error":"…"}`.
   */
  fun Outcome.toEnvelopeJson(): String = when (this) {
    is Outcome.Success -> buildJsonObject {
      put("ok", true)
      put("value", value)
    }
    is Outcome.Failure -> buildJsonObject {
      put("ok", false)
      put("error", message)
    }
  }.toString()
}
