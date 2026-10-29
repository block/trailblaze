// TypeScript TRAILHEAD with teardown: lands on the Settings tab with "Accept Terms" unchecked.
//
// After a passing trail the app is left on Settings with the box checked. `teardown` says so, and
// the next trail's trailhead (with reuse on) skips the relaunch and only unchecks the box.

import { trailblaze, type ToolContext } from "@trailblaze/scripting";

const APP_ID = "xyz.block.trailblaze.examples.sampleapp";

/** Launch the Sample App on the Settings tab with "Accept Terms" unchecked. */
export const sampleapp_openSettings = trailblaze.trailhead(
  {
    supportedPlatforms: ["android"],
    to: "sampleapp/android/settings",
    async teardown(_args, _ctx, trailRun) {
      if (trailRun.outcome !== "passed") return { reusable: false, reason: `trail ${trailRun.outcome}` };
      return { reusable: true, reason: "left on Settings", state: { acceptTermsChecked: true } };
    },
  },
  async (_input, ctx) => {
    const previous = ctx.previousRun;
    if (previous && (await isVisible(ctx, { textRegex: "^Accept Terms$" }))) {
      return previous.reuse("still on Settings", async () => {
        if (previous.state?.acceptTermsChecked) await tap(ctx, "Accept Terms");
        await expectUnchecked(ctx);
        return `Reused Settings from ${previous.sessionId} and unchecked Accept Terms.`;
      });
    }
    const appId = ctx.target?.resolveAppId({ defaultAppId: APP_ID }) ?? APP_ID;
    await ctx.tools.launchApp({ appId, launchMode: "FORCE_RESTART" });
    await tap(ctx, "Settings");
    await expectUnchecked(ctx);
    return `Launched ${appId} on Settings.`;
  },
);

async function tap(ctx: ToolContext, text: string): Promise<void> {
  await ctx.tools.tapOnElementBySelector({
    reason: `Tap "${text}".`,
    nodeSelector: { androidAccessibility: { textRegex: `^${text}$` } },
  });
}

async function isVisible(ctx: ToolContext, match: { textRegex: string }): Promise<boolean> {
  const [matches] = await ctx.tools.findSelectorMatches({ selectors: [{ androidAccessibility: match }] });
  return matches.length > 0;
}

// The checked state is on the Accept Terms row, not on its text.
async function isAcceptTermsChecked(ctx: ToolContext): Promise<boolean> {
  const [matches] = await ctx.tools.findSelectorMatches({
    selectors: [
      { androidAccessibility: { isChecked: true }, containsChild: { androidAccessibility: { textRegex: "^Accept Terms$" } } },
    ],
  });
  return matches.length > 0;
}

async function expectUnchecked(ctx: ToolContext): Promise<void> {
  if (await isAcceptTermsChecked(ctx)) {
    throw new Error("Accept Terms is still checked");
  }
}
