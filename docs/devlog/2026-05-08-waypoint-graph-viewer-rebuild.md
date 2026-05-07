---
title: "Waypoint graph viewer: focal+depth, agent prompt tab, dock UX"
type: devlog
date: 2026-05-08
---

# Waypoint graph viewer: focal+depth, agent prompt tab, dock UX

## Summary

PR #2797 rebuilt the waypoint-graph viewer around three load-bearing ideas: a *subway view* that shows one focal waypoint plus everything reachable within N forward hops (instead of the whole adjacency list at once), a single right-side *dock* with `[Detail][Agent]` tabs (instead of the prior pair of independent panels), and an *agent prompt* tab that renders the exact textual prompt the LLM sees per turn so what humans browse and what the agent reads stay structurally aligned. This devlog captures the implementation choices behind those three pieces — the [vision devlog](2026-05-08-waypoint-shortcut-graph-vision.md) covers why we want the graph at all; this one covers what shipped to render it.

## Why the rebuild was necessary

The pre-rebuild viewer rendered the full adjacency list as a force-directed `react-flow` graph, with separate panels for waypoint detail, shortcut detail, and an LLM-prompt preview. It worked at toy size — the OSS clock pack's ~10 waypoints fit fine — but two problems compounded as packs grew:

- **Visual overload at scale.** Real packs land in the 80–300 waypoint range — iOS Contacts is 100; merchant POS surfaces are upwards of that. A force-directed layout with that many nodes degenerates into hairball regardless of layout algorithm — the user can't see the graph through the edges.
- **Three independent panels meant three independent scroll states.** The waypoint-detail panel, the shortcut-detail panel, and the agent-prompt panel each had their own header and their own collapse state. Selecting a waypoint surfaced detail in panel A; clicking an outgoing shortcut blew away the waypoint detail and surfaced shortcut detail in panel B; the prompt preview in panel C was a separate UI mode entirely. Authors couldn't cross-reference what the LLM would see against the structural detail of the focal waypoint without flipping modes.

The rebuild keeps `react-flow` for the rendering substrate but reframes the user's mental model around a *focal waypoint* and a *forward depth bound*, with the right-side dock as the single place all per-selection context lives.

## Subway view: focal + forward depth

**The model.** At any time the viewer has one focal waypoint and one depth value. The visible graph is the focal plus every waypoint reachable from the focal in `≤ depth` forward shortcut hops. Clicking a non-focal visible waypoint makes that the new focal (re-roots the view). Depth is configurable 1–10 via a number input; the default is 3, which empirically keeps every pack we've authored under ~30 visible nodes while showing enough surrounding context to navigate.

**Why forward-only and not bidirectional.** Bidirectional expansion (depth N forward + depth N backward) felt symmetrical but produced graphs the user couldn't reason about: at depth 2 the home screen pulls in *every* leaf that has a "back to home" shortcut, which is most of them. Forward-only matches the agent's model — it's planning a path *forward* from where it is — so what the human sees is what the agent's planner-tier reasoning operates on.

**Why depth 1–10 and not unbounded.** Depth 10 already shows the full graph for every pack we've authored. The bound exists so a typo (depth 100, depth -1) doesn't blow up the renderer; the upper edge being 10 is a soft cap that pushes "I want everything" toward the explicit `?depth=10` rather than implicit unboundedness.

**Default focal.** The viewer picks the first declared trailhead's `to:` waypoint. That's almost always the app's signed-in home screen, which is the most useful starting point for someone exploring a pack for the first time.

**De-emphasized cross-tab edges.** Some packs (Contacts among them) have edges between top-level tabs that the framework loads but that aren't meaningful navigation paths from a UX perspective (e.g. "tab from Profile to Money"). These render as dimmed edges so they're discoverable but don't compete visually with the meaningful flows.

## Right-side dock with `[Detail][Agent]` tabs

**One dock, two tabs, sticky-collapse.** The dock auto-opens on the user's first selection in a session, then sticky-collapses based on user action thereafter. Two tabs:

- **Detail.** Shows the focal waypoint's id, description, screenshot, `required:` and `forbidden:` selectors with their YAML, and the list of incoming/outgoing shortcuts (each clickable to navigate the focal). Replaces what was previously two panels (waypoint-detail + shortcut-detail) — selecting an incoming shortcut now scrolls the same panel to that shortcut's body, instead of swapping panels.
- **Agent.** Shows the exact textual prompt the LLM sees for the current focal. See "Agent prompt format" below for the format choices.

The tabs strip lives at the top of the dock with an inline close button. The collapsed-dock state shows a thin floating handle on the right edge that re-opens to whichever tab was last active — so users who collapse the dock don't lose their place in it.

**Why one dock and not three panels.** All three pieces of context (waypoint detail, outgoing shortcut detail, agent prompt) are *about the same focal waypoint*. Co-locating them in tabs makes that clear and lets the user keep one scroll position while flipping between views. The cost is that the dock is wider than any individual panel was; we accepted that because the graph area is the primary surface and ~40% screen width for context is reasonable.

## Agent prompt format: lookup tables, not nested paths

