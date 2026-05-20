package xyz.block.trailblaze.cli

import java.io.File

/**
 * Iterative scale-down loop shared by the report exporters (`--gif`, `--video`, `--webp`).
 * Given an artifact that's already been written once at "full" resolution, repeatedly
 * invokes a format-specific [rescale] callback at successively smaller viewport widths
 * until the artifact fits under [maxBytes] — or until we hit the readability floor at
 * [READABILITY_FLOOR_PX].
 *
 * The widths in [SCALE_WIDTHS] are a deliberate trade-off:
 *  - 1280 is roughly the default viewport from the report UI; if we're already over the
 *    cap at native resolution we drop here first.
 *  - 720 is the GitHub-PR-attachment sweet spot — a typical 14-minute session GIF
 *    rendered there lands around 3-4 MB.
 *  - 480 is the floor — text becomes hard to read for the timeline scrubber's step
 *    labels beyond this point, so we stop rather than producing a postage-stamp
 *    artifact that fits the cap but is unusable.
 *
 * Why width-only (rather than fps / playback-speed knobs): scaling is the cheap,
 * format-agnostic lever. All three exporters shrink approximately quadratically with
 * width, so we get most of the wins from this single axis. Frame-rate / playback-speed
 * knobs are a planned follow-up.
 */
internal object MaxArtifactSize {

  /**
   * Pixel-width ladder the loop walks, largest first. Stops at the readability floor
   * (480). Callers receive each width via the [enforce] rescale callback and are
   * responsible for re-encoding the artifact in place at that width.
   *
   * Visibility is `internal` deliberately: this is the single source of truth for the
   * ladder, and we don't want to commit to a public API that lets external callers
   * override it until we've seen a real use case (the kdoc currently lists "CI run
   * that wants to skip 1280 because the source is known to be huge" as the canonical
   * candidate — but no caller actually needs it yet). Test code in this module can
   * still see `SCALE_WIDTHS` because it lives in the same package.
   */
  internal val SCALE_WIDTHS: List<Int> = listOf(1280, 1024, 720, 640, 480)

  /** Floor below which we refuse to scale — see class kdoc. */
  const val READABILITY_FLOOR_PX: Int = 480

  /**
   * Walk [SCALE_WIDTHS] until `artifact.length() <= maxBytes`. Returns the final
   * (width, fits) pair: `width` is `null` if the artifact already fit without any
   * rescale, otherwise the last width the callback was invoked with; `fits` is whether
   * the artifact actually ended up under the cap.
   *
   * The callback is responsible for replacing [artifact] in place — the loop only
   * inspects `artifact.length()` between iterations.
   *
   * **Thread-safety:** not safe to call concurrently on the same [artifact] from
   * multiple threads. The loop reads `artifact.length()` then invokes [rescale], which
   * writes to the same path; two concurrent invocations would race on both. Callers
   * exporting multiple artifacts in parallel (e.g. a CI matrix running `--gif --webp`
   * concurrently) should ensure each parallel branch operates on a different `artifact`
   * file. Within a single export (one ladder walk per artifact), the loop is
   * sequential by construction.
   */
  fun enforce(
    artifact: File,
    maxBytes: Long,
    rescale: (widthPx: Int) -> Unit,
  ): Result {
    if (artifact.length() <= maxBytes) return Result(widthPx = null, fits = true)
    var lastWidth = SCALE_WIDTHS.first()
    for (w in SCALE_WIDTHS) {
      lastWidth = w
      rescale(w)
      if (artifact.length() <= maxBytes) return Result(widthPx = w, fits = true)
    }
    return Result(widthPx = lastWidth, fits = false)
  }

  /** Outcome of [enforce]. */
  data class Result(
    /** Width the artifact was last rescaled to, or `null` if no rescale ran. */
    val widthPx: Int?,
    /** Whether the artifact ended up under the requested cap. */
    val fits: Boolean,
  )

  /**
   * Parse a human-readable byte-size string into a Long byte count. Accepts:
   *  - bare bytes: `1024`, `1024000`
   *  - kilobytes:  `5K`, `5KB`, `5KiB` (all = 5 * 1024)
   *  - megabytes:  `10M`, `10MB`, `10MiB` (all = 10 * 1024 * 1024)
   *  - gigabytes:  `1G`, `1GB`, `1GiB`
   *  - decimal:    `1.5MB`
   *
   * Units use binary (1024-based) multipliers throughout because the dominant consumer
   * (GitHub's PR-attachment limit) is effectively binary-MB. We deliberately don't
   * distinguish `MB` from `MiB` — they mean the same thing here, and forcing users to
   * remember the distinction would lose more than it gains.
   */
  fun parseSize(input: String): Long {
    val s = input.trim()
    require(s.isNotEmpty()) { "Empty size string." }
    val match = SIZE_REGEX.matchEntire(s)
      ?: throw IllegalArgumentException(
        "Invalid size '$input'. Examples: 10MB, 5M, 1.5G, 1024000.",
      )
    val numStr = match.groupValues[1]
    val unit = match.groupValues[2].uppercase()
    val multiplier = when (unit) {
      "", "B" -> 1L
      "K", "KB", "KIB" -> 1024L
      "M", "MB", "MIB" -> 1024L * 1024
      "G", "GB", "GIB" -> 1024L * 1024 * 1024
      else -> throw IllegalArgumentException(
        "Unknown size unit '$unit' in '$input'. Use bytes, K/KB, M/MB, or G/GB.",
      )
    }
    val value = numStr.toDouble() * multiplier
    require(value >= 1.0) { "Size must be at least 1 byte: '$input'." }
    // Guard the Double→Long conversion: any input above `Long.MAX_VALUE` saturates
    // silently to `Long.MAX_VALUE` rather than wrapping, which would accept absurd caps
    // (e.g. `--max-size=99999999999G` ≈ 10²⁰ bytes ≫ 8 EB) as if they were the canonical
    // ~9.2 EB ceiling. We use a strict `<` against `Long.MAX_VALUE.toDouble()` because
    // Double precision around 2⁶³ collapses ~1024 distinct Long values into a single
    // Double — accepting equality would let any value in that final-quantum band through
    // (rounded down to MAX_VALUE), and there's no legitimate use case for the very last
    // ULP. Strict `<` makes the guard unambiguous.
    require(value < Long.MAX_VALUE.toDouble()) {
      "Size '$input' exceeds the maximum representable byte count (~${Long.MAX_VALUE} bytes / ~8 EB)."
    }
    return value.toLong()
  }

  private val SIZE_REGEX = Regex("^([0-9]+(?:\\.[0-9]+)?)\\s*([A-Za-z]*)$")
}
