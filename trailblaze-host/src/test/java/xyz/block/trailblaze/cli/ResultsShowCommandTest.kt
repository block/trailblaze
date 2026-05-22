package xyz.block.trailblaze.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit-level checks for the lightweight pure helpers on [ResultsShowCommand].
 *
 * The HTTP-fetching path is exercised end-to-end by manual smoke tests against a live
 * results repo (resolved from `--repo` or `TRAILBLAZE_RESULTS_REPO`); mocking the GitHub
 * Contents API at unit level would re-encode the API contract rather than test it.
 * The flag-validation + regex-validation paths below are pure and worth pinning so a
 * future flag addition can't bypass the mutual-exclusion / device-segment guards.
 */
class ResultsShowCommandTest {

  @Test
  fun `normalizeCaseId accepts canonical C-prefixed form`() {
    assertEquals("C12345", ResultsShowCommand.normalizeCaseId("C12345"))
  }

  @Test
  fun `normalizeCaseId accepts lowercase c prefix`() {
    // Users hand-typing case IDs at the shell shouldn't need to hit shift; normalize
    // back to canonical so the path on disk matches the case the publish script wrote.
    assertEquals("C12345", ResultsShowCommand.normalizeCaseId("c12345"))
  }

  @Test
  fun `normalizeCaseId accepts bare digits`() {
    assertEquals("C12345", ResultsShowCommand.normalizeCaseId("12345"))
  }

  @Test
  fun `normalizeCaseId trims surrounding whitespace`() {
    assertEquals("C12345", ResultsShowCommand.normalizeCaseId("  C12345 \n"))
  }

  @Test
  fun `normalizeCaseId rejects empty and whitespace-only input`() {
    assertNull(ResultsShowCommand.normalizeCaseId(""))
    assertNull(ResultsShowCommand.normalizeCaseId("   "))
  }

  @Test
  fun `normalizeCaseId rejects non-digit payload`() {
    // Refuse anything that doesn't reduce to digits-after-optional-C — silent fall-back
    // would issue a GitHub fetch for a path that can never exist and confuse the caller.
    assertNull(ResultsShowCommand.normalizeCaseId("CXYZ"))
    assertNull(ResultsShowCommand.normalizeCaseId("C12 345"))
    assertNull(ResultsShowCommand.normalizeCaseId("C-12345"))
  }

  // --- DEVICE_SEGMENT_REGEX -------------------------------------------------
  //
  // The same shape is enforced by the writer-side publisher script's TRAILBLAZE_TEST_RESULTS_DEVICE
  // validation and by the index repo's JSON Schema `device` field pattern. Tests
  // here pin the contract on the reader side so a refactor that loosens the
  // regex shows up loudly.

  @Test
  fun `DEVICE_SEGMENT_REGEX accepts common device profile ids`() {
    listOf(
      "android-phone",
      "android-tablet",
      "ios-iphone",
      "ios-ipad",
      "web",
      "a1",
      "a_b_c",
      "ALL-CAPS-OK",
    ).forEach { id ->
      assertTrue(
        ResultsShowCommand.DEVICE_SEGMENT_REGEX.matches(id),
        "expected '$id' to be a legal device segment",
      )
    }
  }

  @Test
  fun `DEVICE_SEGMENT_REGEX rejects path-traversal and separator characters`() {
    // A malformed device string must not let a writer / reader address paths outside
    // the (case, device) directory — slashes, dots, spaces, traversal segments all
    // collapse to "no". Empty is also a rejection because the path-component must
    // exist (the regex uses `+`, not `*`). The standalone-dot and leading-dot cases
    // matter because each creates a git-hidden / shell-special entry that's hard to
    // notice once committed.
    listOf(
      "",
      ".",
      "..",
      ".hidden",
      "trailing.dot",
      "android/phone",
      "android.phone",
      "android phone",
      "android\\phone",
      "android:phone",
      "android+phone",
    ).forEach { id ->
      assertFalse(
        ResultsShowCommand.DEVICE_SEGMENT_REGEX.matches(id),
        "expected '$id' to be REJECTED as a device segment",
      )
    }
  }

  // --- parseRunBlock --------------------------------------------------------
  //
  // Pure JSON-extraction. Pinning these so a future schema field rename can't
  // silently break the CellSummary surface that `--all-devices` exit-code logic
  // depends on.

