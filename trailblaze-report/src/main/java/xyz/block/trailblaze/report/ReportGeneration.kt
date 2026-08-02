package xyz.block.trailblaze.report

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.util.Console

/**
 * The shared HTML-report generation core used by every report-producing surface: the CLI
 * (`trailblaze report` / after-run reports via `CliReportGenerator`), the CI/Gradle entry
 * point (`GenerateReportCliCommand` in [ReportMain]), and the daemon's `/report` endpoint.
 * Before this file each surface carried its own drifted copy of the same responsibilities,
 * now owned by two composable functions:
 *
 * - [generateWasmReport] owns one legacy WASM report build: **session scoping** (a
 *   temporary symlink [LogsRepo] when the caller narrows to a subset of sessions) and the
 *   **[WasmReport] single-flight invariant** (a JVM-wide lock, because [WasmReport] carries
 *   mutable shared state).
 * - [overlapReports] owns the **concurrent WASM + interactive execution pattern**: the
 *   interactive leg (mostly waiting on an external bun subprocess) runs on a background
 *   thread overlapping the CPU-bound WASM build.
 *
 * Callers keep their own output naming, template resolution, and error surfacing — those
 * genuinely differ per surface — and compose the two functions directly, e.g.
 * `overlapReports(interactive = { ... }, wasm = { generateWasmReport(...) })`. Both legs of
 * [overlapReports] are plain function values, so a caller that pre-parses its sessions (as
 * [SessionLogSnapshot]s) closes over the parse in both legs without new plumbing — the WASM
 * leg additionally accepts the parse via [generateWasmReport]'s `preParsedLogs`.
 */

/**
 * The resolved inputs for one legacy WASM report.
 *
 * At least one of [templateFile] / [trailblazeUiProjectDir] must be non-null (enforced at
 * construction): without a template, the raw-UI-project fallback is the only way to build
 * the report.
 *
 * @param outputFile Exact file the report is written to (callers own naming conventions).
 * @param templateFile Resolved report template, or null when none was found — [WasmReport]
 *   then falls back to building from raw WASM UI project files under
 *   [trailblazeUiProjectDir]. A non-null template that doesn't exist on disk falls back the
 *   same way.
 * @param trailblazeUiProjectDir The trailblaze-ui checkout used for the no-template
 *   fallback, or null when unknown.
 * @param useRelativeImageUrls When true, images are referenced by relative URL instead of
 *   embedded (the daemon serves them from its /static endpoint).
 */
data class WasmReportRequest(
  val outputFile: File,
  val templateFile: File?,
  val trailblazeUiProjectDir: File?,
  val useRelativeImageUrls: Boolean = false,
) {
  init {
    require(templateFile != null || trailblazeUiProjectDir != null) {
      "WasmReportRequest needs a templateFile or a trailblazeUiProjectDir to build the report from"
    }
  }
}

/** What one [overlapReports] call produced. Null legs were skipped or could not be generated. */
data class ReportArtifacts(
  val wasmReport: File?,
  val interactiveReport: File?,
)

/**
 * Serializes [WasmReport.generate] invocations JVM-wide. [WasmReport] is a stateful object
 * with mutable shared state (its image-alias table is cleared per `generate()` call), so
 * only ONE `WasmReport.generate` may run at a time per JVM — callers that overlap (e.g. the
 * daemon's `/report` endpoint racing an after-run report) serialize on the WASM leg while
 * their interactive legs still overlap freely.
 */
private val WASM_GENERATE_LOCK = ReentrantLock()

/**
 * Generates one legacy WASM report for [logsRepo], returning [WasmReportRequest.outputFile].
 *
 * @param sessionIds When non-null, the report is scoped to exactly these sessions: the
 *   WASM report reads every session in the repo it's handed, so it is fed a temporary
 *   [LogsRepo] built over symlinks to just the selected session directories — cleaned up
 *   symlink-by-symlink, never recursively, so the real session data survives. When null,
 *   the report covers every session in [logsRepo] and reads the repo directly, reusing its
 *   parse cache.
 * @param request The resolved output file, template, and UI project dir — see
 *   [WasmReportRequest].
 * @param preParsedLogs Already-parsed logs per session (e.g. from [SessionLogSnapshot]s the
 *   caller captured once for both report legs), used to pre-seed the session-scoped temp
 *   repo's parse cache so the WASM leg doesn't re-read + re-decode every log file. Empty
 *   means the scoped repo parses from disk itself, as before. Only meaningful together with
 *   [sessionIds] — an unscoped build reads [logsRepo] directly and already has its cache.
 */
