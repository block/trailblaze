package xyz.block.trailblaze.yaml.unified

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlMap
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable

/**
 * One label on a trail: a fact about what the trail covers, such as `journey:checkout`.
 *
 * A trail carries two lists of them in its `config:` block. [UnifiedTrailConfig.labels] is curated:
 * a person decided each entry, and no tool changes it. [UnifiedTrailConfig.generatedLabels] is
 * written only by the survey command, from what the trail's runs showed. Keeping them under two
 * keys lets anyone reading one file tell which entries a person chose and which a survey produced.
 *
 * Fields are written in the order [type], [name], `when` ([whenDevices]), `except`
 * ([exceptDevices]), [survey], [reasoning]. `when` and `except` are declared last so the positional
 * accessors of the earlier fields stay put.
 *
 * A label holds for the whole trail unless it names devices. `when` and `except` scope it to some
 * of the trail's devices; [TrailLabelDevices] says how a value picks devices:
 * ```yaml
 * - { type: feature, name: second-screen, when: [lab-pair] }
 * - { type: feature, name: card-reader, when: [ios], except: [ios-ipad] }
 * ```
 */
@Serializable
data class TrailLabel(
  /**
   * What kind of label this is (`journey`, `feature`, `job`). A slug; see
   * [TrailLabels.TYPE_PATTERN].
   */
  val type: String,
  /** Which one, compared exactly. A single line; see [TrailLabels.MAX_NAME_LENGTH]. */
  val name: String,
  /**
   * The survey rule that produced a generated label, as `<survey>/<rule>`. Required on every
   * [UnifiedTrailConfig.generatedLabels] entry and never present on a curated one.
   */
  val survey: String? = null,
  /** Optional one line on why the label holds. */
  val reasoning: String? = null,
  /**
   * The devices this label holds on, written `when`. Absent means every device the trail declares.
   * Each value picks devices as [TrailLabelDevices] describes.
   */
  @SerialName("when") val whenDevices: List<String>? = null,
  /** Devices taken back out of [whenDevices] (or out of every device), written `except`. */
  @SerialName("except") val exceptDevices: List<String>? = null,
) {
  /** The `type:name` form the CLI reads and writes. */
  val key: String
    get() = "$type:$name"
}

/** Shared rules and the canonical YAML form for [TrailLabel] lists. */
object TrailLabels {
  /** The `config:` key holding curated labels. */
  const val CURATED_KEY: String = "labels"

  /** The `config:` key holding labels the survey command writes. */
  const val GENERATED_KEY: String = "generatedLabels"

  /** A label type: lowercase letters, digits, `-` and `.`, starting with a letter or digit. */
  val TYPE_PATTERN: Regex = Regex("^[a-z0-9][a-z0-9.-]*$")

  /** Longest label name accepted. Names come from runs and people, so the limit is generous. */
  const val MAX_NAME_LENGTH: Int = 200

  /** The order generated labels are written in: by type, then name, then survey. */
  val CANONICAL_ORDER: Comparator<TrailLabel> =
    compareBy<TrailLabel> { it.type }.thenBy { it.name }.thenBy { it.survey.orEmpty() }

  /**
   * The YAML block for one label list as the emitter writes it: the key line, then one flow mapping
   * per entry with fields in declaration order. One line per label keeps each diff line one label
   * change, so `git blame` dates each label. Indentation is relative to the `config:` block. An
   * empty list is written `key: []`, because a bare `key:` reads back as null.
   */
  fun renderBlock(key: String, labels: List<TrailLabel>): String = buildString {
    if (labels.isEmpty()) {
      append(key).append(": []\n")
      return@buildString
    }
    append(key).append(":\n")
    for (label in labels) append("  - ").append(renderEntry(label)).append('\n')
  }

  /**
   * One label as a single-line flow mapping: `{ type: journey, name: checkout }`, or with devices
   * `{ type: feature, name: second-screen, when: [lab-pair] }`.
   */
  fun renderEntry(label: TrailLabel): String {
    val fields = buildList {
      add("type" to flowScalar(label.type))
      add("name" to flowScalar(label.name))
      label.whenDevices?.let { add("when" to flowSequence(it)) }
      label.exceptDevices?.let { add("except" to flowSequence(it)) }
      label.survey?.let { add("survey" to flowScalar(it)) }
      label.reasoning?.let { add("reasoning" to flowScalar(it)) }
    }
    return fields.joinToString(", ", prefix = "{ ", postfix = " }") { (k, v) -> "$k: $v" }
  }

  /** [values] as a one-line flow sequence, `[a, b]`, each item quoted as [flowScalar] quotes it. */
  private fun flowSequence(values: List<String>): String =
    values.joinToString(", ", prefix = "[", postfix = "]") { flowScalar(it) }

