package xyz.block.trailblaze.capture.memory

import kotlin.math.abs

/**
 * Decides whether a new [MemorySnapshot] is different enough from the last EMITTED one to be worth
 * a `memory` event. Pure, so the "when there are changes" policy is unit-tested on its own.
 *
 * This policy governs the periodic samples only. Samples taken at a tool-call boundary
 * (`before_tool` / `after_tool`) and the session bookends (`first` / `final`) are always written —
 * the pair around a tool IS the measurement, whether or not it moved.
 *
 * A session samples every few seconds for minutes; writing every sample would bury the timeline in
 * identical rows. The threshold keeps a steady state silent and lets real movement — a screen
 * allocating a few megabytes, the process dying — through.
 *
 * **One metric decides, deliberately.** [MemorySnapshot.usedKb] — the Java heap, or the footprint
 * on iOS — is both what this policy watches and the headline the event carries. A leak that never
 * touches it (native, graphics, an Activity that is never collected) produces no periodic rows;
 * the snapshot holds those figures, so following one is a matter of emitting it here AND in
 * [MemorySnapshot.toEventPayload]. Adding it to only one of the two gets the metric onto rows some
 * other movement already earned, which reads as if nothing happened in between.
 */
object MemoryChangeDetector {

  /** A change in the app's heap used (footprint on iOS) smaller than this is noise. */
  const val USED_THRESHOLD_KB: Long = 1024

  const val REASON_FIRST = "first"
  const val REASON_FINAL = "final"
  const val REASON_BEFORE_TOOL = "before_tool"
  const val REASON_AFTER_TOOL = "after_tool"
  const val REASON_PROCESS_STARTED = "process_started"
  const val REASON_PROCESS_DIED = "process_died"
  const val REASON_PROCESS_RESTARTED = "process_restarted"
  const val REASON_MEMORY_CHANGED = "memory_changed"

  /**
   * The app's process appearing, changing or vanishing between [previous] and [current], or null
   * when it did neither.
   *
   * Separate from [changeReason] because a transition outranks the reason a reading was ASKED for:
   * a tool boundary that discovers the app gone is the only chance to say so — the death is now in
   * the last emitted snapshot, so the next periodic pass compares two pid-less readings and sees
   * nothing to report. A row labelled `after_tool` instead of `process_died` reads as an ordinary
   * sample, and the app's death goes unreported for the whole session.
   */
  fun processTransition(previous: MemorySnapshot?, current: MemorySnapshot): String? {
    if (previous == null) return null
    return when {
      previous.pid == null && current.pid != null -> REASON_PROCESS_STARTED
      previous.pid != null && current.pid == null -> REASON_PROCESS_DIED
      previous.pid != null && current.pid != null && previous.pid != current.pid -> REASON_PROCESS_RESTARTED
      else -> null
    }
  }

  /** The reason [current] should be emitted given the last emitted [previous], or null to skip. */
  fun changeReason(previous: MemorySnapshot?, current: MemorySnapshot): String? {
    if (previous == null) return REASON_FIRST
    processTransition(previous, current)?.let { return it }
    val before = previous.usedKb
    val now = current.usedKb
    // A dump-parse failure (a shape this parser doesn't recognise, e.g. on an older build) nulls
    // `usedKb` without killing the process. Going unreadable stays silent — that's parser noise,
    // not a signal, and AdbMemoryProbeTest/OnDeviceRpcMemoryProbeTest pin that contract. But if we
    // never emit a memory-becomes-readable-again row either, a run that hit the parse failure once
    // stays silent for the rest of the session: the skipped write also skips advancing
    // `lastEmitted`, so once a boundary sample (`before_tool`/`after_tool`/`first`/`final`, which
    // are written unconditionally) freezes `lastEmitted` on that valueless snapshot, only recovery
    // — not the next unreadable sample — can move it again.
    if (before == null) return if (now != null) REASON_MEMORY_CHANGED else null
    if (now == null) return null
    if (abs(now - before) >= USED_THRESHOLD_KB) return REASON_MEMORY_CHANGED
    return null
  }
}
