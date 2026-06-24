// TypeScript TRAILHEAD for the Loading demo.
//
// A trailhead is the first tool a trail runs — the deterministic bootstrap that lands the app on a
// known screen so the rest of the trail is replayable. This one cold-launches the Sample App,
// opens the Loading tab, kicks off the (variable-delay) load, and WAITS for the result to appear
// before returning — the same shape as a production launch+sign-in trailhead that waits for a
// sign-in screen to render after a cold start.
//
// How a trailhead like this runs: it goes in a trail's top-level `- tools:` block, and once the
// trail binds this trailmap (`target: sampleapp`) the daemon compiles this workspace tool to a
// bundle, ships it to the on-device runner, and dispatches each `ctx.tools.*` call against the live
// driver. The wait below uses `findMatches({ selector, timeoutMs })` — the capability PR #3853
// ("Let TypeScript tools wait for an element to appear") added for exactly this.
//
// This file is an AUTHORING REFERENCE — it shows how to write a TypeScript trailhead and is
// validated by its unit test (`sampleapp_launchToLoadedContent.test.ts`). It is intentionally NOT
// backed by a shipped runnable trail: compiling a workspace `.ts` tool requires the dev toolchain
// (`bun`/`esbuild` on PATH), so it isn't something a binary-install user can run by following a
// `trailblaze run …` snippet. The example's runnable, works-everywhere wait demo is the pure-YAML
// `loading/wait-for-content` trail, which composes only built-in tools.
//
// Scoped to the on-device accessibility driver (selector-native taps + findMatches wait).

import { trailblaze, type ToolContext, type TrailblazeNodeSelector } from "@trailblaze/scripting";

const APP_ID = "xyz.block.trailblaze.examples.sampleapp";
const ACCESSIBILITY_DRIVER_TYPE = "android-ondevice-accessibility";
// Generous upper bound covering the slowest delay chip (6s) plus settle. Event-driven, so the
// happy path returns the instant "Content Loaded" appears — the bound only bites on a slow load.
const CONTENT_LOADED_WAIT_MS = 30_000;

/**
 * Launch the Sample App, open the Loading demo, start the load, and wait for "Content Loaded" to
 * appear. Lands the trail on the loaded-content screen regardless of which delay (1s / 3s / 6s) is
 * selected, because it waits for the result rather than sleeping a fixed amount of time.
 */
export const sampleapp_launchToLoadedContent = trailblaze.tool(
  { supportedPlatforms: ["android"], requiresContext: true },
  async (_input, ctx) => {
    if (ctx.device?.driverType && ctx.device.driverType !== ACCESSIBILITY_DRIVER_TYPE) {
      throw new Error(
        `sampleapp_launchToLoadedContent targets ${ACCESSIBILITY_DRIVER_TYPE}, but the session ` +
          `driver is ${ctx.device.driverType}. See the loading/wait-for-content trail for the ` +
          `instrumentation-driver version of this wait.`,
      );
    }
    const appId = ctx.target?.resolveAppId({ defaultAppId: APP_ID }) ?? APP_ID;

    // 1. Cold-launch to a clean state (force-stop kills the process, so Compose state resets).
    await ctx.tools.launchApp({ appId, launchMode: "FORCE_RESTART" });

    // 2. Open the Loading tab, then 3. kick off the variable-delay load.
    await tapByText(ctx, "Loading");
    await tapByText(ctx, "Start Loading");

    // 4. The point: wait (event-driven) for the result to render.
    await waitForText(ctx, "Content Loaded", CONTENT_LOADED_WAIT_MS);

    return `Launched ${appId} and landed on the loaded-content screen.`;
  },
);

/** Tap the element whose text equals `text`. */
async function tapByText(ctx: ToolContext, text: string): Promise<void> {
  await ctx.tools.tapOnElementBySelector({
    reason: `Tap "${text}".`,
    nodeSelector: exactTextSelector(text),
  });
}

/**
 * Wait (up to `timeoutMs`) for `text` to appear, failing if it never does. `findMatches`'s
 * `timeoutMs` polls the live hierarchy until a match appears or the budget elapses, returning
 * whatever matched (empty == never appeared → we throw). This is the event-driven "wait until
 * visible" pattern, and the `findMatches({ selector, timeoutMs })` shape is the capability PR #3853
 * ("Let TypeScript tools wait for an element to appear") added for exactly this.
 */
async function waitForText(ctx: ToolContext, text: string, timeoutMs: number): Promise<void> {
  const matches = await ctx.tools.findMatches({
    selector: exactTextSelector(text),
    timeoutMs,
  });
  if (matches.length === 0) {
    throw new Error(
      `sampleapp_launchToLoadedContent: "${text}" did not appear within ${timeoutMs / 1000}s.`,
    );
  }
}

/** Anchored, regex-escaped Android-accessibility selector matching a node whose text equals `text`. */
function exactTextSelector(text: string): TrailblazeNodeSelector {
  return { androidAccessibility: { textRegex: `^${escapeRegExp(text)}$` } };
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}
