package xyz.block.trailblaze.yaml.unified

import kotlinx.serialization.Serializable

/**
 * Unified-format `config:` block — identity, target, optional per-classifier
 * driver pins, and free-form context/memory/metadata.
 *
 * Retired from the legacy per-platform format: `platform:` (the supported
 * device set is now derived from the steps' recorded classifiers), `title:`
 * (use `metadata.title` if needed).
 *
 * [description] is deliberately kept (not retired): it's a first-class,
 * runtime-surfaced human summary that round-trips losslessly to/from the v1
 * `TrailConfig.description`.
 *
 * [devices] is an **optional, per-classifier** map: keys are the device
 * classifiers this trail targets (`android`, `android-tablet`, `ios-iphone`, …)
 * and each value is the driver to run that classifier on. It folds together
 * what used to be two overlapping fields — the `devices:` support list and a
 * separate `drivers:` map — since both were keyed by the same classifiers.
 *
 * The driver for the device under test is resolved closest-wins with the same
 * [xyz.block.trailblaze.devices.TrailblazeClassifierLineage] the recordings use
 * (so an `android` entry covers `android-phone`/`android-tablet`), then lowered
 * to the single v1 `TrailConfig.driver` for that run. A multi-platform trail
 * pins each platform's driver independently (`android:` and `ios:` need
 * different drivers) — a single scalar couldn't express that.
 *
 * Omit [devices] entirely when the trail pins no drivers: the driver then
 * resolves at run time (`--driver` flag > app setting > device; the flag always
 * overrides a pin), and the supported classifiers are derivable from the steps'
 * per-classifier recordings.
 */
@Serializable
data class UnifiedTrailConfig(
  /** Stable identifier; convention is the source-system path. */
  val id: String? = null,
  /** Target name from the trailmap manifest. */
  val target: String? = null,
  /**
   * Human-readable summary of what the test does. Round-trips losslessly with
   * the v1 `TrailConfig.description` and is surfaced at runtime (e.g. as a
   * display label), so it is preserved through migration rather than dropped.
   */
  val description: String? = null,
  /**
   * Per-classifier device → driver map (e.g. `{android: ANDROID_ONDEVICE_ACCESSIBILITY}`).
   * See the class kdoc: keys declare the targeted classifiers, values pin each
   * one's driver, resolved closest-wins for the device under test. Optional —
   * omit it when no driver needs pinning.
   *
   * The value stays a **bare driver string** by design. If a second
   * per-classifier trail-config field is ever needed, do NOT widen this value
   * to an object in place (that silently breaks every checked-in trail's YAML);
   * introduce a new object-valued map instead. See the unified-syntax devlog.
   */
  val devices: Map<String, String>? = null,
  /** Free-form context injected into the LLM system prompt. */
  val context: String? = null,
  /** Pre-seeded variables for `{{name}}` interpolation in NL and tool params. */
  val memory: Map<String, String>? = null,
  /** Informational only — never read at runtime. Used for traceability. */
  val metadata: Map<String, String>? = null,
)
