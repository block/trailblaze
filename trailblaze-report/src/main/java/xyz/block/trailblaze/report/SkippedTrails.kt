package xyz.block.trailblaze.report

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.report.models.ExecutionMode
import xyz.block.trailblaze.report.models.Outcome
import xyz.block.trailblaze.report.models.SessionResult
import xyz.block.trailblaze.report.models.SkippedTrail
import xyz.block.trailblaze.util.Console

/**
 * The runner's record, inside the logs directory, of the trails it declined to run.
 *
 * ## Why a file and not a session log
 *
 * Every report surface is built from the logs directory: the CLI generates its report from the
 * repo it just wrote to, and CI runs `ReportMain` over that same directory in a LATER process. A
 * skip is resolved before any session opens, so there is nothing in-memory for the report process
 * to be handed - the logs dir is the only channel both surfaces already share, which is why the
 * skip lands there as a file rather than as an argument.
 *
 * ## Layout
 *
 * One file per skip under `<logsDir>/skipped/`, rather than one list file the runner rewrites.
 * CI invokes `trailblaze run <trail>` once per trail into a single logs dir (see
 * `run_trails_via_cli.sh`), so a shared list would be a read-modify-write race between
 * consecutive - and, on a fan-out run, concurrent - processes. A file per skip needs no
 * coordination.
 *
 * The name is derived from the skip's identity, so re-running a skipped trail overwrites its
 * record instead of accumulating duplicates, while the same trail skipped on two devices keeps two.
 *
 * ## Why these names
 *
 * `skipped/` is a directory rather than loose files in the logs root because [ReportMain]'s
 * `moveJsonFilesToSessionDirs` decodes and DELETES every root-level `.json` it can't recognize.
 * The `skip-` prefix keeps the records out of `LogsRepo`'s log-file filter too (which admits only
 * names starting with a hex character), so nothing tries to parse one as a log even though session
 * enumeration will list `skipped/` as a directory - the same harmless way it already lists
 * `reports/`.
 */
object SkippedTrails {

  /** Subdirectory of the logs dir holding one record per skipped trail. */
  const val DIR_NAME: String = "skipped"

  private const val FILE_PREFIX = "skip-"