  @Test
  fun `parseRunBlock returns EMPTY for null input`() {
    // Caller signals "couldn't fetch" (e.g., 404 or HTTP failure) by passing null.
    val result = ResultsShowCommand.parseRunBlock(null)
    assertEquals(ResultsShowCommand.CellSummary.EMPTY, result)
  }

  @Test
  fun `parseRunBlock returns EMPTY for malformed JSON`() {
    assertEquals(ResultsShowCommand.CellSummary.EMPTY, ResultsShowCommand.parseRunBlock("not json"))
    assertEquals(ResultsShowCommand.CellSummary.EMPTY, ResultsShowCommand.parseRunBlock("{"))
  }

  @Test
  fun `parseRunBlock returns EMPTY when run block is absent`() {
    val rawJson = """{"testCaseId":"C12345","device":"android-phone","schemaVersion":1}"""
    assertEquals(ResultsShowCommand.CellSummary.EMPTY, ResultsShowCommand.parseRunBlock(rawJson))
  }

  @Test
  fun `parseRunBlock extracts status, timestamp, consecutiveFailures when present`() {
    val rawJson =
      """
      {
        "testCaseId":"C12345",
        "device":"android-phone",
        "schemaVersion":1,
        "run":{
          "status":"fail",
          "timestamp":"2026-05-21T08:14:00Z",
          "consecutiveFailures":3
        }
      }
      """.trimIndent()
    val result = ResultsShowCommand.parseRunBlock(rawJson)
    assertEquals("fail", result.status)
    assertEquals("2026-05-21T08:14:00Z", result.timestamp)
    assertEquals(3, result.consecutiveFailures)
  }

  @Test
  fun `parseRunBlock leaves consecutiveFailures null when absent`() {
    // latest_success.json never carries consecutiveFailures — a successful run has no
    // failure streak by definition. Parser must accept the field's absence without
    // collapsing the entire CellSummary to EMPTY.
    val rawJson =
      """
      {
        "testCaseId":"C12345",
        "device":"android-phone",
        "schemaVersion":1,
        "run":{
          "status":"pass",
          "timestamp":"2026-05-20T08:14:00Z"
        }
      }
      """.trimIndent()
    val result = ResultsShowCommand.parseRunBlock(rawJson)
    assertEquals("pass", result.status)
    assertEquals("2026-05-20T08:14:00Z", result.timestamp)
    assertNull(result.consecutiveFailures)
  }

  // --- summarizeNotes -------------------------------------------------------
  //
  // Pure verdict logic that feeds both the table-cell notes text AND the
  // `--all-devices` exit code. Tests pin all four cells of the (latest × success)
  // matrix so a future refactor can't silently flip a passing device into "FAILING"
  // or hide an unknown-state device as clean.

  @Test
  fun `summarizeNotes - pass with prior success - clean and passing`() {
    val latest = ResultsShowCommand.CellSummary("pass", "2026-05-21T08:14:00Z", null)
    val latestSuccess = ResultsShowCommand.CellSummary("pass", "2026-05-21T08:14:00Z", null)
    val result = ResultsShowCommand.summarizeNotes(latest, latestSuccess)
    assertEquals("", result.text)
    assertFalse(result.isNonPassing)
  }

  @Test
  fun `summarizeNotes - fail with consecutive count - rendered as count and non-passing`() {
    val latest = ResultsShowCommand.CellSummary("fail", "2026-05-21T08:14:00Z", 3)
    val latestSuccess = ResultsShowCommand.CellSummary("pass", "2026-05-18T08:14:00Z", null)
    val result = ResultsShowCommand.summarizeNotes(latest, latestSuccess)
    assertEquals("3 consecutive fails", result.text)
    assertTrue(result.isNonPassing)
  }

  @Test
  fun `summarizeNotes - fail without consecutive count - generic FAILING and non-passing`() {
    val latest = ResultsShowCommand.CellSummary("fail", "2026-05-21T08:14:00Z", null)
    val latestSuccess = ResultsShowCommand.CellSummary("pass", "2026-05-18T08:14:00Z", null)
    val result = ResultsShowCommand.summarizeNotes(latest, latestSuccess)
    assertEquals("FAILING", result.text)
    assertTrue(result.isNonPassing)
  }

