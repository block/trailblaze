package xyz.block.trailblaze.recording

import kotlinx.coroutines.CoroutineScope
import kotlinx.datetime.Clock
import xyz.block.trailblaze.logs.client.LogEmitter
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.toLogPayload
import xyz.block.trailblaze.yaml.TrailConfig
import xyz.block.trailblaze.yaml.TrailblazeToolYamlWrapper
import xyz.block.trailblaze.yaml.TrailblazeYaml
import xyz.block.trailblaze.yaml.generateRecordedYaml

/**
 * Orchestrates interactive recording: collects [RecordedInteraction]s and emits
 * [TrailblazeLog.TrailblazeToolLog] entries into the existing recording pipeline
 * so [generateRecordedYaml] can produce trail YAML.
 *
 * ## Threading contract
 *
 * **Call all mutating methods from the same dispatcher.** The recording UI passes a Compose
 * scope (`rememberCoroutineScope()`), which serializes everything on the main thread, so
 * `_interactions` and `logs` are accessed from a single thread in practice. The earlier
 * `synchronized(lock)` blocks were defensive against a multi-thread caller that never
 * materialized; dropping them is what makes this class commonMain-portable (Kotlin
 * `synchronized` is JVM-only). Future callers that DO want multi-threaded access should
 * wrap their own access in their own mutex; this class assumes single-dispatcher use.
 *
 * ## Logging dependency
 *
 * Takes a [LogEmitter] (commonMain interface) rather than the full `TrailblazeLogger` because
 * the recorder only ever fires `logEmitter.emit(log)` — no LLM logging, no screen-state
 * logging, no progress events. Decoupling from `TrailblazeLogger` (which lives in
 * jvmAndAndroid because of OkHttp interceptors and Koog reflection used by `logLlmRequest`)
 * is what makes this class buildable for non-JVM targets in the future.
 */
