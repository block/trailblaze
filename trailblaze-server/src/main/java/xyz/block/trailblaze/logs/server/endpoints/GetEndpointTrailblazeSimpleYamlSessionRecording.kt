package xyz.block.trailblaze.logs.server.endpoints

import com.charleskorn.kaml.Yaml
import io.ktor.server.freemarker.FreeMarkerContent
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.serializer
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.report.utils.LogsRepo
import kotlin.reflect.KProperty1

/**
 * Endpoint to get the Trailblaze Simple YAML representation of a Trailblaze session recording.
 */
object GetEndpointTrailblazeSimpleYamlSessionRecording {

  fun register(routing: Routing, logsDirUtil: LogsRepo) = with(routing) {
    get("/recording/tbsimple/{session}") {
      println("Recording Trailblaze Simple YAML (kaml, custom structure)")
      val sessionId = this.call.parameters["session"]
      val logs = logsDirUtil.getLogsForSession(sessionId)
      val trailblazeSimpleYamlBlocks = reconstructRunsFromEvents(logs)

      // Serializers for the new structure
      val paramSerializer = MapSerializer(serializer<String>(), serializer<String>())
      val stepSerializer = MapSerializer(serializer<String>(), paramSerializer)
      val stepsListSerializer = ListSerializer(stepSerializer)
      val objectiveSerializer = MapSerializer(serializer<String>(), stepsListSerializer.nullable)
      val runMapSerializer = MapSerializer(serializer<String>(), ListSerializer(objectiveSerializer))
      val topLevelListSerializer = ListSerializer(MapSerializer(serializer<String>(), AnyValueSerializer))

      val yamlString = Yaml.default.encodeToString(topLevelListSerializer, trailblazeSimpleYamlBlocks)
      val prettifiedYaml = removeQuotesIfNoColon(yamlString)

      println("--- TRAILBLAZE SIMPLE YAML ---\n$prettifiedYaml\n---")

      call.respond(
        FreeMarkerContent(
          "recording_yaml.ftl",
          mapOf(
            "session" to sessionId,
            "yaml" to prettifiedYaml,
            "recordingType" to "Trailblaze Simple Recording",
          ),
        ),
        null,
      )
    }
  }

