package xyz.block.trailblaze.yaml.unified

import kotlinx.serialization.Serializable

/**
 * Unified-format `config:` block — identity, target, optional per-classifier
 * driver pins, and free-form context/memory/metadata.
 *
 * Converting a v1 trail to this format never silently drops config. One v1
 * field deliberately has no unified home: `platform:` is retired (the
 * supported device set derives from the steps' recorded classifiers and the
 * [devices] keys). Electron launch config was never a trail-level concern
 * either — it lives on the target (`target.electron:` /
 * [xyz.block.trailblaze.config.AppTargetYamlConfig.electron]), reached by
 * selecting the target. Everything else carries: the runtime-surfaced
 * scalars ([title], [description], [priority]) round-trip verbatim as fields,
 * the informational v1 `source:` rides in [metadata] under the reserved bridge
 * keys (see the [metadata] kdoc), and the two per-platform v1 scalars
 * (`driver:`, `skip:`) become the per-classifier [devices] / [skip] maps.
 *
 * [devices] is an **optional, per-classifier** map: keys are the device
 * classifiers this trail targets (`android`, `android-tablet`, `ios-iphone`, …)
 * and each value is that device's [TrailblazeDeviceDefinition] (today just the
 * driver to run that classifier on). It folds together what used to be two
 * overlapping fields — the `devices:` support list and a separate `drivers:`
 * map — since both were keyed by the same classifiers.
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
   * Test priority (e.g. `P1`). Trail-level and informational like the v1 `TrailConfig.priority`
   * it round-trips with, but kept a **top-level field** (not a [metadata] entry) because
   * downstream tooling (CI priority filters, test-management sync) treats it as first-class.
   */
  val priority: String? = null,
  /**
   * Per-classifier device map (e.g. `{android: {driver: ANDROID_ONDEVICE_ACCESSIBILITY}}`).
   * See the class kdoc: keys declare the targeted classifiers, values are each
   * one's [TrailblazeDeviceDefinition] (driver pins resolve closest-wins for
   * the device under test). Optional — omit it when no device needs declaring.
   *
   * An entry whose value carries an inner `devices:` map is a named **multi-device
   * configuration** instead — a cast of named devices for one session (its key names the
   * configuration, not a classifier). Configuration names are invisible to classifier
   * lineage: their recording legs, pins, and skips apply only by exact configuration
   * selection (see [UnifiedTrailAdapter.lowerToTrailItems]'s
   * `selectedDeviceConfiguration`), never through a device's chain.
   *
   * The value is the shared device model object from the multi-device trails
   * design (see [TrailblazeDeviceDefinition]); the pre-object bare-string form
   * (`android: ANDROID_ONDEVICE_ACCESSIBILITY`) is DEPRECATED decode-only
   * compatibility handled by [TrailblazeDeviceDefinitionMapSerializer]
   * — encoding always writes the object form.
   */
  @Serializable(with = TrailblazeDeviceDefinitionMapSerializer::class)
  val devices: Map<String, TrailblazeDeviceDefinition>? = null,
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
   * Two keys are **reserved bridge keys** for the v1 field that is metadata by nature but
   * that internal tooling still reads as a first-class `TrailConfig` field:
   * [METADATA_KEY_SOURCE] (v1 `source.type`, empty string for a bare `source: {}` marker) and
   * [METADATA_KEY_SOURCE_REASON] (v1 `source.reason`). Conversion writes them here and lowering
   * lifts them back onto `TrailConfig.source`, so both formats read identically.
   */
  val metadata: Map<String, String>? = null,
  /**
   * Human-readable test title (e.g. the source test-case title), surfaced as the trail name in
   * reports and session lists. Trail-level like the v1 `TrailConfig.title` it round-trips with.
   */
  val title: String? = null,
  /**
   * Trail parameters — declared per-run arguments referenced as `{{args.x}}`. Trail-level (not
   * per-classifier): a trail's parameter contract is the same regardless of which device runs it.
   * Round-trips one-to-one with the v1 [xyz.block.trailblaze.yaml.TrailConfig.args].
   */
  @kotlinx.serialization.Serializable(with = xyz.block.trailblaze.yaml.TrailArgMapSerializer::class)
  val args: Map<String, xyz.block.trailblaze.yaml.TrailArgConfig>? = null,
) {
  /**
   * Names of the multi-device CONFIGURATION entries in [devices] (entries carrying an inner
   * `devices:` map, e.g. `pos-pair`). These names are **invisible to classifier lineage**: a
   * configuration name is matched only by exact configuration selection (the session that runs
   * it), never by a device's classifier resolution chain — otherwise a device whose chain
   * happens to contain the name (e.g. a configuration named `pair` sits on a `pair-a` device's
   * chain) would resolve a configuration's recordings/pins as if they were its own
   * single-device entries.
   */
  val multiDeviceConfigurationNames: Set<String>
    get() = devices?.filterValues { it.isConfiguration }?.keys ?: emptySet()

  /**
   * The configuration a **pre-flight** surface must assume this trail will bind: its single
   * declared configuration, or null when it declares none or more than one.
   *
   * A session is told which configuration it selected; a surface that reasons about a trail before
   * any session exists — a CI `requireRecordings` gate, a skip gate, a planning banner — is not.
   * Passing null there is not neutral: every configuration-keyed leg, pin, and skip is invisible to
   * classifier lineage, so the trail reads as unrecorded and unskipped no matter what it declares.
   *
   * The rule mirrors `MultiDeviceConfigurationResolver.resolve`, which is what actually binds a
   * session, and it must keep mirroring it: this value only means anything because it predicts
   * that decision. The resolver discards ordinary single-device entries
   * (`filterValues { it.isConfiguration }`) and binds the sole surviving configuration, so
   * **ordinary entries alongside a configuration do not withhold the selection** — a trail
   * declaring `pos-pair` plus an `android-phone` pin still runs as `pos-pair`. Predicting null for
   * that shape would reproduce, for mixed trails, the same unrecorded/unskipped misreading this
   * property exists to prevent.
   *
   * The one abstention is more than one configuration: such a trail is refused at session start,
   * so there is no decision to predict. Returning null leaves the pre-existing invisible-legs
   * behavior rather than guessing which one would have run.
   */
  val soleMultiDeviceConfigurationName: String?
    get() = multiDeviceConfigurationNames.singleOrNull()

  companion object {
    /**
     * Reserved [metadata] key bridging v1 `source.type` (a `TrailSourceType` name; empty string
     * for a bare `source: {}` marker). An unrecognized value is left in metadata untouched.
     */
    const val METADATA_KEY_SOURCE: String = "source"

    /** Reserved [metadata] key bridging v1 `source.reason` (typically an issue URL). */
    const val METADATA_KEY_SOURCE_REASON: String = "sourceReason"
  }
}