The agent tab renders the textual prompt the planner-tier LLM would receive if it were asked to navigate from the current focal. The format is the second-most-important architectural choice in this PR (after the focal+depth model itself) because it dictates how the framework feeds the graph to the agent — what humans browse on this page is what the agent reads at runtime.

**The format that works.** A flat lookup-table structure: paths reference waypoints by id with a parenthetical `← parent`, and two appendices follow — `WAYPOINT DESCRIPTIONS` and `SHORTCUTS USED`. Schematically:

```
You are at: contacts/contact-detail-edit
Reachable in ≤ 3 forward hops:

  contacts/edit-add-field-picker  ← contact-detail-edit
  contacts/edit-add-date          ← edit-add-field-picker
  contacts/edit-add-social        ← edit-add-field-picker
  contacts/edit-address-section   ← contact-detail-edit
  ...

WAYPOINT DESCRIPTIONS
  contacts/contact-detail-edit
    Edit screen for a single contact. ...
  contacts/edit-add-field-picker
    Sheet that lets the user pick which kind of field to add ...

SHORTCUTS USED
  contacts_ios_editToAddFieldPicker
    from: contacts/contact-detail-edit
    to:   contacts/edit-add-field-picker
    body: tapElement Add field
  ...
```

**Why this shape.** Three formats were considered:

1. **Nested tree** — `home → tab-a → screen-x → modal-y`. Reads naturally at depth 1–2, but at depth 3+ the indentation explodes and the same waypoint shows up in many subtrees (a leaf reachable via 3 different parents appears 3 times with different leading paths). Quadratic-ish scaling on tokens, and the LLM has to merge the duplicates itself.
2. **Adjacency list with full descriptions inline** — `home/admin (top-level admin home, see also ...) → tab-a (...)`. Description duplication explodes worse than the tree, even at depth 1.
3. **Lookup tables with id-only paths** — what shipped. Each waypoint id appears once in the path list (with one `← parent` showing the closest in-graph predecessor), and once in each appendix. Description text amortizes across however many paths reference it. Token cost grows ~linearly with `|visible waypoints|`, not `|visible paths|`.

The lookup-table format also has the property that *the path list compresses well by depth* — at depth 1 it's just `you → neighbors`; at depth 10 it's still a flat list of ids. The shape doesn't fundamentally change as depth grows, only the count, which makes the format stable as we tune defaults.

**The `← parent` annotation, not a full path.** Showing the closest in-graph predecessor (rather than the full path from focal) keeps every path-list entry to one line regardless of depth. The full path can be reconstructed by following `← parent` links if the LLM needs it; in practice the planner only cares about the immediate predecessor when deciding which next-edge to take.

**Inspect modal for shortcut bodies.** `WaypointGraphShortcut` exposes `toolsList` and `toolClass` so the inspect modal in the Detail tab can show the body of any shortcut without a separate fetch. This is what makes the "expand a shortcut to see its taps" interaction feel instant — every body is in the inlined JSON.

## What the graph builder needs to feed this

`WaypointGraphData` carries waypoints, shortcuts, and trailheads as flat lists with stable ids. Two choices in the builder are load-bearing for the viewer's UX, both worth knowing:

- **Per-shortcut `toolsList` + `toolClass` exposure.** Surfaces the shortcut body to the inspect modal without a separate fetch — the modal renders from the inlined list. Without this, expanding a shortcut would either need a server round-trip (live daemon mode) or a separate JSON-blob lookup (CLI export mode).
- **Workspace-pack-aware shortcut/trailhead loading.** A workspace pack's shortcut tools live under `<workspace>/trails/config/packs/<id>/tools/`, not on the classpath. The builder threads `workspacePackDir` through `ToolYamlLoader.discoverShortcutsAndTrailheads` so calendar's 67 and contacts' 81 shortcut tools surface as graph edges in the standalone CLI export. Without this, the rendered HTML showed waypoints as orphaned nodes — visually confusing because the underlying authored graph was richer than the viewer's view.

## Default `--out` for the CLI export

`./trailblaze waypoint graph` writes to `./.trailblaze/reports/waypoint-graph.html` by default. Two design notes:

- **Why `.trailblaze/`** — tool-namespaced convention this project already uses for tool-owned data (settings, sessions, etc.) and stack-agnostic. Works the same in a Gradle / Node-TS / Python / no-build-system consumer, none of which would necessarily have a `build/`, `dist/`, or `target/` directory.
- **Why `reports/` and not `.trailblaze/` directly** — not everything under `.trailblaze/` is necessarily must-ignore content (a Trailblaze consumer might reasonably commit team-shared settings). Scoping the regenerated artifact to a dedicated `reports/` subdirectory means a careful consumer can `gitignore` just `.trailblaze/reports/` without blanket-ignoring the whole namespace. Consumers who want to ignore everything Trailblaze writes still can — `.trailblaze/` covers it.

## Open follow-ups

Designed but not in scope for #2797:

- **Hash-routing for shareable focal URLs.** `#focal=contacts/home&depth=5` reflected into the address bar so links pin the view; in-memory back/forward history lives in the dock for now.
- **Subway destination picker + shortest-path overlay.** A two-input control (start, end) that overlays all paths between them — same data the planner-tier LLM would see, surfaced visually. Unblocks "show me how to get from here to there" in a way that's more focused than the current "expand depth around the focal" interaction.