  /**
   * Lenient so a record written by a newer runner still reads here: CI generates its report with
   * whatever framework version the report step resolved, which is not always the one that ran the
   * trails.
   */
  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = true
  }

  /**
   * Records [skip] under `<logsDir>/skipped/`.
   *
   * Best-effort: a run must not fail because its skip bookkeeping could not be written, so a
   * failure is logged and swallowed. Returns the file written, or null when it wasn't.
   */
  fun record(logsDir: File, skip: SkippedTrail): File? = try {
    val dir = File(logsDir, DIR_NAME)
    dir.mkdirs()
    File(dir, "$FILE_PREFIX${identityHash(skip)}.json")
      .apply { writeText(json.encodeToString(SkippedTrail.serializer(), skip)) }
  } catch (e: Exception) {
    Console.log("[SkippedTrails] could not record skip for ${skip.trail_path}: ${e.message}")
    null
  }

  /**
   * Every skip recorded into [logsDir], oldest first, then by title so two skips stamped in the
   * same millisecond still come out in a stable order.
   *
   * An unreadable or undecodable record is logged and dropped rather than thrown: it must not take
   * down the report of the trails that DID run. Returns empty when nothing was recorded, which is
   * the normal case for a run with no `config.skip:` in scope.
   */
  fun read(logsDir: File): List<SkippedTrail> {
    val files = File(logsDir, DIR_NAME)
      .listFiles { file -> file.isFile && file.name.startsWith(FILE_PREFIX) && file.extension == "json" }
      ?: return emptyList()
    return files
      .mapNotNull { file ->
        try {
          json.decodeFromString(SkippedTrail.serializer(), file.readText())
        } catch (e: Exception) {
          Console.log("[SkippedTrails] ignoring unreadable record ${file.name}: ${e.message}")
          null
        }
      }
      .sortedWith(RECORD_ORDER)
  }

  /**
   * Oldest first, and total: no two distinct records compare equal.
   *
   * The trailing three are what make it total. A title is not unique - the same trail skipped on
   * two devices in the same millisecond shares one - and a tie here falls through to `listFiles`
   * order, which is filesystem-dependent, so a report's Skipped section could reorder between two
   * reads of one directory. The three together are the record's identity, the same fields
   * [identityHash] keys a file name on, so equality under this comparator means the same skip.
   */
  internal val RECORD_ORDER: Comparator<SkippedTrail> = compareBy(
    { it.recorded_at_epoch_ms },
    { it.title },
    { it.test_key },
    { it.target.orEmpty() },
    { it.device_classifier.orEmpty() },
  )

  /**
   * The synthetic id a skip's report row carries. Every [SessionResult] is keyed by one, and a
   * skipped trail has no session, so this stands in for it - derived from the same identity as the
   * record's file name and therefore stable across re-runs.
   *
   * The `skipped__` prefix is what keeps it from colliding with a real session id and, more
   * usefully, marks the row as one with no session directory behind it: nothing should try to open
   * logs, artifacts, or a per-run report for it.
   */
  fun syntheticSessionId(skip: SkippedTrail): SessionId =
    SessionId.sanitized("skipped__${skip.test_key}__${identityHash(skip)}")

  /**
   * What makes two records the same skip: the trail identity plus the device it was resolved for.
   *
   * Not the trail PATH - the same trail reached through a symlinked workspace, or through a
   * relative and an absolute argument in two invocations, is one skip and should not be reported
   * twice. [SkippedTrail.test_key] is already the report's identity for that trail, so keying on it
   * makes the file name agree with the row the record becomes.
   *
   * [SkippedTrail.target] joins it for the same reason the report's matrix rows key on target: one
   * `config.id` can be authored against several targets, and those are separate rows. Without it,
   * skipping such a trail on two targets would write one record over the other and report one.
   *
   * Truncated to 8 bytes rather than 4. A collision here is silent - two unrelated skips share a
   * file name and one overwrites the other, and share a synthetic session id and fold into one row
   * - so the width is chosen for the consequence, not the likelihood. 4 bytes puts a collision
   * within reach of a few tens of thousands of records in one directory; 8 does not.
   */
  private fun identityHash(skip: SkippedTrail): String =
    MessageDigest.getInstance("SHA-256")
      .digest("${skip.test_key}|${skip.target.orEmpty()}|${skip.device_classifier.orEmpty()}".toByteArray())
      .take(8)
      .joinToString("") { "%02x".format(it) }
}

/**
 * The skip as a report row, so the CI summary lists a held-back trail alongside the ones that ran
 * instead of leaving a reader to notice its absence.
 *
 * Everything a real row measures is absent by definition: no duration, no LLM calls, no device
 * detail beyond what the skip resolved against. The reason travels in
 * [SessionResult.failure_reason] - not because a skip is a failure, but because that is the field
 * every existing consumer already renders as "why this row is not a plain pass", and a parallel
 * field of its own would leave the reason invisible in all of them. [Outcome.SKIPPED] is what says
 * it isn't a failure.
 */
fun SkippedTrail.toSessionResult(): SessionResult = SessionResult(
  session_id = SkippedTrails.syntheticSessionId(this),
  title = title,
  test_key = test_key,
  // A skip IS a trail - one the runner declined - so the row names its file like any other. Left
  // null it would read as half of "this row was never a trail", which is the one thing a skip is
  // not, and the `platform ?: "unknown"` below can supply the other half.
  trail_file_path = trail_path,
  platform = platform ?: "unknown",
  outcome = Outcome.SKIPPED,
  // A skip is decided from the authored trail, before anything picks a replay-vs-AI strategy, so
  // there is no execution mode to report.
  execution_mode = ExecutionMode.UNKNOWN,
  trail_source = trail_source,
  device_classifier = device_classifier,
  // The trail's own `config.metadata`, which is where a durable TestRail case id lives. Every
  // consumer that resolves a case id prefers this map over parsing `test_key`, so a skipped row
  // without it joins to nothing while the same trail's runs join to their case.
  metadata = metadata,
  duration_ms = 0,
  // When the runner made the decision. Not a run time - nothing ran - but the report's ordering
  // field, and the row has to carry one: the viewer groups a trail's rows per device into attempts
  // and orders them by it, falling back to payload order, where skips are appended last. A trail
  // skipped on Monday and run on Tuesday would then show Monday's skip as the latest word on it.
  // [RunReportGenerator.skipSessionJson] stamps the same value on the same row's `ranAt`.
  started_at_epoch_ms = recorded_at_epoch_ms,
  failure_reason = reason,
)
