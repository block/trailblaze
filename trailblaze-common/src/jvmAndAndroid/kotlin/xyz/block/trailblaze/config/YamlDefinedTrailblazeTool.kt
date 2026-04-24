package xyz.block.trailblaze.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import xyz.block.trailblaze.toolcalls.DelegatingTrailblazeTool
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.InstanceNamedTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.yaml.TrailblazeYaml

/**
 * A Trailblaze tool whose behavior is defined entirely in YAML via the `tools:` authoring mode
 * (see [ToolYamlConfig]).
 *
 * At execute time, walks the YAML's `tools:` JSON tree, substitutes `{{params.x}}` tokens with
 * caller-supplied parameter values (plus declared defaults), encodes the result back to YAML,
 * and decodes it via [TrailblazeYaml.decodeTools] to produce a list of executable tools.
 *
 * Mirrors the expansion pattern already used by
 * `xyz.block.trailblaze.scripting.ScriptTrailblazeTool` — just with static param substitution
 * instead of a JavaScript source.
 *
 * This class is NOT directly serializable via a reflected `@Serializable` annotation; each
 * YAML-defined tool name registers a [YamlDefinedToolSerializer] instance pre-bound to its
 * [ToolYamlConfig]. That serializer reads the caller's params from the incoming YAML/JSON and
 * constructs a [YamlDefinedTrailblazeTool] with the right config + params attached.
 */
