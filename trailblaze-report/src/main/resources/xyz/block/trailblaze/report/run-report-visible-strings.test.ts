// The shared reader every report surface takes the Strings tab's data through: the strings the
// logging rule recorded on each capture log.
//
// Run: `bun test run-report-visible-strings.test.ts` from this directory.
import { describe, expect, test } from "bun:test";
import { captureIdFrom, extractVisibleStrings, looksVolatile, visibleStringsShotFiles } from "./run-report-visible-strings";

const pay = { text: "Pay", source: "text", ref: "a12", bounds: [24, 180, 320, 224], visible: true };
const capture = (screenshotFile: string, timestamp: string, extra: Record<string, unknown> = {}) => ({
  class: "xyz.block.trailblaze.logs.client.TrailblazeLog.AgentDriverLog",
  screenshotFile, timestamp, deviceWidth: 402, deviceHeight: 874, visibleStrings: [pay], ...extra,
});

describe("extractVisibleStrings", () => {
  test("one screen per capture log, in capture order, keyed by its screenshot and keeping each string's box", () => {
    const screens = extractVisibleStrings([
      capture("b.png", "2026-09-25T09:21:39Z", { llmRequestLabel: "Tap Pay" }),
      { class: "Status", timestamp: "2026-09-25T09:21:36Z" },
      capture("a.png", "2026-09-25T09:21:37Z", { action: { type: "LaunchApp" }, captureCoverage: { looksTruncated: true } }),
    ]);
    expect(screens).toEqual([
      {
        captureId: "a.png", screenshot: "a.png", timestamp: "2026-09-25T09:21:37Z", action: "LaunchApp", partialCapture: true,
        deviceWidth: 402, deviceHeight: 874, strings: [pay],
      },
      { captureId: "b.png", screenshot: "b.png", timestamp: "2026-09-25T09:21:39Z", action: "Tap Pay", deviceWidth: 402, deviceHeight: 874, strings: [pay] },
    ]);
  });

  test("a driver log and the LLM request on the same screenshot are one screen, the first speaking for it", () => {
    const screens = extractVisibleStrings([
      capture("a.png", "2026-09-25T09:21:37Z", { action: { type: "TapPoint" } }),
      capture("a.png", "2026-09-25T09:21:38Z", { llmRequestLabel: "later", visibleStrings: [] }),
    ])!;
    expect(screens.map((s) => [s.captureId, s.action, s.strings.length])).toEqual([["a.png", "TapPoint", 1]]);
  });

  test("a farm run's signed URL keys the screen by its image name and keeps the URL to load it", () => {
    const url = "https://files.example/sessions/run/s_17.png?X-Sig=abc";
    expect(extractVisibleStrings([capture(url, "2026-09-25T09:21:37Z")])![0]).toMatchObject({ captureId: "s_17.png", screenshot: "s_17.png", captureUrl: url });
  });

  test("a capture with no screenshot is a screen named by the id its log was stamped with", () => {
    const screens = extractVisibleStrings([
      capture("", "2026-09-25T09:21:37Z", { screenshotFile: null, captureId: "capture-1-0000abcd" }),
      capture("a.png", "2026-09-25T09:21:38Z"),
      // Recorded before ids existed: nothing names it, so it sits out.
      capture("", "2026-09-25T09:21:39Z", { screenshotFile: null }),
    ])!;
    expect(screens.map((s) => [s.captureId, s.screenshot ?? null, s.captureUrl ?? null])).toEqual([
      ["capture-1-0000abcd", null, null],
      ["a.png", "a.png", null],
    ]);
  });

  test("a string's visibility survives, and a malformed string is dropped without the rest", () => {
    const screens = extractVisibleStrings([capture("a.png", "2026-09-25T09:21:37Z", {
      visibleStrings: [{ text: "Terms", source: "text", visible: false }, { text: 7 }, null, { text: "Box", bounds: [1, 2, 3] }],
    })])!;
    expect(screens[0].strings).toEqual([
      { text: "Terms", source: "text", visible: false },
      { text: "Box", source: "text", visible: true },
    ]);
  });

  test("logs recorded before the field existed yield null, like a session with no captures", () => {
    expect(extractVisibleStrings([{ screenshotFile: "a.png", timestamp: "2026-09-25T09:21:37Z" }])).toBeNull();
    expect(extractVisibleStrings(null)).toBeNull();
  });
});

