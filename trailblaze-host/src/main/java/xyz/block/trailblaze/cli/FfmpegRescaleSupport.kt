package xyz.block.trailblaze.cli

import java.io.File
import java.util.UUID
import xyz.block.trailblaze.util.Console

/**
 * Shared subprocess scaffolding for the report exporters' rescale paths
 * ([ReportGifExporter], [ReportVideoExporter], [ReportWebpExporter]). Each exporter has
 * a format-specific ffmpeg invocation, but the surrounding ceremony — running the
 * subprocess, draining stdout into the log, checking the exit code, atomically writing
 * onto the caller's `dest` path so a crash can't clobber a previously-good artifact,
 * and cleaning up the temp file on every exit path — is identical across all three.
 *
 * The same capture-side deduplication was applied in PR #3089, which extracted
 * [PlaywrightReportCapture]. With `--max-size` adding a third
 * format-specific rescale path on top of two existing ones, this helper does the same
 * thing on the encoder side: three callers, one place to maintain the subprocess
 * lifecycle.
 *
 * @see runFfmpegToTemp
 */
internal object FfmpegRescaleSupport {

  /**
   * Run an ffmpeg command that writes to a unique system-temp file (with the requested
   * [tempSuffix] so ffmpeg can infer the muxer from the filename if it needs to), then
   * atomically rename it onto [dest]. The command is built by [buildArgs] — the
   * implementation appends its own temp-file path as the last argv element, so callers
   * supply everything BEFORE the output path.
   *
   * Behavior:
   *  - Temp file lives in `java.io.tmpdir` with a UUID-prefixed name; never shares a
   *    directory with `dest` (so a botched encode can't litter the user's output dir).
   *  - The temp file is registered with [File.deleteOnExit] so a JVM SIGKILL (CI
   *    timeout, OOM killer) doesn't leak hundreds of megabytes onto agent disks.
   *  - On success, [dest] is replaced atomically via `renameTo`; cross-filesystem
   *    rename failure falls back to `copyTo`.
   *  - On any exit path (success, ffmpeg non-zero, exception), the temp file is
   *    deleted via finally + runCatching so it can never linger.
   *
   * @param tag Log-line prefix (`ReportGifExporter`, `ReportVideoExporter`, etc.).
   *   Each ffmpeg line is logged as `[$tag/ffmpeg] $line`. The lowercase form is also
   *   used as part of the temp filename — must be `[A-Za-z0-9._-]+` so it can safely
   *   participate in a filename across macOS / Linux / Windows.
   * @param dest Final destination path the caller wants the artifact written to.
   *   Overwritten on success. If `dest.parentFile` is non-null, it must already exist
   *   as a directory — every caller in the report-export path does
   *   `outputXxx.parentFile?.mkdirs()` upstream. A null parent (cwd-relative path
   *   like `File("out.mp4")`) is fine: the rename writes to the JVM working directory.
   * @param tempSuffix Extension for the system-temp file, including the leading dot
   *   (`.mp4`, `.gif`, `.webp`). Picked so ffmpeg's filename-based muxer detection
   *   works regardless of what extension (if any) `dest` carries.
   * @param errorContext Free-form context appended to BOTH the exit-code and the
   *   empty-output `check` messages — typically the target width or other
   *   format-specific knob — so a multi-pass loop doesn't surface identical-looking
   *   failures across iterations.
   * @param buildArgs Lambda that produces the full ffmpeg argv given the temp output
   *   [File]. Implementation is responsible for `"ffmpeg"`, `"-y"`, all inputs, all
   *   filters, all encoder flags, and finally a string form of the path. Receiving a
   *   `File` rather than a `String` keeps the type discipline on the helper-side: the
   *   helper owns the temp file's identity, callers convert it to argv-string form
   *   exactly once.
   */
  fun runFfmpegToTemp(
    tag: String,
    dest: File,
    tempSuffix: String,
    errorContext: String,
    buildArgs: (tempFile: File) -> List<String>,
  ) {
    require(tempSuffix.startsWith(".")) { "tempSuffix must include leading dot: '$tempSuffix'" }
    require(TAG_PATTERN.matches(tag)) {
      // The lowercased tag participates in the temp filename; a slash, colon, or other
      // path-significant character would land us in a sibling directory of /tmp at best
      // and produce a portable-filesystem violation at worst. Constrained to the union
      // of safe identifier chars across macOS / Linux / Windows.
      "tag must match $TAG_PATTERN (path-safe identifier chars only): '$tag'"
    }
    require(dest.parentFile?.let { it.exists() && it.isDirectory } != false) {
      // Without this guard, a missing-parent dest would surface as a generic rename
      // failure 30+ seconds into the ffmpeg run; this short-circuits with a clear
      // message instead. Every report-export caller does `parentFile?.mkdirs()` first,
      // so this is a defense for future callers more than a runtime concern today.
      "dest parent directory does not exist: ${dest.parentFile?.absolutePath}"
    }
    val temp = File(
      System.getProperty("java.io.tmpdir"),
      "trailblaze-${tag.lowercase()}-${UUID.randomUUID().toString().take(8)}$tempSuffix",
    )
    // Registered before the subprocess starts so even an immediate ffmpeg crash leaves
    // a JVM-managed cleanup hook in place. Idempotent: if the finally below already
    // deleted the file, deleteOnExit is a no-op.
    temp.deleteOnExit()
    try {
      val cmd = buildArgs(temp)
      val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
      proc.inputStream.bufferedReader().useLines { lines ->
        lines.forEach { Console.log("[$tag/ffmpeg] $it") }
      }
      val exit = proc.waitFor()
      check(exit == 0) { "ffmpeg $errorContext failed with exit code $exit" }
      check(temp.exists() && temp.length() > 0) {
        // A multi-pass scale loop runs this check N times — without the context, an
        // empty-output failure at pass 3 reads identically to one at pass 1. Including
        // the same errorContext used by the exit-code check above keeps both failure
        // paths symmetric in CI logs.
        "ffmpeg $errorContext reported success but produced no output at ${temp.absolutePath}"
      }
      if (dest.exists()) dest.delete()
      if (!temp.renameTo(dest)) {
        // Cross-filesystem rename (system temp → user output dir on a different
        // volume) can fail; fall back to a copy. The finally below cleans the temp.
        temp.copyTo(dest, overwrite = true)
      }
    } finally {
      runCatching { if (temp.exists()) temp.delete() }
    }
  }