class YamlDefinedTrailblazeTool(
  val config: ToolYamlConfig,
  val params: Map<String, JsonElement>,
) : DelegatingTrailblazeTool, InstanceNamedTrailblazeTool {

  override val instanceToolName: String get() = config.id

  override fun toExecutableTrailblazeTools(
    executionContext: TrailblazeToolExecutionContext,
  ): List<ExecutableTrailblazeTool> {
    val resolvedParams = resolveParamValues()
    val toolsList = config.toolsList
      ?: error(
        "YAML tool '${config.id}' has no 'tools:' block — cannot expand. This is a bug in the " +
          "tool loader; a ToolYamlConfig without 'tools:' should not have produced a " +
          "YamlDefinedTrailblazeTool.",
      )

    val expandedArray = buildJsonArray {
      toolsList.forEach { add(substitute(it, resolvedParams)) }
    }

    val yaml = TrailblazeYaml.jsonToYaml(expandedArray)
    val decoded = TrailblazeYaml.Default.decodeTools(yaml)
    return decoded.mapIndexed { index, wrapper ->
      val tool = wrapper.trailblazeTool
      tool as? ExecutableTrailblazeTool
        ?: error(
          "YAML tool '${config.id}' expanded tool at index $index (name='${wrapper.name}') is " +
            "not an ExecutableTrailblazeTool — got ${tool::class.simpleName}. Only executable " +
            "primitives are supported in tools: mode expansion.",
        )
    }
  }

  /**
   * Resolves each declared parameter to one of:
   * - the caller-supplied value (if present in [params]);
   * - the declared [DefaultBehavior.Use] value (if the caller omitted and a default was declared);
   * - a sentinel [OMIT_SENTINEL] meaning "drop this key when substituting."
   *
   * Required params missing from the caller throw.
   */
  private fun resolveParamValues(): Map<String, JsonElement> {
    val out = LinkedHashMap<String, JsonElement>()
    config.parameters.forEach { p ->
      val caller = params[p.name]
      when {
        caller != null -> out[p.name] = caller
        p.required -> error(
          "YAML tool '${config.id}' is missing required parameter '${p.name}'.",
        )
        else -> when (val d = p.default) {
          DefaultBehavior.DropIfOmitted -> out[p.name] = OMIT_SENTINEL
          is DefaultBehavior.Use -> out[p.name] = d.value
        }
      }
    }
    return out
  }

  private fun substitute(element: JsonElement, resolved: Map<String, JsonElement>): JsonElement =
    when (element) {
      is JsonObject -> buildJsonObject {
        element.forEach { (key, value) ->
          val substituted = substitute(value, resolved)
          if (substituted !== OMIT_SENTINEL) put(key, substituted)
        }
      }
      is JsonArray -> buildJsonArray {
        element.forEach { item ->
          val substituted = substitute(item, resolved)
          if (substituted !== OMIT_SENTINEL) add(substituted)
        }
      }
      is JsonPrimitive -> substitutePrimitive(element, resolved)
    }

  /**
   * Substitutes `{{params.x}}` tokens in a string primitive.
   *
   * - If the entire string is a single `{{params.x}}` token, returns the resolved value as its
   *   native JSON type (int stays int, object stays object, etc.) — preserves typing for
   *   whole-value substitution.
   * - If the token is the only content AND resolves to [OMIT_SENTINEL], propagates the sentinel
   *   so the enclosing object/array can drop the key/item.
   * - Otherwise substitutes the token's string rendering inline.
   *
   * Non-string primitives pass through unchanged.
   */
  private fun substitutePrimitive(
    primitive: JsonPrimitive,
    resolved: Map<String, JsonElement>,
  ): JsonElement {
    if (!primitive.isString) return primitive
    val raw = primitive.content
    val wholeMatch = WHOLE_TOKEN_REGEX.matchEntire(raw)
    if (wholeMatch != null) {
      val (prefix, name) = parseToken(wholeMatch.groupValues[1])
      require(prefix == "params") { unsupportedPrefixMessage(prefix, name) }
      val value = resolved[name]
        ?: error("YAML tool '${config.id}' references unknown parameter '$name' in {{params.$name}}")
      return value
    }
    return JsonPrimitive(
      EMBEDDED_TOKEN_REGEX.replace(raw) { match ->
        val (prefix, name) = parseToken(match.groupValues[1])
        require(prefix == "params") { unsupportedPrefixMessage(prefix, name) }
        val value = resolved[name]
          ?: error(
            "YAML tool '${config.id}' references unknown parameter '$name' in {{params.$name}}",
          )
        if (value === OMIT_SENTINEL) ""
        else when (value) {
          is JsonPrimitive -> value.content
          else -> value.toString()
        }
      },
    )
  }

  private fun parseToken(body: String): Pair<String, String> {
    val trimmed = body.trim()
    val dot = trimmed.indexOf('.')
    require(dot > 0 && dot < trimmed.length - 1) {
      "YAML tool '${config.id}' has a malformed interpolation token '{{ $trimmed }}' — expected " +
        "'{{params.name}}' / '{{memory.name}}' / '{{device.name}}'."
    }
    val prefix = trimmed.substring(0, dot)
    val name = trimmed.substring(dot + 1)
    require(name.matches(ToolYamlConfig.PARAM_NAME_REGEX)) {
      "YAML tool '${config.id}' interpolation token '{{ $trimmed }}' has an invalid name " +
        "'$name' — must match ${ToolYamlConfig.PARAM_NAME_REGEX.pattern}."
    }
    return prefix to name
  }

  private fun unsupportedPrefixMessage(prefix: String, name: String): String = when (prefix) {
    "memory", "device" ->
      "YAML tool '${config.id}' uses {{$prefix.$name}} — memory/device interpolation is not yet " +
        "wired up in this build. Only {{params.*}} is supported today."
    else ->
      "YAML tool '${config.id}' uses unknown interpolation prefix '$prefix' in {{$prefix.$name}}. " +
        "Supported prefixes: params (today), memory/device (future)."
  }

  companion object {
    /** Sentinel used during substitution to signal "drop the enclosing map key or list item." */
    internal val OMIT_SENTINEL: JsonElement = JsonPrimitive("__TRAILBLAZE_YAML_TOOL_OMIT__")

    private val WHOLE_TOKEN_REGEX = Regex("""^\{\{\s*([^{}]+?)\s*\}\}$""")
    private val EMBEDDED_TOKEN_REGEX = Regex("""\{\{\s*([^{}]+?)\s*\}\}""")
  }
}
