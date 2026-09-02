package xyz.block.trailblaze.host.driver

import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.HostYamlRunResult
import xyz.block.trailblaze.host.yaml.RunOnHostParams

/**
 * Everything the host does *with* one driver, in one place: find its devices, decide whether to
 * list them, run a trail on one, read its screen.
 *
 * This is the plug point. Adding a driver means writing one of these and registering it in an app
 * config; removing one means deleting both. Before descriptors, each of those four concerns was a
 * `when (driverType)` arm in a different file, so a driver's personality was spread across the
 * host and no single place told you what it could do.
 *
 * Not every driver has one yet. [HostDriverDescriptorRegistry] is consulted first and the
 * pre-existing `when` arms still handle the rest — see the registry's KDoc for how the two
 * coexist during the conversion.
 */
interface HostDriverDescriptor {

  /**
   * The enum entries this descriptor speaks for. Disjoint from every other descriptor's in a
   * registry.
   *
   * A set rather than a single type because one integration can back several entries: Revyl's
   * Android and iOS drivers are one CLI, one credential, and one device catalog, so splitting
   * them into two descriptors would probe that catalog twice per discovery pass. That is the ONLY
   * reason to share — one backend split across platforms. Different execution engines that merely
   * enumerate the same transport (the several drivers offered on every connected Android device,
   * say) get one descriptor each and share the enumeration through [HostDeviceInventory]; bundling
   * them would turn "remove this driver" from deleting a file into surgery inside a shared class.
   *
   * Everything else on this interface applies uniformly to the whole set; a driver needing
   * different [listingVisibility] per entry wants its own descriptor.
   */
  val driverTypes: Set<TrailblazeDriverType>

  /**
   * Whether this driver's devices appear in user-facing device listings.
   *
   * Read by every listing surface so they cannot drift apart — see [DeviceListingVisibility].
   */
  val listingVisibility: DeviceListingVisibility

  /**
   * The devices this driver can currently drive, or empty when the driver is unavailable on this
   * host (its CLI isn't installed, its endpoint isn't responding, its credentials are absent).
   *
   * Returning empty is how a descriptor says "not available" — there is no separate availability
   * probe, because every caller of one would have to handle the empty case anyway.
   *
   * [inventory] is what the host already enumerated this pass. A driver whose devices live on a
   * shared transport maps from it instead of re-enumerating (one `adb devices` call feeds every
   * Android driver); a driver that owns its transport ignores it and probes on its own.
   *
   * Called on every device-discovery pass. Descriptors run concurrently, and one that exceeds the
   * manager's discovery budget is abandoned — even mid-blocking-call — so it cannot stall the
   * pass. An implementation that shells out or hits the network should still bound its own work:
   * a self-bounded probe degrades to a partial answer, an abandoned one contributes nothing.
   */
  suspend fun discoverDevices(inventory: HostDeviceInventory): List<TrailblazeConnectedDeviceSummary>

  /** Runs a trail on one of this driver's devices. */
  suspend fun runYaml(deps: HostRunDeps, params: RunOnHostParams): HostYamlRunResult

  /**
   * Captures the current screen, or null when this driver has no live session for [deviceId] and
   * therefore nothing to read.
   *
   * [driverType] is passed rather than derived because the caller already resolved it to find this
   * descriptor, and a descriptor covering several entries would otherwise have to infer it back.
   * [deps] carries host collaborators for drivers whose live session state hasn't moved into
   * their descriptor yet — see [HostScreenStateDeps].
   */
  suspend fun screenState(
    driverType: TrailblazeDriverType,
    deviceId: TrailblazeDeviceId,
    deps: HostScreenStateDeps,
  ): ScreenState?
}

/**
 * Whether a driver's devices belong in the lists a user browses.
 *
 * The distinction exists because "you can run on it" and "we should show it to you" are different
 * questions: a cloud driver is worth addressing by name from a trail without cluttering the local
 * device list that most runs pick from.
 */
enum class DeviceListingVisibility {
  /**
   * Listed wherever devices are shown, subject to the user's enabled-drivers setting.
   */
  LISTED,

  /**
   * Runnable by explicit `--device <id>`, but omitted from browsable listings and shown
   * regardless of the enabled-drivers setting when something does ask for it directly.
   */
  ADDRESSABLE_NOT_LISTED,
}
