package xyz.block.trailblaze.model

import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform

/**
 * The active target paired with the device it's running on — a "currently resolved target"
 * snapshot.
 *
 * Used as a single carrier for "what app is under test on what device" so call sites (tools,
 * host utilities) don't have to reach for hardcoded per-target helpers or repeat
 * `appTarget.getPossibleAppIdsForPlatform(platform).first()` boilerplate. Typically
 * constructed at the call site that knows both halves — host runners that own the session,
 * or (transitionally) tools that have access to both pieces and build it ad hoc until
 * `TrailblazeToolExecutionContext.resolvedTarget` is wired through. The follow-up to thread it
 * via the execution context is tracked in #2699.
 *
 * Also serves as the Kotlin-side prototype for the `input.target` runtime surface that
 * scripted (TS/JS) tools will eventually receive — same fields, same shape — so a future
 * tool migration doesn't have to redesign the abstraction.
 *
 * **Equality.** This is a `data class`, so structural equality compares `target` by the
 * underlying [TrailblazeHostAppTarget] reference (the abstract base only has identity equality).
 * In current usage targets are `data object` singletons, so two `ResolvedTarget` instances with
 * "the same target" compare equal as long as those singletons are reused. If a future caller
 * constructs fresh [TrailblazeHostAppTarget] instances dynamically and relies on
 * `Set<ResolvedTarget>` semantics, override `equals`/`hashCode` here to key on
 * `target.id + deviceId` instead.
 */
data class ResolvedTarget(
  val target: TrailblazeHostAppTarget,
  val deviceId: TrailblazeDeviceId,
) {
  /** Convenience accessor — same value as `deviceId.trailblazeDevicePlatform`. */
  val platform: TrailblazeDevicePlatform
    get() = deviceId.trailblazeDevicePlatform

  /** Pass-through to [TrailblazeHostAppTarget.id] so call sites don't need both refs. */
  val id: String
    get() = target.id

  /**
   * Primary **declared** app id for the active platform — the first entry in
   * `target.getPossibleAppIdsForPlatform(platform)`. Delegates to
   * [TrailblazeHostAppTarget.requireFirstAppIdForPlatform], which throws if the target declares
   * none.
   *
   * **Important.** This does NOT consult the device. If a target declares multiple app ids
   * (e.g. a primary + a fallback variant) and only the fallback is actually installed on
   * [deviceId]'s simulator, this getter will still return the declared-first id — which may
   * silently launch the wrong build (or fail). Production launch flows that need the
   * device-resolved id should call the JVM-only resolver
   * `xyz.block.trailblaze.host.ios.IosHostUtils.findInstalledAppIdForTarget(target, deviceId)`
   * instead, which intersects the declared ids with what `simctl listapps` reports.
   *
   * `appId` is kept here for the test/CLI context where there's no live simulator to query
   * (e.g. configuration validation, codegen, scripted-tool prototypes), and as the shape that
   * the future scripted-tool runtime surface (`input.target.appId`) will expose.
   */
  val appId: String
    get() = target.requireFirstAppIdForPlatform(platform)

  /**
   * Full ordered list of app ids declared for the active platform. Returns an empty list
   * when none are declared (in contrast to [appId] which throws). Callers that need to pick
   * across "real product id vs fallback variants" iterate this list.
   */
  val appIds: List<String>
    get() = target.getPossibleAppIdsForPlatform(platform).orEmpty()
}
