package xyz.block.trailblaze.yaml.unified

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.builtins.ListSerializer
import xyz.block.trailblaze.yaml.serializers.TrailblazeToolYamlWrapperSerializer

/**
 * Hand-rolled YAML emitter for [UnifiedTrail]. The surrounding mapping/list
 * structure is composed as text, and inner tool lists are delegated to KAML
 * via the existing tool-wrapper serializer so per-tool formatting matches
 * what the recorder already writes.
 *
 * Why hand-rolled: a v3 step is a map whose values have different shapes per
 * key (string for `step`, boolean for `recordable`, list for each classifier).
 * Stock kotlinx-serialization needs a uniform value type per map; modeling the
 * union would add more complexity than the emit pipeline saves.
 */
internal class UnifiedTrailEmitter(
  private val yamlInstance: Yaml,
  private val toolWrapperSerializer: TrailblazeToolYamlWrapperSerializer,
) {
  fun emit(trail: UnifiedTrail, leadingComments: List<String>): String {
    val sb = StringBuilder()
    for (line in leadingComments) {
      for (subLine in line.split('\n')) {
        sb.append("# ").append(subLine).append('\n')
      }
    }
    if (leadingComments.isNotEmpty()) sb.append('\n')

    sb.append("config:\n")
    val configYaml = yamlInstance.encodeToString(
      UnifiedTrailConfig.serializer(),
      trail.config,
    )
    appendIndented(sb, configYaml, indent = INDENT_2)

    sb.append('\n')
    sb.append("trail:\n")
    trail.trail.forEachIndexed { idx, step ->
      if (idx > 0) sb.append('\n')
      appendStep(sb, step)
    }
    if (!sb.endsWith('\n')) sb.append('\n')
    return sb.toString()
  }

  private fun appendStep(sb: StringBuilder, step: UnifiedTrailStep) {
    sb.append(INDENT_2).append("- step: ")
    appendScalar(sb, step.step, blockIndent = INDENT_6)
    for ((classifier, tools) in step.recordings) {
      sb.append(INDENT_4).append(classifier).append(':')
      if (tools.isEmpty()) {
        sb.append(" []\n")
      } else {
        sb.append('\n')
        val toolListYaml = yamlInstance.encodeToString(
          ListSerializer(toolWrapperSerializer),
          tools,
        )
        appendIndented(sb, toolListYaml, indent = INDENT_6)
      }
    }
    if (!step.recordable) {
      sb.append(INDENT_4).append("recordable: false\n")
    }
    step.maxRetries?.let { sb.append(INDENT_4).append("maxRetries: ").append(it).append('\n') }
  }

  /**
   * Append [text] with every non-empty line prefixed by [indent]. Trailing
   * blank lines are dropped; a trailing newline is always written so the
   * caller can append the next sibling key on a fresh line.
   */
  private fun appendIndented(sb: StringBuilder, text: String, indent: String) {
    val lines = text.split('\n').dropLastWhile { it.isEmpty() }
    for (line in lines) {
      if (line.isEmpty()) {
        sb.append('\n')
      } else {
        sb.append(indent).append(line).append('\n')
      }
    }
  }

  /**
   * Append a string scalar suitable for an inline `step: <value>` line.
   * Multi-line strings emit as YAML literal-block style with each line
   * indented by [blockIndent]. Single-line strings emit as a quoted scalar so
   * we never accidentally produce ambiguous YAML.
   *
   * Chomping indicator picked per the value's trailing-newline structure so
   * the round-trip is byte-exact:
   *  - no trailing newline → `|-` (strip)
   *  - exactly one trailing newline → `|` (clip — adds one back on parse)
   *  - two or more trailing newlines → `|+` (keep all)
   */
  private fun appendScalar(sb: StringBuilder, value: String, blockIndent: String) {
    if (value.contains('\n')) {
      val chomp = when {
        !value.endsWith('\n') -> "-"
        value.endsWith("\n\n") -> "+"
        else -> ""
      }
      sb.append("|").append(chomp).append('\n')
      val lines = value.split('\n').dropLastWhile { it.isEmpty() }
      for (line in lines) {
        if (line.isEmpty()) {
          sb.append('\n')
        } else {
          sb.append(blockIndent).append(line).append('\n')
        }
      }
    } else {
      sb.append('"')
      for (ch in value) {
        when (ch) {
          '\\' -> sb.append("\\\\")
          '"' -> sb.append("\\\"")
          '\t' -> sb.append("\\t")
          '\r' -> sb.append("\\r")
          else -> sb.append(ch)
        }
      }
      sb.append('"').append('\n')
    }
  }

  companion object {
    private const val INDENT_2 = "  "
    private const val INDENT_4 = "    "
    private const val INDENT_6 = "      "
  }
}
