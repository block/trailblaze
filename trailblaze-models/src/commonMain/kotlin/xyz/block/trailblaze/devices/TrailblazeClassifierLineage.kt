package xyz.block.trailblaze.devices

/**
 * The classifier-lineage primitive: given a device classifier, produces its
 * **total, ordered (most-specific-first) ancestor chain** — the single source
 * of truth for "which broader classifiers does this one fall back to."
 *
 * Two consumers share this exact primitive:
 *  - [xyz.block.trailblaze.yaml.unified.UnifiedTrailAdapter] — closest-wins
 *    resolution of a unified trail's per-classifier recordings for the device
 *    under test.
 *  - the waypoint schema (classifier-keyed assertions + closest-wins) — same
 *    lookup, different payload.
 *
 * Both used to rely on a *caller-supplied* most-specific-first chain. That put
 * the ordering and completeness of the fallback chain in every caller's hands,
 * and nothing guaranteed it was total or consistent across the two consumers.
 * Centralizing it here makes the lineage a property of the classifier itself.
 *
 * ## How a parent is computed
 *
 * For a classifier `C`, its parent is:
 *  1. an **explicit override** if one is registered for `C` (see
 *     [registerParentOverride]); otherwise
 *  2. the **string-derived** parent — drop the trailing `-segment`
 *     (`android-phone-37` → `android-phone` → `android`).
 *
 * String-derivation means **arbitrary classifier depth works for free** with
 * no schema or parser change: a deeper classifier (`android-phone-37`)
 * resolves up through every intermediate (`android-phone`) to its family
 * (`android`). Explicit overrides exist only for **irregular families that
 * don't string-derive** — e.g. a hardware family whose name isn't a prefix of
 * its platform, which needs `<family>` → `android` wired in by hand.
 *
 * ## Totality
 *
 * For a non-blank classifier, [chainFor] is total: it returns a non-empty list
 * whose first element is the input classifier, and it always terminates —
 * string-derivation strictly shortens the string each step, and a cycle in the
 * override map is broken by a visited-set so a malformed override can never
 * hang resolution. A blank classifier has no identity to resolve and yields an
 * empty chain.
 *
 * ## Representation note
 *
 * Device-classifier providers emit a device's identity as a **broad-first
 * segment list** (e.g. `[ios, iphone]`, `[android, phone]`) — the same shape
 * [xyz.block.trailblaze.recordings.TrailRecordings.computePossibleFileNamesForDeviceClassifiers]
 * joins with `-` to build legacy per-platform filenames (`ios-iphone.trail.yaml`).
 * [resolutionChain] applies that same join to recover the device's compound
 * identity (`ios-iphone`) and then expands it through the lineage.
 */
object TrailblazeClassifierLineage {

  /**
   * Globally-registered explicit parent overrides (child classifier → parent
   * classifier), held as an immutable map reassigned wholesale on registration.
   *
   * **Threading contract:** registration is intended for one-time startup
   * wiring that completes before any concurrent decode reads the registry —
   * the same "register before read" contract the tool registry uses. This is a
   * plain `commonMain` var with no portable cross-thread synchronization
   * (`synchronized` isn't available on every target), so it does not promise an
   * atomic snapshot to a reader racing a late registration; callers that need a
   * fixed override set should pass it explicitly to [chainFor] / [resolutionChain].
   *
   * Empty by default. Open-source classifier families (`android-phone`,
   * `ios-ipad`, …) all string-derive to their platform, so they need no
   * entry; the registry exists for distribution-specific irregular families
   * that a downstream module wires in at startup.
   */
  private var registeredOverrides: Map<String, String> = emptyMap()

  /** Read-only view of the currently-registered explicit parent overrides. */
  val parentOverrides: Map<String, String> get() = registeredOverrides

