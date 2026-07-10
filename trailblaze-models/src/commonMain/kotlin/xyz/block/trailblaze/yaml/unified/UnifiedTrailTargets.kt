package xyz.block.trailblaze.yaml.unified

import xyz.block.trailblaze.devices.TrailblazeDevicePlatform

/**
 * Pure derivation of the device coverage a unified trail declares — "what devices does this trail
 * carry direction for" — read from the trail's own content rather than its filename.
 *
 * A unified trail lives in a single file (canonically `trail.yaml`), so the filename no longer
 * encodes a classifier the way a legacy per-device `<classifier>.trail.yaml` does. The coverage is
 * instead the union of every classifier the file declares:
 *  - the keys of the optional `config.devices` run matrix, and
 *  - every classifier keying a `recording:` block on the trailhead or any step.
 *
 * These two sources can legitimately disagree: `config.devices` is the *declared run matrix* and may
 * omit a family that still carries recordings (e.g. a trail can omit iOS from `config.devices` while
 * every step still records an `ios:` block), and recordings mix broad (`android`, `ios`) and
 * device-specific (`android-tablet`, `ios-iphone`) keys within one file. We keep every declared key
 * verbatim so the coverage is faithful to what's authored, and prefer "a recording exists" over
 * "declared in `config.devices`" — a family with recordings is covered whether or not the run matrix
 * lists it.
 *
 * Shared source of truth: [xyz.block.trailblaze.devices.TrailDeviceSelector.supportedPlatformsForTrail]
 * folds these classifiers up to platforms for device selection, and the desktop Trails browser uses
 * the same union to display a unified trail's targets.
 */
object UnifiedTrailTargets {

  /**
   * The union of device-classifier keys [trail] declares: every `config.devices` key plus every
   * classifier keying a `recording:` block on the trailhead or any step. Insertion order is
   * config-devices-first, then trailhead, then steps in order — de-duplicated. Callers that need a
   * stable display order should sort.
   */
  fun declaredClassifiers(trail: UnifiedTrail): Set<String> = buildSet {
    trail.config.devices?.keys?.let { addAll(it) }
    trail.trailhead?.recordings?.keys?.let { addAll(it) }
    trail.trail.forEach { step -> addAll(step.recordings.keys) }
  }

  /**
   * The set of [TrailblazeDevicePlatform]s [trail] covers, folded up from [declaredClassifiers].
   * See [platformFor] for the per-classifier fold; classifiers whose prefix is not a known platform
   * contribute nothing here but still appear in [declaredClassifiers], the finer-grained view.
   */
  fun declaredPlatforms(trail: UnifiedTrail): Set<TrailblazeDevicePlatform> =
    platformsOf(declaredClassifiers(trail))

  /**
   * The [TrailblazeDevicePlatform] a single classifier string folds up to, or `null` if its prefix
   * is not a known platform. A classifier is `<platform>[-<category>]`, so the platform is the
   * segment before the first `-` (`android-tablet` → `ANDROID`, `ios-iphone` → `IOS`, bare `android`
   * → `ANDROID`). An internal device-family classifier such as `kiosk-a` folds to `null`.
   *
   * This is the single source of truth for the classifier→platform fold; every UI and selection site
   * that holds classifier strings (rather than a whole [UnifiedTrail]) routes through here so the
   * scheme lives in one place.
   */
  fun platformFor(classifier: String): TrailblazeDevicePlatform? =
    TrailblazeDevicePlatform.fromString(classifier.substringBefore('-'))

  /**
   * Folds a collection of classifier strings up to the set of platforms they cover, dropping
   * classifiers whose prefix is not a known platform. Insertion order is preserved and de-duplicated.
   */
  fun platformsOf(classifiers: Collection<String>): Set<TrailblazeDevicePlatform> =
    classifiers.mapNotNullTo(LinkedHashSet()) { platformFor(it) }
}
