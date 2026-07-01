package xyz.block.trailblaze.api.waypoint

import kotlinx.serialization.Serializable

/**
 * A named, assertable app location, expressed once across every platform/form-factor it
 * supports. A waypoint is **a single definition with classifier-keyed, sparse blocks** — the same
 * model the unified trail format uses for trail steps. Shared identity fields ([id],
 * [description], [route]) sit at the top; each device-classifier block ([byClassifier]) carries
 * only what is specific to that classifier (its selectors, its example, an optional route
 * override). Every field is resolved closest-wins up the device's classifier lineage — see
 * [resolveFor].
 *
 * ## Why classifier-keyed (v2)
 *
 * v1 expressed "one waypoint across platforms" as N separate files that merely shared an [id]
 * string, distinguished only by their `waypoints/<platform>/` directory — implicitly linked and
 * drift-prone (a rename on one side and not the other left a trailhead pointing at a waypoint id
 * that no longer existed). v2 makes the linkage *structural*: one definition, one source of truth
 * per place, with the genuinely platform-specific parts (selectors — different drivers) nested
 * under their classifier key.
 *
 * ```yaml
 * id: myapp/items
 * description: "Items home (All items / Categories / Modifiers …)."
 * route: myapp-scheme://items          # shared default address (covers android + ios)
 * android:                             # family block: selectors shared by phone + tablet
 *   required: [ { selector: { androidAccessibility: { textRegex: "All items" } } } ]
 *   example: { file: items.android.example.json, capturedAt: 2026-06-27T08:45:09Z }
 * ios:
 *   required: [ { selector: { iosAxe: { uniqueId: "ItemsHeader" } } } ]
 * web:
 *   route: https://app.example.com/items   # per-classifier route OVERRIDE
 *   required: [ { selector: { web: { ariaRole: heading, ariaNameRegex: "Items" } } } ]
 * ```
 *
 * ## Trailmap-scoped id convention (URL-style)
 *
 * Waypoint [id]s follow URL conventions: `<trailmap-id>/<segment>[/<segment>...]`. The slash is
 * both the trailmap-namespace separator and the IA hierarchy separator within the trailmap. Use
 * `-` for multi-word atoms within a single segment (`myapp/inbox/inventory-upsell`). The id is
 * **logical, not platform-tagged** — a waypoint named `myapp/home` is the conceptual "MyApp home"
 * regardless of platform; the per-platform recognition signals live in [byClassifier].
 *
 * ## Route binding and provenance ([route])
 *
 * [route] is the canonical address this waypoint binds to — the deep link (mobile) / client route
 * / web path that navigates here. Its presence is a *provenance stamp*: a route-bound waypoint is
 * **declared** (its identity comes from the app's authoritative route catalog and is verified by
 * arriving there), versus a legacy auto-discovered guess. By convention a route-bound waypoint's
 * [id] mirrors the route path, but `id != route` is allowed and a route-less waypoint is
 * legitimate. Route is the one field that *may* appear at the top level (the same address usually
 * works across platforms); a [WaypointVariant.route] overrides it only where the address genuinely
 * differs. **Selectors never appear at the top** — they're driver-specific.
 *
 * ## Description as LLM hint
 *
 * Think of [description] like agent-skill frontmatter: a **short**, one-line hint an LLM (or a
 * human picking a waypoint to assert against) can scan to decide whether this is the right one.
 * Keep selector implementation details out of it (those go on [WaypointCondition.description] or
 * as YAML comments); include one sentence on what's on screen plus disambiguation against
 * same-trailmap siblings ("Distinct from X").
 */
@Serializable(with = WaypointDefinitionSerializer::class)
data class WaypointDefinition(
  val id: String,
  val description: String? = null,
  /**
   * Shared default route (see "Route binding and provenance" above). `null` for legacy /
   * route-less waypoints. A [WaypointVariant.route] overrides this per classifier.
   */
  val route: String? = null,
  /**
   * Per-device-classifier blocks, keyed by classifier name (`android`, `ios`, `web`,
   * `android-tablet`, …). Each block is sparse; [resolveFor] fills each field from the closest
   * classifier in the device's lineage that declares it.
   */
  val byClassifier: Map<String, WaypointVariant> = emptyMap(),
)
