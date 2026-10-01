package xyz.block.trailblaze.report.strings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import xyz.block.trailblaze.api.VisibleStringSource
import xyz.block.trailblaze.api.VolatileText

/** One step's worth of difference between two runs of the same trail. */
data class StepDiff(
  /** The screen's position in both files, counting from 0 — the only key two locales share. */
  val stepIndex: Int,
  val action: String?,
  val baselineCaptureId: String,
  val candidateCaptureId: String,
  /** Strings the candidate shows that the baseline did not. */
  val added: List<String>,
  /** Strings the baseline showed that the candidate does not. */
  val removed: List<String>,
  /** Strings both runs show verbatim under the same property. */
  val unchanged: List<String>,
  /**
   * Text both runs show verbatim, whatever property it came from. This, not [unchanged], is the
   * untranslated set: a caption that moved from `text` to `contentDescription` between runs is
   * still text nobody translated, and pair-based comparison would drop it from the report while
   * [changed] rightly still calls the move a change.
   */
  val unchangedText: List<String>,
  /** The baseline lost part of its tree, so [added] may be text that was there all along. */
  val baselinePartial: Boolean,
  /** The candidate lost part of its tree, so [removed] may be text that is still there. */
  val candidatePartial: Boolean,
) {
  val changed: Boolean get() = added.isNotEmpty() || removed.isNotEmpty()

  /** Either side lost part of its tree, so this step's differences prove nothing on their own. */
  val inconclusive: Boolean get() = baselinePartial || candidatePartial
}

/** Steps that exist on only one side, which means the two runs took different paths. */
data class VisibleStringsDiffResult(
  val steps: List<StepDiff>,
  val baselineOnlySteps: List<Int>,
  val candidateOnlySteps: List<Int>,
  val baselineLocale: String?,
  val candidateLocale: String?,
) {
  val aligned: Boolean get() = baselineOnlySteps.isEmpty() && candidateOnlySteps.isEmpty()
}

/**
 * Compares two `visible-strings.ndjson` files step by step.
 *
 * Steps align by position in the file, never by content: in a localization diff every string
 * changes, so a content hash matches nothing in the one case this exists for. That is also why the
 * result is only meaningful for a mechanical trail — an LLM-driven objective takes a different path
 * each run and its step numbers mean nothing across runs.
 *
 * Reads both format versions. A version-1 file's step numbers are its positions, and its
 * `repeatOfStepIndex` pointers are resolved to the screenshot they name, so a file recorded before
 * the format changed diffs against one recorded after.
 */
object VisibleStringsDiff {

  private val JSON = Json { ignoreUnknownKeys = true }

  fun diff(baseline: ParsedFile, candidate: ParsedFile): VisibleStringsDiffResult {
    val before = baseline.stringsByStep()
    val after = candidate.stringsByStep()
    val shared = before.keys.intersect(after.keys).sorted()

    val steps = shared.map { stepIndex ->
      val b = before.getValue(stepIndex)
      val c = after.getValue(stepIndex)
      val ambiguous = (b.strings + c.strings).groupBy { it.text }.filterValues { it.size > 1 }.keys
      StepDiff(
        stepIndex = stepIndex,
        action = c.line.action ?: b.line.action,
        baselineCaptureId = b.line.captureId,
        candidateCaptureId = c.line.captureId,
        added = (c.strings - b.strings).render(ambiguous),
        removed = (b.strings - c.strings).render(ambiguous),
        unchanged = (b.strings intersect c.strings).render(ambiguous),
        unchangedText = (b.strings.map { it.text }.toSet() intersect c.strings.map { it.text }.toSet()).sorted(),
        baselinePartial = b.line.partialCapture == true,
        candidatePartial = c.line.partialCapture == true,
      )
    }

    return VisibleStringsDiffResult(
      steps = steps,
      baselineOnlySteps = (before.keys - after.keys).sorted(),
      candidateOnlySteps = (after.keys - before.keys).sorted(),
      baselineLocale = baseline.run?.locale,
      candidateLocale = candidate.run?.locale,
    )
  }

  /** A parsed file: the run header, if it had one, and every screen line in step order. */
  data class ParsedFile(
    val run: VisibleStringsRunLine?,
    val screens: List<VisibleStringsScreenLine>,
  )

