package xyz.block.trailblaze.host

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlScalar
import java.io.File
import xyz.block.trailblaze.yaml.unified.TrailLabel
import xyz.block.trailblaze.yaml.unified.TrailLabels

/**
 * Adds survey labels to trail files: the writer behind `trailblaze survey --update-trails`.
 *
 * It edits only each trail's `config.generatedLabels` block, as text, so the rest of the file stays
 * byte for byte as written. It only adds: an entry already there stays, whatever the runs showed
 * this time, because one device's runs can never prove a label is gone. The block it writes is the
 * one [TrailLabelLint] requires: one line per entry, sorted by [TrailLabels.CANONICAL_ORDER].
 */
object TrailLabelWriter {

  /** What writing [added] into one trail did. [text] is the new file; unchanged when nothing was new. */
  data class Edit(val text: String, val added: List<TrailLabel>)

  /**
   * [text] with [labels] added to its `generatedLabels`, existing entries kept. An entry is new when
   * no entry has its type, name and survey. A failure, and the file untouched, when the block can't
   * be read, when the config is not in block style, or when a label breaks the label rules.
   */
  fun addGeneratedLabels(text: String, labels: List<TrailLabel>): Result<Edit> {
    labels.firstNotNullOfOrNull { label ->
      TrailLabelLint.entryProblems(TrailLabels.GENERATED_KEY, label).firstOrNull()
        ?: "${TrailLabels.GENERATED_KEY} `${label.key}`: `survey` is required on a generated label".takeIf { label.survey.isNullOrBlank() }
    }?.let { return Result.failure(IllegalArgumentException(it)) }
    val lists = TrailLabels.readLists(text)
      ?: return Result.failure(IllegalArgumentException("not a trail: no `config:` map"))
    val existing = lists.getValue(TrailLabels.GENERATED_KEY).getOrElse {
      return Result.failure(IllegalArgumentException("its ${TrailLabels.GENERATED_KEY} can't be read (${it.message?.lineSequence()?.firstOrNull()})"))
    }.orEmpty()
    if ("\r\n" in text) {
      return Result.failure(IllegalArgumentException("it uses CRLF line endings, which the edit would mix with LF"))
    }
    val identity = { l: TrailLabel -> Triple(l.type, l.name, l.survey) }
    val have = existing.map(identity).toMutableSet()
    val added = labels.filter { have.add(identity(it)) }
    if (added.isEmpty()) return Result.success(Edit(text, emptyList()))
    val layout = configLayout(text)
      ?: return Result.failure(IllegalArgumentException("its `config:` is not in block style, so the labels can't be placed"))
    val block = TrailLabels.renderBlock(TrailLabels.GENERATED_KEY, (existing + added).sortedWith(TrailLabels.CANONICAL_ORDER))
      .lines().filter { it.isNotEmpty() }.map { layout.indent + it }
    val lines = layout.lines.toMutableList()
    val current = layout.keys[TrailLabels.GENERATED_KEY]
    if (current != null) {
      repeat(current.last - current.first + 1) { lines.removeAt(current.first) }
      lines.addAll(current.first, block)
    } else {
      lines.addAll(insertionLine(layout), block)
    }
    val written = lines.joinToString("\n")
    // The splice is text surgery, so read the result back: it must hold exactly these labels, in the
    // form the lint accepts. Anything else is a bug here, and the file is left as it was.
    val reread = TrailLabels.readLists(written)?.get(TrailLabels.GENERATED_KEY)?.getOrNull()
    val expected = (existing + added).sortedWith(TrailLabels.CANONICAL_ORDER)
    if (reread != expected || TrailLabelLint.nonCanonicalGeneratedBlock(written, expected) != null) {
      return Result.failure(IllegalStateException("the edited file did not read back as written; left unchanged"))
    }
    // Entries already there are kept as written, so a broken one would survive the edit: refuse a
    // result `trailblaze check` would reject.
    TrailLabelLint.lintUndecodedTrail("", written)?.let {
      return Result.failure(IllegalArgumentException("its labels break the label rules: ${it.problems.first()}"))
    }
    return Result.success(Edit(written, added))
  }

  /**
   * Where a new `generatedLabels` block goes, mirroring the emitter so a later replay write-back
   * doesn't move it: right after `labels`, else before the first key the emitter writes after the
   * label lists, else at the end of `config:`.
   */
  private fun insertionLine(layout: ConfigLayout): Int {
    layout.keys[TrailLabels.CURATED_KEY]?.let { return it.last + 1 }
    return layout.keys.entries.filter { it.key in KEYS_AFTER_LABELS }.minOfOrNull { it.value.first } ?: layout.end
  }