  /**
   * [value] as a scalar that is safe inside a flow mapping: plain when it reads back as the same
   * string, double-quoted otherwise. Quoted whenever it holds a flow indicator, a `: ` or ` #`, or
   * would read back as something other than a string in any common YAML reader (`null`, `true`,
   * `001`, `2026-11-03`). The web editor's js-yaml reads `001` as a number and a date-shaped value
   * as a date, so anything starting with a digit, or with `+` or `.` then a digit, is quoted. The
   * web editor writes labels with a copy of this rule, in the Trail Runner web app's
   * `trail-model.js`; keep the two identical.
   */
  private fun flowScalar(value: String): String =
    if (
      PLAIN_SAFE.matches(value) &&
        value.lowercase() !in RESERVED_PLAIN &&
        !NUMBER_LIKE.containsMatchIn(value)
    ) {
      value
    } else {
      doubleQuoted(value)
    }

  private val NUMBER_LIKE: Regex =
    Regex("^(?:[0-9]|[+.][0-9]|\\.(?:inf|nan)$)", RegexOption.IGNORE_CASE)

  // No leading `-` (a sequence entry), `@` or `` ` `` (reserved indicators); no `:`, `,`, `#`,
  // brackets or braces anywhere; no leading or trailing space.
  private val PLAIN_SAFE: Regex =
    Regex("^[A-Za-z0-9_./()+](?:[A-Za-z0-9 _./@()+'-]*[A-Za-z0-9_./@()+'-])?$")

  private val RESERVED_PLAIN: Set<String> =
    setOf("null", "~", "true", "false", "yes", "no", "on", "off", "y", "n")

  private fun doubleQuoted(value: String): String = buildString {
    append('"')
    for (ch in value) {
      when (ch) {
        '\\' -> append("\\\\")
        '"' -> append("\\\"")
        '\n' -> append("\\n")
        '\t' -> append("\\t")
        '\r' -> append("\\r")
        // Every other control or line-breaking character as an escape, so the line stays one line
        // of valid YAML: raw C0/C1 controls are not printable YAML, and U+2028/U+2029 break lines.
        else ->
          if (
            ch < ' ' ||
              ch in '\u007F'..'\u009F' ||
              ch == '\u2028' ||
              ch == '\u2029' ||
              ch == '\uFEFF'
          ) {
            append("\\u").append(ch.code.toString(16).padStart(4, '0'))
          } else {
            append(ch)
          }
      }
    }
    append('"')
  }

  /**
   * The two `config` label lists of a trail's [text], keyed [CURATED_KEY] and [GENERATED_KEY], read
   * on their own so nothing else in the file (a template in a typed field, an unknown key) stops
   * them being read. Templates are masked, never expanded, and put back in each label. A list is
   * null when its key is absent or `null`, and a failure when it does not decode. Null when [text]
   * is not YAML with a `config:` map.
   */
  fun readLists(text: String): Map<String, Result<List<TrailLabel>?>>? {
    val mask = TemplateMask.of(text)
    val root =
      runCatching { LENIENT_YAML.parseToYamlNode(mask.masked) }.getOrNull() as? YamlMap
        ?: return null
    val config =
      root.entries.entries.firstOrNull { it.key.content == "config" }?.value as? YamlMap
        ?: return null
    return listOf(CURATED_KEY, GENERATED_KEY).associateWith { key ->
      val node =
        config.entries.entries.firstOrNull { it.key.content == key }?.value
          ?: return@associateWith Result.success(null)
      runCatching {
        LENIENT_YAML.decodeFromYamlNode(ListSerializer(TrailLabel.serializer()).nullable, node)
          ?.map(mask::restore)
      }
    }
  }

  private val LENIENT_YAML = Yaml(configuration = YamlConfiguration(strictMode = false))

  /**
   * A trail's text with each `{{VAR}}` template swapped for a placeholder, so tools that only read
   * the labels can parse a file whose templates are not plain YAML, without expanding them.
   * Expanding would write runtime values (a path, an environment variable's secret) back into the
   * labels. [restore] puts each template back exactly as written.
   */
  class TemplateMask
  private constructor(
    val masked: String,
    private val originals: List<String>,
    private val placeholder: Regex,
  ) {
    fun restore(value: String): String =
      if (originals.isEmpty()) value
      else placeholder.replace(value) { originals[it.groupValues[1].toInt()] }

    fun restore(label: TrailLabel): TrailLabel =
      label.copy(
        type = restore(label.type),
        name = restore(label.name),
        survey = label.survey?.let(::restore),
        reasoning = label.reasoning?.let(::restore),
        whenDevices = label.whenDevices?.map(::restore),
        exceptDevices = label.exceptDevices?.map(::restore),
      )

    companion object {
      private val TEMPLATE = Regex("""\{\{[^{}]*\}\}""")

      fun of(text: String): TemplateMask {
        // A prefix the text never contains, so restoring cannot touch anything the author wrote.
        var prefix = "trailblaze-template-"
        while (prefix in text) prefix += "x"
        val originals = mutableListOf<String>()
        val masked =
          TEMPLATE.replace(text) {
            originals += it.value
            "$prefix${originals.size - 1}t"
          }
        return TemplateMask(masked, originals, Regex(Regex.escape(prefix) + """(\d+)t"""))
      }
    }
  }
}
