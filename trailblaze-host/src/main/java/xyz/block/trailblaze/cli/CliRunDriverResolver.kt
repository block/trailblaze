package xyz.block.trailblaze.cli

import xyz.block.trailblaze.devices.TrailblazeDriverType

/** Outcome of [CliRunDriverResolver.resolve] — the requested driver type, or why it's invalid. */
sealed interface CliRunDriverResolution {
  /** [driverType] is `null` when no driver was requested (run on the default). */
  data class Resolved(val driverType: TrailblazeDriverType?) : CliRunDriverResolution

  /**
   * The requested driver string names no known driver — the caller must fail loud
   * (never silently run on the default driver). [reason]/[hint] slot into the CLI's
   * `reportCliError` envelope; [message] is the single-line form for HTTP payloads.
   */
  data class Unrecognized(val reason: String, val hint: String) : CliRunDriverResolution {
    val message: String get() = "$reason; $hint"
  }
}

/**
 * Driver-string validation shared by the CLI's `--driver` flag, the daemon's `/cli/run`
 * handler, AND the runner's read of a trail's own driver pin (including a unified trail's
 * per-classifier `config.devices:` pin — see `DesktopYamlRunner.trailPinnedDriverResolution`),
 * so the paths cannot drift: an unrecognized driver name must be rejected with an error naming
 * the valid values everywhere. Before this seam, these paths silently fell back to the default
 * driver — a typo'd driver in a CI script or trail file kept the step green while testing a
 * different driver entirely.
 *
 * Pure — no I/O, no logging; the caller renders the failure for its surface.
 */
object CliRunDriverResolver {

  fun resolve(driverString: String?): CliRunDriverResolution {
    if (driverString == null) return CliRunDriverResolution.Resolved(null)
    val driverType = TrailblazeDriverType.fromString(driverString)
      ?: return CliRunDriverResolution.Unrecognized(
        reason = "unknown driver type '$driverString'",
        hint = "valid driver types: ${TrailblazeDriverType.entries.joinToString { it.name }}",
      )
    return CliRunDriverResolution.Resolved(driverType)
  }
}
