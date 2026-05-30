import { selectors, trailblaze } from "@trailblaze/scripting";
import {
  elementIsVisible,
  tryOrFalse,
} from "./wikipedia_shared";

// Probe selector for the fundraising-banner close button. The previous shape
// was a CSS class list (`.frb-close, .cdx-button.frb-close`) which doesn't
// round-trip through `findMatches` — the captured `cssSelector` field is
// equality-matched against a single `#id` / `[data-testid="..."]`, not a
// query. Matching by ARIA `button` role + accessible-name regex catches the
// banner close button regardless of which class variant the Codex skin ships
// this quarter. The click ref below remains class-based since the Playwright
// `ref:` dispatch supports `css=` query syntax directly.
//
// `ariaNameRegex` is case-sensitive by contract — we deliberately enumerate
// the expected capitalizations (`Close` / `Dismiss`, matching Codex's
// accessible-name convention) rather than reaching for `(?i)`. If Wikipedia
// ships a lower-cased variant, the probe returns false and the tool
// no-ops gracefully — the explicit-case shape is the load-bearing safety
// property, not match completeness. No `^...$` anchor (per the repo's
// "no anchored regex selectors" convention) — a button named "Close menu"
// is also a legitimate match.
const BANNER_PROBE = selectors.web({
  ariaRole: "button",
  ariaNameRegex: "Close|Dismiss",
});

// Click ref (with `css=` prefix; this one is for the Playwright tool dispatcher).
const BANNER_CLICK_REF = "css=button.frb-close, .frb-close, .cdx-button.frb-close";

/** Input args for `wikipedia_web_dismissBannerIfPresent` — empty, the tool takes no parameters. */
export interface DismissBannerIfPresentArgs {
  // No fields. Declared as a named interface (not `Record<string, never>`) so
  // `ts-json-schema-generator` emits a `{"type":"object",...}` schema rather
  // than the empty `{}` the alias resolves to.
}

/**
 * Dismiss any visible Wikipedia fundraising / fundraiser banner. Use this
 * whenever the task is to close a banner, dismiss a popup, or clear out
 * Wikipedia's donate prompt. If no banner is currently visible the tool is
 * a no-op that returns successfully, so it's safe to call unconditionally
 * at the start of a flow. Probes with a sub-second visibility check before
 * attempting the click so the no-banner path is near-instant.
 */
// Single type arg — the analyzer defaults `TResult` to `string`, matching the SDK's
// `<TInput, TResult = string>` default. Authoring `<Args, string>` would require a
// named type alias for the primitive (the analyzer rejects inline primitive type
// arguments), and there's no value in the indirection for a tool that returns a
// plain message.
//
// (spec, handler) overload: the inline spec object carries `supportedPlatforms`
// + `requiresContext` so the runtime `_meta` gates are populated entirely from
// the `.ts` source — no need for the YAML descriptor's `_meta:` block. The
// analyzer extracts the literal at the call site.
export const wikipedia_web_dismissBannerIfPresent = trailblaze.tool<DismissBannerIfPresentArgs>(
  { supportedPlatforms: ["web"], requiresContext: true },
  async (_args, ctx) => {
    if (!(await elementIsVisible(ctx, BANNER_PROBE))) {
      return "No Wikipedia banner visible — nothing to dismiss.";
    }

    // `tryOrFalse` still guards the click because the banner can disappear
    // between the probe and the click (e.g. an auto-dismiss script firing).
    const dismissed = await tryOrFalse(() =>
      ctx.tools.web_click({
        ref: BANNER_CLICK_REF,
      }),
    );

    return dismissed
      ? "Dismissed Wikipedia fundraising banner."
      : "Banner disappeared between probe and click — no action taken.";
  },
);
