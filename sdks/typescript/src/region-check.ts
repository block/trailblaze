import { percent, topColorShares, type ColorShare } from "./color.js";
import type { ScreenImage } from "./screen-image.js";
import type { Bounds, TrailblazeNodeSelector } from "./generated/selectors.js";
import type { ToolContext } from "./tool-core.js";
import { ToolError } from "./tool-error.js";

/** How one attempt, or the whole check, came out. */
export const RegionOutcome = {
  Passed: "passed",
  Failed: "failed",
  CantCheck: "cant_check",
} as const;
export type RegionOutcome = (typeof RegionOutcome)[keyof typeof RegionOutcome];

/** `code` on a failed check's `ToolError` payload. */
export const RegionFailureCode = {
  Mismatch: "region_color_mismatch",
  CantCheck: "region_cant_check",
} as const;

export const RegionDefaults = {
  /** Re-read budget when a step sets no `timeoutMs`. */
  TimeoutMs: 10_000,
  /**
   * Longest side of each read, in pixels. Above `readScreenImage`'s 512 default so a full pane is
   * read at most about 2:1, which keeps one-or-two-pixel text strokes in the sample. Measured on an
   * iPad pane, a read this size decodes and scans in well under a second on the host.
   */
  MaxDimension: 1024,
  /** How many of the most common colours a result lists. */
  TopColors: 3,
} as const;

/**
 * The inputs every region check takes. A tool declares these fields in its own input type, in its own
 * file, rather than extending this: the tool-definition extractor reads only the tool's file and
 * can't follow an interface imported from the SDK. Passing the input to `checkRegion` type-checks it.
 */
export interface RegionCheckInput {
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
}

/** What a rule says about one read. */
export interface RuleVerdict {
  outcome: RegionOutcome;
  /** One line of numbers against thresholds, e.g. `97.5% of pixels within colour difference 5 of #000000 (needs ≥ 75%)`. */
  detail: string;
  /** The measured numbers, for the failure payload. */
  measured: Record<string, number>;
  /** The thresholds they were held to. */
  thresholds: Record<string, number>;
  /**
   * A "can't check" that no re-read can fix (a selector the driver can't resolve, say). The core
   * stops waiting and fails at once.
   */
  final?: boolean;
}

/** A match rule: reads one image of the region and decides. */
export type RegionRule = (image: ScreenImage) => RuleVerdict;

/** What names the check in its result, its evidence screenshot and its failure payload. */
export interface RegionCheckOptions {
  /** The calling tool's name, e.g. `assertRegionColor`. The result line and screenshot start with it. */
  toolName: string;
}

interface Attempt {
  verdict: RuleVerdict;
  bounds?: Bounds;
  image?: ScreenImage;
}

/**
 * Runs [rule] over the region [input] names until it passes or the time runs out, saves evidence,
 * and returns the result line. A tool brings only the rule (one image in, a verdict out); this does
 * the rest:
 *
 *  1. Resolves the region from a Trailblaze selector with `findSelectorMatches`. Exactly one match
 *     with a non-empty rectangle, or the check can't run.
 *  2. Reads that rectangle's pixels with `ctx.screen.image({ bounds })`. Every read is a fresh
 *     capture, and `readScreenImage` refuses a screen that is black everywhere.
 *  3. Applies the rule. On a miss, re-resolves and re-reads until it passes or `timeoutMs` runs
 *     out, so a repaint (an app back from the background) needs no sleep in the trail.
 *  4. Saves evidence on every run, pass or fail: a named screenshot, plus the bounds, the measured
 *     numbers, the thresholds and the top colours in the result message.
 *
 * "Can't check" is never a pass. Throws a `ToolError` on a failure or a "can't check", and its
 * payload's `code` ([RegionFailureCode]) says which, so a report can tell "the screen was wrong"
 * from "the screen could not be read".
 *
 * @example
 *   interface AssertMostlyBlackInput {
 *     selector: TrailblazeNodeSelector;
 *     name?: string;
 *     timeoutMs?: number;
 *   }
 *
 *   export const assertMostlyBlack = trailblaze.tool<AssertMostlyBlackInput>({ surfaceToLlm: false }, (input, ctx) =>
 *     checkRegion(ctx, input, (image) => {
 *       const share = image.count((r, g, b) => r < 16 && g < 16 && b < 16) / (image.width * image.height);
 *       return {
 *         outcome: share >= 0.9 ? RegionOutcome.Passed : RegionOutcome.Failed,
 *         detail: `${percent(share)} near black (needs ≥ 90%)`,
 *         measured: { nearBlackPercent: share * 100 },
 *         thresholds: { minNearBlackPercent: 90 },
 *       };
 *     }, { toolName: "assertMostlyBlack" }),
 *   );
 */
