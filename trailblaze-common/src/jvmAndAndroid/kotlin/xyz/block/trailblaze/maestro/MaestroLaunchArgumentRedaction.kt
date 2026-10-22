package xyz.block.trailblaze.maestro

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.block.trailblaze.logs.client.temp.YamlJsonBridge
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

/** Only one reference can be paired with a nonempty execution value without binding provenance. */
private fun verifiedReferenceRange(authored: String, executed: String?): IntRange? {
  if (executed == null) return null
  val range = authoredReferenceRanges(authored).singleOrNull() ?: return null
  val prefix = authored.substring(0, range.first)
  val suffix = authored.substring(range.last + 1)
  val bindingLength = executed.length - prefix.length - suffix.length
  if (bindingLength <= 0 || !executed.startsWith(prefix) || !executed.endsWith(suffix)) return null
  val token = authored.substring(range)
  return range.takeIf { !executed.contains(token) &&
    executed.substring(prefix.length, prefix.length + bindingLength).isNotBlank() }
}

/** Launch arguments can contain credentials. Recognize both authored YAML and Maestro log JSON. */
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

internal fun maestroLaunchArgumentSecrets(yaml: String): List<String> =
  runCatching {
    maestroLaunchArgumentSecrets(
      YamlJsonBridge.yamlNodeToJsonElement(Yaml.default.parseToYamlNode(yaml), coerceNumbers = false)
    )
  }.getOrDefault(emptyList())

private fun argumentValues(value: JsonElement?): List<String> = when (value) {
  is JsonObject -> value.values.flatMap(::argumentValues)
  is JsonArray -> value.flatMap(::argumentValues)
  is JsonPrimitive -> listOf(value.content).filter { it.isNotBlank() }
  else -> emptyList()
}

/** Authored literals and mixed values can recur verbatim in authored commands. */
internal fun maestroAuthoredLaunchLiterals(authored: JsonElement, executed: JsonElement): List<String> {
  return authoredLaunchArgumentPairs(authored, executed).flatMap { (text, resolved) ->
    val references = authoredReferenceRanges(text)
    if (references.isEmpty()) listOf(text)
    else if (references.singleOrNull() == text.indices) {
      // A whole reference is replayable only when it actually resolved. An unchanged token is
      // literal source text and must still be scrubbed if it appears in another command.
      if (resolved == null || resolved == text) listOf(text) else emptyList()
    }
    else {
      var cursor = 0
      val literalParts = buildList {
        for (range in references) {
          add(text.substring(cursor, range.first))
          cursor = range.last + 1
        }
        add(text.substring(cursor))
      }.filter { it.isNotBlank() }
      buildList {
        // The full resolved mixed value may have been authored again elsewhere. A literal
        // fragment anchors that match without treating every whole-reference value as authored.
        if (resolved != null && resolved != text && literalParts.any { it.length >= 3 }) add(resolved)
        addAll(literalParts)
        addAll(literalParts.map { it.trim { char -> !char.isLetterOrDigit() } }.filter { it.isNotEmpty() })
      }
    }
  }.distinct()
}

private fun authoredLaunchArgumentPairs(authored: JsonElement, executed: JsonElement?): List<Pair<String, String?>> =
  when (authored) {
    is JsonObject -> authored.flatMap { (key, child) ->
      val executedChild = (executed as? JsonObject)?.get(key)
      if (key == "launchApp" || key == "launchAppCommand") {
        val launch = child as? JsonObject
        val resolvedLaunch = executedChild as? JsonObject
        val arguments = launch?.get("arguments") ?: launch?.get("launchArguments")
        val resolvedArguments = resolvedLaunch?.get("arguments") ?: resolvedLaunch?.get("launchArguments")
        argumentPairs(arguments, resolvedArguments)
      } else authoredLaunchArgumentPairs(child, executedChild)
    }
    is JsonArray -> authored.flatMapIndexed { index, child ->
      authoredLaunchArgumentPairs(child, (executed as? JsonArray)?.getOrNull(index))
    }
    else -> emptyList()
  }

