package xyz.block.trailblaze.api.waypoint

import kotlinx.serialization.Serializable

/**
 * A named, assertable app location identified by structural properties of the view
 * hierarchy. Composed of a list of [required] selector entries (all must match) and a
 * list of [forbidden] selector entries (none may match).
 *
 * ## Pack-scoped id convention
 *
 * Waypoint [id]s use a `<pack-id>/<local-name>` shape (e.g. `clock/android/alarm_tab`,
 * `gmail/inbox`). The slash is the namespace separator between the owning pack and the
 * waypoint's local name within that pack. On disk, waypoint descriptors live under
 * `trailblaze-config/packs/<pack-id>/waypoints/`, so the leading pack segment maps to
 * the owning pack directory — but the descriptor filename itself does not need to
 * mirror the slash-separated id segments. Pack ownership is implicit by directory
 * layout, so simple local names like `alarm_tab` stay readable and the slash-prefixed
 * form gives an LLM the namespace at a glance.
 *
 * Three-part ids `<pack-id>/<platform>/<local-name>` are used by packs whose waypoints
 * are platform-specific (e.g. `myapp/android/checkout_keypad`, `myapp/ios/splash`).
 * The platform segment is conventional, not enforced by the loader.
 *
 * This intentionally diverges from the older underscore tool-naming convention
 * (`2026-01-14-tool-naming-convention.md`), which was driven by serialization needing to
 * look up backing Kotlin classes from the tool name — a constraint that no longer holds
 * now that YAML descriptors carry fully-qualified class names directly. New pack-scoped
 * artifacts (routes, trails, waypoints) should follow the slash convention; legacy flat
 * runtime tool ids stay on underscore for back-compat with the existing namespace.
 *
 * ## Description as LLM hint
 *
 * Think of [description] like Claude / agent skill frontmatter: a **short**, one-line
 * hint an LLM (or a human author picking a waypoint to assert against) can scan to
 * decide whether this waypoint is the right one. It is the surface the runtime shows
 * in `trailblaze waypoint list` and that agents will rank against, so its job is
 * "what is this and when should I pick it" — not "how is it implemented".
 *
 * **Keep out of [description]:**
 *  - Selector implementation details (regex patterns, resource id conventions). Put
 *    those on the individual [WaypointSelectorEntry.description] fields, or as YAML
 *    comments next to the selector — that's where someone debugging matches looks.
 *  - Multi-paragraph rationale, history, or design notes. Those belong in commit
 *    messages, devlog entries, or a sibling README.
 *
 * **Do include:**
 *  - One sentence on what is on screen.
 *  - Disambiguation against same-pack siblings when relevant ("Distinct from X").
 *
 * Bad (mixes hint with implementation):
 * ```
 * description: >
 *   Google Clock app, Clock tab is selected at the bottom nav, showing the cities
 *   list. Resource IDs use a regex alternation to match both the Google variant
 *   (com.google.android.deskclock) and the AOSP variant (com.android.deskclock).
 * ```
 *
 * Good (one-line LLM hint):
 * ```
 * description: "Clock tab selected; cities list visible. Distinct from clock/android/alarm_tab."
 * ```
 *
 */
@Serializable
data class WaypointDefinition(
  val id: String,
  val description: String? = null,
  val required: List<WaypointSelectorEntry> = emptyList(),
  val forbidden: List<WaypointSelectorEntry> = emptyList(),
)
