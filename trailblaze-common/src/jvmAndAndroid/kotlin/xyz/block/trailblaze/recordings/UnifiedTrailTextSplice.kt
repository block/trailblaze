package xyz.block.trailblaze.recordings

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import xyz.block.trailblaze.yaml.unified.UnifiedTrail
import xyz.block.trailblaze.yaml.unified.UnifiedTrailStep

/**
 * Applies a recording save to a trail file's TEXT, so everything the save didn't change keeps its
 * exact bytes: comments, quoting, key order, and fields the decoder doesn't model.
 *
 * Re-encoding the whole decoded document is lossy. It drops comments, and it rewrites every value
 * it re-emits, so a quoted `'true'` or `"1234"` can come back as a boolean or a number. A save only
 * adds, replaces, or removes one device's legs (and that device's `config.devices` entry), so this
 * edits exactly those entries in place. Each new entry's text is cut from the full encoding of the
 * merged document and re-indented to sit where it goes.
 *
 * Returns null when the change is anything else (a step's prose, a step added or removed, a
 * trailhead added) or the file's layout isn't one it can edit line by line (flow-style maps). The
 * caller then falls back to encoding the whole document, and checks every splice by decoding it.
 */
internal object UnifiedTrailTextSplice {

  private const val RECORDING = "recording"

  fun splice(original: String, before: UnifiedTrail, after: UnifiedTrail, encode: (UnifiedTrail) -> String): String? =
    runCatching { spliceOrNull(original, before, after, encode) }.getOrNull()

  private fun spliceOrNull(
    original: String,
    before: UnifiedTrail,
    after: UnifiedTrail,
    encode: (UnifiedTrail) -> String,
  ): String? {
    if (before.trail.size != after.trail.size) return null
    val changedSteps = before.trail.indices.filter { before.trail[it] != after.trail[it] }
    if (changedSteps.any { !onlyRecordingsDiffer(before.trail[it], after.trail[it]) }) return null
    val trailheadChanged = before.trailhead != after.trailhead
    if (trailheadChanged) {
      val b = before.trailhead ?: return null
      val a = after.trailhead ?: return null
      if (!onlyRecordingsDiffer(b, a)) return null
    }
    val configChanged = before.config != after.config
    if (configChanged && before.config.copy(devices = after.config.devices) != after.config) return null
    if (changedSteps.isEmpty() && !trailheadChanged && !configChanged) return original

    val o = Doc(original)
    val e = Doc(encode(after))
    val edits = mutableListOf<Edit>()

    if (configChanged) {
      val oDevices = o.root.childMap("config")?.childMap("devices") ?: return null
      val eDevices = e.root.childMap("config")?.childMap("devices") ?: return null
      val keys = (before.config.devices.orEmpty().keys + after.config.devices.orEmpty().keys)
        .filter { before.config.devices?.get(it) != after.config.devices?.get(it) }
      edits += editEntries(o, oDevices, e, eDevices, keys, after.config.devices.orEmpty().keys) ?: return null
    }
    if (trailheadChanged) {
      val oHead = o.root.childMap("trailhead") ?: return null
      val eHead = e.root.childMap("trailhead") ?: return null
      edits += editRecordings(o, oHead, e, eHead, before.trailhead!!, after.trailhead!!) ?: return null
    }
    if (changedSteps.isNotEmpty()) {
      val oSteps = o.root.childItems("trail") ?: return null
      val eSteps = e.root.childItems("trail") ?: return null
      if (oSteps.size != before.trail.size || eSteps.size != after.trail.size) return null
      for (i in changedSteps) {
        edits += editRecordings(o, oSteps[i], e, eSteps[i], before.trail[i], after.trail[i]) ?: return null
      }
    }
    return o.apply(edits)
  }

  private fun onlyRecordingsDiffer(before: UnifiedTrailStep, after: UnifiedTrailStep): Boolean =
    before.copy(recordings = after.recordings) == after

  /** Edits turning [oStep]'s `recording:` legs into [eStep]'s, for the classifiers that differ. */
  private fun editRecordings(
    o: Doc,
    oStep: MapRegion,
    e: Doc,
    eStep: MapRegion,
    before: UnifiedTrailStep,
    after: UnifiedTrailStep,
  ): List<Edit>? {
    val keys = (before.recordings.keys + after.recordings.keys)
      .filter { before.recordings[it] != after.recordings[it] }
    val oRecording = oStep.entry(RECORDING)
    if (oRecording == null) {
      // No `recording:` yet: add the merged one whole, as the step's last entry.
      if (before.recordings.isNotEmpty()) return null
      val eRecording = eStep.entry(RECORDING) ?: return null
      val text = e.reindent(eRecording.lines, from = eRecording.column, to = oStep.keyColumn) ?: return null
      return listOf(Edit.insertAfter(oStep.lastLine, text))
    }
    val oLegs = oStep.childMap(RECORDING) ?: return null
    val eLegs = eStep.childMap(RECORDING) ?: return null
    return editEntries(o, oLegs, e, eLegs, keys, after.recordings.keys)
  }

