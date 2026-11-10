package xyz.block.trailblaze.cli

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import xyz.block.trailblaze.host.TrailLabelWriter
import xyz.block.trailblaze.recordings.UnifiedRecordingWriter
import xyz.block.trailblaze.yaml.unified.TrailLabel

/**
 * `trailblaze survey --update-trails`: turns a survey report's labels into `generatedLabels` on the
 * trails whose runs earned them.
 *
 * A label lands on a trail when one of the runs it cites ran that trail, matched by `config.id`.
 * Only the surveys the caller selected write: one a selected survey pulled in through `dependsOn`
 * ran to feed it, and was not asked for. Each entry is `{ type, name, survey }`. A label's
 * `reasoning` describes the batch it came from ("completed in 2 of 3 runs"), so it stays in the
 * report: in the trail it would change with every run.
 */
internal object SurveyTrailUpdate {

  @Serializable
  data class Report(
    val surveys: List<Survey> = emptyList(),
    val sessions: List<SessionResult> = emptyList(),
    val labels: List<Label> = emptyList(),
    val excluded: List<PathRef> = emptyList(),
    val unreadable: List<PathRef> = emptyList(),
  ) {
    /** Where each session given stores its survey results, which a saved report must not replace. */
    fun storedResultFiles(): List<File> =
      (sessions.mapNotNull { it.session.path } + excluded.map { it.path } + unreadable.map { it.path })
        .map { File(it, STORED_SURVEYS_FILE) }
  }

  @Serializable
  data class PathRef(val path: String)

  @Serializable
  data class Survey(val id: String, val neededBy: List<String>? = null)

  @Serializable
  data class SessionResult(val session: Session)

  @Serializable
  data class Session(val id: String, val trailId: String? = null, val path: String? = null)

  @Serializable
  data class Label(val survey: String, val type: String, val name: String, val runs: List<String> = emptyList())

  /** What one update did. Paths are as found under the roots given. */
  data class Outcome(
    /** Each trail that gained labels, with the labels it gained. */
    val updated: Map<File, List<TrailLabel>>,
    /** Labeled trails that already had every label. */
    val unchanged: Int,
    /** Labeled trail ids no file under the roots declares. */
    val missing: List<String>,
    /** Trail ids several files declare, so none was written. */
    val ambiguous: Map<String, List<File>>,
    /** Trails that could not be written, with why. */
    val failed: Map<File, String>,
  )

  private val json = Json { ignoreUnknownKeys = true }

  fun parse(reportJson: String): Report = json.decodeFromString(Report.serializer(), reportJson)

  /** The labels each trail id earned from the selected surveys, in report order, without repeats. */
  fun labelsByTrail(report: Report): Map<String, List<TrailLabel>> {
    val selected = report.surveys.filter { it.neededBy.isNullOrEmpty() }.map { it.id }.toSet()
    val trailOf = report.sessions.associate { it.session.id to it.session.trailId }
    val out = linkedMapOf<String, LinkedHashSet<TrailLabel>>()
    for (label in report.labels) {
      if (label.survey !in selected) continue
      val entry = TrailLabel(type = label.type, name = label.name, survey = label.survey)
      for (trail in label.runs.mapNotNull { trailOf[it] }.distinct()) out.getOrPut(trail) { linkedSetOf() }.add(entry)
    }
    return out.mapValues { it.value.toList() }
  }

  /** Writes [labelsByTrail] of [report] into the trail files under [roots]. Edits files; commits nothing. */
  fun apply(report: Report, roots: List<File>): Outcome {
    val wanted = labelsByTrail(report)
    val files = TrailLabelWriter.trailsById(roots)
    val updated = linkedMapOf<File, List<TrailLabel>>()
    val failed = linkedMapOf<File, String>()
    val ambiguous = linkedMapOf<String, List<File>>()
    val missing = mutableListOf<String>()
    var unchanged = 0
    for ((trailId, labels) in wanted) {
      val matches = files[trailId]
      when {
        matches == null -> missing += trailId
        matches.size > 1 -> ambiguous[trailId] = matches
        else -> {
          val file = matches.single()
          // One trail that can't be read or written costs only that trail.
          runCatching {
            val edit = TrailLabelWriter.addGeneratedLabels(file.readText(), labels).getOrThrow()
            if (edit.added.isNotEmpty()) UnifiedRecordingWriter.writeFileAtomically(file, edit.text)
            edit.added
          }.fold(
            onSuccess = { added -> if (added.isEmpty()) unchanged++ else updated[file] = added },
            onFailure = { failed[file] = it.message ?: it::class.simpleName.orEmpty() },
          )
        }
      }
    }
    return Outcome(updated, unchanged, missing, ambiguous, failed)
  }

  /** The outcome for a person, one line per trail written or not; relative to [cwd] where it can be. */
  fun render(outcome: Outcome, cwd: File): String = buildString {
    fun rel(f: File) = f.absoluteFile.relativeToOrNull(cwd.absoluteFile)?.path ?: f.path
    val added = outcome.updated.values.sumOf { it.size }
    appendLine("Labels: added $added to ${outcome.updated.size} trail(s); ${outcome.unchanged} already had theirs.")
    outcome.updated.forEach { (file, labels) ->
      appendLine("  ${rel(file)}")
      labels.forEach { appendLine("    + ${it.key} (${it.survey})") }
    }
    outcome.failed.forEach { (file, why) -> appendLine("  not written: ${rel(file)}: $why") }
    outcome.ambiguous.forEach { (id, files) ->
      appendLine("  not written: config.id $id is declared by ${files.size} files: ${files.joinToString { rel(it) }}")
    }
    if (outcome.missing.isNotEmpty()) {
      val shown = outcome.missing.take(MAX_LISTED).joinToString()
      val more = if (outcome.missing.size > MAX_LISTED) ", and ${outcome.missing.size - MAX_LISTED} more" else ""
      appendLine("  ${outcome.missing.size} labeled trail(s) have no file under the paths given: $shown$more")
    }
  }

  private const val MAX_LISTED = 10

  /** A session's stored results, as the surveys CLI names the file. */
  private const val STORED_SURVEYS_FILE = "surveys.json"
}