  /**
   * Builds an ffmpeg `scale` filter expression for a target pixel width. Captures the
   * format-specific even-dimension rule the three exporters need:
   *
   *  - [EvenHeight.LANCZOS_AUTO] (`-1`): height auto-computed without rounding. Safe for
   *    formats that accept any pixel height — GIF, since GIF89a has no even-dimension
   *    constraint.
   *  - [EvenHeight.LANCZOS_EVEN] (`-2`): height auto-computed and rounded to the nearest
   *    even value. Required for libx264 (which mandates even dimensions) and used for
   *    libwebp_anim too so downstream WebP decoders that dislike odd dimensions don't
   *    misbehave.
   *
   * Returns just the filter expression text (`"scale=720:-1:flags=lanczos"`), without
   * trailing comma or `-vf` flag. Callers compose it into their format-specific
   * filter graph however the surrounding ffmpeg invocation needs.
   *
   * Returns `null` when `targetWidthPx` is null, so callers can do
   * `scaleFilter(w, ...)?.let { ... }` or string-concatenate with an empty default.
   *
   * Requires `targetWidthPx > 0` when non-null. Zero or negative widths would pass
   * through to ffmpeg as `scale=0:...` or `scale=-N:...` and fail downstream with an
   * opaque codec error; catching it here makes a programmer error surface as a fast,
   * clear precondition violation instead.
   */
  fun scaleFilter(targetWidthPx: Int?, evenHeight: EvenHeight): String? {
    if (targetWidthPx == null) return null
    require(targetWidthPx > 0) {
      "targetWidthPx must be positive, got $targetWidthPx"
    }
    val heightArg = when (evenHeight) {
      EvenHeight.LANCZOS_AUTO -> "-1"
      EvenHeight.LANCZOS_EVEN -> "-2"
    }
    return "scale=$targetWidthPx:$heightArg:flags=lanczos"
  }

  /** Height-rounding rule for [scaleFilter] — see that function's kdoc. */
  enum class EvenHeight {
    /** `-1`: auto-computed, no rounding. Use for codecs that accept odd dimensions (GIF). */
    LANCZOS_AUTO,

    /** `-2`: auto-computed, rounded to even. Use for libx264 (required) and libwebp_anim
     *  (decoders prefer even). */
    LANCZOS_EVEN,
  }

  /** Tag-character whitelist — see kdoc on [runFfmpegToTemp]. */
  private val TAG_PATTERN = Regex("^[A-Za-z0-9._-]+$")

  /**
   * Probe `ffmpeg -encoders` for [encoderName] and `check` it's available. The
   * intermediate-string check is a simple `contains` — sufficient since encoder names
   * are unique and prefix-free in ffmpeg's listing. Fails with [missingHint] appended to
   * a standard "ffmpeg with the X encoder is required" message so each exporter can
   * surface its own install advice (different brew/apt/apk recipes per platform, or
   * "switch to --gif" workaround text). Run before launching the capture pipeline so a
   * misconfigured environment fails in ~100ms rather than after 30s of headless
   * screenshotting plus a generic non-zero ffmpeg exit.
   *
   * Replaces what was historically per-exporter copies of the same probe — see
   * [ReportWebpExporter.requireLibwebpAnim]; consolidated here so an ffmpeg-side
   * change (e.g. the `-encoders` flag spelling) only needs to be tracked in one place.
   */
  fun requireEncoder(encoderName: String, missingHint: String) {
    // Empty `encoderName` would make `out.contains("")` vacuously true downstream, so
    // a misconfigured caller would silently report any-encoder-available and then hit
    // an opaque codec error at encode time. Reject at the boundary instead. Same for
    // `missingHint` — an empty hint produces a useless error message ("ffmpeg with the
    // `libwebp` encoder is required but was not found on PATH. ") that gives the user
    // nothing to act on.
    require(encoderName.isNotBlank()) { "encoderName must be non-blank" }
    require(missingHint.isNotBlank()) { "missingHint must be non-blank — pass install advice for this encoder" }
    val available = runCatching {
      val proc = ProcessBuilder("ffmpeg", "-hide_banner", "-encoders")
        .redirectErrorStream(true)
        .start()
      val out = proc.inputStream.bufferedReader().readText()
      proc.waitFor()
      out.contains(encoderName)
    }.getOrDefault(false)
    check(available) {
      "ffmpeg with the `$encoderName` encoder is required but was not found on PATH. " +
        missingHint
    }
  }
}
