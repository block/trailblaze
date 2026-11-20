// The colour rule behind `assertRegionColor`: what percentage of a region's pixels are within a
// CIEDE2000 colour difference of one colour. The caller owns every threshold. A lossy (compressed)
// read is measured like any other: compression moves colours a little, and the caller's
// `maxColorDifference` absorbs it. The evidence line says when a read was lossy.

import { colorCounts, deltaE2000, percent, RegionOutcome, rgbHex, rgbToLab, type RegionRule, type Rgb } from "@trailblaze/scripting";

export const RegionColorDefaults = {
  /** CIEDE2000 colour difference that still counts as the same colour. About 2 is the smallest a person notices. */
  MaxColorDifference: 3,
  /** Percentage of the region that must be the colour, when no `maxMatchPercent` is set either. */
  MinMatchPercent: 60,
} as const;

/** Float slack on a percentage bound, far below one pixel of any screen. */
const PercentEpsilon = 1e-9;

/** The share of a region that may match: a floor, a ceiling, or both. Percentages, 0 to 100. */
export interface MatchBand {
  minMatchPercent?: number;
  maxMatchPercent?: number;
}

/**
 * Percentage of pixels within [maxColorDifference] of [target], held to [band]. A floor says the
 * colour is there ("the pane is dark"); a ceiling says it is not ("the red banner is gone").
 */
export function colorRule(target: Rgb, maxColorDifference: number, band: MatchBand): RegionRule {
  const targetLab = rgbToLab(target);
  const hex = rgbHex(target);
  const { minMatchPercent, maxMatchPercent } = band;
  const needs = [
    minMatchPercent !== undefined ? `≥ ${percent(minMatchPercent / 100)}` : undefined,
    maxMatchPercent !== undefined ? `≤ ${percent(maxMatchPercent / 100)}` : undefined,
  ]
    .filter((n) => n !== undefined)
    .join(" and ");
  const thresholds: Record<string, number> = { maxColorDifference };
  if (minMatchPercent !== undefined) thresholds.minMatchPercent = minMatchPercent;
  if (maxMatchPercent !== undefined) thresholds.maxMatchPercent = maxMatchPercent;
  return (image) => {
    // One distance per distinct colour, not per pixel: a screen has far fewer colours than pixels.
    let within = 0;
    for (const [rgb, n] of colorCounts(image)) {
      if (deltaE2000(rgbToLab(rgb), targetLab) <= maxColorDifference) within += n;
    }
    const total = image.width * image.height;
    const matchPercent = (within / total) * 100;
    // A share exactly on a bound passes: 1400 of 20000 pixels is 7.000000000000001%, not above 7.
    // A zero ceiling stays exact, so one matching pixel fails it.
    const passed =
      (minMatchPercent === undefined || matchPercent >= minMatchPercent - PercentEpsilon) &&
      (maxMatchPercent === undefined ||
        (maxMatchPercent === 0 ? within === 0 : matchPercent <= maxMatchPercent + PercentEpsilon));
    // A few stray pixels round to 0.0%, so name the count whenever any matched.
    const count = within > 0 ? `, ${within} of ${total} pixels match` : "";
    return {
      outcome: passed ? RegionOutcome.Passed : RegionOutcome.Failed,
      detail: `${percent(matchPercent / 100)} of pixels within colour difference ${maxColorDifference} of ${hex} (needs ${needs})${count}`,
      measured: { matchPercent },
      thresholds,
    };
  };
}