fun generateWasmReport(
  logsRepo: LogsRepo,
  sessionIds: List<SessionId>? = null,
  request: WasmReportRequest,
  preParsedLogs: Map<SessionId, List<TrailblazeLog>> = emptyMap(),
): File = withSessionScopedLogsRepo(logsRepo, sessionIds, preParsedLogs) { scopedRepo ->
  WASM_GENERATE_LOCK.withLock {
    WasmReport.generate(
      logsRepo = scopedRepo,
      trailblazeUiProjectDir = request.trailblazeUiProjectDir ?: logsRepo.logsDir,
      outputFile = request.outputFile,
      reportTemplateFile = request.templateFile,
      useRelativeImageUrls = request.useRelativeImageUrls,
    )
  }
  request.outputFile
}

/**
 * The shared overlap pattern: run [interactive] on a background thread while [wasm] runs
 * on the calling thread, then join. Contract:
 *
 * - An [interactive] exception is logged and resolves to null (best-effort leg); its side
 *   effects up to the failure still land.
 * - A [wasm] exception propagates to the caller (each surface has its own error contract),
 *   but only AFTER [interactive] has been joined — the interactive artifact is never
 *   discarded and its subprocess never orphaned by a WASM crash.
 *
 * Both legs are plain function values so every caller can inject its own generators —
 * including customization seams like `CliReportGenerator`'s open methods. Null
 * [interactive] skips that leg.
 */
fun overlapReports(interactive: (() -> File?)?, wasm: () -> File?): ReportArtifacts {
  val interactiveFuture: CompletableFuture<File?>? = interactive?.let { leg ->
    CompletableFuture.supplyAsync {
      runCatching { leg() }
        .onFailure { Console.error("Warning: interactive report generation threw: ${it.message}") }
        .getOrNull()
    }
  }
  val wasmReport = try {
    wasm()
  } catch (t: Throwable) {
    interactiveFuture?.join()
    throw t
  }
  return ReportArtifacts(wasmReport = wasmReport, interactiveReport = interactiveFuture?.join())
}

/**
 * Runs [block] against a [LogsRepo] containing only [sessionIds]: a temporary directory
 * of symlinks into the real session directories, so [WasmReport.generate] (which reads
 * every session in the repo it's handed) sees just the selected sessions without
 * copying any data. Null [sessionIds] means "no scoping" — [block] gets [logsRepo]
 * itself, keeping its parse cache.
 *
 * [preParsedLogs] seeds the scoped repo's single-read parse cache (see the matching
 * [LogsRepo] constructor parameter): sessions present in the map skip the scoped repo's
 * init-time re-parse of files the caller already decoded.
 *
 * Cleanup deletes the symlinks one by one and then the temp dir. IMPORTANT:
 * `File.deleteRecursively()` follows symlinks and would delete the real session files;
 * `Files.deleteIfExists()` removes only the link itself.
 */
internal fun <T> withSessionScopedLogsRepo(
  logsRepo: LogsRepo,
  sessionIds: List<SessionId>?,
  preParsedLogs: Map<SessionId, List<TrailblazeLog>> = emptyMap(),
  block: (LogsRepo) -> T,
): T {
  if (sessionIds == null) return block(logsRepo)
  val tempDir = Files.createTempDirectory("trailblaze-report-").toFile()
  try {
    for (sessionId in sessionIds) {
      val sessionDir = File(logsRepo.logsDir, sessionId.value)
      if (sessionDir.exists()) {
        Files.createSymbolicLink(
          File(tempDir, sessionId.value).toPath(),
          sessionDir.toPath(),
        )
      }
    }
    val scopedRepo = LogsRepo(logsDir = tempDir, watchFileSystem = false, preParsedLogs = preParsedLogs)
    try {
      return block(scopedRepo)
    } finally {
      scopedRepo.close()
    }
  } finally {
    tempDir.listFiles()?.forEach { Files.deleteIfExists(it.toPath()) }
    tempDir.delete()
  }
}
