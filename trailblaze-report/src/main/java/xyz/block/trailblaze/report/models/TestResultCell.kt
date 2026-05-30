package xyz.block.trailblaze.report.models

import kotlinx.serialization.Serializable

/**
 * Per-(test case, device) snapshot written into a test-results index repo.
 *
 * One [TestResultCell] is the canonical payload of `results/<scheme>/<case>/<device>/latest.json`
 * (and `latest_success.json`) in the test-results repo. The shape is intentionally a thin
 * envelope around the existing typed report data — [CiRunMetadata] and [SessionResult] are
 * reused verbatim from the report generator, so any consumer (the in-build HTML report,
 * triage tooling, ad-hoc analytics) can use the same `kotlinx.serialization` types to round-trip.
 *
 * The publisher computes [consecutive_failures] from the cell file's git history at write time;
 * it is the only piece of state that doesn't exist in the source [CiSummaryReport].
 *
 * @property schema_version Schema version for forward compatibility.
 *
 *   **Versioning contract:**
 *   - Compatible additions (no bump required): adding nullable fields to [CiRunMetadata],
 *     [xyz.block.trailblaze.report.models.SessionResult], or this envelope; adding new
 *     [xyz.block.trailblaze.report.models.Outcome] enum values (readers MUST tolerate
 *     unknown outcomes — see `ResultsCommand.CellView.normalizeOutcome`).
 *   - Incompatible (REQUIRES a version bump): renaming an existing field, changing a
 *     field's type, removing a field, splitting one field into many. A version bump means
 *     coordinating the writer and reader rollouts so a reader for vN doesn't accidentally
 *     mis-interpret a v(N+1) document — readers MUST reject `schema_version >
 *     CURRENT_SCHEMA_VERSION` (see `xyz.block.trailblaze.cli.ResultsShowCommand.CellView.MAX_SUPPORTED_SCHEMA_VERSION`).
 *   - During a transition window where both the old and new writers may publish, the new
 *     writer should preserve unknown fields from the existing on-disk file (see how
 *     `TestResultsRepoPublisher.writeCellFile` merges into existing content) so a vN reader
 *     can still find the fields it expects.
 * @property test_case_id Identifier for the case (e.g. `C12345` for TestRail). Path-partitioned
 *   in the index repo so `git log results/testrail/<id>/<device>/latest.json` shows that
 *   cell's full history.
 * @property device Device profile the case ran on (e.g. `android-phone`). One cell per
 *   (case, device) pair — different devices are independent cells.
 * @property consecutive_failures Number of consecutive failing runs leading up to this one
 *   (publisher-computed by walking prior commits to the cell file). 0 on a passing run.
 *   Omitted in `latest_success.json` because a successful run by definition has none.
 * @property metadata Build-level CI context, copied unchanged from the originating
 *   [CiSummaryReport.metadata].
 * @property result The session's per-test row, copied unchanged from
 *   [CiSummaryReport.results].
 */
@Serializable
data class TestResultCell(
  val schema_version: Int = CURRENT_SCHEMA_VERSION,
  val test_case_id: String,
  val device: String,
  val consecutive_failures: Int? = null,
  val metadata: CiRunMetadata,
  val result: SessionResult,
) {
  companion object {
    /**
     * Schema version emitted by the current writer. The reader accepts any value ≤ this and
     * tolerates unknown future fields. Bump only on an incompatible change (e.g. renaming
     * a required field) and update [xyz.block.trailblaze.cli.commands.ResultsCommand] in lockstep.
     */
    const val CURRENT_SCHEMA_VERSION = 2
  }
}
