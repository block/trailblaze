package xyz.block.trailblaze.host

import xyz.block.trailblaze.yaml.createTrailblazeYaml
import xyz.block.trailblaze.yaml.unified.TrailDocument
import xyz.block.trailblaze.yaml.unified.TrailLabel
import xyz.block.trailblaze.yaml.unified.TrailLabelDevices
import xyz.block.trailblaze.yaml.unified.TrailLabels
import xyz.block.trailblaze.yaml.unified.UnifiedTrail

/**
 * Gate on a trail's `config.labels` and `config.generatedLabels`.
 *
 * Both lists: every `type` is a slug ([TrailLabels.TYPE_PATTERN]), every `name` one trimmed line of
 * at most [TrailLabels.MAX_NAME_LENGTH] characters, and no entry repeats. Curated entries never carry
 * `survey`, because a person wrote them; generated entries always do, because a survey did. A
 * label's `when` and `except` must each name at least one of the trail's devices and, together,
 * leave at least one ([TrailLabelDevices]).
 *
 * `generatedLabels` must also be written exactly as the survey command writes it: one line per
 * entry, fields in the order `type`, `name`, `when`, `except`, `survey`, `reasoning`, sorted by
 * [TrailLabels.CANONICAL_ORDER]. A tool rewrites that block, so any other layout is churn in the next
 * rewrite's diff. The finding prints the canonical block to paste.
 */
object TrailLabelLint {

  /** Env kill-switch: `1`/`true` (case-insensitive) skips the gate entirely. */
  const val DISABLE_ENV_VAR: String = "TRAILBLAZE_DISABLE_TRAIL_LABEL_GATE"

  /** One finding per offending trail. */
  data class Finding(
    val trailRelPath: String,
    /** One line per broken rule, naming the list and the entry. */
    val problems: List<String>,
    /** The `generatedLabels` block as it should read, when the file's block is not in that form. */
    val canonicalGeneratedBlock: String? = null,
  )

  /**
   * PURE. Lint one trail. [text] is the raw file, needed for the format check, since decoding
   * forgets layout; [trail] is the same file decoded. Returns null when both lists are clean.
   */
  fun lint(trailRelPath: String, text: String, trail: UnifiedTrail): Finding? =
    lintLists(
      trailRelPath,
      text,
      trail.config.labels.orEmpty(),
      trail.config.generatedLabels.orEmpty(),
      TrailLabelDevices.devicesOf(trail),
    )

  /**
   * [devices] is the trail's devices, or null when the trail could not be decoded; then `when` and
   * `except` are checked for shape only.
   */
  private fun lintLists(
    trailRelPath: String,
    text: String,
    curated: List<TrailLabel>,
    generated: List<TrailLabel>,
    devices: List<TrailLabelDevices.Device>?,
  ): Finding? {
    val problems = buildList {
      (curated.map { TrailLabels.CURATED_KEY to it } + generated.map { TrailLabels.GENERATED_KEY to it })
        .forEach { (listKey, label) ->
          val deviceProblems = if (devices == null) {
            TrailLabelDevices.valueProblems("when", label.whenDevices) +
              TrailLabelDevices.valueProblems("except", label.exceptDevices)
          } else {
            TrailLabelDevices.resolve(label, devices).problems
          }
          deviceProblems.forEach { add("${where(listKey, label)}: $it") }
        }
      curated.forEach { label ->
        addAll(entryProblems(TrailLabels.CURATED_KEY, label))
        if (label.survey != null) {
          add("${where(TrailLabels.CURATED_KEY, label)}: `survey` is only for generated labels; remove it")
        }
      }
      generated.forEach { label ->
        addAll(entryProblems(TrailLabels.GENERATED_KEY, label))
        if (label.survey.isNullOrBlank()) {
          add("${where(TrailLabels.GENERATED_KEY, label)}: `survey` is required on a generated label")
        }
      }
      duplicates(curated) { it.key }.forEach { add("${TrailLabels.CURATED_KEY}: `$it` is listed more than once") }
      duplicates(generated) { "${it.key} (${it.survey})" }
        .forEach { add("${TrailLabels.GENERATED_KEY}: `$it` is listed more than once") }
    }
    val canonical = generated.takeIf { it.isNotEmpty() }?.let { nonCanonicalGeneratedBlock(text, it) }
    if (problems.isEmpty() && canonical == null) return null
    val allProblems = if (canonical == null) {
      problems
    } else {
      problems + (
        "${TrailLabels.GENERATED_KEY}: not in the form the survey command writes (one line per entry, " +
          "fields type/name/when/except/survey/reasoning, sorted by type, then name, then survey)"
        )
    }
    return Finding(trailRelPath, allProblems, canonical)
  }

  internal fun entryProblems(listKey: String, label: TrailLabel): List<String> = buildList {
    val at = where(listKey, label)
    if (!TrailLabels.TYPE_PATTERN.matches(label.type)) {
      add("$at: `type` must be lowercase letters, digits, `-` or `.`, starting with a letter or digit")
    }
    if (label.name.isBlank()) add("$at: `name` is blank")
    if (label.name != label.name.trim()) add("$at: `name` has leading or trailing whitespace")
    if (label.name.length > TrailLabels.MAX_NAME_LENGTH) {
      add("$at: `name` is longer than ${TrailLabels.MAX_NAME_LENGTH} characters")
    }
    listOf("name" to label.name, "survey" to label.survey, "reasoning" to label.reasoning)
      .filter { (_, value) -> value != null && ('\n' in value || '\r' in value) }
      .forEach { (field, _) -> add("$at: `$field` must be one line") }
  }

  private fun where(listKey: String, label: TrailLabel): String = "$listKey `${label.key}`"