  /**
   * Register an explicit parent for an irregular family classifier that does
   * not string-derive to its broader classifier. Idempotent for an identical
   * `child` → `parent` pair; re-registering the same child with a *different*
   * parent replaces the previous mapping.
   *
   * Must be called during startup wiring, before the first trail decode that
   * relies on it — the same "register before read" contract the tool registry
   * uses. Registering against the global registry affects every subsequent
   * [chainFor] / [resolutionChain] call that doesn't pass its own `overrides`.
   */
  fun registerParentOverride(child: String, parent: String) {
    require(child.isNotBlank()) { "Lineage override child classifier must not be blank" }
    require(parent.isNotBlank()) { "Lineage override parent classifier must not be blank" }
    require(child != parent) {
      "Lineage override child and parent must differ (got `$child` → `$parent`)"
    }
    registeredOverrides = registeredOverrides + (child to parent)
  }

  /** Test-only: drop all globally-registered overrides so suites don't leak into each other. */
  internal fun clearRegisteredOverridesForTest() {
    registeredOverrides = emptyMap()
  }

  /**
   * Most-specific-first ancestor chain for a single [classifier]. See the
   * class kdoc for the parent-resolution rule and totality guarantee.
   */
  fun chainFor(
    classifier: TrailblazeDeviceClassifier,
    overrides: Map<String, String> = registeredOverrides,
  ): List<TrailblazeDeviceClassifier> =
    chainForString(classifier.classifier, overrides).map { TrailblazeDeviceClassifier(it) }

  /**
   * Resolution chain for a device's [deviceClassifiers] — the broad-first
   * segment list a [TrailblazeDeviceClassifiersProvider] emits (`[ios, iphone]`).
   *
   * The chain is, most-specific-first and de-duplicated:
   *  1. the device's **compound identity** (segments joined with `-`, e.g.
   *     `ios-iphone`) expanded through the lineage — `[ios-iphone, ios]`;
   *  2. then each **original segment's** own lineage (most-specific segment
   *     first), so a non-hierarchical segment that isn't a suffix of the
   *     compound is still probed. A provider that emits `[ios, revyl-cloud]`
   *     joins to `ios-revyl-cloud` (which derives `ios-revyl → ios`, never
   *     `revyl-cloud`), so without step 2 a recording keyed `revyl-cloud:`
   *     would never resolve. Step 2 appends `revyl-cloud` (and its parent) as a
   *     lower-priority fallback after the compound identity.
   *
   * Empty input → empty chain (no device identity to resolve).
   */
  fun resolutionChain(
    deviceClassifiers: List<TrailblazeDeviceClassifier>,
    overrides: Map<String, String> = registeredOverrides,
  ): List<TrailblazeDeviceClassifier> {
    // Drop blank segments before joining so a stray empty classifier can't corrupt the
    // compound identity (`[ios, ""]` must not become `"ios-"`). Providers emit non-blank
    // classifiers; this is purely defensive.
    val segments = deviceClassifiers.filter { it.classifier.isNotBlank() }
    if (segments.isEmpty()) return emptyList()
    val ordered = LinkedHashSet<String>()
    // 1. Compound device identity (most specific) → its lineage.
    val compound = segments.joinToString("-") { it.classifier }
    ordered.addAll(chainForString(compound, overrides))
    // 2. Each original segment's lineage, most-specific segment first, so a
    //    non-suffix segment (e.g. `revyl-cloud`) remains probeable as a fallback.
    for (segment in segments.asReversed()) {
      ordered.addAll(chainForString(segment.classifier, overrides))
    }
    return ordered.map { TrailblazeDeviceClassifier(it) }
  }

  private fun chainForString(classifier: String, overrides: Map<String, String>): List<String> {
    if (classifier.isBlank()) return emptyList()
    val chain = ArrayList<String>()
    val seen = HashSet<String>()
    var current: String? = classifier
    while (current != null && seen.add(current)) {
      chain.add(current)
      current = parentOf(current, overrides)
    }
    return chain
  }

  /**
   * Parent of [classifier]: explicit override if present, else the
   * string-derived parent (trailing `-segment` dropped). Returns null when the
   * classifier is a root (no override and no derivable `-segment`).
   */
  private fun parentOf(classifier: String, overrides: Map<String, String>): String? {
    overrides[classifier]?.let { return it }
    val lastDash = classifier.lastIndexOf('-')
    // No usable hyphen (none, leading `-foo`, or trailing `foo-`) → root.
    if (lastDash <= 0 || lastDash == classifier.length - 1) return null
    return classifier.substring(0, lastDash)
  }
}
