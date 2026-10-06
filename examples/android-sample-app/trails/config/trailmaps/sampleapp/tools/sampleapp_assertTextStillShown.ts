// Inverted not-visible check for the Sample App's multi-line eval
// (`evals/multiline-not-visible.trail.yaml`).
//
// A trail can't pass on an assertion that is supposed to fail, and an agent told to report FAILED
// when a check passes doesn't reliably do it. This tool runs `assertNotVisibleWithText` on text
// that IS on screen and passes only when that check correctly fails. On a false pass it also taps
// Clear All, which removes the text, so the trail's recorded last step fails even if the agent
// shrugs the error off.

import { trailblaze } from "@trailblaze/scripting";

/** Part of the message assertNotVisibleWithText fails with when the text is on screen. */
const STILL_PRESENT = "still present";

/** Input for {@link sampleapp_assertTextStillShown}. */
export interface AssertTextStillShownInput {
  /** The element's full text, copied exactly from the screen. */
  text: string;
}

/**
 * Confirm text IS on screen by running assertNotVisibleWithText on it and expecting that check to
 * fail. Passes when assertNotVisibleWithText finds the text; fails when it reports the text absent.
 */
export const sampleapp_assertTextStillShown = trailblaze.tool<AssertTextStillShownInput>(
  { supportedPlatforms: ["android"], requiresContext: true },
  async (args, ctx) => {
    const text = String(args.text ?? "");
    try {
      await ctx.tools.assertNotVisibleWithText({ text });
    } catch (e) {
      // Only "the text is still there" is the outcome this tool expects. Any other failure (no
      // complete capture, a driver error) proves nothing, so it fails the step as it is.
      if (!String(e instanceof Error ? e.message : e).includes(STILL_PRESENT)) throw e;
      return `assertNotVisibleWithText found ${JSON.stringify(text)} on screen.`;
    }
    await ctx.tools.tapOnElementBySelector({
      nodeSelector: { containsChild: { androidAccessibility: { textRegex: "Clear All" } } },
    });
    throw new Error(`assertNotVisibleWithText reported ${JSON.stringify(text)} is not on screen.`);
  },
);
