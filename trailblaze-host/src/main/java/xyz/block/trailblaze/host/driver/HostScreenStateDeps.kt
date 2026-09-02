package xyz.block.trailblaze.host.driver

import maestro.Driver
import xyz.block.trailblaze.devices.TrailblazeDeviceId

/**
 * The host-side collaborators a [HostDriverDescriptor.screenState] needs — the capture-path
 * sibling of [HostRunDeps], bundled for the same reason: so the interface doesn't grow a
 * parameter every time one driver needs something a sibling doesn't.
 *
 * [activeMaestroDriver] is here because some drivers' live session state still lives on the
 * shared device manager during the conversion (the Maestro path's active driver per device,
 * which serves both Playwright drivers and unconverted iOS). It is the narrow capability those
 * captures use, not the whole manager, so descriptor tests can stub it with `{ null }`. A
 * descriptor that owns its own session state — Revyl's per-device CLI clients, say — ignores it.
 */
class HostScreenStateDeps(
  val activeMaestroDriver: (TrailblazeDeviceId) -> Driver?,
)