describe("captureIdFrom", () => {
  test("the image name in a path, a URL's path, or a URL's query; the reference whole otherwise", () => {
    expect(captureIdFrom("s_1.png")).toBe("s_1.png");
    expect(captureIdFrom("/tmp/logs/run/s_1.webp")).toBe("s_1.webp");
    expect(captureIdFrom("https://h/get?key=sessions%2Frun%2Fs_2.png&sig=x")).toBe("s_2.png");
    expect(captureIdFrom("not-an-image")).toBe("not-an-image");
  });
});

describe("looksVolatile", () => {
  // The same cases as the Kotlin VolatileText tests, so the two rules stay in step.
  test("amounts and clocks change between runs; counted nouns and localized dates are copy", () => {
    expect(["$1,204.55", "9:41", "9:41 PM", "9:41 a.m.", "12/25/2026"].every(looksVolatile)).toBe(true);
    expect(["Total due", "2 items", "5 de enero de 2026", "Pedido n.º 4"].some(looksVolatile)).toBe(false);
  });
});

describe("capture times", () => {
  test("a capture stamped on the device clock is moved onto the host clock the recording uses", () => {
    // The device runs 3 s behind the host: a tool it logged at :10 (1 s long) reached the host at :14.
    const tool = { class: "xyz.block.trailblaze.logs.client.TrailblazeLog.TrailblazeToolLog", toolName: "tap", clock: "device", timestamp: "2026-09-25T09:21:10Z", durationMs: 1000, hostReceivedAt: "2026-09-25T09:21:14Z" };
    const [screen] = extractVisibleStrings([tool, capture("a.png", "2026-09-25T09:21:20Z", { clock: "device" })])!;
    expect(screen.timestamp).toBe("2026-09-25T09:21:23.000Z");
    // A capture already on the host clock is left where it is.
    const [host] = extractVisibleStrings([tool, capture("b.png", "2026-09-25T09:21:20Z", { clock: "host" })])!;
    expect(host.timestamp).toBe("2026-09-25T09:21:20Z");
  });
});

describe("visibleStringsShotFiles", () => {
  test("names each screenshot a screen with strings was captured on, once, and never a path", () => {
    const screen = (screenshot: string | null, n: number) => ({ captureId: screenshot || "capture-9-00000000", screenshot, deviceWidth: 1, deviceHeight: 1, strings: Array(n).fill({ text: "x", source: "text", visible: true }) });
    expect(visibleStringsShotFiles([screen("a.png", 1), screen("a.png", 1), screen("empty.png", 0), screen("../up.png", 1), screen(null, 1), screen("b.webp", 2)]))
      .toEqual(["a.png", "b.webp"]);
  });

  test("a line from before the screenshot field names its image by its capture id", () => {
    expect(visibleStringsShotFiles([{ captureId: "old.png", deviceWidth: 1, deviceHeight: 1, strings: [{ text: "x", source: "text", visible: true }] }]))
      .toEqual(["old.png"]);
  });

  test("a farm capture never downloaded is named by its URL, and a GIF capture counts", () => {
    const url = "https://artifacts.example.com/a?key=run%2Fscreens%2Ffarm.png";
    expect(visibleStringsShotFiles([
      { captureId: "farm.png", screenshot: "farm.png", captureUrl: url, deviceWidth: 1, deviceHeight: 1, strings: [{ text: "x", source: "text", visible: true }] },
      { captureId: "c.gif", screenshot: "c.gif", deviceWidth: 1, deviceHeight: 1, strings: [{ text: "y", source: "text", visible: true }] },
      // A relative path is not a URL, so the bare name stands.
      { captureId: "d.png", screenshot: "d.png", captureUrl: "screens/d.png", deviceWidth: 1, deviceHeight: 1, strings: [{ text: "z", source: "text", visible: true }] },
    ])).toEqual([url, "c.gif", "d.png"]);
  });
});