  /** Keys `UnifiedTrailEmitter` writes after the label lists. */
  private val KEYS_AFTER_LABELS = setOf("context", "memory", "metadata", "title", "args")

  /**
   * The direct children of `config:`, each with the lines its block spans (its key line, then every
   * deeper line, or `- ` item at the key's indent), and [end], the line after the config's last
   * line. Null when the config is not a block map this can read line by line (flow style, say).
   */
  internal data class ConfigLayout(
    val lines: List<String>,
    val indent: String,
    val keys: Map<String, IntRange>,
    val end: Int,
  )

  internal fun configLayout(text: String): ConfigLayout? {
    val lines = text.split("\n")
    val configStart = lines.indexOfFirst { CONFIG_KEY_LINE.matches(it) }
    if (configStart < 0) return null
    val body = (configStart + 1 until lines.size)
      .takeWhile { i -> lines[i].isEmpty() || lines[i][0].isWhitespace() || lines[i].startsWith("#") }
    val first = body.firstOrNull { lines[it].isNotBlank() && !lines[it].trimStart().startsWith("#") } ?: return null
    val indent = lines[first].substring(0, lines[first].length - lines[first].trimStart().length)
    if (indent.isEmpty()) return null
    val keys = mutableMapOf<String, IntRange>()
    var lastContent = configStart
    var i = body.firstOrNull() ?: return null
    val stop = (body.lastOrNull() ?: configStart) + 1
    while (i < stop) {
      val line = lines[i]
      if (line.isNotBlank() && !line.trimStart().startsWith("#")) lastContent = i
      val key = KEY_LINE.matchEntire(line.removePrefix(indent))?.groupValues?.let { it[1].ifEmpty { it[2] } }
      if (!line.startsWith(indent) || line.length == indent.length || line[indent.length].isWhitespace() || key == null) {
        i++
        continue
      }
      var end = i + 1
      while (end < stop) {
        val next = lines[end]
        val nextIndent = next.length - next.trimStart().length
        val inBlock = next.isNotBlank() &&
          (nextIndent > indent.length || (nextIndent == indent.length && next.trimStart().startsWith("-")))
        if (!inBlock) break
        lastContent = end
        end++
      }
      keys[key] = i until end
      i = end
    }
    return ConfigLayout(lines, indent, keys, lastContent + 1)
  }

  private val CONFIG_KEY_LINE = Regex("""^config:\s*(#.*)?$""")

  /** A key line inside the config block: `key:`, `"key":` or `'key':`, then a value or nothing. */
  private val KEY_LINE = Regex("""^(?:["']([^"']+)["']|([A-Za-z_][\w.-]*))\s*:(?:\s.*)?$""")

  /**
   * Every trail file under [roots] (a trail file, or a directory walked for `*.trail.yaml` and
   * `trail.yaml`), by its `config.id`. An id two files share maps to both, so the caller can refuse
   * to pick one.
   */
  fun trailsById(roots: List<File>): Map<String, List<File>> {
    val files = roots.flatMap { root ->
      if (root.isFile) listOf(root)
      else root.walkTopDown()
        .onEnter { it.name != ".git" && it.name != "node_modules" && it.name != "build" }
        .filter { it.isFile && isTrailFile(it) }
        .toList()
    }.distinctBy { it.canonicalFile }
    // A file that can't be read declares no id here; its trail, if labeled, is reported missing.
    return files.mapNotNull { f -> runCatching { f.readText() }.getOrNull()?.let(::configId)?.let { it to f } }
      .groupBy({ it.first }, { it.second })
  }

  private fun isTrailFile(f: File) = f.name == "trail.yaml" || f.name.endsWith(".trail.yaml")

  /** A trail's `config.id`, read with templates masked; null when it has none. */
  internal fun configId(text: String): String? {
    val masked = TrailLabels.TemplateMask.of(text)
    val root = runCatching { LENIENT_YAML.parseToYamlNode(masked.masked) }.getOrNull() as? YamlMap ?: return null
    val config = root.entries.entries.firstOrNull { it.key.content == "config" }?.value as? YamlMap ?: return null
    val id = config.entries.entries.firstOrNull { it.key.content == "id" }?.value as? YamlScalar ?: return null
    return masked.restore(id.content).takeIf { it.isNotBlank() }
  }

  private val LENIENT_YAML = Yaml(configuration = YamlConfiguration(strictMode = false))
}