  // Returns a list: [ {tapOn: Bagel}, {run: [...] } ]
  private fun reconstructRunsFromEvents(events: List<TrailblazeLog>): List<Map<String, Any?>> {
    val yamlBlocks = mutableListOf<Map<String, Any?>>()
    var currentObjective: ObjectiveBuilder? = null
    var inRun = false
    var currentTaskId: String? = null
    val objectives = mutableListOf<Map<String, List<Map<String, Map<String, String>>>?>>()
    for (event in events) {
      when (event) {
        is TrailblazeLog.TopLevelMaestroCommandLog -> {
          // Flush any in-progress run block before appending Maestro command
          if (inRun && (objectives.isNotEmpty() || currentObjective != null)) {
            if (currentObjective != null) {
              objectives.add(currentObjective.build())
              currentObjective = null
            }
            yamlBlocks.add(mapOf("run" to objectives.toList()))
            objectives.clear()
            inRun = false
            currentTaskId = null
          }
          // Output the Maestro command as a top-level map
          val commandParts = event.command.removePrefix("- ").split(": ", limit = 2)
          if (commandParts.size == 2) {
            yamlBlocks.add(mapOf(commandParts[0] to commandParts[1]))
          } else {
            yamlBlocks.add(mapOf("command" to event.command))
          }
        }
        is TrailblazeLog.TrailblazeAgentTaskStatusChangeLog -> {
          val statusClass = event.agentTaskStatus::class.simpleName ?: ""
          val taskId = event.agentTaskStatus.statusData.taskId
          if ("InProgress" in statusClass) {
            if (!inRun) {
              inRun = true
              currentTaskId = taskId
              currentObjective = null
            } else if (currentTaskId != null && currentTaskId != taskId) {
              // New taskId, flush current run and start new
              if (currentObjective != null) {
                objectives.add(currentObjective.build())
                currentObjective = null
              }
              yamlBlocks.add(mapOf("run" to objectives.toList()))
              objectives.clear()
              currentTaskId = taskId
              inRun = true
            }
          } else if (("Success" in statusClass || "Failure" in statusClass) && inRun && currentTaskId == taskId) {
            if (objectives.isNotEmpty() || currentObjective != null) {
              if (currentObjective != null) {
                objectives.add(currentObjective.build())
                currentObjective = null
              }
              yamlBlocks.add(mapOf("run" to objectives.toList()))
              objectives.clear()
            }
            inRun = false
            currentTaskId = null
          }
        }
        is TrailblazeLog.ObjectiveStartLog -> {
          if (currentObjective != null) {
            objectives.add(currentObjective.build())
          }
          currentObjective = ObjectiveBuilder(event.description)
          inRun = true
        }
        is TrailblazeLog.ObjectiveCompleteLog -> {
          if (currentObjective != null) {
            objectives.add(currentObjective.build())
            currentObjective = null
          }
        }
        is TrailblazeLog.TrailblazeToolLog -> {
          if (inRun && currentObjective != null) {
            val toolName = event.toolName
            val command = event.command
            if (toolName != "tapOnElementByNodeId" && toolName != "objectiveComplete") {
              val params = command::class.members
                .filterIsInstance<KProperty1<Any, *>>()
                .filter { it.name != "class" && it.name != "reason" }
                .associate { it.name to it.get(command) }
                .filterValues { it != null && it != "null" }
                .mapValues { it.value.toString() }
              currentObjective.steps.add(mapOf(toolName to params))
            }
          }
        }
        else -> {
          // Ignore other event types
        }
      }
    }
    // At the end, flush any remaining run block
    if (inRun && (objectives.isNotEmpty() || currentObjective != null)) {
      if (currentObjective != null) {
        objectives.add(currentObjective.build())
        currentObjective = null
      }
      yamlBlocks.add(mapOf("run" to objectives.toList()))
    }
    return yamlBlocks
  }

  private class ObjectiveBuilder(val description: String) {
    val steps = mutableListOf<Map<String, Map<String, String>>>()
    fun build(): Map<String, List<Map<String, Map<String, String>>>?> = if (steps.isEmpty()) {
      mapOf(description to null)
    } else {
      mapOf(description to steps.toList())
    }
  }

  // Helper for serializing Any? values (String, List<Map<String, ...>>, or null)
  private object AnyValueSerializer : kotlinx.serialization.KSerializer<Any?> {
    override val descriptor = serializer<String>().descriptor
    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: Any?) {
      when (value) {
        null -> encoder.encodeNull()
        is String -> encoder.encodeString(value)
        is List<*> -> {
          @Suppress("UNCHECKED_CAST")
          val listSerializer = ListSerializer(MapSerializer(serializer<String>(), AnyValueSerializer))
          encoder.encodeSerializableValue(listSerializer, value as List<Map<String, Any?>>)
        }
        is Map<*, *> -> {
          @Suppress("UNCHECKED_CAST")
          val mapSerializer = MapSerializer(serializer<String>(), AnyValueSerializer)
          encoder.encodeSerializableValue(mapSerializer, value as Map<String, Any?>)
        }
        else -> throw IllegalStateException("Unsupported type in YAML: ${value?.javaClass}")
      }
    }
    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): Any? = error("Not needed")
  }

  // Post-process YAML to remove quotes from strings that do not contain a colon
  private fun removeQuotesIfNoColon(yaml: String): String {
    // Regex to match quoted strings (single or double quotes)
    val regex = Regex("""(['\"])([^'\":\n]+)\1""")
    return regex.replace(yaml) { matchResult ->
      val quote = matchResult.groupValues[1]
      val content = matchResult.groupValues[2]
      // Only remove quotes if there's no colon in the content
      if (':' !in content) content else "$quote$content$quote"
    }
  }
}