  /**
   * Edits turning the [keys] entries of block map [oMap] into [eMap]'s: replaced in place when
   * both have one, removed when only [oMap] does, appended after [oMap]'s last entry when only
   * [eMap] does. [kept] is the full key set the result must hold, so emptying the map refuses.
   */
  private fun editEntries(
    o: Doc,
    oMap: MapRegion,
    e: Doc,
    eMap: MapRegion,
    keys: List<String>,
    kept: Set<String>,
  ): List<Edit>? {
    if (oMap.entries.isEmpty() || kept.isEmpty()) return null
    val edits = mutableListOf<Edit>()
    val appended = mutableListOf<String>()
    for (key in keys) {
      val oEntry = oMap.entry(key)
      val eEntry = eMap.entry(key)
      when {
        eEntry == null && oEntry != null -> edits += Edit(oEntry.firstLine, oEntry.lastLine, emptyList())
        eEntry == null -> Unit
        oEntry != null ->
          edits += Edit(
            oEntry.firstLine,
            oEntry.lastLine,
            e.reindent(eEntry.lines, from = eEntry.column, to = oEntry.column) ?: return null,
          )
        else -> appended += e.reindent(eEntry.lines, from = eEntry.column, to = oMap.keyColumn) ?: return null
      }
    }
    if (appended.isNotEmpty()) edits += Edit.insertAfter(oMap.lastLine, appended)
    return edits
  }

  /** One replacement of lines [first]..[last] (1-based, inclusive; `last = first - 1` inserts). */
  private data class Edit(val first: Int, val last: Int, val lines: List<String>) {
    companion object {
      fun insertAfter(line: Int, lines: List<String>) = Edit(line + 1, line, lines)
    }
  }

  /** One entry of a block map: its key, where it starts, and its last content line. */
  private class Entry(val key: String, val value: YamlNode, val column: Int, val firstLine: Int, val lastLine: Int, doc: Doc) {
    val lines: List<String> = doc.lines.subList(firstLine - 1, lastLine)
  }

  /** A block map and the lines it spans, its entries' extents worked out from where the next one starts. */
  private class MapRegion(val map: YamlMap, regionLastLine: Int, private val doc: Doc) {
    val entries: List<Entry>
    val keyColumn: Int
    val lastLine: Int

    init {
      val sorted = map.entries.entries.sortedBy { it.key.location.line }
      val firstLines = sorted.map { it.key.location.line }
      // Two entries on one line means a flow map; its entries can't be edited as lines.
      require(firstLines.distinct().size == firstLines.size) { "flow map" }
      entries = sorted.mapIndexed { i, (key, value) ->
        val next = firstLines.getOrNull(i + 1)?.minus(1) ?: regionLastLine
        Entry(key.content, value, key.location.column, firstLines[i], doc.trimTrailing(firstLines[i], next), doc)
      }
      val columns = entries.map { it.column }.distinct()
      require(columns.size == 1) { "uneven map" }
      keyColumn = columns.single()
      lastLine = entries.maxOf { it.lastLine }
    }

    fun entry(key: String): Entry? = entries.firstOrNull { it.key == key }

    /** The block map under [key], spanning that entry's lines. Null when it is absent or not a block map. */
    fun childMap(key: String): MapRegion? {
      val entry = entry(key) ?: return null
      val value = entry.value as? YamlMap ?: return null
      // A value on the key's own line is a flow map.
      if (value.entries.keys.any { it.location.line <= entry.firstLine }) return null
      return MapRegion(value, entry.lastLine, doc)
    }

    /** The block maps of the list under [key], each spanning up to where the next item starts. */
    fun childItems(key: String): List<MapRegion>? {
      val entry = entry(key) ?: return null
      val list = entry.value as? YamlList ?: return null
      val maps = list.items.map { it as? YamlMap ?: return null }
      val starts = maps.map { m -> m.entries.keys.minOfOrNull { it.location.line } ?: return null }
      if (starts.any { it <= entry.firstLine }) return null
      return maps.mapIndexed { i, m -> MapRegion(m, starts.getOrNull(i + 1)?.minus(1) ?: entry.lastLine, doc) }
    }
  }

  private class Doc(text: String) {
    val lines: List<String> = text.split("\n")
    val root: MapRegion = MapRegion(
      Yaml.default.parseToYamlNode(text) as? YamlMap ?: error("root is not a map"),
      lines.size,
      this,
    )

    /** [last] stepped back over blank and comment-only lines, which belong to whatever follows. */
    fun trimTrailing(first: Int, last: Int): Int {
      var end = last
      while (end > first && lines[end - 1].trim().let { it.isEmpty() || it.startsWith("#") }) end--
      return end
    }

    /** [block] shifted from key column [from] to [to]; null when a line has too little indent to shift left. */
    fun reindent(block: List<String>, from: Int, to: Int): List<String>? {
      val delta = to - from
      return block.map { line ->
        when {
          line.isBlank() -> ""
          delta >= 0 -> " ".repeat(delta) + line
          line.take(-delta).isBlank() -> line.drop(-delta)
          else -> return null
        }
      }
    }

    fun apply(edits: List<Edit>): String {
      val out = lines.toMutableList()
      // Bottom-up so earlier line numbers stay valid; at one line, a replacement goes before an insert.
      for (edit in edits.sortedWith(compareByDescending<Edit> { it.first }.thenByDescending { it.last })) {
        repeat(edit.last - edit.first + 1) { out.removeAt(edit.first - 1) }
        out.addAll(edit.first - 1, edit.lines)
      }
      return out.joinToString("\n")
    }
  }
}
