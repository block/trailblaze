package xyz.block.trailblaze.report

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import xyz.block.trailblaze.report.models.CiRunMetadata
import xyz.block.trailblaze.report.models.CiSummaryReport
import xyz.block.trailblaze.report.models.Outcome
import xyz.block.trailblaze.report.models.SOURCE_TYPE_HANDWRITTEN
import xyz.block.trailblaze.report.models.SkippedTrail
import xyz.block.trailblaze.report.utils.LogsRepo

/**
 * The runner-to-report channel for trails that never ran.
 *
 * A skip is honored before any session opens, so it leaves nothing in the logs the report is built
 * from. These records are the only evidence a skipped trail existed, which makes their identity
 * rules (what counts as the same skip, what counts as two) the contract worth pinning down.
 */
class SkippedTrailsTest {

  private fun skip(
    testKey: String = "checkout/pay",
    target: String? = null,
    deviceClassifier: String? = "android-phone",
    reason: String = "flaky on tablets, see #2194",
    recordedAt: Long = 1_700_000_000_000,
    metadata: Map<String, String>? = null,
  ) = SkippedTrail(
    trail_path = "/repo/trails/$testKey.trail.yaml",
    title = "Pay at checkout",
    test_key = testKey,
    target = target,
    reason = reason,
    platform = "android",
    device_classifier = deviceClassifier,
    trail_source = SOURCE_TYPE_HANDWRITTEN,
    metadata = metadata,
    recorded_at_epoch_ms = recordedAt,
  )

  private fun withLogsDir(body: (File) -> Unit) {
    val logsDir = Files.createTempDirectory("trailblaze-skipped-test").toFile()
    try {
      body(logsDir)
    } finally {
      logsDir.deleteRecursively()
    }
  }

  @Test
  fun `a recorded skip reads back whole`() = withLogsDir { logsDir ->
    val recorded = skip()
    SkippedTrails.record(logsDir, recorded)

    assertEquals(listOf(recorded), SkippedTrails.read(logsDir))
  }

  @Test
  fun `read is empty for a logs dir no skip was ever recorded into`() = withLogsDir { logsDir ->
    // The normal case: nothing was skipped, and asking must not create the directory or throw.
    assertEquals(emptyList<SkippedTrail>(), SkippedTrails.read(logsDir))
    assertTrue(!File(logsDir, SkippedTrails.DIR_NAME).exists())
  }

  @Test
  fun `re-recording the same skip replaces it instead of adding a duplicate`() = withLogsDir { logsDir ->
    // CI runs `trailblaze run <trail>` once per trail into one logs dir, and a build can retry a
    // whole shard. Without identity-derived file names each pass would append another copy and the
    // report would show the same held-back trail N times.
    SkippedTrails.record(logsDir, skip(recordedAt = 1_700_000_000_000))
    SkippedTrails.record(logsDir, skip(recordedAt = 1_700_000_009_999))

    val read = SkippedTrails.read(logsDir)
    assertEquals(1, read.size)
    assertEquals(1_700_000_009_999, read.single().recorded_at_epoch_ms)
  }

  @Test
  fun `the same trail skipped on two devices stays two records`() = withLogsDir { logsDir ->
    SkippedTrails.record(logsDir, skip(deviceClassifier = "android-phone"))
    SkippedTrails.record(logsDir, skip(deviceClassifier = "android-tablet"))

    assertEquals(
      listOf("android-phone", "android-tablet"),
      SkippedTrails.read(logsDir).mapNotNull { it.device_classifier }.sorted(),
    )
  }

  @Test
  fun `one trail id skipped on two targets stays two records`() = withLogsDir { logsDir ->
    // A `config.id` can be authored against several targets, and the report gives each its own
    // matrix row. Collapsing them here would report one of the two skips and silently drop the
    // other.
    SkippedTrails.record(logsDir, skip(target = "shop"))
    SkippedTrails.record(logsDir, skip(target = "wallet"))

    assertEquals(
      listOf("shop", "wallet"),
      SkippedTrails.read(logsDir).mapNotNull { it.target }.sorted(),
    )
  }

  @Test
  fun `skips stamped in the same millisecond still have one order`() {
    // Two records of one trail differ only by device, so they share a timestamp AND a title. If the
    // comparator stops there the pair is "equal", the order falls through to whatever `listFiles`
    // handed back, and the report's Skipped section reshuffles between two reads of one directory.
    // Asserted against the comparator rather than through a real directory: the filesystem is free
    // to return the right order by luck, which is exactly what makes the bug hard to see.
    val ios = skip(deviceClassifier = "ios-iphone")
    val android = skip(deviceClassifier = "android-phone")

    assertEquals(
      listOf(android, ios),
      listOf(ios, android).sortedWith(SkippedTrails.RECORD_ORDER),
    )
  }

