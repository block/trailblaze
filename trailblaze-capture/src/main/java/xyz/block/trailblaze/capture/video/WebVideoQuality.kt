package xyz.block.trailblaze.capture.video

import xyz.block.trailblaze.util.Console

/**
 * How sharp a web session recording is encoded, traded against its size. Chosen per process with
 * [ENV_VAR]; see [RecordingFormat.webScreencastEncodeArgs] for how it is applied.
 *
 * Sizes are from a real 5:53 web signup session at 1280x800. They scale with how much the page
 * changes, not with session length, so a page that never sits still runs higher.
 */
enum class WebVideoQuality(
  /** libvpx-vp9 `-crf`: lower is sharper and bigger. */
  val crf: Int,
) {
  /** ~0.5 MB per minute: clean text with no JPEG grain, about the size recordings were before. */
  STANDARD(crf = 24),

  /**
   * ~0.9 MB per minute: small text stays crisp through scrolling and page changes. A long session
   * can outgrow what the HTML report embeds (12 MiB, ~14 minutes here), and the report then shows
   * per-step screenshots instead of the video.
   */
  HIGH(crf = 8),
  ;

  companion object {
    /** `standard` (the default) or `high`. Read by the daemon, so restart it for a change to apply. */
    const val ENV_VAR = "TRAILBLAZE_WEB_VIDEO_QUALITY"

    /** The quality [ENV_VAR] names; unset reads as [STANDARD], and an unknown value is logged and ignored. */
    fun fromEnv(env: (String) -> String? = System::getenv): WebVideoQuality {
      val raw = env(ENV_VAR)?.trim()?.takeIf { it.isNotEmpty() } ?: return STANDARD
      return entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: STANDARD.also {
        Console.log("[WebVideoQuality] ignoring $ENV_VAR=$raw; expected standard or high")
      }
    }
  }
}
