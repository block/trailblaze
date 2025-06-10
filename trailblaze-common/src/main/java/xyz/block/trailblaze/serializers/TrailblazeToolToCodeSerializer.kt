package xyz.block.trailblaze.serializers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.temp.flattenTrailblazeJson
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.ObjectiveStatusTrailblazeTool

class TrailblazeToolToCodeSerializer {

  var indentCount = 0

  private fun fqClassName(command: JsonObject): String = command.getValue("class").toString().replace("\"", "")

  private fun indent(): String = buildString {
    repeat(indentCount) {
      append("  ")
    }
  }

  companion object {
    val STANDARD_PRETTY_PRINT_JSON = Json { prettyPrint = true }
  }

  fun serializeTrailblazeToolToCode(incomingTrailblazeTool: TrailblazeTool): String = buildString {
    val normalJson = TrailblazeJsonInstance.encodeToString(incomingTrailblazeTool)
    val recordingJson = flattenTrailblazeJson(normalJson)
    val trailblazeToolJsonObject = STANDARD_PRETTY_PRINT_JSON.decodeFromString<JsonObject>(recordingJson)

    val fqClassName = fqClassName(trailblazeToolJsonObject)
    val simpleClassName = fqClassName.substringAfterLast(".")

    val props = trailblazeToolJsonObject.keys.filter {
      it != "class"
    }

    if (props.isEmpty()) {
      append("${indent()}$simpleClassName()")
    } else {
      appendLine("${indent()}$simpleClassName(")
      indentCount++
      props.forEach { key ->
        append("${indent()}$key = ")
        val actualValue = trailblazeToolJsonObject.getValue(key)
        when (actualValue) {
          is JsonArray -> append(actualValue.toString())
          is JsonObject -> append(actualValue.toString())
          is JsonPrimitive -> {
            append(actualValue)
          }

          JsonNull -> "null"
        }
        appendLine(",")
      }
      indentCount--
      append("${indent()})")
    }
  }

  fun serializeToCode(map: Map<String, List<TrailblazeTool>>): String {
    val allCommandTypesToDetermineImports = map.entries.flatMap { it.value }
    if (allCommandTypesToDetermineImports.isEmpty()) {
      return "null"
    }
    val jsonFromTrailblazeInstance = TrailblazeJsonInstance.encodeToString<List<TrailblazeTool>>(
      allCommandTypesToDetermineImports,
    )
    val normalizedJson = flattenTrailblazeJson(jsonFromTrailblazeInstance)
    val commandsAsJsonObjects = STANDARD_PRETTY_PRINT_JSON.decodeFromString<JsonArray>(normalizedJson)

    val imports = commandsAsJsonObjects.map { commandJsonObject ->
      fqClassName(commandJsonObject as JsonObject)
    }.distinct()

    return buildString {
      imports
        .plus(TrailblazeTool::class.java.name)
        .sorted()
        .forEach { import ->
          appendLine("import $import")
        }
      appendLine()

      map.entries.forEach { (prompt, trailblazeTools: List<TrailblazeTool>) ->
        appendLine("${indent()}run(\"\"\"$prompt\"\"\") {")
        indentCount++
        appendLine("${indent()}listOf(")
        trailblazeTools
          .filter { it !is ObjectiveStatusTrailblazeTool }
          .forEach { trailblazeTool ->
            indentCount++
            append(serializeTrailblazeToolToCode(trailblazeTool))
            appendLine(",")
            indentCount--
          }
        appendLine("${indent()})")
        indentCount--
        appendLine("${indent()}}")
        appendLine()
      }
    }
  }
}