export async function checkRegion(
  ctx: ToolContext,
  input: RegionCheckInput,
  rule: RegionRule,
  options: RegionCheckOptions,
): Promise<string> {
  const toolName = options.toolName;
  if (input.selector === undefined || input.selector === null || typeof input.selector !== "object") {
    throw new Error(`${toolName}: \`selector\` is required.`);
  }
  // A memory reference reaches the tool as a string ("1000"), so coerce before the deadline math:
  // `Date.now() + "1000"` concatenates into a timestamp centuries away.
  const timeoutMs = Number(input.timeoutMs ?? RegionDefaults.TimeoutMs);
  if (!Number.isFinite(timeoutMs) || timeoutMs < 0) {
    throw new Error(`${toolName}: \`timeoutMs\` must be 0 or more, got ${JSON.stringify(input.timeoutMs)}.`);
  }
  const regionName = input.name?.trim() || "region";

  const started = Date.now();
  const deadline = started + timeoutMs;
  let attempts = 0;
  let last: Attempt;
  do {
    attempts++;
    last = await attempt(input.selector, ctx, rule, Math.max(1, deadline - Date.now()));
    if (last.verdict.outcome === RegionOutcome.Passed || last.verdict.final) break;
  } while (Date.now() < deadline);

  const topColors = last.image ? topColorShares(last.image, RegionDefaults.TopColors) : [];
  const evidence = [
    `${toolName} ${regionName}: ${describeOutcome(last.verdict.outcome)}. ${capitalize(last.verdict.detail)}.`,
    last.bounds
      ? `Region [${last.bounds.left},${last.bounds.top}][${last.bounds.right},${last.bounds.bottom}]` +
        (last.image ? ` read at ${last.image.width}x${last.image.height} (${last.image.lossless ? "lossless" : "lossy"}).` : ".")
      : undefined,
    topColors.length > 0
      ? `Top colours: ${topColors.map((c) => `${c.hex} ${percent(c.share)}`).join(", ")}${last.image?.lossless ? "" : " (rounded, lossy capture)"}.`
      : undefined,
  ]
    .filter((l) => l !== undefined)
    .join(" ");
  const elapsedS = ((Date.now() - started) / 1000).toFixed(1);
  // The snapshot call carries the evidence in its arguments, which the session log keeps;
  // a passing tool's return message doesn't reach the report.
  const snapshotName = `${toolName}_${regionName}`.replace(/[^A-Za-z0-9_-]+/g, "_");
  const snapshot = await saveSnapshot(ctx, snapshotName, evidence);
  const message = `${evidence} ${attempts} ${attempts === 1 ? "read" : "reads"} in ${elapsedS} s. ${snapshot}`;

  if (last.verdict.outcome === RegionOutcome.Passed) return message;
  throw new ToolError(message, {
    data: {
      code: last.verdict.outcome === RegionOutcome.Failed ? RegionFailureCode.Mismatch : RegionFailureCode.CantCheck,
      tool: toolName,
      region: regionName,
      bounds: last.bounds,
      measured: last.verdict.measured,
      thresholds: last.verdict.thresholds,
      topColors,
      attempts,
    },
  });
}

