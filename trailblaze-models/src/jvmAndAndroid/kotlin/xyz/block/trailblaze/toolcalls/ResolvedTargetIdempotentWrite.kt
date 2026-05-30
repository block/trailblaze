package xyz.block.trailblaze.toolcalls

import java.io.File

/**
 * Shared idempotency helper used by every emitter that writes per-tool sidecar files
 * (workspace `ResolvedTargetReportEmitter`, OSS `DocsGenerator`, internal
 * `TargetToolBaselineGenerator`). Sidecar files are content-hash compared against the
 * existing on-disk bytes before writing; unchanged content is skipped entirely so mtimes
 * stay stable across repeated regenerations. That matters because:
 *
 *  - The framework docs CI step (`scripts/generate-docs-and-diff.sh`) asserts
 *    `git diff --exit-code` after regeneration — content stability is the green-bar
 *    contract.
 *  - Local developer loops re-run `trailblaze check` / `:docs:generator:run` /
 *    `:internal-docs-generator:run` repeatedly. Without this helper, every run churns
 *    `git status` with mtime-only changes that drown out real diffs.
 *
 * Co-located with [ResolvedTargetToolDetailRenderer] so any module that uses the renderer
 * already has the helper without an extra dependency.
 */
object ResolvedTargetIdempotentWrite {

  /**
   * Writes [content] to [file] only if the current on-disk bytes don't match. Returns
   * `true` if a write happened, `false` on a no-op skip. A trailing newline is appended if
   * [content] doesn't already end with one, matching POSIX text-file conventions and
   * keeping the comparison stable across editors that auto-trim.
   *
   * Creates parent directories on demand.
   */
  fun writeIfChanged(file: File, content: String): Boolean {
    val finalText = if (content.endsWith("\n")) content else content + "\n"
    if (file.isFile) {
      val existing = runCatching { file.readText() }.getOrNull()
      if (existing == finalText) return false
    }
    file.parentFile?.mkdirs()
    file.writeText(finalText)
    return true
  }

  /**
   * Sidecar-specific variant of [writeIfChanged] that asserts [file] lands strictly under
   * [containingDir] before any I/O. Defends against tool names whose authoring-side
   * validation (e.g. [xyz.block.trailblaze.config.InlineScriptToolConfig.TOOL_NAME_PATTERN])
   * is bypassed or weakened — a malicious or accidentally pathological name like
   * `../escape` or `nested/dir/leak` would otherwise let the sidecar writer scribble outside
   * the per-target tools directory. Canonical-path containment is the same check
   * [xyz.block.trailblaze.config.project.TrailmapSource.requireTrailmapRelativePath] uses for the
   * trailmap-relative read side.
   *
   * Throws [IllegalArgumentException] when the file resolves outside [containingDir].
   * Returns the same `true`/`false` no-op signal as [writeIfChanged].
   */
  fun writeSidecarIfChanged(containingDir: File, file: File, content: String): Boolean {
    val containerCanon = runCatching { containingDir.canonicalFile }.getOrNull() ?: containingDir
    val fileCanon = runCatching { file.canonicalFile }.getOrNull() ?: file
    require(fileCanon.toPath().startsWith(containerCanon.toPath())) {
      "Refusing to write sidecar outside its containing directory. Got file=$fileCanon, " +
        "containingDir=$containerCanon. Likely a malformed tool name slipped past " +
        "tool-name validation — check the resolved tool registry."
    }
    return writeIfChanged(file, content)
  }
}
