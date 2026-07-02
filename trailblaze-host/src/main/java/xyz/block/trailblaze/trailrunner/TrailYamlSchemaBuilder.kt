package xyz.block.trailblaze.trailrunner

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Builds the JSON Schema that yaml-language-server validates `.trail.yaml` files against — the trail
 * counterpart to [ToolYamlSchemaBuilder]. The high-value, low-risk half is **tool-name + parameter
 * completion inside the `recording:` → `tools:` blocks**, scoped to the tools that actually register
 * for the trail's **target** (the `.tool.yaml` schema scopes by trailmap; a trail scopes by target).
 *
 * **Deliberately permissive on structure.** A `.trail.yaml` comes in two on-disk shapes — the common
 * legacy **v1** (a YAML *list* of `- config:` / `- prompts:` / `- tools:` items) and the newer
 * **unified** format (a `config:` + `trail:` *mapping*). The schema is a `oneOf` over both, and every
 * structural object stays open (`additionalProperties: true`, few `required`) so a valid trail is
 * never red-squiggled just because the schema didn't model a field. That matters more here than for
 * `.tool.yaml`: falsely flagging a real, runnable trail would be a regression against today's editor
 * (which only does YAML-syntax + a server-side semantic lint). Even the tool-call item — reused from
 * [ToolYamlSchemaBuilder.toolCallItemSchema] — is built with `closedToolNames = false` here (unlike
 * `.tool.yaml`, which closes it for typo detection): a recorded `tools:` block legitimately carries
 * framework primitives and non-LLM-facing tools outside any target set, and recordings are already
 * validated, so completion is offered without ever flagging an unknown tool name.
 *
 * Pure (catalog + target tool-name set in, schema-JSON string out) so it's unit-tested without a
 * daemon — see `TrailYamlSchemaBuilderTest`.
 */
object TrailYamlSchemaBuilder {

  /**
   * @param entries the full tool catalog (see [ToolCatalogBuilder]).
   * @param targetToolNames the tool ids that register for this trail's target+driver (from
   *   `buildRunToolsResponse`); when null (target unresolvable) we fall back to the whole catalog so
   *   completion still works — over-offering is far better than an empty/erroring schema.
   */
  fun build(entries: List<ToolCatalogEntry>, targetToolNames: Set<String>?): String {
    // Framework tools (e.g. `mobile_maestro`, `assertVisibleWithText`, `launchApp`) are always in
    // completion scope: they're not in a target's LLM-facing registered set, yet they're exactly the
    // primitives that show up in recorded `tools:` blocks. Mirrors ToolYamlSchemaBuilder's framework
    // inclusion so both editors complete the same framework surface.
    val inScope = entries
      .filter { targetToolNames == null || it.id in targetToolNames || it.trailmap == ToolYamlSchemaBuilder.FRAMEWORK_TRAILMAP }
      .distinctBy { it.id }
      .sortedBy { it.id }
    return JSON.encodeToString(JsonObject.serializer(), schema(inScope))
  }

  private fun schema(tools: List<ToolCatalogEntry>): JsonObject = buildJsonObject {
    put("\$schema", "http://json-schema.org/draft-07/schema#")
    put("\$id", "https://trailblaze.block.xyz/schemas/trail.schema.json")
    put("title", "Trailblaze trail definition (.trail.yaml)")
    put(
      "description",
      "A Trailblaze trail. Two accepted shapes: the legacy v1 list of `config:`/`prompts:`/`tools:` " +
        "items, or the unified `config:` + `trail:` mapping. Tool calls inside `recording:` blocks " +
        "autocomplete + validate against the tools registered for this trail's target.",
    )
    // Open tool-name set (closedToolNames=false): known tools autocomplete, but an unknown/framework
    // tool in a recording is never flagged — see the kdoc on toolCallItemSchema.
    val toolCallItem = ToolYamlSchemaBuilder.toolCallItemSchema(tools, closedToolNames = false)
    putJsonArray("oneOf") {
      add(v1ListSchema(toolCallItem))
      add(unifiedMappingSchema())
    }
  }

