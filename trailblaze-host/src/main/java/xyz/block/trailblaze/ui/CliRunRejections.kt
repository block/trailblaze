package xyz.block.trailblaze.ui

import java.nio.file.InvalidPathException
import java.nio.file.Paths
import xyz.block.trailblaze.config.ServedTrailmaps
import xyz.block.trailblaze.logs.server.endpoints.CliRunResponse
import xyz.block.trailblaze.model.TrailExecutionResult
import xyz.block.trailblaze.model.TrailblazeHostAppTarget

/**
 * Failure responses for `/cli/run` requests the daemon REJECTS as invalid before attempting a
 * run (bad input, not a failed run). Each carries [CliRunResponse.ERROR_KIND_MISUSE] so a
 * delegated CLI exits MISUSE (3) — the same exit code the in-process path uses for the same
 * mistake — while an older CLI that doesn't read the field degrades to the plain run-failure
 * exit (1). Pure (no I/O, no logging) so the classification is unit-testable; the handler
 * (`TrailblazeDesktopApp.handleCliRunRequest`) owns the logging around each rejection.
 */
internal fun cliRunMisuseResponse(error: String): CliRunResponse = CliRunResponse(
  success = false,
  error = error,
  errorKind = CliRunResponse.ERROR_KIND_MISUSE,
)

/** Rejection for a `/cli/run` request that carries no trail YAML at all. */
internal fun cliRunNoYamlResponse(): CliRunResponse = cliRunMisuseResponse("No YAML content provided")

/**
 * Rejection for a `/cli/run` request that could run on several connected devices ([specs],
 * fully-qualified, never empty) without naming one. Same fail-loud contract as the CLI's
 * in-process path: never silently pick one of several devices — and the same MISUSE exit,
 * so the fix (pass `--device`) is signalled identically on both paths.
 */
internal fun cliRunMultipleDevicesResponse(specs: List<String>): CliRunResponse = cliRunMisuseResponse(
  "Multiple devices connected: ${specs.joinToString(", ")}. " +
    "Specify which device to run on (e.g. --device ${specs.first()} from the CLI).",
)

/**
 * The daemon's response for a run the RUNNER rejected as invalid before attempting it
 * ([TrailExecutionResult.Failed] with [TrailExecutionResult.Failed.misuse] set, e.g. an
 * unrecognized unified-trail driver pin — only concrete against the connected device, so it
 * can only be validated runner-side). Null for every other outcome: an ordinary failure,
 * success, or cancellation keeps the handler's normal result flow.
 *
 * Callers must return a non-null response immediately: a misuse rejection is made before any
 * session is created, so the handler's session-log poll would stall its full timeout window
 * waiting on a session that never existed.
 */
internal fun cliRunRunnerRejectionResponse(result: TrailExecutionResult): CliRunResponse? =
  if (result is TrailExecutionResult.Failed && result.misuse) {
    cliRunMisuseResponse(result.errorMessage ?: "Run request rejected as invalid")
  } else {
    null
  }

/**
 * Why [target] must not run for a caller in [callerWorkspaceDir]: the caller's workspace has its own
 * copy of the target's trailmap, or of one it depends on, and this daemon would run another copy —
 * the bundled one, or another checkout's. Running would use the daemon's copy and could pass on code
 * the caller never wrote; see [ServedTrailmaps]. Checks the target instance itself, not the live
 * registry, so a workspace reload mid-setup cannot swap in an unchecked copy. Null when nothing is
 * shadowed, or [callerWorkspaceDir] is absent (an older CLI or a non-CLI submission) or unparseable.
 * [callerConfigDir] is the caller's `TRAILBLAZE_CONFIG_DIR`; see [ServedTrailmaps].
 */
internal fun shadowedTrailmapsRefusal(
  callerWorkspaceDir: String?,
  target: TrailblazeHostAppTarget?,
  callerConfigDir: String? = null,
): String? {
  target ?: return null
  val callerDir = callerWorkspaceDir?.takeIf { it.isNotBlank() }?.let {
    try {
      Paths.get(it)
    } catch (_: InvalidPathException) {
      null
    }
  } ?: return null
  val shadowed = ServedTrailmaps.shadowedFor(callerDir, target.id, ServedTrailmaps.originsOf(target), callerConfigDir)
  return if (shadowed.isEmpty()) null else ServedTrailmaps.refusal(shadowed)
}

/**
 * [shadowedTrailmapsRefusal] as the run's failure response. [CliRunResponse.ERROR_KIND_INFRA]: the
 * trail never ran, and the fix is the daemon's setup, not the trail.
 */
internal fun cliRunShadowedTrailmapsResponse(
  callerWorkspaceDir: String?,
  target: TrailblazeHostAppTarget?,
  callerConfigDir: String? = null,
): CliRunResponse? = shadowedTrailmapsRefusal(callerWorkspaceDir, target, callerConfigDir)?.let {
  CliRunResponse(success = false, error = it, errorKind = CliRunResponse.ERROR_KIND_INFRA)
}
