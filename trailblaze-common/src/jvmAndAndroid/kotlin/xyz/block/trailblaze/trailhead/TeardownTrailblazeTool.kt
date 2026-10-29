package xyz.block.trailblaze.trailhead

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.toolcalls.HostLocalExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.RawArgumentTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolMetadata
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * The one step of the "Teardown" finish objective: calls each started trailhead invocation's
 * `teardown` (last started first), applies the forced-`false` rules and stores each result for
 * the next trail on the device. Runs through the runner's normal tool dispatch so nested tool calls
 * from `teardown` log like any other step; never recorded, never offered to the LLM.
 *
 * Returns an error when any `teardown` failed. The runner reports that on the finish objective
 * and does not change the trail's verdict.
 */
class TeardownTrailblazeTool(
  private val run: TrailheadRun,
  private val outcome: TrailOutcome,
  /**
   * Shared by every invocation; one that runs long leaves the rest less. Best effort while a
   * `teardown` is inside a nested tool call: the scripted host waits for that call to return
   * before the timeout can land, so the overrun is bounded by the nested tool's own timeouts.
   */
  private val budget: Duration = DEFAULT_BUDGET,
) : HostLocalExecutableTrailblazeTool, RawArgumentTrailblazeTool {

  override val advertisedToolName: String get() = TOOL_NAME

  override val rawToolArguments: JsonObject
    get() = buildJsonObject {
      put("outcome", outcome.wireName())
      put("trailheads", JsonArray(run.invocations.map { JsonPrimitive(it.tool.trailheadToolName) }))
    }

  override val toolMetadata: TrailblazeToolMetadata
    get() = TrailblazeToolMetadata(surfaceToLlm = false, isRecordable = false)

  override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
    run.trailStartMs?.let { start ->
      TrailheadEventLog.append(toolExecutionContext, TrailheadEventStream.Type.TRAIL_START, start) {}
    }
    TrailheadEventLog.append(
      toolExecutionContext,
      TrailheadEventStream.Type.TRAIL_END,
      run.trailEndMs ?: System.currentTimeMillis(),
    ) { put("outcome", outcome.wireName()) }

    val trailRun = TrailblazeJsonInstance
      .encodeToJsonElement(TrailRunWire.serializer(), TrailRunWire(outcome))
      .jsonObject
    val started = TimeSource.Monotonic.markNow()
    val failures = mutableListOf<String>()
    val summaries = mutableListOf<String>()
    // This trail's results replace everything the last one left on each device it set up or could
    // have changed: in a multi-device trail that is every bound device, trailhead or not.
    val bound = toolExecutionContext.deviceBindings?.let { bindings ->
      bindings.names.mapNotNull { bindings.deviceFor(it)?.trailblazeDeviceId?.let(TrailheadHandoffStore::keyOf) }
    }.orEmpty()
    (run.invocations.map { it.key.deviceId } + bound).toSet().forEach(TrailheadHandoffStore::dropDevice)
    run.handoffsDropped = true
    val activeDevice = TrailheadRuns.deviceNameOf(toolExecutionContext)
    for (invocation in run.invocations.reversed()) {
      val name = invocation.tool.trailheadToolName
      val remaining = budget - started.elapsedNow()
      var raw: JsonObject? = null
      val failure: String? = if (invocation.deviceName != null && invocation.deviceName != activeDevice) {
        // Dispatch here reaches only the active device, so its cleanup would land on the wrong one.
        "not run: it set up device ${invocation.deviceName}, and the trail ended on ${activeDevice ?: "another device"}"
      } else if (remaining <= Duration.ZERO) {
        "timed out: the ${budget.inWholeSeconds}s Teardown budget was spent before it started"
      } else {
        try {
          raw = withTimeout(remaining) { invocation.tool.teardown(toolExecutionContext, invocation.args, trailRun) }
          null
        } catch (e: TimeoutCancellationException) {
          "timed out after ${budget.inWholeSeconds}s"
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName.orEmpty()
        }
      }
      val result = TrailheadRuns.recordResult(toolExecutionContext, run, invocation, outcome, raw, failure)
      if (failure != null) failures += "$name: $failure"
      summaries += "$name: reusable=${result.reusable} (${result.reason})"
    }
    val message = summaries.joinToString("\n")
    return if (failures.isEmpty()) {
      TrailblazeToolResult.Success(message = message)
    } else {
      TrailblazeToolResult.Error.ExceptionThrown(errorMessage = "teardown failed — ${failures.joinToString("; ")}\n$message")
    }
  }

  companion object {
    const val TOOL_NAME: String = "teardown"
    val DEFAULT_BUDGET: Duration = 45.seconds
  }
}
