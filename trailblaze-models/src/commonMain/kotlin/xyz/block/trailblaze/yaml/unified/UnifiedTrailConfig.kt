package xyz.block.trailblaze.yaml.unified

import kotlinx.serialization.Serializable

/**
 * Unified-format `config:` block — identity, target, optional per-classifier
 * driver pins, and free-form context/memory/metadata.
 *
 * Converting a v1 trail to this format never silently drops config. Two v1
 * fields deliberately have no unified home: `platform:` is retired (the
 * supported device set derives from the steps' recorded classifiers and the
 * [devices] keys), and `electron:` is refused — conversion fails loud on it
 * (see [UnifiedTrailAdapter.v1ConfigToUnifiedConfig]) because it is
 * driver-specific structured launch config with zero corpus usage; its
 * unified home (likely target-level, not per-trail) is deferred until a real
 * Electron trail needs one. Everything else carries: the runtime-surfaced
 * scalars ([title], [description]) round-trip verbatim as fields, the
 * informational v1 `priority:` / `source:` ride in [metadata] under the
 * reserved bridge keys (see the [metadata] kdoc), and the two per-platform v1
 * scalars (`driver:`, `skip:`) become the per-classifier [devices] / [skip]
 * maps.
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
  /**
   * Per-classifier skip map (e.g. `{android: "blocked on #123"}`). When the entry that resolves
   * closest-wins for the device under test is non-blank, the trail is parsed and validated but not
   * executed. Per-classifier (not a scalar) so a trail can be skipped on one device family while
   * still running on others — resolved with the same lineage the recordings and [devices] pins use,
   * then lowered to the single v1 `TrailConfig.skip` for that run. A device-agnostic caller (no
   * classifiers) treats the trail as skipped if *any* classifier declares a non-blank reason.
   */
  val skip: Map<String, String>? = null,
  /**
   * Free-form labels for grouping/filtering (e.g. `[smoke, flaky]`). Trail-level, not per-device —
   * a tag names the whole test, so it stays a flat list like the v1 `TrailConfig.tags`. Lowered
   * verbatim so the CLI's `--tags` filter sees unified trails too.
   */
  val tags: List<String>? = null,
  /** Free-form context injected into the LLM system prompt. */
  val context: String? = null,
  /** Pre-seeded variables for `{{name}}` interpolation in NL and tool params. */
  val memory: Map<String, String>? = null,
  /**
   * Informational only — never read at runtime. Used for traceability.
   *
   * Three keys are **reserved bridge keys** for the v1 fields that are metadata by nature but
   * that internal tooling still reads as first-class `TrailConfig` fields:
   * [METADATA_KEY_PRIORITY] (v1 `priority:`), [METADATA_KEY_SOURCE] (v1 `source.type`, empty
   * string for a bare `source: {}` marker), and [METADATA_KEY_SOURCE_REASON] (v1
   * `source.reason`). Conversion writes them here and lowering lifts them back onto
   * `TrailConfig.priority` / `TrailConfig.source`, so both formats read identically.
   */
  val metadata: Map<String, String>? = null,
  /**
   * Human-readable test title (e.g. the source test-case title), surfaced as the trail name in
   * reports and session lists. Trail-level like the v1 `TrailConfig.title` it round-trips with.
   */
  val title: String? = null,
) {
  companion object {
    /** Reserved [metadata] key bridging the v1 `priority:` scalar (e.g. `P1`). */
    const val METADATA_KEY_PRIORITY: String = "priority"

    /**
     * Reserved [metadata] key bridging v1 `source.type` (a `TrailSourceType` name; empty string
     * for a bare `source: {}` marker). An unrecognized value is left in metadata untouched.
     */
    const val METADATA_KEY_SOURCE: String = "source"

    /** Reserved [metadata] key bridging v1 `source.reason` (typically an issue URL). */
    const val METADATA_KEY_SOURCE_REASON: String = "sourceReason"
  }
}
