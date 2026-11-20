// Unit tests for `assertRegionColor`. They drive the typed tool with the SDK's mock client, its `findSelectorMatches`
// and `readScreenImage` answering from images drawn in the test, so they pin the verdicts, the
// "can't check" cases, the re-read loop and the evidence without a device. Run via `bun test`.

import { describe, expect, test } from "bun:test";
import { createMockClient, createMockContext } from "@trailblaze/scripting/testing";
import { RegionFailureCode, type Bounds, type MatchDescriptor } from "@trailblaze/scripting";

import { assertRegionColor } from "./assertRegionColor";

type Color = readonly [number, number, number];
const BLACK: Color = [0, 0, 0];
const WHITE: Color = [255, 255, 255];
const NEAR_BLACK: Color = [28, 28, 30];
const SELECTED_ROW: Color = [42, 42, 42];

const PANE: Bounds = { left: 0, top: 0, right: 200, bottom: 100 };
const SELECTOR = { iosMaestro: { resourceIdRegex: "^appearance_pane$" } };

/** A drawn screen region. `text()` adds thin strokes, about 1.6% of the area, like a settings list. */
class Canvas {
  readonly data: Uint8Array;
  constructor(
    readonly width: number,
    readonly height: number,
    background: Color,
    readonly lossless = true,
  ) {
    this.data = new Uint8Array(width * height * 4);
    this.fill({ left: 0, top: 0, right: width, bottom: height }, background);
  }
  fill(box: Bounds, [r, g, b]: Color): this {
    for (let y = box.top; y < box.bottom; y++) {
      for (let x = box.left; x < box.right; x++) this.data.set([r, g, b, 255], (y * this.width + x) * 4);
    }
    return this;
  }
  text(color: Color): this {
    for (let y = 10; y < this.height - 10; y += 20) this.fill({ left: 20, top: y, right: 60, bottom: y + 2 }, color);
    return this;
  }
  read() {
    return {
      width: this.width,
      height: this.height,
      data: Buffer.from(this.data).toString("base64"),
      lossless: this.lossless,
      pixelRect: { left: 0, top: 0, right: this.width, bottom: this.height },
      source: { encoding: this.lossless ? "png" : "webp", width: this.width, height: this.height, scaleX: 1, scaleY: 1 },
    };
  }
}

type Read = Canvas | Error;

/** What `takeSnapshot` answers when it wrote a file. */
const SNAPSHOT_SAVED = "Snapshot 'region' saved as snapshot.webp.";

/**
 * The SDK's mock client with the selector resolving to [matches] and the reads returning [reads] in
 * order (the last one repeats). It records every call, so tests can see the reads, the waits and the
 * snapshot.
 */
function fakeClient(reads: Read[], matches: MatchDescriptor[] = [{ indexPath: [0], bounds: PANE }]) {
  const client = createMockClient();
  client.stub("findSelectorMatches", { textContent: "", structuredContent: [matches] });
  if (reads.length > 0) {
    client.stubSequence(
      "readScreenImage",
      reads.map((read) =>
        read instanceof Error ? { textContent: "", errorMessage: read.message } : { textContent: "", structuredContent: read.read() },
      ),
    );
  }
  client.stub("takeSnapshot", { textContent: SNAPSHOT_SAVED });
  return client;
}

const ctx = () => createMockContext({ platform: "ios", device: { driverType: "ios-host" } });
const reads = (client: ReturnType<typeof fakeClient>) => client.calls.filter((c) => c.tool === "readScreenImage").length;
const darkPane = () => new Canvas(200, 100, BLACK).fill({ left: 0, top: 40, right: 200, bottom: 60 }, SELECTED_ROW).text(WHITE);
const lightPane = () => new Canvas(200, 100, WHITE).text(NEAR_BLACK);
/** The check a dark-theme trail writes: the app's dark background covers most of the pane. */
const DARK_BG = { color: "#000000" } as const;

async function failureOf(run: Promise<unknown>): Promise<{ message: string; data: Record<string, unknown> }> {
  try {
    await run;
  } catch (e) {
    return { message: (e as Error).message, data: (e as { data?: Record<string, unknown> }).data ?? {} };
  }
  throw new Error("expected the check to fail");
}