  @Test
  fun `summarizeNotes - never passed - appends to existing notes and non-passing`() {
    val latest = ResultsShowCommand.CellSummary("fail", "2026-05-21T08:14:00Z", 5)
    val latestSuccess = ResultsShowCommand.CellSummary.EMPTY
    val result = ResultsShowCommand.summarizeNotes(latest, latestSuccess)
    assertEquals("5 consecutive fails · never passed", result.text)
    assertTrue(result.isNonPassing)
  }

  @Test
  fun `summarizeNotes - unknown status on listed device is non-passing`() {
    // The directory listing in `--all-devices` is authoritative — if the device dir
    // exists, the test ran on that device. A null status from a 403, corrupt file, or
    // missing file is a real "we can't determine" state. CI-gate semantics must treat
    // this conservatively (not pass) so a token issue can't silently green-light a
    // `results show … && deploy` chain.
    val latest = ResultsShowCommand.CellSummary.EMPTY
    val latestSuccess = ResultsShowCommand.CellSummary("pass", "2026-05-18T08:14:00Z", null)
    val result = ResultsShowCommand.summarizeNotes(latest, latestSuccess)
    assertEquals("data unavailable", result.text)
    assertTrue(result.isNonPassing)
  }

  @Test
  fun `summarizeNotes - pass with no recorded success is non-passing`() {
    // Contradictory state that's only reachable when `latest_success.json` failed to fetch
    // (403, missing, mid-write) — the publisher always writes both files on a pass, so a
    // pass-status with no recorded success means we couldn't read the full picture. Notes
    // say "never passed" to surface the missing-success signal; verdict must NOT green-light
    // the CI gate because we're working with incomplete information.
    val latest = ResultsShowCommand.CellSummary("pass", "2026-05-21T08:14:00Z", null)
    val latestSuccess = ResultsShowCommand.CellSummary.EMPTY
    val result = ResultsShowCommand.summarizeNotes(latest, latestSuccess)
    assertEquals("never passed", result.text)
    assertTrue(result.isNonPassing)
  }

  // --- parseRunBlock defensive branches -------------------------------------
  //
  // Three branches that exist to be robust against partial / future-shape documents
  // but are easy to silently break in a refactor. Cheap to pin.

  @Test
  fun `parseRunBlock returns EMPTY when run is not a JSON object`() {
    // Defensive: a manual edit / corrupted writer could leave `run` as a string or array.
    // The `runCatching { it.jsonObject }` swallows the cast failure and we should still
    // produce a CellSummary (EMPTY), not crash.
    val rawJson = """{"testCaseId":"C12345","device":"android-phone","schemaVersion":1,"run":"oops"}"""
    assertEquals(ResultsShowCommand.CellSummary.EMPTY, ResultsShowCommand.parseRunBlock(rawJson))
  }

  @Test
  fun `parseRunBlock returns null status when run-status field is absent`() {
    // The `run` block exists but its `status` field doesn't. Reader treats this as a
    // null status (downstream `summarizeNotes` routes that to "data unavailable" +
    // non-passing verdict — same effect as "couldn't read at all").
    val rawJson =
      """
      {
        "testCaseId":"C12345",
        "device":"android-phone",
        "schemaVersion":1,
        "run":{"timestamp":"2026-05-21T08:14:00Z"}
      }
      """.trimIndent()
    val result = ResultsShowCommand.parseRunBlock(rawJson)
    assertNull(result.status)
    assertEquals("2026-05-21T08:14:00Z", result.timestamp)
  }

  @Test
  fun `parseRunBlock coerces non-int consecutiveFailures to null`() {
    // Defensive against a partial-write / corrupted file where the field exists but
    // isn't an integer. `toIntOrNull` returns null and downstream
    // `summarizeNotes` falls through to the generic "FAILING" branch (no count).
    val rawJson =
      """
      {
        "testCaseId":"C12345",
        "device":"android-phone",
        "schemaVersion":1,
        "run":{"status":"fail","timestamp":"2026-05-21T08:14:00Z","consecutiveFailures":"abc"}
      }
      """.trimIndent()
    val result = ResultsShowCommand.parseRunBlock(rawJson)
    assertEquals("fail", result.status)
    assertNull(result.consecutiveFailures)
  }
}
