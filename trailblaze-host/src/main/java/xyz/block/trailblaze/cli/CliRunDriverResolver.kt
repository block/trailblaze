package xyz.block.trailblaze.cli

import xyz.block.trailblaze.devices.TrailblazeDriverType

/** Outcome of [CliRunDriverResolver.resolve] — the requested driver type, or why it's invalid. */
sealed interface CliRunDriverResolution {
  /** [driverType] is `null` when no driver was requested (run on the default). */
  data class Resolved(val driverType: TrailblazeDriverType?) : CliRunDriverResolution

  /**
   * The requested driver string names no RUNNABLE driver — the caller must fail loud
   * (never silently run on the default driver). Covers both a name that matches nothing and
   * one that matches a driver whose runtime has been retired
   * ([TrailblazeDriverType.RETIRED_DRIVERS]). [reason]/[hint] slot into the CLI's
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

  /**
   * The drivers a user may still ask for — every enum value whose runtime exists. The retired
   * ones are excluded so the "valid driver types" hint never advertises a name that this same
   * function rejects a branch later.
   */
  private val runnableDrivers: List<TrailblazeDriverType> =
    TrailblazeDriverType.entries - TrailblazeDriverType.RETIRED_DRIVERS

  fun resolve(driverString: String?): CliRunDriverResolution {
    if (driverString == null) return CliRunDriverResolution.Resolved(null)
    val driverType = TrailblazeDriverType.fromString(driverString)
      ?: return CliRunDriverResolution.Unrecognized(
        reason = "unknown driver type '$driverString'",
        hint = "valid driver types: ${runnableDrivers.joinToString { it.name }}",
      )
    return resolve(driverType)
  }

  /**
   * The already-parsed form, for callers whose driver choice never was a string — a trail's
   * decoded `devices:` pin, a persisted app setting. Same retirement check, so a driver that
   * bypassed [resolve]'s string parse cannot bypass its rejection.
   */
  fun resolve(driverType: TrailblazeDriverType?): CliRunDriverResolution {
    if (driverType == null) return CliRunDriverResolution.Resolved(null)
    // A retired driver still PARSES — the enum value is kept so old recordings and session logs
    // deserialize — but nothing can run on it, so it is rejected here rather than a line later
    // when a descriptor lookup or an on-device agent factory fails without naming the cause.
    if (driverType in TrailblazeDriverType.RETIRED_DRIVERS) {
      // Same platform as the dead pin. Naming a cross-platform default here would answer an iOS
      // retirement with an Android driver.
      val replacement = TrailblazeDriverType.replacementForRetired(driverType)
      return CliRunDriverResolution.Unrecognized(
        reason = "driver '${driverType.name}' has been retired and its runtime is gone",
        hint = if (replacement != null) {
          "use ${replacement.name}, and re-record any trail still pinned to the retired driver"
        } else {
          "no driver remains for ${driverType.platform.name}; re-record any trail still pinned " +
            "to the retired driver against a supported platform"
        },
      )
    }
    return CliRunDriverResolution.Resolved(driverType)
  }
}