async function attempt(
  selector: TrailblazeNodeSelector,
  ctx: ToolContext,
  rule: RegionRule,
  waitMs: number,
): Promise<Attempt> {
  let matches;
  try {
    // A wait budget makes the tool poll until the element shows, so the first read waits for the
    // screen to load without a sleep. It returns at once when the element is already there.
    [matches] = await ctx.tools.findSelectorMatches({ selectors: [selector], timeoutMs: waitMs });
  } catch (e) {
    // Final: a resolver that throws (a driver with no view hierarchy, a malformed selector) won't
    // recover on a re-read, and retrying would re-issue the call back to back for the whole budget.
    return { verdict: cantCheckVerdict(`the selector could not be resolved: ${errorText(e)}`, true) };
  }
  if (matches.length === 0) return { verdict: cantCheckVerdict("no element matches the selector") };
  if (matches.length > 1) {
    return { verdict: cantCheckVerdict(`${matches.length} elements match the selector; narrow it to one`) };
  }
  const bounds = matches[0].bounds ?? undefined;
  if (!bounds) return { verdict: cantCheckVerdict("the matched element has no bounds") };
  if (bounds.right <= bounds.left || bounds.bottom <= bounds.top) {
    return { bounds, verdict: cantCheckVerdict("the matched element has an empty rectangle") };
  }

  let image: ScreenImage;
  try {
    image = await ctx.screen.image({ bounds, maxDimension: RegionDefaults.MaxDimension });
  } catch (e) {
    // Includes a screen that is black everywhere: a secure window, a screen that is off, or a
    // failed capture. Not a pass, and not the app's fault either.
    return { bounds, verdict: cantCheckVerdict(`the screen could not be read: ${errorText(e)}`) };
  }
  if (image.width === 0 || image.height === 0) return { bounds, image, verdict: cantCheckVerdict("the read came back empty") };
  return { bounds, image, verdict: rule(image) };
}

function capitalize(text: string): string {
  return text.charAt(0).toUpperCase() + text.slice(1);
}

/**
 * A "can't check" verdict with no numbers, for a rule that can't judge the image it got (too small,
 * say). [final] stops the re-reads, for a reason no re-read can fix.
 */
export function cantCheckVerdict(reason: string, final = false): RuleVerdict {
  return { outcome: RegionOutcome.CantCheck, detail: reason, measured: {}, thresholds: {}, final };
}

/**
 * The phrase `takeSnapshot` puts in its result only when it wrote a file. Its result has no
 * structured field, so this text is the contract: `TakeSnapshotTool.SNAPSHOT_SAVED_MESSAGE`, pinned by
 * `TakeSnapshotToolMessagesTest`.
 */
const SNAPSHOT_SAVED_MARKER = " saved as ";

async function saveSnapshot(ctx: ToolContext, screenName: string, description: string): Promise<string> {
  // `takeSnapshot` is a framework tool every driver that reads pixels has, but it isn't in the
  // SDK's typed tool map, so dispatch it by name.
  const tools = ctx.tools as unknown as Record<string, (args: Record<string, unknown>) => Promise<unknown>>;
  try {
    // `takeSnapshot` succeeds without saving a file when the driver has no screenshot (a
    // hierarchy-only provider), so only its "saved as" answer counts as saved.
    const result = await tools.takeSnapshot({ screenName, description });
    const text = typeof result === "string" ? result.trim() : "";
    if (text.includes(SNAPSHOT_SAVED_MARKER)) return `Snapshot: ${screenName}.`;
    return `Snapshot not saved: ${text || "the driver saved no screenshot."}`;
  } catch (e) {
    return `Snapshot not saved: ${errorText(e)}.`;
  }
}

function describeOutcome(outcome: RegionOutcome): string {
  switch (outcome) {
    case RegionOutcome.Passed:
      return "passed";
    case RegionOutcome.Failed:
      return "failed";
    case RegionOutcome.CantCheck:
      return "can't check";
  }
}

function errorText(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}
