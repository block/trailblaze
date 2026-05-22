package xyz.block.trailblaze.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Structural regression guard for the `getTrailblazeReportJsonFromBrowser` JS bridge in
 * `src/wasmJsMain/resources/index.html`.
 *
 * The bridge function isn't reachable from JVM tests (it lives in HTML, runs in the
 * browser, and the Kotlin side calls it via the Wasm/JS-interop external declaration in
 * `External.kt`). A full behavioral test would need a JS runtime (Nashorn was removed in
 * JDK 15; Rhino / GraalJS / Playwright would each be a non-trivial dependency).
 *
 * Instead this test pins the **textual structure** of the bridge function — it catches
 * the specific bug shape that broke PR #3149's predecessor: a "clear after first read"
 * pattern that returned `null` to concurrent callers. The test reads index.html and
 * asserts the buggy pattern is absent and the new shared-promise pattern is present.
 *
 * If you legitimately refactor the bridge, update both the `BANNED_PATTERNS` and the
 * `REQUIRED_PATTERNS` below — but think hard before deleting one without a replacement.
 */
class ReportJsonBridgeStructureTest {

  // Patterns that must NOT appear inside the script block. Each pair is (substring, why-banned).
  // Order matches the historical bug shapes the test guards against.
  private val bannedPatterns: List<Pair<String, String>> =
    listOf(
      "window.trailblaze_report[key] = null" to
        "The 'clear after first read' pattern that broke #3149 — a second concurrent " +
        "caller would read null and the Kotlin side would throw JsonDecodingException.",
      "window.trailblaze_report[key] = value" to
        "Storing the parsed value in a side cache is the second half of the same bug. " +
        "The per-key promise should be the single source of truth.",
    )

  // Patterns that MUST appear inside the script block.
  private val requiredPatterns: List<Pair<String, String>> =
    listOf(
      "window.trailblaze_report_decompression_promises" to
        "The shared per-key promise map. Every caller must await the same promise.",
      "callback(trailblazeReportFallback(key))" to
        "Fallback callback on error / no-data — keeps Kotlin's CompletableDeferred " +
        "from hanging when decompression or parse fails.",
    )

  @Test
  fun `bridge function has shared-promise shape, not clear-after-read`() {
    val indexHtml = locateIndexHtml()
    val content = indexHtml.readText()
    val scriptBlock = extractBridgeScriptBlock(content, indexHtml.absolutePath)

    for ((banned, reason) in bannedPatterns) {
      assertFalse(
        scriptBlock.contains(banned),
        "index.html still contains banned pattern `$banned`.\n  Why this is banned: $reason\n" +
          "  Source: ${indexHtml.absolutePath}",
      )
    }

    for ((required, reason) in requiredPatterns) {
      assertTrue(
        scriptBlock.contains(required),
        "index.html is missing required pattern `$required`.\n  Why this is required: $reason\n" +
          "  Source: ${indexHtml.absolutePath}",
      )
    }
  }

  @Test
  fun `bridge function awaits the shared promise instead of re-reading a cache`() {
    val indexHtml = locateIndexHtml()
    val scriptBlock = extractBridgeScriptBlock(indexHtml.readText(), indexHtml.absolutePath)

    // The "in-progress" branch must await the shared promise and pass its resolved string
    // to callback — not re-read from a side cache. We assert the await-then-callback shape
    // by looking for both anchors within a short window of each other.
    val awaitIdx =
      scriptBlock.indexOf("await window.trailblaze_report_decompression_promises[key]")
    assertTrue(awaitIdx >= 0, "missing `await window.trailblaze_report_decompression_promises[key]`")
    // Anything that looks like reading a side cache for the JSON string is forbidden between
    // the await and its callback.
    val tail = scriptBlock.substring(awaitIdx, minOf(scriptBlock.length, awaitIdx + 600))
    assertFalse(
      tail.contains("window.trailblaze_report["),
      "between the shared-promise await and the callback, the code is reading from a " +
        "side cache (`window.trailblaze_report[...]`). That re-introduces the race that #3149 " +
        "fixed. Tail under inspection:\n$tail",
    )
  }

  private fun locateIndexHtml(): File {
    // Tests run with cwd = the module dir; the resource lives in a sibling sourceset.
    // The repo-root fallback uses the two-arg File(parent, child) form so the parent
    // segment is a bare directory name (no trailing slash) — the sensitive-terms
    // scanner has a banned-substring rule that would otherwise reject this file.
    // See `scripts/scan_opensource_sensitive_terms.sh` for the rule.
    val candidates =
      listOf(
        File("src/wasmJsMain/resources/index.html"),
        File("opensource", "trailblaze-ui/src/wasmJsMain/resources/index.html"),
      )
    return candidates.firstOrNull { it.isFile }
      ?: fail(
        "Could not locate index.html. Tried: " +
          candidates.joinToString(", ") { it.absolutePath },
      )
  }

  /**
   * Extract just the inline script block that defines `getTrailblazeReportJsonFromBrowser`.
   * Keeps the assertions narrow — we don't want to trip on banned substrings that legitimately
   * appear in HTML comments elsewhere in the page.
   */
  private fun extractBridgeScriptBlock(content: String, sourcePath: String): String {
    val anchor = "window.getTrailblazeReportJsonFromBrowser = async function"
    val start = content.indexOf(anchor)
    if (start < 0) {
      fail(
        "Could not find `$anchor` in $sourcePath. The bridge function was renamed or removed; " +
          "update this test to match.",
      )
    }
    // Read forward until we hit the next top-level `window.` assignment, which marks the end
    // of the function definition (the next statement in the script block is
    // `window.trailblaze_report_compressed = {};` or similar).
    val nextWindowAssignment = content.indexOf("\n        window.", start + anchor.length)
    val end = if (nextWindowAssignment > 0) nextWindowAssignment else content.length
    return content.substring(start, end)
  }
}
