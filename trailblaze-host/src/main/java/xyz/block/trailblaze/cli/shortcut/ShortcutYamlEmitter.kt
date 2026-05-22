package xyz.block.trailblaze.cli.shortcut

import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.cli.shortcut.ShortcutProposer.ToolBody
import xyz.block.trailblaze.cli.yaml.TrailblazeNodeSelectorYamlEmitter
import xyz.block.trailblaze.cli.yaml.TrailblazeNodeSelectorYamlEmitter.yamlQuote

/**
 * Hand-rolled YAML emitter for the generated `*.shortcut.yaml` files. Lives separate
 * from `ToolYamlConfig` serialization because the `tools:` block is a `List<JsonObject>`
 * — kaml round-tripping through JsonObject loses the regex-string formatting that
 * authors expect, and the existing hand-authored shortcut YAMLs in the repo use a
 * specific indent + quoting style we want to match exactly.
 *
 * The selector-body recursion + per-field ladder lives in
 * [TrailblazeNodeSelectorYamlEmitter] so this emitter and `WaypointSuggestSelectorCommand`
 * can't drift on the field set the way they did when `isMultiLine` landed in one and not
 * the other. This emitter only owns the surrounding shortcut-file skeleton: id /
 * description / shortcut / parameters / tools block headers.
 */
object ShortcutYamlEmitter {

  fun emit(
    shortcutId: String,
    fromWaypointId: String,
    toWaypointId: String,
    description: String,
    body: ToolBody,
  ): String = buildString {
    appendLine("id: $shortcutId")
    appendLine("description: ${yamlQuote(description)}")
    appendLine("shortcut:")
    appendLine("  from: $fromWaypointId")
    appendLine("  to: $toWaypointId")
    appendLine("parameters: []")
    appendLine("tools:")
    emitToolBody(this, body)
  }

  private fun emitToolBody(sb: StringBuilder, body: ToolBody) {
    when (body) {
      is ToolBody.TapOnElementBySelector -> {
        sb.appendLine("  - tapOnElementBySelector:")
        sb.appendLine("      reason: \"\"")
        sb.appendLine("      nodeSelector:")
        emitSelectorBody(sb, body.selector, indent = 8)
      }
      is ToolBody.Scroll -> {
        sb.appendLine("  - scroll:")
        sb.appendLine("      forward: ${body.forward}")
      }
      is ToolBody.Swipe -> {
        sb.appendLine("  - swipe:")
        sb.appendLine("      direction: ${yamlQuote(body.direction)}")
      }
      ToolBody.PressBack -> {
        sb.appendLine("  - pressBackButton: {}")
      }
      is ToolBody.InputText -> {
        sb.appendLine("  - inputText:")
        sb.appendLine("      text: ${yamlQuote(body.text)}")
      }
      ToolBody.HideKeyboard -> {
        sb.appendLine("  - hideKeyboard: {}")
      }
    }
  }

  private fun emitSelectorBody(sb: StringBuilder, selector: TrailblazeNodeSelector, indent: Int) {
    TrailblazeNodeSelectorYamlEmitter.emit(selector, indent) { sb.appendLine(it) }
  }
}