describe("assertRegionColor", () => {
  test("passes when enough of the region is close enough to the colour", async () => {
    const result = await assertRegionColor({ selector: SELECTOR, color: "#000000" }, ctx(), fakeClient([darkPane()]));
    expect(result).toMatch(/assertRegionColor region: passed\. \d+\.\d% of pixels within colour difference 3 of #000000 \(needs ≥ 60\.0%\)/);
  });

  test("a near colour counts inside maxColorDifference and not outside it", async () => {
    const nearBlack = new Canvas(200, 100, [8, 8, 8]); // colour difference about 1.3 from black
    await expect(
      assertRegionColor({ selector: SELECTOR, color: "#000000", maxColorDifference: 3 }, ctx(), fakeClient([nearBlack])),
    ).resolves.toContain("passed");
    const { data } = await failureOf(
      assertRegionColor({ selector: SELECTOR, color: "#000000", maxColorDifference: 1, timeoutMs: 0 }, ctx(), fakeClient([nearBlack])),
    );
    expect(data.code).toBe(RegionFailureCode.Mismatch);
  });

  test("fails with the colours it found", async () => {
    const { message, data } = await failureOf(
      assertRegionColor({ selector: SELECTOR, name: "main pane", color: "#000000", timeoutMs: 0 }, ctx(), fakeClient([lightPane()])),
    );
    expect(message).toContain("assertRegionColor main pane: failed. 0.0% of pixels within colour difference 3 of #000000");
    expect(message).toContain("Top colours: #FFFFFF 98.4%, #1C1C1E 1.6%.");
    expect(data.measured).toEqual({ matchPercent: 0 });
    expect(data.thresholds).toEqual({ maxColorDifference: 3, minMatchPercent: 60 });
  });

  test("passes on a dark pane, says what it measured, and names the evidence after the region", async () => {
    const client = fakeClient([darkPane()]);
    const result = await assertRegionColor({ ...DARK_BG, selector: SELECTOR, name: "main pane" }, ctx(), client);

    expect(result).toContain("assertRegionColor main pane: passed. 78.8% of pixels within colour difference 3 of #000000 (needs ≥ 60.0%)");
    expect(result).toContain("Region [0,0][200,100] read at 200x100 (lossless).");
    expect(result).toContain("Top colours: #000000");
    const snapshot = client.calls.find((c) => c.tool === "takeSnapshot")?.args;
    expect(snapshot?.screenName).toBe("assertRegionColor_main_pane");
    expect(snapshot?.description).toContain("assertRegionColor main pane: passed.");
  });

  test("reads the region's bounds at more than the default 512 px", async () => {
    const client = fakeClient([darkPane()]);
    await assertRegionColor({ ...DARK_BG, selector: SELECTOR }, ctx(), client);

    const read = client.calls.find((c) => c.tool === "readScreenImage")!;
    expect(read.args.bounds).toEqual(PANE);
    expect(read.args.maxDimension as number).toBeGreaterThan(512);
  });

  test("fails on a light rendering, with a mismatch code and evidence", async () => {
    const client = fakeClient([lightPane()]);
    const { data } = await failureOf(assertRegionColor({ ...DARK_BG, selector: SELECTOR, timeoutMs: 0 }, ctx(), client));
    expect(data.code).toBe(RegionFailureCode.Mismatch);
    expect(data.bounds).toEqual(PANE);
    expect(client.calls.some((c) => c.tool === "takeSnapshot")).toBe(true);
  });

  test("re-reads until the app repaints, with no sleep in the trail", async () => {
    const client = fakeClient([lightPane(), lightPane(), darkPane()]);
    const result = await assertRegionColor({ ...DARK_BG, selector: SELECTOR, timeoutMs: 30_000 }, ctx(), client);

    expect(result).toContain("passed");
    expect(result).toContain("3 reads in");
    // Each read re-resolves the selector, so a region that moved is followed.
    expect(client.calls.filter((c) => c.tool === "findSelectorMatches")).toHaveLength(3);
  });

  test("a lossy capture is measured with the caller's maxColorDifference, and the evidence says it was lossy", async () => {
    // Compression nudges the background off black; a maxColorDifference the caller chose for lossy reads absorbs it.
    const lossy = new Canvas(200, 100, [6, 5, 8], false).text(WHITE);
    const client = fakeClient([lossy]);
    const result = await assertRegionColor({ ...DARK_BG, selector: SELECTOR, maxColorDifference: 5 }, ctx(), client);
    expect(result).toContain("passed");
    expect(result).toContain("(lossy).");
    expect(result).toContain("(rounded, lossy capture)");
    expect(reads(client)).toBe(1);
  });

  describe("maxMatchPercent: the colour is absent", () => {
    const CYAN: Color = [0, 236, 237];
    const withSticker = () => new Canvas(200, 100, WHITE).fill({ left: 80, top: 40, right: 120, bottom: 50 }, CYAN);
    const ABSENT = { selector: SELECTOR, color: "#00ECED", maxMatchPercent: 0 } as const;

    test("passes when no pixel matches, with no floor", async () => {
      const result = await assertRegionColor(ABSENT, ctx(), fakeClient([lightPane()]));
      expect(result).toContain("passed. 0.0% of pixels within colour difference 3 of #00ECED (needs ≤ 0.0%)");
    });

    test("fails when the colour is still there, with the ceiling in the payload", async () => {
      const { message, data } = await failureOf(assertRegionColor({ ...ABSENT, timeoutMs: 0 }, ctx(), fakeClient([withSticker()])));
      expect(message).toContain("failed. 2.0% of pixels within colour difference 3 of #00ECED (needs ≤ 0.0%)");
      expect(data.code).toBe(RegionFailureCode.Mismatch);
      expect(data.thresholds).toEqual({ maxColorDifference: 3, maxMatchPercent: 0 });
    });

    test("re-reads until the colour is gone", async () => {
      const result = await assertRegionColor({ ...ABSENT, timeoutMs: 30_000 }, ctx(), fakeClient([withSticker(), lightPane()]));
      expect(result).toContain("passed");
      expect(result).toContain("2 reads in");
    });

    test("with a floor too, both bounds apply", async () => {
      const band = { selector: SELECTOR, color: "#00ECED", minMatchPercent: 1, maxMatchPercent: 3, timeoutMs: 0 };
      await expect(assertRegionColor(band, ctx(), fakeClient([withSticker()]))).resolves.toContain("(needs ≥ 1.0% and ≤ 3.0%)");
      const { data } = await failureOf(assertRegionColor({ ...band, maxMatchPercent: 1.5 }, ctx(), fakeClient([withSticker()])));
      expect(data.code).toBe(RegionFailureCode.Mismatch);
    });

    test("a share exactly on a bound passes despite float rounding, and a zero ceiling stays exact", async () => {
      // 1400 of 20000 pixels is 7.000000000000001% in floating point.
      const seven = new Canvas(200, 100, WHITE).fill({ left: 0, top: 0, right: 140, bottom: 10 }, CYAN);
      const exact = { selector: SELECTOR, color: "#00ECED", minMatchPercent: 7, maxMatchPercent: 7, timeoutMs: 0 };
      await expect(assertRegionColor(exact, ctx(), fakeClient([seven]))).resolves.toContain("passed");
      const onePixel = new Canvas(200, 100, WHITE).fill({ left: 5, top: 5, right: 6, bottom: 6 }, CYAN);
      const { message, data } = await failureOf(assertRegionColor({ ...ABSENT, timeoutMs: 0 }, ctx(), fakeClient([onePixel])));
      expect(data.code).toBe(RegionFailureCode.Mismatch);
      expect(message).toContain("0.01% of pixels within colour difference 3 of #00ECED (needs ≤ 0.0%), 1 of 20000 pixels match");
    });

    test("refuses a ceiling outside 0 to 100, or below the floor", async () => {
      await expect(assertRegionColor({ ...ABSENT, maxMatchPercent: -1 }, ctx(), fakeClient([]))).rejects.toThrow(
        "`maxMatchPercent` must be 0 to 100",
      );
      await expect(assertRegionColor({ ...ABSENT, maxMatchPercent: "none" as never }, ctx(), fakeClient([]))).rejects.toThrow(
        "`maxMatchPercent` must be 0 to 100",
      );
      await expect(
        assertRegionColor({ ...ABSENT, minMatchPercent: 10, maxMatchPercent: 5 }, ctx(), fakeClient([])),
      ).rejects.toThrow("is above `maxMatchPercent` (5), so nothing can pass");
    });
  });

  test("rejects a colour that isn't #RRGGBB", async () => {
    await expect(assertRegionColor({ selector: SELECTOR, color: "black" }, ctx(), fakeClient([]))).rejects.toThrow(
      "`color` must be #RRGGBB",
    );
  });
});

describe("can't check is never a pass", () => {
  const cases: Array<[string, MatchDescriptor[], Read[], string]> = [
    ["no element matches", [], [darkPane()], "no element matches the selector"],
    [
      "several elements match",
      [
        { indexPath: [0], bounds: PANE },
        { indexPath: [1], bounds: PANE },
      ],
      [darkPane()],
      "2 elements match the selector; narrow it to one",
    ],
    ["the element has no bounds", [{ indexPath: [0], bounds: null }], [darkPane()], "the matched element has no bounds"],
    [
      "the element's rectangle is empty",
      [{ indexPath: [0], bounds: { left: 10, top: 10, right: 10, bottom: 50 } }],
      [darkPane()],
      "the matched element has an empty rectangle",
    ],
    [
      "the whole screen is black",
      [{ indexPath: [0], bounds: PANE }],
      [new Error("readScreenImage: the whole screenshot is black, so there are no pixels to check.")],
      "the screen could not be read",
    ],
  ];

  for (const [label, matches, screen, reason] of cases) {
    test(label, async () => {
      const client = fakeClient(screen, matches);
      const { message, data } = await failureOf(assertRegionColor({ ...DARK_BG, selector: SELECTOR, timeoutMs: 0 }, ctx(), client));
      expect(message).toContain(`can't check. ${reason.charAt(0).toUpperCase()}${reason.slice(1)}`);
      expect(data.code).toBe(RegionFailureCode.CantCheck);
      expect(client.calls.some((c) => c.tool === "takeSnapshot")).toBe(true);
    });
  }

  test("a selector that can't be resolved", async () => {
    const client = fakeClient([darkPane()]);
    client.stub("findSelectorMatches", { textContent: "", errorMessage: "no view hierarchy from this driver" });
    const { message, data } = await failureOf(assertRegionColor({ ...DARK_BG, selector: SELECTOR }, ctx(), client));
    expect(message).toContain("The selector could not be resolved:");
    expect(message).toContain("no view hierarchy from this driver");
    expect(message).toContain("1 read in");
    expect(data.code).toBe(RegionFailureCode.CantCheck);
  });

  test("a snapshot the driver couldn't take a screenshot for isn't reported as saved", async () => {
    const client = fakeClient([darkPane()]);
    client.stub("takeSnapshot", { textContent: "Snapshot 'assertRegionColor_region' captured (no screenshot available)." });
    const result = await assertRegionColor({ ...DARK_BG, selector: SELECTOR }, ctx(), client);
    expect(result).toContain("passed");
    expect(result).toContain("Snapshot not saved: Snapshot 'assertRegionColor_region' captured (no screenshot available).");
  });

  test("a timeout from a memory reference arrives as a string and still bounds the wait", async () => {
    const client = fakeClient([lightPane()]);
    const started = Date.now();
    const { message } = await failureOf(assertRegionColor({ ...DARK_BG, selector: SELECTOR, timeoutMs: "0" as never }, ctx(), client));
    expect(message).toContain("1 read in");
    expect(Date.now() - started).toBeLessThan(2_000);
  });

  test("a maxColorDifference and minMatchPercent from memory references arrive as strings and still apply", async () => {
    const nearBlack = new Canvas(200, 100, [8, 8, 8]); // colour difference about 1.3 from black
    const result = await assertRegionColor(
      { ...DARK_BG, selector: SELECTOR, maxColorDifference: "3" as never, minMatchPercent: "90" as never },
      ctx(),
      fakeClient([nearBlack]),
    );
    expect(result).toContain("within colour difference 3 of #000000 (needs ≥ 90.0%)");
    await expect(
      assertRegionColor({ ...DARK_BG, selector: SELECTOR, maxColorDifference: "near" as never }, ctx(), fakeClient([nearBlack])),
    ).rejects.toThrow("`maxColorDifference` must be 0 or more");
    await expect(
      assertRegionColor({ ...DARK_BG, selector: SELECTOR, minMatchPercent: "most" as never }, ctx(), fakeClient([nearBlack])),
    ).rejects.toThrow("`minMatchPercent` must be above 0 and at most 100");
  });

  test("a timeout that isn't a number is refused", async () => {
    await expect(
      assertRegionColor({ ...DARK_BG, selector: SELECTOR, timeoutMs: "soon" as never }, ctx(), fakeClient([darkPane()])),
    ).rejects.toThrow("`timeoutMs` must be 0 or more");
  });

  test("a snapshot that can't be saved doesn't change the verdict", async () => {
    const client = fakeClient([darkPane()]);
    client.stub("takeSnapshot", { textContent: "", errorMessage: "no screen state provider" });
    const result = await assertRegionColor({ ...DARK_BG, selector: SELECTOR }, ctx(), client);
    expect(result).toContain("passed");
    expect(result).toContain("Snapshot not saved:");
    expect(result).toContain("no screen state provider");
  });
});
