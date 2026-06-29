package xyz.block.trailblaze.ui.waypoints

/**
 * UI-side mirror of an authored **shortcut tool** — a `ToolYamlConfig` whose
 * `shortcut: { from, to, variant? }` metadata block is populated. Rendered as a solid
 * directed edge between waypoint nodes on the map view.
 *
 * Lives in commonMain so the JVM Desktop tab and a future WASM target render the same
 * visualization. The JVM host wiring discovers authored shortcuts/trailheads via
 * `ToolYamlLoader.discoverShortcutsAndTrailheads()` so entries backed by either
 * `Mode.CLASS` or `Mode.TOOLS` bodies are preserved (the older `discoverYamlDefinedTools()`
 * path filtered to `Mode.TOOLS` only and would silently drop class-bodied shortcuts/
 * trailheads — don't reintroduce that filter), then converts each shortcut
 * `ToolYamlConfig` into one of these — the conversion is a flat field copy. We do not
 * pull `ToolYamlConfig` itself across the boundary because its body may carry
 * kotlinx-json types that we don't need on the visualization side.
 *
 * See [`docs/devlog/2026-04-28-shortcuts-as-tools.md`](../../../../../../../../docs/devlog/2026-04-28-shortcuts-as-tools.md)
 * for the data-model rationale (one type — `ToolYamlConfig` — with optional `shortcut:`
 * block; no parallel hierarchy).
 */
data class ShortcutDisplayItem(
  /** Tool id from the YAML — registry key, used for de-dup and as a tooltip label. */
  val id: String,
  /** Human-readable description from the YAML, if any. */
  val description: String?,
  /** Source waypoint id (e.g. `clock/android/alarm_tab`). */
  val from: String,
  /** Destination waypoint id. */
  val to: String,
  /**
   * Optional disambiguator for shortcuts that share the same `(from, to)` pair. The
   * canonical addressing tuple is `(from, to, variant?)` — most shortcuts never need it.
   */
  val variant: String?,
)

/**
 * UI-side mirror of an authored **trailhead tool** — a `ToolYamlConfig` whose
 * `trailhead: { to }` metadata block is populated. Rendered as an entry-point glyph
 * on the map view: the trailhead has no `from` (it bootstraps the agent from any state
 * to a known waypoint), so it draws as an edge from a virtual "outside" node into
 * its [to] target.
 *
 * Trailheads exist as their own UI shape (rather than `ShortcutDisplayItem` with a
 * nullable `from`) so the map renderer can keep the operational distinction visible —
 * gated-on-waypoint shortcuts vs. always-available trailheads — without runtime checks
 * on a sentinel value.
 */
data class TrailheadDisplayItem(
  /** Tool id from the YAML — registry key, used for de-dup and as a tooltip label. */
  val id: String,
  /** Human-readable description from the YAML, if any. */
  val description: String?,
  /**
   * Destination waypoint id this trailhead lands at, or null for a [dynamic] trailhead whose
   * destination varies by input — those have no fixed target and so draw no entry edge on the map.
   */
  val to: String? = null,
  /** True when this trailhead's destination varies by input (no fixed waypoint to anchor to). */
  val dynamic: Boolean = false,
)
