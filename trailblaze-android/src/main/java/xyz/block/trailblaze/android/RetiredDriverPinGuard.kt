package xyz.block.trailblaze.android

import xyz.block.trailblaze.devices.TrailblazeDriverType

/**
 * Decides whether a trail's own `config.driver:` pin must be refused because it names a driver
 * whose runtime is gone ([TrailblazeDriverType.RETIRED_DRIVERS]). Returns the refusal message, or
 * `null` when the run may proceed.
 *
 * Exists because the pin and the agent's driver are two different inputs. [AndroidTrailblazeRule]'s
 * agent factory rejects a retired `driverTypeOverride`, but the base rule's per-trail seam
 * ([AndroidTrailblazeRule.onTrailConfigResolved]) is a no-op, so nothing ever copies a pinned
 * driver onto that override. Without this check a trail pinned to a retired driver runs to green on
 * whatever driver the override happens to hold — a pass on a driver nobody asked for, which is the
 * exact outcome retiring a driver is supposed to make impossible.
 *
 * Precedence matches the per-trail driver flip that subclasses implement on the seam: a
 * [forcedDriver] (the `trailblaze.driverType` instrumentation arg, written by a per-(config,
 * device) pin or a build-level override) means "this shard runs on that driver, the trail YAMLs
 * don't get a vote", so the pin is not read at all — including a retired one. Only an unforced run,
 * where the pin would otherwise decide the driver, is refused.
 *
 * That instrumentation arg is the ONLY force, and deliberately so. `RunYamlRequest.driverType` —
 * which the host sends on every RPC run — is the device's RESOLVED driver
 * (`device.trailblazeDriverType`), not an explicit user request, so widening [forcedDriver] to
 * include it would retire nothing: every host-driven run carries a live driver, and no retired pin
 * would ever be refused again. Nor would honoring a live `--driver` alongside a retired pin be a
 * kindness: a pin naming a dead driver means the trail's recorded selectors were matched against
 * that driver's hierarchy, and they resolve to nothing against the replacement's. The trail needs
 * re-recording, which is exactly what the refusal says.
 *
 * A [pinnedDriver] that parses to nothing is not this function's failure to report: the flip path
 * already logs an unknown `config.driver` and falls back to the resolved driver.
 *
 * Pure — no I/O, no logging; the caller throws. Parsing matches the host's `CliRunDriverResolver`
 * (case-insensitive enum name), so the same pin string is refused on both sides of the wire.
 */
internal fun retiredTrailDriverPinRefusal(
  pinnedDriver: String?,
  forcedDriver: TrailblazeDriverType?,
): String? {
  if (forcedDriver != null) return null
  val pinned = pinnedDriver?.let { TrailblazeDriverType.fromString(it) } ?: return null
  if (pinned !in TrailblazeDriverType.RETIRED_DRIVERS) return null
  return TrailblazeDriverType.retiredDriverMessage(pinned)
}
