package xyz.block.trailblaze.report

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.block.trailblaze.logs.model.SessionId

/**
 * Unit tests for [WasmReport.mergeSessionVideoFrames] — the pure fold that makes the concurrent
 * per-session video-frame extraction in [WasmReport.extractVideoFrames] safe. The fan-out decodes
 * sessions in parallel; correctness rests on the merge being a deterministic function of the
 * (session-ordered) result list, not of which decode finished first. These drive the fold directly
 * with plain [WasmReport.SessionVideoFrames] inputs — no device, no ffmpeg, no sprites.
 */
class WasmReportMergeVideoFramesTest {

  private fun frames(vararg keys: String): Map<String, ByteArray> =
    keys.associateWith { it.toByteArray() }

  @Test
  fun `merges every session's frames, aliases, trim, and degenerate flags`() {
    val a = SessionId("a")
    val b = SessionId("b")
    val merged = WasmReport.mergeSessionVideoFrames(
      listOf(
        a to WasmReport.SessionVideoFrames(
          frames = frames("a/0", "a/1"),
          aliases = mapOf("a/2" to "a/0"),
          trim = WasmReport.TrimmedVideoInfo(0, 100),
          degenerate = false,
        ),
        b to WasmReport.SessionVideoFrames(
          frames = frames("b/0"),
          aliases = mapOf("b/1" to "b/0"),
          trim = WasmReport.TrimmedVideoInfo(5, 50),
          degenerate = true,
        ),
      ),
    )

    assertEquals(setOf("a/0", "a/1", "b/0"), merged.frames.keys)
    assertEquals(mapOf("a/2" to "a/0", "b/1" to "b/0"), merged.aliases)
    assertEquals(setOf("a", "b"), merged.trimInfo.keys)
    assertEquals(WasmReport.TrimmedVideoInfo(5, 50), merged.trimInfo["b"])
    assertEquals(setOf("b"), merged.degenerateSpriteSessions)
  }

  @Test
  fun `frame order follows the input session order, not map hashing`() {
    // A HashMap-based merge would reorder these; the fold must preserve perSession order so the
    // embedded frame sequence is stable across runs.
    val perSession = listOf(
      SessionId("z") to WasmReport.SessionVideoFrames(frames("z/0"), emptyMap(), null, false),
      SessionId("m") to WasmReport.SessionVideoFrames(frames("m/0", "m/1"), emptyMap(), null, false),
      SessionId("a") to WasmReport.SessionVideoFrames(frames("a/0"), emptyMap(), null, false),
    )

    val merged = WasmReport.mergeSessionVideoFrames(perSession)

    assertEquals(listOf("z/0", "m/0", "m/1", "a/0"), merged.frames.keys.toList())
  }

  @Test
  fun `output is identical no matter which decode finished first`() {
    // awaitAll preserves the launch order, but the merge itself must not depend on ordering beyond
    // the list it's handed: shuffling the SAME results must yield the same content (order tracks the
    // list, which is what upstream pins to session order).
    val results = listOf(
      SessionId("s1") to WasmReport.SessionVideoFrames(frames("s1/0"), mapOf("s1/1" to "s1/0"), WasmReport.TrimmedVideoInfo(1, 2), false),
      SessionId("s2") to WasmReport.SessionVideoFrames(frames("s2/0"), emptyMap(), null, true),
      SessionId("s3") to WasmReport.SessionVideoFrames(frames("s3/0"), emptyMap(), WasmReport.TrimmedVideoInfo(3, 4), false),
    )

    val forward = WasmReport.mergeSessionVideoFrames(results)
    val reversed = WasmReport.mergeSessionVideoFrames(results.reversed())

    assertEquals(forward.frames.keys, reversed.frames.keys)
    assertEquals(forward.aliases, reversed.aliases)
    assertEquals(forward.trimInfo, reversed.trimInfo)
    assertEquals(forward.degenerateSpriteSessions, reversed.degenerateSpriteSessions)
  }

  @Test
  fun `an empty session contributes nothing`() {
    val merged = WasmReport.mergeSessionVideoFrames(
      listOf(
        SessionId("real") to WasmReport.SessionVideoFrames(frames("real/0"), emptyMap(), WasmReport.TrimmedVideoInfo(0, 1), false),
        SessionId("empty") to WasmReport.SessionVideoFrames.EMPTY,
      ),
    )

    assertEquals(setOf("real/0"), merged.frames.keys)
    assertEquals(setOf("real"), merged.trimInfo.keys)
    assertTrue(merged.aliases.isEmpty())
    assertTrue(merged.degenerateSpriteSessions.isEmpty())
  }

  @Test
  fun `a degenerate session with no frames still registers as degenerate`() {
    val merged = WasmReport.mergeSessionVideoFrames(
      listOf(
        SessionId("degen") to WasmReport.SessionVideoFrames(emptyMap(), emptyMap(), null, degenerate = true),
      ),
    )

    assertTrue(merged.frames.isEmpty())
    assertEquals(setOf("degen"), merged.degenerateSpriteSessions)
  }
}
