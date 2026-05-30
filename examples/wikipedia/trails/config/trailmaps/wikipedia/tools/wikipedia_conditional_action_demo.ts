// Sample scripted tool demonstrating the `ConditionalAction` primitive end-to-end —
// the framework primitive that replaces the deleted prompt-pattern objective
// classifier (`CONDITIONAL_PATTERNS`). See #3455 for the design.
//
// This file deliberately ships WITHOUT a companion `.yaml` descriptor, same
// convention as `wikipedia_typed_demo.ts`. The trailmap loader scans
// `tools/*.yaml`, so without a sibling YAML this file is NOT registered with
// the runtime — it's a compileable, IDE-typecheckable reference that an
// adopting team can copy and tailor (per-target dismiss tool, internal launch
// composition, whatever fits the team's shape — those are explicit Phase 3+
// follow-ups, NOT shipped here).
//
// What this example illustrates:
//   1. Authoring a `ConditionalAction[]` catalog with one entry that's
//      `condition + action + postcondition`.
//   2. Pre-resolving the catalog's selectors via `captureViewHierarchy(client,
//      [...selectors])` to build the sync ViewHierarchy snapshot.
//   3. Running `runConditionalActions(client, conditionalActions, snap)` and observing
//      the `{ handled: [...ids] }` return shape.
//
// What this example deliberately does NOT illustrate:
//   - Per-target catalogs — adopting-team dismiss-popup catalogs ship in
//     Phase 3+ by the team that owns each target, not in the framework.
//   - Integration into a launch tool (composition is also Phase 3+, an
//     adopting team's call).
//   - `when` / `if-else` branching primitives (deliberately out of Phase 2
//     scope — #3455 calls these out explicitly).

import {
  captureViewHierarchy,
  ConditionalActionFailedError,
  runConditionalActions,
  selectors,
  trailblaze,
  type ConditionalAction,
} from "@trailblaze/scripting";

/** Inputs for the ConditionalAction-primitive demo tool. */
export interface ConditionalActionDemoInput {
  // No fields — the catalog is hard-coded for the demo. A real adopter would
  // expose flags (e.g. `aggressive: boolean`) here as needed.
}

/** Result of the ConditionalAction-primitive demo tool. */
export interface ConditionalActionDemoOutput {
  /** IDs of the catalog entries whose `action` was dispatched. Empty when nothing matched. */
  handled: string[];
}

/**
 * Demonstrates the `runConditionalActions` primitive against the Wikipedia main page's
 * optional fundraising banner. Builds a single-entry catalog with a `condition`
 * (banner visible), `action` (click the close button), and `postcondition`
 * (banner no longer visible — also doubles as the skip-fast check when the
 * banner was never there).
 *
 * Idempotent — if the banner isn't present, the tool no-ops without dispatching
 * the click action (snapshot-bulk-evaluate's no-match fast path).
 */
export const wikipedia_conditional_action_demo = trailblaze.tool<
  ConditionalActionDemoInput,
  ConditionalActionDemoOutput
>(
  { supportedPlatforms: ["web"], requiresContext: true },
  async (_input, ctx) => {
    // The fundraising banner's close button — Codex skin's ARIA shape. Same
    // probe `wikipedia_web_dismissBannerIfPresent.ts` uses, intentionally so
    // an adopter can compare the two authoring styles side by side.
    const BANNER_CLOSE = selectors.web({
      ariaRole: "button",
      ariaNameRegex: "Close|Dismiss",
    });

    const catalog: ConditionalAction[] = [
      {
        id: "dismiss-fundraising-banner",
        description:
          "Close the fundraising banner if one is showing on the main page.",
        condition: (snap) => snap.visible(BANNER_CLOSE),
        action: async () => {
          // The action's body is plain async TS — composing other Trailblaze
          // tools via `ctx.tools.<name>(args)` is the typical shape. The
          // ConditionalAction primitive itself never opens a wire to the daemon;
          // the action does, on the entries that apply.
          await ctx.tools.web_click({
            ref: "css=button.frb-close, .frb-close, .cdx-button.frb-close",
          });
        },
        // The postcondition does double duty:
        //   - Pre-action: if the banner already isn't visible (page never had
        //     one, or a prior step closed it), the entry skips entirely —
        //     zero action dispatch, zero verify round-trip.
        //   - Post-action: if the click didn't actually dismiss the banner
        //     (catalog drift, wrong selector, racing modal), `runConditionalActions`
        //     throws `ConditionalActionFailedError("dismiss-fundraising-banner", ...)`
        //     so the call site can surface the offending entry's id.
        postcondition: (snap) => !snap.visible(BANNER_CLOSE),
      },
    ];

    // The same `ctx.tools` namespace satisfies `TrailblazeClient`'s public surface
    // (only `tools` is publicly typed; `callTool` is hidden). Pass the context
    // through to the primitive directly.
    const snap = await captureViewHierarchy(ctx, [BANNER_CLOSE]);
    try {
      const result = await runConditionalActions(ctx, catalog, snap);
      return { handled: result.handled };
    } catch (e) {
      // Adopters that prefer to swallow drift (best-effort dismissal) can catch
      // here. This demo re-throws so the failure surfaces to the trail with the
      // entry id pointed at — fail loud over fail silent is the default.
      if (e instanceof ConditionalActionFailedError) {
        throw new Error(
          `ConditionalAction "${e.conditionalActionId}" failed during demo dismissal: ${e.message}`,
        );
      }
      throw e;
    }
  },
);