  // ── v1: a YAML list of config / prompts / tools items ─────────────────────────────────────────────
  private fun v1ListSchema(toolCallItem: JsonObject): JsonObject = buildJsonObject {
    put("type", "array")
    put("description", "Legacy v1 trail: an ordered list of `config:`, `prompts:`, and `tools:` items.")
    putJsonObject("items") {
      // One list item. Modeled as a single OPEN object rather than a strict `oneOf` of the three known
      // kinds: an item carries one of `config:`/`prompts:`/`tools:` (each validated + completed via the
      // properties below), but `additionalProperties: true` + no `required` means any other/future item
      // shape (e.g. a `- trailhead:` item) passes instead of tripping a false "matches no branch" error.
      put("type", "object")
      put("additionalProperties", true)
      putJsonObject("properties") {
        put("config", configObjectSchema())
        putJsonObject("prompts") {
          put("type", "array")
          put("description", "Ordered natural-language steps. A `step:` performs an action; a `verify:` asserts state.")
          put("items", promptStepSchema(toolCallItem))
        }
        putJsonObject("tools") {
          put("type", "array")
          put("description", "A block of tool calls run outside a prompt step (setup/teardown).")
          put("items", toolCallItem)
        }
      }
    }
  }

  /** The `config:` value schema — known [xyz.block.trailblaze.yaml.TrailConfig] fields for completion,
   * open (`additionalProperties: true`) so a new/unknown config field never false-flags a valid trail. */
  private fun configObjectSchema(): JsonObject = buildJsonObject {
    put("type", "object")
    put("additionalProperties", true)
    putJsonObject("properties") {
      stringField("context", "Extra context added to the system prompt for every step.")
      stringField("id", "Stable trail id.")
      stringField("title", "Human-readable trail title.")
      stringField("description", "Longer description of what the trail does.")
      stringField("priority", "Priority label (e.g. P0…P3).")
      stringField("target", "Target identifier: an org alias, a package id, or a web URL.")
      stringField("platform", "Platform hint for device selection — commonly `android`, `ios`, or `web`.")
      stringField("driver", "Explicit driver type (e.g. `ANDROID_ONDEVICE_ACCESSIBILITY`, `IOS_HOST`); takes precedence over platform.")
      stringField("skip", "When set, mark the trail skipped with this reason (empty string = not skipped).")
      putJsonObject("tags") { put("type", "array"); put("description", "Free-form labels for grouping/filtering."); putJsonObject("items") { put("type", "string") } }
      putJsonObject("metadata") { put("type", "object"); put("description", "Arbitrary string metadata (e.g. external test-case ids)."); put("additionalProperties", true) }
      putJsonObject("memory") { put("type", "object"); put("description", "Pre-seeded AgentMemory variables, visible to {{name}} interpolation."); put("additionalProperties", true) }
    }
  }

  /**
   * One prompt step: a `step:` OR a `verify:` (mirrors DirectionStep / VerificationStep), each with the
   * same optional `recording:` / `recordable:` / `postcondition:` / `maxRetries:` fields. Open per-step
   * so we never flag a valid step; the completion lives in `recording.tools`.
   */
  private fun promptStepSchema(toolCallItem: JsonObject): JsonObject = buildJsonObject {
    put("type", "object")
    put("additionalProperties", true)
    putJsonObject("properties") {
      stringField("step", "An action to perform, in natural language.")
      stringField("verify", "An assertion about the current screen, in natural language.")
      putJsonObject("recordable") { put("type", "boolean"); put("description", "Whether this step is recorded/replayed. Defaults to true.") }
      putJsonObject("maxRetries") { put("type", "integer"); put("description", "Per-step AI retry budget override.") }
      putJsonObject("postcondition") { put("type", "object"); put("description", "A condition that must hold after this step (e.g. an assertion waypoint)."); put("additionalProperties", true) }
      putJsonObject("recording") {
        put("type", "object")
        put("description", "Recorded tool calls that replay this step deterministically.")
        put("additionalProperties", true)
        putJsonObject("properties") {
          putJsonObject("tools") {
            put("type", "array")
            put("description", "Ordered tool calls this step replays. Autocompletes + validates against the trail's target tools.")
            put("items", toolCallItem)
          }
        }
      }
    }
  }

  /**
   * Unified format: a mapping with `config:` + `trail:`. Kept intentionally loose — the unified step
   * shape carries per-device-classifier recordings whose keys are dynamic, so deeply modelling it here
   * would risk false errors for little completion gain. We validate only that the two top-level keys
   * are objects/arrays and leave their interiors open. (Tool-completion for unified recordings is a
   * follow-up once the format is more common.)
   */
  private fun unifiedMappingSchema(): JsonObject = buildJsonObject {
    put("type", "object")
    put("description", "Unified trail: a `config:` mapping plus a `trail:` list of steps.")
    putJsonArray("required") { add("config"); add("trail") }
    put("additionalProperties", true)
    putJsonObject("properties") {
      putJsonObject("config") { put("type", "object"); put("additionalProperties", true) }
      putJsonObject("trail") { put("type", "array") }
    }
  }

  private fun JsonObjectBuilder.stringField(name: String, description: String) {
    putJsonObject(name) { put("type", "string"); put("description", description) }
  }
}
