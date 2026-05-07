package xyz.block.trailblaze.recording

import xyz.block.trailblaze.yaml.TrailYamlItem
import xyz.block.trailblaze.yaml.TrailblazeToolYamlWrapper
import xyz.block.trailblaze.yaml.TrailblazeYaml
import xyz.block.trailblaze.yaml.wrapTrailblazeTool

/**
 * Pure encode/decode helpers between [RecordedInteraction] and trail YAML — moved out of
 * `InteractionRecorder.Companion` so they're reachable from any commonMain caller (including
 * a future wasmJs client) without dragging in the recorder's JVM-only instance dependencies
 * (`TrailblazeLogger`, `synchronized`).
 *
 * Three shapes are produced:
 *
 *  - **Single tool, bare**: `name:\n  field: value`. Used by the in-card YAML editor and the
 *    Tool Palette's preview text; minimal and readable.
 *  - **Single tool, trail-wrapped**: `- tools:\n    - name:\n        field: value`. The
 *    runnable form `TrailblazeDeviceManager.runYaml` accepts; what Replay and the Tool
 *    Palette's "Run on Device" button feed to the runner.
 *  - **Multi tool, trail-wrapped**: same shape with multiple tool entries. The replay-from-
 *    here button bundles the tail of the recording this way.
 *
 * The decode side ([decodeSingleToolYaml]) parses a bare-single-tool block (as the in-card
 * editor and the Tool Palette emit) by wrapping it in `- ` first, since `TrailblazeYaml.decodeTools`
 * only accepts the list form. The wrap-as-list-item indent rule must match the encode side
 * exactly — without that match, the in-card editor's "edit then save" path would parse what
 * it just rendered as malformed YAML.
 */
object RecordingYamlCodec {
  /**
   * Routes through [TrailblazeYaml.Default], which is multiplatform via expect/actual: on JVM
   * it discovers every classpath-registered tool serializer; on wasmJs it currently returns an
   * empty registry (no classpath discovery). That's enough for any code path that hands the
   * codec a [RecordedInteraction] whose tool was constructed from a registered class — the
   * tool already carries its serializer reference via `wrapTrailblazeTool`.
   *
   * For wasmJs callers that need to round-trip novel tools, the open path is to extend the
   * wasmJs `actual fun buildTrailblazeYamlDefault()` with the imperative tool registration
   * those callers care about. Out of scope here; commonMain compatibility lands first.
   */
  private val trailblazeYaml: TrailblazeYaml get() = TrailblazeYaml.Default

  /** Serialize a single recorded interaction's tool to bare YAML (no list wrapping). */
  fun singleToolToYaml(interaction: RecordedInteraction): String =
    trailblazeYaml.encodeToolToYaml(interaction.toolName, interaction.tool)

  /**
   * Serialize one [interaction] as a complete, runnable trail YAML — wrapped in `- tools:`
   * so it parses through the same path the trail runner uses for full trails.
   */
  fun singleInteractionToTrailYaml(interaction: RecordedInteraction): String {
    val wrapper = wrapTrailblazeTool(interaction.tool, interaction.toolName)
    val item = TrailYamlItem.ToolTrailItem(listOf(wrapper))
    return trailblazeYaml.encodeToString(listOf(item))
  }

  /**
   * Serialize a contiguous range of [interactions] as a single trail YAML — one `tools:` block
   * containing every tool in order. Empty input yields an empty string. Encoding via the same
   * `ToolTrailItem` shape as [singleInteractionToTrailYaml] keeps the runner-side parser path
   * identical regardless of how many tools are in the slice.
   */
  fun interactionsToTrailYaml(interactions: List<RecordedInteraction>): String {
    if (interactions.isEmpty()) return ""
    val wrappers = interactions.map { wrapTrailblazeTool(it.tool, it.toolName) }
    val item = TrailYamlItem.ToolTrailItem(wrappers)
    return trailblazeYaml.encodeToString(listOf(item))
  }

