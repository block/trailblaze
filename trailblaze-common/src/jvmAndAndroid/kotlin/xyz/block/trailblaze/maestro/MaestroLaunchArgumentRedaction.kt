package xyz.block.trailblaze.maestro

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.block.trailblaze.toolcalls.REDACTED_TOOL_ARG_PLACEHOLDER
import xyz.block.trailblaze.yaml.TrailArgTokens

private val LEGACY_MEMORY_PATH = Regex("""[A-Za-z_][A-Za-z0-9_.-]*""")

private fun supportedReferenceBody(body: String): Boolean =
  if (TrailArgTokens.isArgsToken(body)) TrailArgTokens.isValidDottedPath(body)
  else LEGACY_MEMORY_PATH.matches(body)

private fun authoredReferenceRanges(text: String): List<IntRange> =
  TrailArgTokens.TOKEN_REGEX.findAll(text)
    .filter { match -> TrailArgTokens.tokenBody(match)?.let(::supportedReferenceBody) == true }
    .map { it.range }
    .toList()

/** Launch arguments can contain credentials. Recognize them in Maestro log JSON. */
internal fun maestroLaunchArgumentSecrets(value: JsonElement): List<String> = when (value) {
  is JsonObject -> value.flatMap { (key, child) ->
    if (key == "launchApp" || key == "launchAppCommand") {
      val launch = child as? JsonObject
      val arguments = launch?.get("arguments") ?: launch?.get("launchArguments")
      argumentValues(arguments)
    } else {
      maestroLaunchArgumentSecrets(child)
    }
  }
  is JsonArray -> value.flatMap(::maestroLaunchArgumentSecrets)
  else -> emptyList()
}

private fun argumentValues(value: JsonElement?): List<String> = when (value) {
  is JsonObject -> value.values.flatMap(::argumentValues)
  is JsonArray -> value.flatMap(::argumentValues)
  is JsonPrimitive -> listOf(value.content).filter { it.isNotBlank() }
  else -> emptyList()
}

internal fun JsonObject.withMaestroLaunchArgumentsRedacted(): JsonObject =
  if (maestroLaunchArgumentSecrets(this).isEmpty()) this else redactLaunches(this) as JsonObject

/** Mask a launch value repeated in another command, without shredding unrelated short strings. */
internal fun JsonObject.withKnownMaestroLaunchValuesRedacted(values: Collection<String>): JsonObject =
  scrubKnownValues(
    this, values.filter { it.isNotBlank() && it != "true" && it != "false" }.sortedByDescending { it.length },
  ) as JsonObject

private fun scrubKnownValues(value: JsonElement, secrets: List<String>): JsonElement = when (value) {
  is JsonObject -> JsonObject(value.mapValues { (_, child) -> scrubKnownValues(child, secrets) })
  is JsonArray -> JsonArray(value.map { child -> scrubKnownValues(child, secrets) })
  is JsonPrimitive -> {
    if (!value.isString) {
      if (value.content in secrets) JsonPrimitive(REDACTED_TOOL_ARG_PLACEHOLDER) else value
    } else if (value.content == REDACTED_TOOL_ARG_PLACEHOLDER) value else {
      val scrubbed = redactKnownMaestroText(value.content, secrets)
      if (scrubbed == value.content) value else JsonPrimitive(scrubbed)
    }
  }
  else -> value
}

private fun redactKnownMaestroText(text: String, secrets: List<String>): String =
  secrets.fold(text) { current, secret ->
    // Mask the entire unresolved token when a declared value is in its name. A partial marker
    // inside token syntax would make the recording invalid and obscure which command was unsafe.
    val unresolved = authoredReferenceRanges(current).filter { range ->
      secret.length >= 3 && current.substring(range).contains(secret)
    }
    val withoutTokens = unresolved.sortedByDescending { it.first }.fold(current) { acc, range ->
      acc.replaceRange(range, REDACTED_TOOL_ARG_PLACEHOLDER)
    }
    Regex(Regex.escape(secret)).replace(withoutTokens) { match ->
      if (secret.length < 3 && withoutTokens != secret) match.value else REDACTED_TOOL_ARG_PLACEHOLDER
    }
  }

private fun redactLaunches(value: JsonElement): JsonElement = when (value) {
  is JsonObject -> JsonObject(value.mapValues { (key, child) ->
    if (key == "sourceInfo" && child is JsonObject) {
      // Nested flows retain the whole source document, including otherwise-masked arguments.
      JsonObject(child.filterKeys { it != "source" }.mapValues { redactLaunches(it.value) })
    } else if ((key == "launchApp" || key == "launchAppCommand") && child is JsonObject) {
      JsonObject(child.mapValues { (field, argumentMap) ->
        if ((field == "arguments" || field == "launchArguments") && argumentMap is JsonObject) {
          JsonObject(argumentMap.mapValues { JsonPrimitive(REDACTED_TOOL_ARG_PLACEHOLDER) })
        } else redactLaunches(argumentMap)
      })
    } else redactLaunches(child)
  })
  is JsonArray -> JsonArray(value.map(::redactLaunches))
  else -> value
}