  @Test
  fun `the skip directory is not enumerated as a session`() = withLogsDir { logsDir ->
    // `skipped/` lives inside the logs dir, and LogsRepo treats every child directory as a session.
    // Counted as one, `LogsSummary.count` reports a session the summary has no row for, and every
    // report generator is asked for the logs of a directory that has none.
    File(logsDir, "2026-08-27_10-00-00_checkout").mkdirs()
    SkippedTrails.record(logsDir, skip())

    val sessionIds = LogsRepo(logsDir, watchFileSystem = false).getSessionIds().map { it.value }
    assertEquals(listOf("2026-08-27_10-00-00_checkout"), sessionIds)
  }

  @Test
  fun `an undecodable record is dropped and the rest still read`() = withLogsDir { logsDir ->
    // A half-written or newer-format record must not take down the report of the trails that ran.
    SkippedTrails.record(logsDir, skip())
    File(logsDir, "${SkippedTrails.DIR_NAME}/skip-deadbeef.json").writeText("{not json")

    assertEquals(listOf("checkout/pay"), SkippedTrails.read(logsDir).map { it.test_key })
  }

  @Test
  fun `records land in their own subdirectory under a name the log reader ignores`() = withLogsDir { logsDir ->
    // Two separate hazards, both avoided by where and how the file is named: `ReportMain` deletes
    // unrecognized `.json` files in the logs-dir ROOT, and `LogsRepo` treats a file whose name
    // starts with a hex character as a session log to decode.
    val written = SkippedTrails.record(logsDir, skip())!!

    assertEquals(File(logsDir, SkippedTrails.DIR_NAME), written.parentFile)
    assertTrue(written.name.startsWith("skip-"), "unexpected record name: ${written.name}")
    assertTrue(logsDir.listFiles()!!.none { it.isFile && it.extension == "json" })
  }

  @Test
  fun `the report row says SKIPPED and carries the reason`() {
    val row = skip().toSessionResult()

    assertEquals(Outcome.SKIPPED, row.outcome)
    assertEquals("flaky on tablets, see #2194", row.failure_reason)
    assertEquals("Pay at checkout", row.title)
    // The matrix keys a row on test_key and a column on platform + classifier. Sharing them with
    // the sessions that DID run is what puts the skipped device's cell on the same row as theirs
    // instead of in a row of its own.
    assertEquals("checkout/pay", row.test_key)
    assertEquals("android", row.platform)
    assertEquals("android-phone", row.device_classifier)
    assertEquals(SOURCE_TYPE_HANDWRITTEN, row.trail_source)
    assertEquals(0, row.duration_ms)
    // No session ran, so nothing may point at a session directory that would need to exist.
    assertEquals(SkippedTrails.syntheticSessionId(skip()), row.session_id)
  }

  @Test
  fun `a skipped row still names the trail it was`() {
    // A null `trail_file_path` is half of how a row says "this was never a trail" - the reading
    // that lets a report consumer discount a harness test that replayed nothing. A skip is the
    // opposite: a real trail the runner declined. It also carries a real platform, so it would not
    // land in that bucket on the null alone, but naming its file is what keeps it out on both.
    val row = skip().toSessionResult()

    assertEquals("/repo/trails/checkout/pay.trail.yaml", row.trail_file_path)
  }

  @Test
  fun `a skipped trail keeps the durable case id its runs join on`() {
    // `config.metadata.testRailCaseId` is how a trail declares a case id its `test_key` no longer
    // spells. Every consumer prefers that map over parsing the key, so dropping it here would give
    // a skipped row a null case id while the same trail's runs carry a real one - one trail's
    // history split in two at exactly the rows that explain the gap.
    val row = skip(metadata = mapOf("testRailCaseId" to "4839323")).toSessionResult()

    assertEquals(mapOf("testRailCaseId" to "4839323"), row.metadata)
  }

  @Test
  fun `a skip's row states when it was decided, so a later run of the same trail supersedes it`() {
    // The viewer orders a trail's rows for one device by the stamp the index renders here, and
    // falls back to payload order, where skips are appended last. Unstamped, a trail skipped in
    // one run and RUN in a later one would still show the old skip as the latest word on it.
    val row = skip(recordedAt = 1_700_000_000_000).toSessionResult()
    val meta = RunIndexGenerator.stubMeta(row, CiSummaryReport(metadata = CiRunMetadata(), results = listOf(row)), null)

    assertNotNull(meta["ranAt"], "a skipped row with no stamp sorts by payload order, which puts it last")
  }

  @Test
  fun `two devices skipping one trail get distinct row ids`() {
    // Both records become rows in the same report; a shared id would fold them into one row and
    // lose a device's skip entirely.
    val phone = SkippedTrails.syntheticSessionId(skip(deviceClassifier = "android-phone"))
    val tablet = SkippedTrails.syntheticSessionId(skip(deviceClassifier = "android-tablet"))

    assertTrue(phone != tablet, "both skips resolved to $phone")
  }

  @Test
  fun `a row id survives a re-run of the same skip`() {
    // The id is what a later report keys the row on, so it has to be derived from the skip's
    // identity rather than from when it was recorded.
    assertEquals(
      SkippedTrails.syntheticSessionId(skip(recordedAt = 1_700_000_000_000)),
      SkippedTrails.syntheticSessionId(skip(recordedAt = 1_700_000_009_999)),
    )
  }
}