  /**
   * Serialize a [trailhead] tool reference followed by [interactions] as a single trail YAML.
   * The trailhead is emitted as a bare tool-id reference (`- <trailheadId>`) since trailheads
   * today are class-backed tools with no instance args; the interactions follow with their
   * usual full encoding. Output:
   *
   * ```yaml
   * - tools:
   *     - <trailheadId>
   *     - <interaction-1-name>:
   *         <fields>
   *     - <interaction-2-name>:
   *         <fields>
   * ```
   *
   * Backs the recording tab's "Verify Trail" button, which needs a runnable trail that resets
   * the device to a known starting state via the trailhead before re-running the captured
   * tools. With no [trailhead] this would be equivalent to [interactionsToTrailYaml] — but
   * the Verify Trail caller is expected to gate on `trailhead != null` before reaching here
   * (no-trailhead Verify is the friction-discovery moment for the trailhead nudge).
   *
   * Implemented by encoding the interactions block via the typed serializer and splicing the
   * trailhead id into the resulting `tools:` list. Hand-rolling the whole document would
   * require instantiating a `TrailblazeTool` for the trailhead (it's class-backed via
   * reflection — fine on JVM, brittle for future wasmJs use); splicing the text leaves
   * the encoder responsible for the interaction tools' indentation and quoting rules.
   */
  fun interactionsToTrailYamlWithTrailhead(
    trailheadToolId: String,
    interactions: List<RecordedInteraction>,
    trailheadParamValues: Map<String, String> = emptyMap(),
  ): String {
    require(trailheadToolId.isNotBlank()) { "trailheadToolId must not be blank" }
    val trailheadBlock = renderTrailheadBlock(trailheadToolId, trailheadParamValues)
    val baseYaml = interactionsToTrailYaml(interactions)
    if (baseYaml.isBlank()) {
      // No interactions — just the trailhead by itself.
      return "- tools:\n$trailheadBlock"
    }
    // Splice the trailhead as the first list item under `tools:`. The encoder produces
    // `- tools:` on its own line with each tool entry indented under it; we find the line
    // immediately after `tools:` and insert our own list item before it. The 4-space indent
    // matches what kaml emits for nested list items in this schema.
    val lines = baseYaml.lineSequence().toMutableList()
    val toolsLineIndex = lines.indexOfFirst { it.trimEnd().endsWith("tools:") }
    require(toolsLineIndex >= 0) {
      "Expected encoded interactions trail to contain a 'tools:' header; got: $baseYaml"
    }
    lines.add(toolsLineIndex + 1, trailheadBlock.trimEnd('\n'))
    return lines.joinToString("\n")
  }

  /**
   * Render a trailhead as a list item under a `tools:` header, with the trailing newline.
   *
   * - No params: `    - toolName\n` (bare-name form, what kaml emits for parameter-less tools)
   * - With params: `    - toolName:\n        param1: value\n        param2: value\n`
   *
   * The indentation lines up with what kaml produces for nested tool entries: `    -` opens
   * the list item, `        ` (8 spaces) indents the param scalars under the tool name. Param
   * values are wrapped in single quotes so colons, hashes, and other YAML metacharacters in
   * user-supplied strings don't get interpreted as structure (`account: foo:bar` would
   * otherwise reparse as a nested map).
   */
  private fun renderTrailheadBlock(toolId: String, paramValues: Map<String, String>): String {
    val nonBlankParams = paramValues.filterValues { it.isNotBlank() }
    if (nonBlankParams.isEmpty()) {
      return "    - $toolId\n"
    }
    val sb = StringBuilder()
    sb.append("    - ").append(toolId).append(":\n")
    nonBlankParams.forEach { (name, value) ->
      val quoted = value.replace("\\", "\\\\").replace("'", "''")
      sb.append("        ").append(name).append(": '").append(quoted).append("'\n")
    }
    return sb.toString()
  }

  /**
   * Inverse of [singleToolToYaml]: decode bare single-tool YAML (`name:\n  field: value`)
   * into a [TrailblazeToolYamlWrapper]. Throws if the YAML is malformed or doesn't decode to
   * exactly one tool. Callers wrap in [runCatching] when they want inline error display
   * rather than propagation.
   */
  fun decodeSingleToolYaml(singleToolYaml: String): TrailblazeToolYamlWrapper {
    val asListItem = singleToolYaml.trimEnd().lineSequence()
      .withIndex().joinToString("\n") { (i, line) -> if (i == 0) "- $line" else "  $line" }
    val wrappers = trailblazeYaml.decodeTools(asListItem)
    return wrappers.singleOrNull()
      ?: error("Expected exactly one tool definition; got ${wrappers.size}")
  }
}