  /** Thrown for a file that is not this format, naming the line so the user can look at it. */
  class MalformedFile(val lineNumber: Int, cause: Throwable) :
    Exception("line $lineNumber is not a ${VisibleStringsLog.FILE_NAME} record", cause)

  /**
   * An unrecognized `kind` is rejected rather than skipped. Skipping turns a one-character typo
   * into a *wrong answer*: the step vanishes and gets reported as the two runs taking different
   * paths, which reads like a finding about the app instead of a broken file. Every line carries
   * `v`, so a format that gains a new kind announces itself by bumping the version rather than by
   * relying on old readers to ignore what they do not understand.
   */
  fun parse(contents: String): ParsedFile {
    var run: VisibleStringsRunLine? = null
    val screens = mutableListOf<VisibleStringsScreenLine>()
    contents.lineSequence().withIndex().filter { it.value.isNotBlank() }.forEach { (index, line) ->
      try {
        when (val kind = JSON.parseToJsonElement(line).jsonObject["kind"]?.jsonPrimitive?.content) {
          "run" -> run = JSON.decodeFromString<VisibleStringsRunLine>(line)
          "screen" -> screens += screenLine(line, screens)
          else -> throw IllegalArgumentException("unrecognized kind ${kind ?: "(absent)"}")
        }
      } catch (e: Exception) {
        throw MalformedFile(index + 1, e)
      }
    }
    // A version-1 file can hold two lines for one screenshot (a driver log and the LLM request made
    // on that screen). Version 2 writes the first only, and positions are the pairing key, so the
    // later one goes too — after the pointers above are resolved, since those count every line.
    val seen = mutableSetOf<String>()
    return ParsedFile(run, screens.filter { seen.add(it.captureId) })
  }

  /**
   * A version-1 line points at its repeat by step number, and a step number is a position; version
   * 2 names the screenshot. Resolve the old pointer against the lines already read — a repeat always
   * points backwards — so everything past the parser sees one shape. Bounds are not converted: the
   * diff compares text, never position.
   */
  private fun screenLine(line: String, earlier: List<VisibleStringsScreenLine>): VisibleStringsScreenLine {
    val obj = JSON.parseToJsonElement(line).jsonObject
    val parsed = JSON.decodeFromString<VisibleStringsScreenLine>(line)
    val v1Repeat = obj["repeatOfStepIndex"]?.jsonPrimitive?.intOrNull ?: return parsed
    return parsed.copy(repeatOf = earlier.getOrNull(v1Repeat)?.captureId)
  }

  /**
   * A string is identified by its text AND the property it came from. Moving a label from `text`
   * to `contentDescription` turns a visible caption into a screen-reader-only one — a real change
   * the file already records, and comparing text alone would report it as nothing happening.
   */
  private data class Entry(val text: String, val source: VisibleStringSource)

  private class Step(val line: VisibleStringsScreenLine, val strings: Set<Entry>)

  /**
   * Sorted, so the console output and anything snapshotting it are stable run to run. Set
   * arithmetic has no defined order.
   *
   * [ambiguous] is computed across the whole step rather than per list, so a label that merely
   * moved between properties does not print as an identical `-` and `+` pair.
   */
  private fun Set<Entry>.render(ambiguous: Set<String>): List<String> =
    map { if (it.text in ambiguous) "${it.text} [${it.source}]" else it.text }.sorted()

  /**
   * Volatile strings are dropped before comparing: a clock or a balance differs on every run and
   * would bury the real changes. They stay in the file, which is the record of what was on screen,
   * and are judged here rather than there so a better rule reaches files already written.
   *
   * A repeated capture carries no strings of its own, so it resolves back to the step it points at.
   * Without that, every repeat would read as a screen that lost all its text.
   */
  private fun ParsedFile.stringsByStep(): Map<Int, Step> {
    val byCapture = screens.associateBy { it.captureId }
    return screens.withIndex().associate { (position, line) ->
      val source = line.repeatOf?.let { byCapture[it] } ?: line
      position to Step(
        line = line,
        strings = source.strings.filterNot { VolatileText.looksVolatile(it.text) }.map { Entry(it.text, it.source) }.toSet(),
      )
    }
  }
}
