package xyz.block.trailblaze.recording

import kotlinx.coroutines.CoroutineScope
import kotlinx.datetime.Clock
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.toolcalls.toLogPayload
import xyz.block.trailblaze.yaml.TrailConfig
import xyz.block.trailblaze.yaml.TrailblazeYaml
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import xyz.block.trailblaze.yaml.generateRecordedYaml

/**
 * Orchestrates interactive recording: collects [RecordedInteraction]s and emits
 * [TrailblazeLog.TrailblazeToolLog] entries into the existing recording pipeline
 * so [generateRecordedYaml] can produce trail YAML.
 */
class InteractionRecorder(
  private val logger: TrailblazeLogger,
  private val session: TrailblazeSession,
  scope: CoroutineScope,
  toolFactory: InteractionToolFactory,
) {
  private val lock = Any()
  private val _interactions = mutableListOf<RecordedInteraction>()
  val interactions: List<RecordedInteraction>
    get() = synchronized(lock) { _interactions.toList() }

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
    synchronized(lock) {
      isRecording = true
      _interactions.clear()
      logs.clear()
    }
  }

  fun stopRecording() {
    buffer.flush()
    synchronized(lock) {
      isRecording = false
    }
  }

  /** Remove a recorded interaction by index. Updates both the interaction list and logs. */
  fun removeInteraction(index: Int) {
    synchronized(lock) {
      if (index in _interactions.indices) {
        _interactions.removeAt(index)
        logs.removeAt(index)
      }
    }
  }

  /** Remove a recorded interaction by identity. Safer than index-based removal under concurrent updates. */
  fun removeInteraction(interaction: RecordedInteraction) {
    synchronized(lock) {
      val index = _interactions.indexOf(interaction)
      if (index >= 0) {
        _interactions.removeAt(index)
        logs.removeAt(index)
      }
    }
  }

  private fun recordInteraction(interaction: RecordedInteraction) {
    synchronized(lock) {
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
      logger.log(session, toolLog)
    }
  }

  /** Generate trail YAML from all recorded interactions via the existing pipeline. */
  fun generateTrailYaml(config: TrailConfig? = null): String {
    return synchronized(lock) {
      logs.generateRecordedYaml(
        trailblazeYaml = trailblazeYaml,
        sessionTrailConfig = config,
      )
    }
  }

  companion object {
    private val trailblazeYaml: TrailblazeYaml by lazy { createTrailblazeYaml() }

    /** Serialize a single recorded interaction's tool to YAML. */
    fun singleToolToYaml(interaction: RecordedInteraction): String =
      trailblazeYaml.encodeToolToYaml(interaction.toolName, interaction.tool)
  }
}
