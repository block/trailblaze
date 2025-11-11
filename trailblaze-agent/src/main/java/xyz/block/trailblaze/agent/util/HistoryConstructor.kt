package xyz.block.trailblaze.agent.util

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.block.trailblaze.agent.model.PromptRecordingResult
import xyz.block.trailblaze.api.AgentMessages.toContentString
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.yaml.TrailblazeToolYamlWrapper
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.isAccessible

// This function takes a failed PromptRecordingResult and reconstructs the tool call message history
// to send to the LLM for the prompt. This will allow the LLM to take over at the point the test
// failed in order to attempt a recovery.
fun PromptRecordingResult.Failure.toLlmResponseHistory(): MutableList<Message> {
  val history = mutableListOf<Message>()
  this.successfulTools.forEachIndexed { index, tool ->
    // Create success response and adad it to the history
    history.add(
      Message.User(
        content = tool.generateContentString(),
        metaInfo = RequestMetaInfo.create(Clock.System),
      ),
    )
  }
  // Create failure response and add it to the history
  history.add(
    Message.User(
      content = failureResult.toContentString(
        toolName = failedTool.name,
        toolArgs = JsonObject(failedTool.getToolArgs()),
      ),
      metaInfo = RequestMetaInfo.create(Clock.System),
    ),
  )
  return history
}

private fun TrailblazeToolYamlWrapper.generateContentString() = TrailblazeToolResult.Success.toContentString(
  toolName = name,
  toolArgs = JsonObject(getToolArgs()),
)

private fun TrailblazeToolYamlWrapper.getToolArgs(): Map<String, JsonElement> {
  val kClass = this::class
  val constructor = kClass.primaryConstructor
    ?: kClass.constructors.maxByOrNull { it.parameters.size } // fallback if no primary
    ?: return emptyMap()
  val propsByName = kClass.memberProperties.associateBy { it.name }

  val result = LinkedHashMap<String, JsonElement>()
  for (param in constructor.parameters) {
    val name = param.name ?: continue // skip parameters without a name
    val prop = propsByName[name]
    if (prop != null) {
      @Suppress("UNCHECKED_CAST")
      val p = prop as KProperty1<Any?, *>
      try {
        p.isAccessible = true
        result[name] = JsonPrimitive(p.get(this).toString())
      } catch (_: Throwable) {
        // ignore failing parameters for now
      }
    }
  }
  return result
}