  private fun <T> duplicates(labels: List<TrailLabel>, identity: (TrailLabel) -> T): List<T> =
    labels.groupingBy(identity).eachCount().filterValues { it > 1 }.keys.toList()

  /**
   * The canonical `generatedLabels` block, indented as the file indents it, or null when the file
   * already has exactly that. The file's block is the key line inside `config:` plus every line
   * after it that is deeper, or a `- ` item at the key's own indent. A block this scan cannot find
   * (a flow-style `config: { ... }`, say) is not in canonical form either, so it fails too, and the
   * finding offers the block at the default two-space indent.
   */
  internal fun nonCanonicalGeneratedBlock(text: String, generated: List<TrailLabel>): String? {
    val lines = text.lines()
    val expected = { indent: String ->
      TrailLabels.renderBlock(TrailLabels.GENERATED_KEY, generated.sortedWith(TrailLabels.CANONICAL_ORDER))
        .lines()
        .filter { it.isNotEmpty() }
        .joinToString("\n", postfix = "\n") { indent + it }
    }
    val configStart = lines.indexOfFirst { CONFIG_KEY_LINE.matches(it) }
    if (configStart < 0) return expected(DEFAULT_INDENT)
    // A direct child of `config:`, at the indent of its first key; a nested key of the same name is not it.
    val indent = lines.drop(configStart + 1).firstOrNull { it.isNotBlank() && !it.trimStart().startsWith("#") }
      ?.let { it.substring(0, it.length - it.trimStart().length) }
      ?.takeIf { it.isNotEmpty() } ?: return expected(DEFAULT_INDENT)
    val start = (configStart + 1 until lines.size)
      .takeWhile { i -> lines[i].isEmpty() || lines[i][0].isWhitespace() || lines[i].startsWith("#") }
      .firstOrNull { i -> lines[i].startsWith("$indent${TrailLabels.GENERATED_KEY}:") }
      ?: return expected(indent)
    var end = start + 1
    while (end < lines.size) {
      val line = lines[end]
      val lineIndent = line.length - line.trimStart().length
      val inBlock = line.isNotBlank() &&
        (lineIndent > indent.length || (lineIndent == indent.length && line.trimStart().startsWith("-")))
      if (!inBlock) break
      end++
    }
    val actual = lines.subList(start, end).joinToString("\n", postfix = "\n")
    return expected(indent).takeIf { it != actual }
  }

  private const val DEFAULT_INDENT = "  "

  private val CONFIG_KEY_LINE = Regex("""^config:\s*(#.*)?$""")

  /**
   * PURE. [lint] for a trail whose full decode failed, so a decode error elsewhere in the file
   * cannot hide a label problem: nothing else makes that decode error fatal. Reads the two lists
   * from [text] on their own; a list that does not decode (an entry missing `type` or `name`, say)
   * is a finding, and lists that do decode get the same rules as [lint]. Null when the labels are
   * clean or the file is not YAML at all. `when` and `except` are checked against the trail's devices
   * when the file decodes once its templates are masked, and for shape alone otherwise.
   */
  fun lintUndecodedTrail(trailRelPath: String, text: String): Finding? {
    val decoded = TrailLabels.readLists(text) ?: return null
    val unreadable = decoded.mapNotNull { (key, result) ->
      result.exceptionOrNull()?.let {
        "$key: cannot be read (${it.message?.lineSequence()?.firstOrNull() ?: it::class.simpleName}); " +
          "every entry needs `type` and `name`, and `when`/`except` are lists (`when: [ios]`)"
      }
    }
    if (unreadable.isNotEmpty()) return Finding(trailRelPath, unreadable)
    return lintLists(
      trailRelPath,
      text,
      decoded.getValue(TrailLabels.CURATED_KEY).getOrThrow().orEmpty(),
      decoded.getValue(TrailLabels.GENERATED_KEY).getOrThrow().orEmpty(),
      devices = maskedDevices(text),
    )
  }

  // One per thread, reused: building one wires the whole tool registry, and decoders are not
  // thread-safe (as in TrailCorpus).
  private val maskedDecoder = ThreadLocal.withInitial { createTrailblazeYaml() }

  /**
   * The devices of [text] decoded with its templates masked, so a template elsewhere in the trail
   * (`context: {{CWD}}/fixtures`) does not stop `when` and `except` being checked against them.
   * Null when even the masked text does not decode; then only their shape is checked.
   */
  private fun maskedDevices(text: String): List<TrailLabelDevices.Device>? {
    val mask = TrailLabels.TemplateMask.of(text)
    val trail =
      runCatching {
          when (val doc = maskedDecoder.get().decodeTrailDocument(mask.masked)) {
            is TrailDocument.Unified -> doc.trail
          }
        }
        .getOrNull() ?: return null
    return TrailLabelDevices.devicesOf(trail).map { device ->
      TrailLabelDevices.Device(mask.restore(device.id), device.keys.map(mask::restore))
    }
  }

  /** Fatal rendering for `trailblaze check`. */
  fun renderFailures(findings: List<Finding>): String = buildString {
    appendLine("── trail-label gate (FATAL) ───────────────────────────────────")
    appendLine("${findings.size} trail(s) have labels that break the label rules.")
    findings.sortedBy { it.trailRelPath }.forEach { finding ->
      appendLine("")
      finding.problems.forEach { appendLine("  FAIL ${finding.trailRelPath}: $it") }
      finding.canonicalGeneratedBlock?.let { block ->
        appendLine("  Replace the block with:")
        block.lines().filter { it.isNotEmpty() }.forEach { appendLine("    $it") }
      }
    }
    appendLine("")
    appendLine("Silence with $DISABLE_ENV_VAR=1.")
  }
}
