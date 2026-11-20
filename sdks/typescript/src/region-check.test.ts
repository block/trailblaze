// `checkRegion` with a rule of the test's own, the way another trailmap's tool would use it. The
// mock client answers `findSelectorMatches` and `readScreenImage` from images drawn in the test.
// `assertRegionColor`'s own tests cover the "can't check" cases and the evidence line in detail.

import { describe, expect, test } from "bun:test";

import type { Bounds } from "./generated/selectors.js";
import {
  cantCheckVerdict,
  checkRegion,
  RegionFailureCode,
  RegionOutcome,
  type RegionCheckInput,
  type RegionRule,
} from "./region-check.js";
import { createMockClient, createMockContext } from "./testing.js";
import { tool } from "./tool.js";

const PANE: Bounds = { left: 0, top: 0, right: 4, bottom: 1 };
const SELECTOR = { iosMaestro: { resourceIdRegex: "^pane$" } };

/** A `readScreenImage` answer: one row of [shade] grey pixels, 4 wide. */
function greyRow(shade: number) {
  const data = new Uint8Array(16);
  for (let i = 0; i < 16; i += 4) data.set([shade, shade, shade, 255], i);
  return {
    textContent: "",
    structuredContent: {
      width: 4,
      height: 1,
      data: Buffer.from(data).toString("base64"),
      lossless: true,
      pixelRect: { left: 0, top: 0, right: 4, bottom: 1 },
      source: { encoding: "png", width: 4, height: 1, scaleX: 1, scaleY: 1 },
    },
  };
}

function client(...shades: number[]) {
  const c = createMockClient();
  c.stub("findSelectorMatches", { textContent: "", structuredContent: [[{ indexPath: [0], bounds: PANE }]] });
  c.stubSequence("readScreenImage", shades.map(greyRow));
  c.stub("takeSnapshot", { textContent: "Snapshot 'pane' saved as snapshot.png." });
  return c;
}

/** A rule of another team's: the region's first pixel is at most [max] grey. */
const darkerThan =
  (max: number): RegionRule =>
  (image) => {
    const shade = image.pixel(0, 0).r;
    return {
      outcome: shade <= max ? RegionOutcome.Passed : RegionOutcome.Failed,
      detail: `shade ${shade} (needs ≤ ${max})`,
      measured: { shade },
      thresholds: { maxShade: max },
    };
  };

const assertDarkerThan = (max: number, rule: RegionRule = darkerThan(max)) =>
  tool<RegionCheckInput>((input, ctx) => checkRegion(ctx, input, rule, { toolName: "assertDarkerThan" }));

const ctx = () => createMockContext({ platform: "ios" });

async function failureOf(run: Promise<unknown>): Promise<{ message: string; data: Record<string, unknown> }> {
  try {
    await run;
  } catch (e) {
    return { message: (e as Error).message, data: (e as { data?: Record<string, unknown> }).data ?? {} };
  }
  throw new Error("expected the check to fail");
}

describe("checkRegion with a tool's own rule", () => {
  test("passes with the rule's detail, named after the tool and the region, and saves evidence", async () => {
    const c = client(20);
    const result = await assertDarkerThan(50)({ selector: SELECTOR, name: "pane" }, ctx(), c);
    expect(result).toContain("assertDarkerThan pane: passed. Shade 20 (needs ≤ 50).");
    expect(result).toContain("Region [0,0][4,1] read at 4x1 (lossless).");
    expect(c.calls.find((call) => call.tool === "takeSnapshot")?.args.screenName).toBe("assertDarkerThan_pane");
  });

  test("a miss fails with the rule's numbers in the payload", async () => {
    const { message, data } = await failureOf(assertDarkerThan(50)({ selector: SELECTOR, timeoutMs: 0 }, ctx(), client(200)));
    expect(message).toContain("assertDarkerThan region: failed. Shade 200 (needs ≤ 50).");
    expect(data).toMatchObject({
      code: RegionFailureCode.Mismatch,
      tool: "assertDarkerThan",
      measured: { shade: 200 },
      thresholds: { maxShade: 50 },
    });
  });

  test("re-reads until the rule passes", async () => {
    const c = client(200, 200, 20);
    await expect(assertDarkerThan(50)({ selector: SELECTOR }, ctx(), c)).resolves.toContain("passed");
    expect(c.calls.filter((call) => call.tool === "readScreenImage")).toHaveLength(3);
  });

  test("a rule's final can't-check stops the re-reads and never passes", async () => {
    const c = client(20);
    const tooSmall: RegionRule = () => cantCheckVerdict("the region is too small to judge", true);
    const { message, data } = await failureOf(assertDarkerThan(50, tooSmall)({ selector: SELECTOR }, ctx(), c));
    expect(message).toContain("can't check. The region is too small to judge.");
    expect(data.code).toBe(RegionFailureCode.CantCheck);
    expect(c.calls.filter((call) => call.tool === "readScreenImage")).toHaveLength(1);
  });
});
