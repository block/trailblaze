// "Wait until visible" scripted tool for the Sample App trailmap.
//
// A screen whose render time varies has to be waited *for*, not slept *through*. A fixed
// `delay(3000)` either flakes when the screen is slow or wastes time when it's fast; an
// event-driven wait returns the instant the target appears and only spends the full budget on a
// genuinely slow render. This is exactly the pattern a real app's launch flow uses to wait for,
// say, a sign-in screen to render after a cold start before it interacts with it — distilled here
// against the Loading demo screen so it's easy to see in isolation.
//
// It composes the framework's `findMatches`/`maestro` primitives, uses zero Node APIs, and so runs
// unchanged on the in-process (QuickJS) path — see `runtime: inProcess` semantics in the README.

import { trailblaze, type ToolContext } from "@trailblaze/scripting";

// The on-device accessibility driver exposes the selector-native `findMatches` wait; the
// instrumentation/Maestro driver waits through Maestro's own `extendedWaitUntil`. A dual-driver
// wait has to branch here — an `androidAccessibility` selector isn't valid on the Maestro branch.
const ACCESSIBILITY_DRIVER_TYPE = "android-ondevice-accessibility";

// Generous default so a slow screen (the "Slow (6s)" chip on the Loading demo, plus any animation
// settle) still lands comfortably inside the budget. Event-driven, so the happy path returns far
// sooner — the larger bound only ever matters on a slow render.
const DEFAULT_WAIT_MS = 30_000;

/** Input for {@link sampleapp_waitForText}. */
export interface WaitForTextInput {
  /**
   * The exact, visible text to wait for (e.g. "Content Loaded"). Matched as an anchored regex
   * (`^…$`) against the live view hierarchy, with regex metacharacters escaped, so it matches the
   * whole text of a node rather than a substring.
   */
  text: string;
  /**
   * How long to wait, in milliseconds, before giving up. Defaults to 30000. The tool returns the
   * moment the text appears, so this is an upper bound, not a sleep.
   */
  timeoutMs?: number;
}

/**
 * Wait (up to `timeoutMs`) for an element whose text equals `text` to appear, then return. Throws a
 * clear timeout error if it never shows. Use this to make a step robust to a screen that loads with
 * a variable delay — it waits for the result to appear instead of sleeping a fixed amount of time.
 */
// The spec MUST be an inline object literal here: this is a descriptor-less tool (no sibling
// `.yaml`), so the build-time analyzer reads `supportedPlatforms` / `requiresContext` straight off
// this literal. A `const SPEC = {…}` reference would be dropped and the tool would advertise
// un-gated. Composes Android device-control primitives, so it's Android-only and needs a live
// driver context (`requiresContext: true` guarantees `ctx`).
export const sampleapp_waitForText = trailblaze.tool<WaitForTextInput>(
  { supportedPlatforms: ["android"], requiresContext: true },
  async (args, ctx) => {
    const text = String(args.text ?? "").trim();
    if (!text) {
      throw new Error("sampleapp_waitForText: `text` is required and must be non-empty.");
    }
    const timeoutMs = args.timeoutMs && args.timeoutMs > 0 ? args.timeoutMs : DEFAULT_WAIT_MS;

    await waitUntilTextShown(ctx, text, timeoutMs);
    return `"${text}" became visible within ${timeoutMs / 1000}s.`;
  },
);

/**
 * On the accessibility driver, use the selector-native `findMatches` wait (non-throwing — an empty
 * result means "never appeared", which we turn into a clear error). On the instrumentation driver,
 * let Maestro perform the `extendedWaitUntil` against its own hierarchy.
 *
 * Both branches match on the SAME anchored, regex-escaped value: Maestro's `text` selector is
 * regex-backed too, so feeding it the raw input would let metacharacters in arbitrary text (e.g.
 * `Total: $5.00 (USD)`) match the wrong node — or a different node that merely contains the text —
 * only on the instrumentation driver. Escaping + anchoring keeps the two drivers in lockstep.
 */
async function waitUntilTextShown(ctx: ToolContext, text: string, timeoutMs: number): Promise<void> {
  const anchored = anchoredTextRegex(text);
  if (usesAccessibilityDriver(ctx)) {
    const matches = await ctx.tools.findMatches({
      selector: { androidAccessibility: { textRegex: anchored } },
      timeoutMs,
    });
    if (matches.length === 0) {
      throw timeoutError(text, timeoutMs);
    }
    return;
  }

  await ctx.tools.maestro({
    commands: [
      {
        extendedWaitUntil: {
          visible: { text: anchored },
          timeout: timeoutMs,
        },
      },
    ],
  });
}

function usesAccessibilityDriver(ctx: ToolContext): boolean {
  return ctx.device?.driverType === ACCESSIBILITY_DRIVER_TYPE;
}

function timeoutError(text: string, timeoutMs: number): Error {
  return new Error(
    `sampleapp_waitForText: "${text}" did not become visible within ${timeoutMs / 1000}s.`,
  );
}

/** Anchored, regex-escaped pattern matching `text` exactly — shared by both driver branches. */
function anchoredTextRegex(text: string): string {
  return `^${escapeRegExp(text)}$`;
}

/** Escape every regex metacharacter so arbitrary user text is matched literally. */
function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}