class InteractionRecorder(
  private val logEmitter: LogEmitter,
  private val session: TrailblazeSession,
  scope: CoroutineScope,
  toolFactory: InteractionToolFactory,
) {
  private val _interactions = mutableListOf<RecordedInteraction>()
  val interactions: List<RecordedInteraction>
    get() = _interactions.toList()

  private val logs = mutableListOf<TrailblazeLog>()

  @Volatile
  var isRecording: Boolean = false
    private set

  val buffer = InteractionEventBuffer(
    scope = scope,
    toolFactory = toolFactory,
    onInteraction = ::recordInteraction,
  )

  fun startRecording() {
    isRecording = true
    _interactions.clear()
    logs.clear()
  }

  fun stopRecording() {
    buffer.flush()
    isRecording = false
  }

  /** Remove a recorded interaction by index. Updates both the interaction list and logs. */
  fun removeInteraction(index: Int) {
    if (index in _interactions.indices) {
      _interactions.removeAt(index)
      logs.removeAt(index)
    }
  }

  /** Remove a recorded interaction by identity. Safer than index-based removal under concurrent updates. */
  fun removeInteraction(interaction: RecordedInteraction) {
    val index = _interactions.indexOf(interaction)
    if (index >= 0) {
      _interactions.removeAt(index)
      logs.removeAt(index)
    }
  }

  /**
   * Swap the tool on a previously-recorded interaction. The candidate metadata, screenshot,
   * and timestamp are preserved so the card identity in the UI doesn't churn — only the
   * emitted [TrailblazeTool] (and therefore the YAML output) changes.
   *
   * The matching log entry is also rewritten so [generateTrailYaml] reflects the swap.
   * No-op if the interaction isn't currently recorded (e.g. the user already deleted it).
   */
  fun replaceInteractionTool(
    interaction: RecordedInteraction,
    newTool: TrailblazeTool,
    newToolName: String,
  ) {
    val index = _interactions.indexOf(interaction)
    if (index < 0) return
    _interactions[index] = interaction.copy(tool = newTool, toolName = newToolName)
    // `logs` is typed at the sealed parent; recordInteraction only ever stores ToolLogs
    // here, so the cast is a structural fact rather than a leap of faith.
    val existingLog = logs[index] as TrailblazeLog.TrailblazeToolLog
    logs[index] = existingLog.copy(
      trailblazeTool = newTool.toLogPayload(),
      toolName = newToolName,
    )
  }

  /**
   * Parses [editedYaml] (the same single-tool YAML shape [singleToolToYaml] emits) and
   * swaps the recorded interaction in-place via [replaceInteractionTool]. Returns a
   * [Result] so the caller can surface parse failures as inline UI errors instead of
   * crashing — typing into a YAML editor produces malformed input on every keystroke.
   *
   * The edit YAML is expected to look like the card's rendered text:
   *
   * ```
   * inputText:
   *   text: hello
   * ```
   *
   * It's wrapped into a list (`- inputText: ...`) before decoding because [TrailblazeYaml.decodeTools]
   * only accepts the list form. Any tool that round-trips through [singleToolToYaml] +
   * [TrailblazeYaml.decodeTools] is editable; that's every tool registered on the YAML
   * instance, which is the same set the recorder can produce.
   */
  fun replaceInteractionFromYaml(
    interaction: RecordedInteraction,
    editedYaml: String,
  ): Result<Unit> = runCatching {
    val wrapper = RecordingYamlCodec.decodeSingleToolYaml(editedYaml)
    replaceInteractionTool(interaction, wrapper.trailblazeTool, wrapper.name)
  }

  /**
   * Append (or insert at [position]) a manually-constructed interaction — for the Tool Palette
   * UI where the author picks a tool from the toolbox-derived list and fills in its params,
   * rather than performing a gesture on the device.
   *
   * Unlike [recordInteraction] (the buffer's hot path), this does NOT gate on `isRecording` —
   * the user often wants to add a step to a finished recording before saving. Out-of-range
   * positions clamp to `[0, size]`.
   */
  fun insertInteraction(interaction: RecordedInteraction, position: Int? = null) {
    val targetIndex = position?.coerceIn(0, _interactions.size) ?: _interactions.size
    _interactions.add(targetIndex, interaction)
    val toolLog = TrailblazeLog.TrailblazeToolLog(
      trailblazeTool = interaction.tool.toLogPayload(),
      toolName = interaction.toolName,
      successful = true,
      traceId = null,
      durationMs = 0L,
      session = session.sessionId,
      timestamp = Clock.System.now(),
      isRecordable = true,
    )
    logs.add(targetIndex, toolLog)
    logEmitter.emit(toolLog)
  }

  private fun recordInteraction(interaction: RecordedInteraction) {
    if (!isRecording) return
    _interactions.add(interaction)

    val toolLog = TrailblazeLog.TrailblazeToolLog(
      trailblazeTool = interaction.tool.toLogPayload(),
      toolName = interaction.toolName,
      successful = true,
      traceId = null,
      durationMs = 0L,
      session = session.sessionId,
      timestamp = Clock.System.now(),
      isRecordable = true,
    )
    logs.add(toolLog)
    logEmitter.emit(toolLog)
  }

  /** Generate trail YAML from all recorded interactions via the existing pipeline. */
  fun generateTrailYaml(config: TrailConfig? = null): String =
    logs.generateRecordedYaml(
      trailblazeYaml = trailblazeYaml,
      sessionTrailConfig = config,
    )

  companion object {
    /**
     * Routes through [TrailblazeYaml.Default] (expect/actual) rather than the JVM-only
     * `createTrailblazeYaml()`. Same change [RecordingYamlCodec] made — keeps the recorder's
     * remaining JVM dependencies focused on `toLogPayload` (which still needs JVM reflection
     * for YAML-defined tools), not on YAML registry construction.
     */
    private val trailblazeYaml: TrailblazeYaml get() = TrailblazeYaml.Default

    /**
     * Encode/decode helpers below forward to [RecordingYamlCodec] in commonMain. They stay on
     * the recorder's companion as thin shims so existing call sites compile unchanged; the
     * canonical home for new callers is [RecordingYamlCodec] directly, which is reachable
     * from any commonMain caller (including a future wasmJs client) without dragging in this
     * class's JVM-only instance dependencies.
     */
    fun singleToolToYaml(interaction: RecordedInteraction): String =
      RecordingYamlCodec.singleToolToYaml(interaction)

    fun decodeSingleToolYaml(singleToolYaml: String): TrailblazeToolYamlWrapper =
      RecordingYamlCodec.decodeSingleToolYaml(singleToolYaml)

    fun singleInteractionToTrailYaml(interaction: RecordedInteraction): String =
      RecordingYamlCodec.singleInteractionToTrailYaml(interaction)

    fun interactionsToTrailYaml(interactions: List<RecordedInteraction>): String =
      RecordingYamlCodec.interactionsToTrailYaml(interactions)
  }
}
