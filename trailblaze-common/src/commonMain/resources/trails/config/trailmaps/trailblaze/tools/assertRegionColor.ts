// `assertRegionColor`: the colour primitive. Asserts what percentage of one element's on-screen
// pixels are close to a hex colour. A percentage of matching pixels, not an average colour: an
// average blends the background with its text and icons, so a light pane of dark text and a dark
// pane of light text can both average to grey. The selector, re-reads and evidence come from the
// SDK's `checkRegion`; the colour maths from its colour helpers.
//
// Every threshold is the caller's. A theme check is a colour check with the app's own background:
// `color` is what the app paints, `maxColorDifference` how far a pixel may drift and still count
// (raise it on a driver whose captures are lossy), and `minMatchPercent` how much of the region must
// be that colour. `maxMatchPercent` flips it: how much of the region may be that colour at most, so
// "the sticker is gone" is the same check as "the sticker is there".
//
// `surfaceToLlm: false`: a recorded-trail check, like `runIf`. It ships in the always-enabled
// `meta` toolset so a recording can dispatch it by name on every target.

import { checkRegion, parseHex, trailblaze, type TrailblazeNodeSelector } from "@trailblaze/scripting";
import { colorRule, RegionColorDefaults } from "../lib/regionColor";

// The region fields are spelled out rather than `extends RegionCheckInput`: the tool-definition
// extractor reads only this file, and can't follow an interface imported from the SDK. Passing
// `input` to `checkRegion` type-checks them against its `RegionCheckInput`.
interface AssertRegionColorInput {
  /**
   * The element whose on-screen rectangle is checked: a Trailblaze selector, the same object
   * `assertVisibleBySelector` takes. Pick the pane's container, not a label inside it. It must
   * match exactly one element; none or several fails the step as "can't check".
   */
  selector: TrailblazeNodeSelector;
  /** Short name for the region in the result and the evidence screenshot, e.g. `settings_sidebar`. */
  name?: string;
  /** Keep re-reading the screen until the check passes or this many milliseconds pass. Default 10000. */
  timeoutMs?: number;
  /** The expected colour, `#RRGGBB`. */
  color: string;
  /**
   * How different a pixel's colour may be from `color` and still count as a match, on the CIEDE2000
   * colour-difference scale where 0 is identical. About 2 is the smallest difference a person
   * notices; 3 (the default) looks like the same colour; about 6 also absorbs the drift in a
   * compressed screenshot; black against a dark grey like `#2A2A2A` is about 17, and black against
   * white is 100.
   */
  maxColorDifference?: number;
  /**
   * Percentage of the region's pixels, above 0 and up to 100, that must match `color`. Default 60,
   * or no floor when `maxMatchPercent` is set.
   */
  minMatchPercent?: number;
  /**
   * Percentage of the region's pixels, 0 to 100, that may match `color` at most: an absence check.
   * 0 means none of the pixels read. A region is read at most 1024 pixels on its longer side, so
   * a mark thinner than that sampling step can fall between samples. No ceiling by default.
   */
  maxMatchPercent?: number;
}

/**
 * Fails unless at least `minMatchPercent`, and at most `maxMatchPercent`, of the element's on-screen
 * pixels are within `maxColorDifference` of `color`. Re-reads until it passes or `timeoutMs` runs out. Fails as
 * "can't check" when the selector matches no element or several, or the screen can't be read.
 * Saves a named screenshot and the measured percentage either way.
 */
export const assertRegionColor = trailblaze.tool<AssertRegionColorInput>(
  { surfaceToLlm: false },
  async (input, ctx) => {
    const target = parseHex(input.color);
    if (target === null) throw new Error(`assertRegionColor: \`color\` must be #RRGGBB, got ${JSON.stringify(input.color)}.`);
    // A memory reference (`maxColorDifference: ${dark_diff}`) arrives as a string, so coerce, as
    // `timeoutMs` does.
    const maxColorDifference = Number(input.maxColorDifference ?? RegionColorDefaults.MaxColorDifference);
    if (!Number.isFinite(maxColorDifference) || maxColorDifference < 0) {
      throw new Error(
        `assertRegionColor: \`maxColorDifference\` must be 0 or more, got ${JSON.stringify(input.maxColorDifference)}.`,
      );
    }
    // No floor when only a ceiling is set, so `maxMatchPercent: 0` alone means "none of this colour".
    const hasMax = input.maxMatchPercent !== undefined && input.maxMatchPercent !== null;
    const hasMin = input.minMatchPercent !== undefined && input.minMatchPercent !== null;
    const minMatchPercent = hasMin || !hasMax ? Number(input.minMatchPercent ?? RegionColorDefaults.MinMatchPercent) : undefined;
    if (minMatchPercent !== undefined && (!Number.isFinite(minMatchPercent) || !(minMatchPercent > 0 && minMatchPercent <= 100))) {
      throw new Error(
        `assertRegionColor: \`minMatchPercent\` must be above 0 and at most 100, got ${JSON.stringify(input.minMatchPercent)}.`,
      );
    }
    const maxMatchPercent = hasMax ? Number(input.maxMatchPercent) : undefined;
    if (maxMatchPercent !== undefined && (!Number.isFinite(maxMatchPercent) || !(maxMatchPercent >= 0 && maxMatchPercent <= 100))) {
      throw new Error(
        `assertRegionColor: \`maxMatchPercent\` must be 0 to 100, got ${JSON.stringify(input.maxMatchPercent)}.`,
      );
    }
    if (minMatchPercent !== undefined && maxMatchPercent !== undefined && minMatchPercent > maxMatchPercent) {
      throw new Error(
        `assertRegionColor: \`minMatchPercent\` (${minMatchPercent}) is above \`maxMatchPercent\` (${maxMatchPercent}), so nothing can pass.`,
      );
    }
    return checkRegion(ctx, input, colorRule(target, maxColorDifference, { minMatchPercent, maxMatchPercent }), {
      toolName: "assertRegionColor",
    });
  },
);