private fun argumentPairs(authored: JsonElement?, executed: JsonElement?): List<Pair<String, String?>> = when (authored) {
  is JsonObject -> authored.flatMap { (key, child) -> argumentPairs(child, (executed as? JsonObject)?.get(key)) }
  is JsonArray -> authored.flatMapIndexed { index, child -> argumentPairs(child, (executed as? JsonArray)?.getOrNull(index)) }
  is JsonPrimitive -> listOf(authored.content to (executed as? JsonPrimitive)?.content).filter { it.first.isNotBlank() }
  else -> emptyList()
}

internal fun JsonObject.withMaestroLaunchArgumentsRedacted(): JsonObject =
  if (maestroLaunchArgumentSecrets(this).isEmpty()) this else redactLaunches(this) as JsonObject

/** Preserve only references that demonstrably resolved in the executed command. */
internal fun JsonObject.withAuthoredMaestroLaunchArgumentsRedacted(executed: JsonObject): JsonObject =
  redactLaunches(this, executed) as JsonObject

/** Mask a launch value repeated in another command, without shredding unrelated short strings. */
internal fun JsonObject.withKnownMaestroLaunchValuesRedacted(
  values: Collection<String>,
  preserveReferences: Boolean,
  executed: JsonObject? = null,
): JsonObject = scrubKnownValues(
  this, values.filter { it.isNotBlank() && it != "true" && it != "false" }.sortedByDescending { it.length },
  preserveReferences, executed,
) as JsonObject

private fun scrubKnownValues(
  value: JsonElement,
  secrets: List<String>,
  preserveReferences: Boolean,
  executed: JsonElement? = null,
): JsonElement = when (value) {
  is JsonObject -> JsonObject(value.mapValues { (key, child) ->
    scrubKnownValues(child, secrets, preserveReferences, (executed as? JsonObject)?.get(key))
  })
  is JsonArray -> JsonArray(value.mapIndexed { index, child ->
    scrubKnownValues(child, secrets, preserveReferences, (executed as? JsonArray)?.getOrNull(index))
  })
  is JsonPrimitive -> {
    if (!value.isString) {
      if (value.content in secrets) JsonPrimitive(REDACTED_TOOL_ARG_PLACEHOLDER) else value
    } else if (value.content == REDACTED_TOOL_ARG_PLACEHOLDER) value else {
      val text = value.content
      val reference = if (preserveReferences) verifiedReferenceRange(text, (executed as? JsonPrimitive)?.content)
        ?.takeIf { range -> secrets.none { secret ->
          Regex(Regex.escape(secret)).findAll(text).any { match ->
            match.range.first <= range.last && match.range.last >= range.first &&
              (match.range.first < range.first || match.range.last > range.last || match.range == range)
          }
        } } else null
      val scrubbed = if (reference == null) redactKnownMaestroText(text, secrets) else
        redactKnownMaestroText(text.substring(0, reference.first), secrets) +
          text.substring(reference) +
          redactKnownMaestroText(text.substring(reference.last + 1), secrets)
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

private fun redactLaunches(value: JsonElement, executed: JsonElement? = null): JsonElement = when (value) {
  is JsonObject -> JsonObject(value.mapValues { (key, child) ->
    if (key == "sourceInfo" && child is JsonObject) {
      // Nested flows retain the whole source document, including otherwise-masked arguments.
      JsonObject(child.filterKeys { it != "source" }.mapValues { redactLaunches(it.value) })
    } else if ((key == "launchApp" || key == "launchAppCommand") && child is JsonObject) {
      val executedLaunch = (executed as? JsonObject)?.get(key) as? JsonObject
      JsonObject(child.mapValues { (field, argumentMap) ->
        if ((field == "arguments" || field == "launchArguments") && argumentMap is JsonObject) {
          val executedArguments = executedLaunch?.get(field) as? JsonObject
          JsonObject(argumentMap.mapValues { (name, authored) ->
            if (authored is JsonPrimitive && authored.isString &&
              verifiedReferenceRange(authored.content, (executedArguments?.get(name) as? JsonPrimitive)?.content) == authored.content.indices
            ) authored else JsonPrimitive(REDACTED_TOOL_ARG_PLACEHOLDER)
          })
        } else redactLaunches(argumentMap, executedLaunch?.get(field))
      })
    } else redactLaunches(child, (executed as? JsonObject)?.get(key))
  })
  is JsonArray -> JsonArray(value.mapIndexed { index, child ->
    redactLaunches(child, (executed as? JsonArray)?.getOrNull(index))
  })
  else -> value
}
