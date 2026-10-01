// The Strings tab's model and markup (run-report-strings.ts): every string once, each tied back to
// the screenshots it appeared on and its box there.
//
// Run: `bun test app/run-report-strings.test.ts` from the web/ directory.
import { describe, expect, test } from "bun:test";
import { buildStringsModel, carryStringsSelection, DEFAULT_STRING_KINDS, DEFAULT_STRING_VISIBILITY, resolveStringsSelection, stillCaptureIds, stringCrops, stringsTabHtml, type StringsViewState } from "./run-report-strings";

type Entry = VisibleStringEntry;
// Bounds are corners: [left, top, right, bottom].
const str = (text: string, extra: Partial<Entry> = {}): Entry => ({ text, source: "text", visible: true, bounds: [10, 20, 40, 60], ...extra });
const screen = (n: number, strings: Entry[], extra: Partial<VisibleStringsScreen> = {}): VisibleStringsScreen => ({
  captureId: `run_${n}.png`, screenshot: `run_${n}.png`, deviceWidth: 100, deviceHeight: 200, strings, ...extra,
});
const esc = (s: unknown) => String(s == null ? "" : s).replace(/[<>&"]/g, (c) => ({ "<": "&lt;", ">": "&gt;", "&": "&amp;", '"': "&quot;" }[c]!));
const state = (over: Partial<StringsViewState> = {}): StringsViewState => ({
  mode: "string", sel: null, screen: 0, hit: 0, kinds: { ...DEFAULT_STRING_KINDS }, vis: DEFAULT_STRING_VISIBILITY, ...over,
});

describe("buildStringsModel", () => {
  test("a capture showing exactly what an earlier one did folds into that screen", () => {
    const model = buildStringsModel([
      screen(0, [str("Send")]),
      screen(1, [str("Send")]),
      screen(2, [str("Sent")]),
      // Same text, moved: a different screen.
      screen(3, [str("Send", { bounds: [0, 0, 50, 20] })]),
      screen(4, [str("Send")]),
    ], {});
    expect(model.captures).toBe(5);
    expect(model.screens.map((s) => s.captures)).toEqual([[1, 2, 5], [3], [4]]);
    expect(model.entries.map((e) => [e.text, e.hits.map((h) => h.screen)])).toEqual([["Send", [0, 2]], ["Sent", [1]]]);
  });

  test("one string on several screens is one entry with a hit per screen, each keeping its own box", () => {
    const model = buildStringsModel([
      screen(0, [str("Home", { bounds: [0, 180, 50, 200] })]),
      screen(1, [str("Home", { bounds: [0, 170, 50, 190] })]),
    ], {});
    expect(model.entries).toHaveLength(1);
    expect(model.entries[0].hits.map((h) => h.bounds)).toEqual([[0, 180, 50, 200], [0, 170, 50, 190]]);
  });

  test("sorts strings into copy, changing and accessibility-only, judging clocks when read", () => {
    const model = buildStringsModel([
      screen(0, [
        str("Pay"),
        str("9:21 AM"),
        str("Resend in 55 seconds"),
        str("button", { source: "roleDescription" }),
        str("Settings", { source: "contentDescription" }),
        str("Selected", { source: "state" }),
        str("Email", { source: "hint" }),
      ]),
      screen(1, [str("Resend in 54 seconds")]),
    ], {});
    const kinds = Object.fromEntries(model.entries.map((e) => [e.text, e.kind]));
    expect(kinds).toEqual({
      Pay: "copy",
      "9:21 AM": "changing",
      // Texts that differ only in their digits are one countdown, not two strings.
      "Resend in 55 seconds": "changing",
      button: "a11y",
      // An icon's label and a spoken state are never drawn; an empty field's hint is.
      Settings: "a11y",
      Selected: "a11y",
      Email: "copy",
    });
    expect(model.entries.find((e) => e.text === "Resend in 55 seconds")!.variants).toEqual(["Resend in 55 seconds", "Resend in 54 seconds"]);
  });

  test("finds each screen's screenshot by file name or by the capture URL's base name, and its Timeline step", () => {
    const model = buildStringsModel([
      screen(0, [str("A")]),
      screen(1, [str("B")], { screenshot: "other", captureUrl: "https://host/files/run_1.png" }),
      screen(2, [str("C")]),
    ], { "run_0.png": "data:x", "sub/run_1.png": "data:y" }, { "run_0.png": { step: 4, kid: 1 } });
    expect(model.screens.map((s) => s.shot)).toEqual(["run_0.png", "sub/run_1.png", null]);
    expect(model.screens[0].at).toEqual({ step: 4, kid: 1 });
    expect(model.screens[2].at).toBeNull();
  });

  test("numbered labels shown side by side stay two strings, while one ticking across screens folds", () => {
    const model = buildStringsModel([
      screen(0, [str("Table 1"), str("Table 2", { bounds: [50, 20, 90, 60] })]),
      screen(1, [str("Table 2", { bounds: [50, 20, 90, 60] }), str("Table 1"), str("Table 3", { bounds: [0, 100, 40, 140] })]),
      screen(2, [str("Table 4", { bounds: [0, 100, 40, 140] })]),
    ], {});
    expect(model.entries.map((e) => [e.variants, e.kind, e.hits.map((h) => h.screen)])).toEqual([
      [["Table 1"], "copy", [0, 1]],
      [["Table 2"], "copy", [0, 1]],
      [["Table 3", "Table 4"], "changing", [1, 2]],
    ]);
  });
});

describe("carryStringsSelection", () => {
  test("a live update that inserts an earlier capture keeps the same string, screen and place selected", () => {
    const before = buildStringsModel([screen(1, [str("Pay")]), screen(2, [str("Paid"), str("Pay", { bounds: [0, 0, 5, 5] })])], {});
    const after = buildStringsModel([screen(0, [str("Home")]), screen(1, [str("Pay")]), screen(2, [str("Paid"), str("Pay", { bounds: [0, 0, 5, 5] })])], {});
    const pay = before.entries.find((e) => e.text === "Pay")!.id;
    const carried = carryStringsSelection(before, after, state({ sel: pay, screen: 1, hit: 1 }));
    expect(after.entries[carried.sel!].text).toBe("Pay");
    expect(after.screens[carried.screen].captureId).toBe("run_2.png");
    expect(after.screens[after.entries[carried.sel!].hits[carried.hit].screen].captureId).toBe("run_2.png");
  });
});

describe("resolveStringsSelection", () => {
  const model = buildStringsModel([
    screen(0, [str("Pay"), str("button", { source: "roleDescription" })]),
    screen(1, [str("Paid"), str("Pay")]),
  ], {});

  test("selects the first shown string when nothing is selected, and moves off one a chip hides", () => {
    expect(resolveStringsSelection(model, state()).sel).toBe(0);
    const a11y = model.entries.find((e) => e.kind === "a11y")!.id;
    expect(resolveStringsSelection(model, state({ sel: a11y })).sel).toBe(0);
    expect(resolveStringsSelection(model, state({ sel: a11y, kinds: { ...DEFAULT_STRING_KINDS, a11y: true } })).sel).toBe(a11y);
  });

  test("by screen, a selected string's hit follows the screen in view", () => {
    const pay = model.entries.find((e) => e.text === "Pay")!.id;
    expect(resolveStringsSelection(model, state({ mode: "screen", screen: 1, sel: pay })).hit).toBe(1);
  });
});

describe("visibility filter", () => {
  const model = buildStringsModel([
    screen(0, [str("Pay"), str("Terms", { visible: false })]),
    screen(1, [str("Terms"), str("Pay", { visible: false, bounds: [0, 190, 30, 200] })]),
  ], { "run_0.png": "data:a", "run_1.png": "data:b" });
  const html = (over: Partial<StringsViewState>) => stringsTabHtml(model, resolveStringsSelection(model, state(over)), "", (k) => `data:${k}`, esc);
  const rowTexts = (h: string) => [...h.matchAll(/class="strtext">([^<]*)</g)].map((m) => m[1]);

  test("shows only what was on screen by default, and offers each choice with its count", () => {
    const h = html({});
    expect(rowTexts(h)).toEqual(["Pay", "Terms"]);
    // Each string was visible on one screen, so one thumbnail each: the off-screen place is left out.
    expect(h.match(/data-str-hit=/g)).toHaveLength(1);
    expect(h).toContain('data-str-vis="visible" aria-pressed="true" title="On screen when captured">Visible <span class="c">2</span>');
    expect(h).toContain('data-str-vis="hidden" aria-pressed="false"');
    expect(h).not.toContain("strbox k-copy off");
  });

  test("not visible lists the places a string was in the tree but off screen, drawn dashed", () => {
    const pay = model.entries.find((e) => e.text === "Pay")!.id;
    const h = html({ vis: "hidden", sel: pay });
    expect(h.match(/data-str-hit=/g)).toHaveLength(1);
    expect(h).toContain("Screen 2");
    expect(h).toContain('class="strbox sel off" title="Pay"');
    expect(h).toContain("No — in the view tree but not on screen");
  });

  test("all shows every place", () => {
    const pay = model.entries.find((e) => e.text === "Pay")!.id;
    expect(html({ vis: "all", sel: pay }).match(/data-str-hit=/g)).toHaveLength(2);
  });

  test("a string never on screen is not listed until the filter asks for it", () => {
    const tucked = buildStringsModel([screen(0, [str("Pay"), str("Footer", { visible: false })])], {});
    const ids = (vis: StringsViewState["vis"]) => [...stringsTabHtml(tucked, resolveStringsSelection(tucked, state({ vis })), "", () => "", esc).matchAll(/class="strtext">([^<]*)</g)].map((m) => m[1]);
    expect(ids("visible")).toEqual(["Pay"]);
    expect(ids("hidden")).toEqual(["Footer"]);
    expect(ids("all")).toEqual(["Pay", "Footer"]);
  });
});

describe("stringsTabHtml", () => {
  const model = buildStringsModel([
    screen(0, [str("Pay", { bounds: [10, 20, 40, 60] }), str("Cancel", { bounds: [50, 100, 75, 110] })]),
    screen(1, [str("Pay", { bounds: [0, 0, 100, 50] })]),
  ], { "run_0.png": "data:image/png;base64,AAAA" }, { "run_0.png": { step: 7, kid: null } });
  const shotSrc = (key: string) => (key === "run_0.png" ? "data:image/png;base64,AAAA" : "");

  test("draws the selected string's box from its corners, scaled to the capture's device size", () => {
    const html = stringsTabHtml(model, resolveStringsSelection(model, state({ sel: 0 })), "", shotSrc, esc);
    expect(html).toContain('src="data:image/png;base64,AAAA"');
    expect(html).toContain('class="strbox sel" title="Pay" style="left:10.000%;top:10.000%;width:30.000%;height:20.000%"');
    // The rest of the screen's strings are there too, to hover and pick, from the keyboard as well.
    expect(html).toContain('<button type="button" class="strbox k-copy" data-str-id="1" title="Cancel" aria-label="Select “Cancel”" style="left:50.000%;top:50.000%;width:25.000%;height:5.000%"></button>');
    expect(html).toContain("(10, 20) to (40, 60), 30×40 of 100×200");
    expect(html).toContain('data-lightbox-step="7"');
    // One thumbnail per screen the string appeared on.
    expect(html.match(/data-str-hit=/g)).toHaveLength(2);
  });

  test("a capture with no screenshot still lists its strings and draws their boxes, and says it has none", () => {
    const bare = buildStringsModel([screen(0, [str("Signed in")], { captureId: "capture-5-0000abcd", screenshot: null })], {}, {});
    const html = stringsTabHtml(bare, resolveStringsSelection(bare, state({ sel: 0 })), "", () => "data:never", esc);
    expect(html).toContain('class="strtext">Signed in<');
    expect(html).toContain("No screenshot for this capture");
    expect(html).toContain("<tr><td>Screenshot</td><td>None</td></tr>");
    expect(html).toContain('class="strbox sel" title="Signed in"');
    expect(html).not.toContain("<img");
  });

  test("a capture time with sub-millisecond digits is read even where Date.parse refuses them", () => {
    const realParse = Date.parse;
    Date.parse = (v: string) => (/\.\d{4,}/.test(v) ? NaN : realParse(v)); // as some engines do
    try {
      const bare = buildStringsModel([screen(0, [str("Signed in")], { captureId: "c1", screenshot: null, timestamp: "2026-09-28T10:00:05.123456789Z" })], {}, {});
      expect(bare.screens[0].atMs).toBe(realParse("2026-09-28T10:00:05.123Z"));
      expect(bare.screens[0].frameTimes.map((f) => f.atMs)).toEqual([realParse("2026-09-28T10:00:05.123Z")]);
    } finally {
      Date.parse = realParse;
    }
  });

  test("a screen seen more than once without a screenshot keeps every look's time, so any of them can give its frame", () => {
    // The recording may cover only a later look; the screen still has to find it.
    const model = buildStringsModel([
      screen(0, [str("Signed in")], { captureId: "early", screenshot: null, timestamp: "2026-09-28T10:00:01Z" }),
      screen(1, [str("Signed in")], { captureId: "late", screenshot: null, timestamp: "2026-09-28T10:00:09Z" }),
    ], {}, { early: { step: 2, kid: null }, late: { step: 7, kid: 0 } });

    expect(model.screens).toHaveLength(1);
    expect(model.screens[0].frameTimes).toEqual([
      { captureId: "early", atMs: Date.parse("2026-09-28T10:00:01Z"), at: { step: 2, kid: null } },
      { captureId: "late", atMs: Date.parse("2026-09-28T10:00:09Z"), at: { step: 7, kid: 0 } },
    ]);
  });

  test("a screen folded from several screenshot-less captures can take its saved frame from any of them", () => {
    // Only the later look may have had a frame saved; the screen still has to find it.
    const model = buildStringsModel([
      screen(0, [str("Signed in")], { captureId: "early", screenshot: null, timestamp: "2026-09-28T10:00:01Z" }),
      screen(1, [str("Signed in")], { captureId: "late", screenshot: null, timestamp: "2026-09-28T10:00:09Z" }),
      screen(2, [str("Signed in")], { captureId: "late", screenshot: null, timestamp: "2026-09-28T10:00:10Z" }),
    ], {}, {});

    expect(stillCaptureIds(model.screens[0])).toEqual(["early", "late"]);
  });

  test("a screen with a screenshot takes no saved frame", () => {
    const model = buildStringsModel([screen(0, [str("Signed in")])], { "run_0.png": "data:image/png;x" }, {});
    expect(model.screens[0].shot).toBe("run_0.png");
    expect(stillCaptureIds(model.screens[0])).toEqual([]);
  });

  test("a capture with no screenshot shows the recording's frame at its time, labelled as one", () => {
    const bare = buildStringsModel([screen(0, [str("Signed in")], { captureId: "capture-5-0000abcd", screenshot: null, timestamp: "2026-09-28T10:00:05Z" })], {}, {});
    expect(bare.screens[0].atMs).toBe(Date.parse("2026-09-28T10:00:05Z"));
    const asked: string[] = [];
    const frameFor = (s: { captureId: string | null }) => { asked.push(String(s.captureId)); return "data:image/jpeg;frame"; };
    const html = stringsTabHtml(bare, resolveStringsSelection(bare, state({ sel: 0 })), "", () => "data:never", esc, frameFor);
    expect(asked).toContain("capture-5-0000abcd");
    expect(html).toContain('<img alt="Screen 1" src="data:image/jpeg;frame" /><span class="strfromvideo">From video</span>');
    expect(html).toContain("<tr><td>Screenshot</td><td>None — this is a frame from the recording</td></tr>");
    // The string is cut out of the frame, like it would be out of a screenshot.
    expect(html).toContain('.strsh-0{background-image:url("data:image/jpeg;frame")}');
    expect(html).toContain('class="strcrop strsh-0"');
  });

  test("while a capture's frame is being taken the tab says so, and a screenshot is never swapped for one", () => {
    const bare = buildStringsModel([screen(0, [str("Signed in")], { captureId: "capture-5-0000abcd", screenshot: null })], {}, {});
    const html = stringsTabHtml(bare, resolveStringsSelection(bare, state({ sel: 0 })), "", () => "data:never", esc, () => undefined);
    expect(html).toContain("Taking this frame from the recording…");
    expect(html).toContain('<span class="strnoshot">Loading frame</span>');
    expect(html).not.toContain("<img");

    const shot = buildStringsModel([screen(0, [str("Pay")])], { "run_0.png": 1 }, {});
    const withShot = stringsTabHtml(shot, resolveStringsSelection(shot, state({ sel: 0 })), "", (k) => `data:${k}`, esc, () => "data:image/jpeg;frame");
    expect(withShot).toContain('src="data:run_0.png"');
    expect(withShot).not.toContain("frame");
  });

  test("a screen seen first without a screenshot shows, and crops from, the one a later look at it took, not a frame", () => {
    const m = buildStringsModel([
      screen(0, [str("Pay")], { captureId: "capture-5-0000abcd", screenshot: null }),
      screen(1, [str("Pay")]),
    ], { "run_1.png": 1 }, {});
    expect(m.screens.length).toBe(1);
    expect(m.screens[0].captures).toEqual([1, 2]);
    const html = stringsTabHtml(m, resolveStringsSelection(m, state({ sel: 0 })), "", (k) => `data:${k}`, esc, () => "data:image/jpeg;frame");
    expect(html).toContain('src="data:run_1.png"');
    expect(html).not.toContain("From video");
    expect(html).toContain('.strsh-0{background-image:url("data:run_1.png")}');
    expect(html).toContain('class="strcrop strsh-0"');
  });

  test("a screen whose screenshot is not in the report still shows the string's box, and says so", () => {
    const html = stringsTabHtml(model, resolveStringsSelection(model, state({ sel: 0, hit: 1 })), "", shotSrc, esc);
    expect(html).toContain("Screenshot run_1.png is not in this report");
    expect(html).toContain('class="strbox sel" title="Pay" style="left:0.000%;top:0.000%;width:100.000%;height:25.000%"');
    expect(html).not.toContain("data-lightbox-step");
  });

  test("each row shows its string cut out of the screenshot, which the page carries once per screen", () => {
    const html = stringsTabHtml(model, resolveStringsSelection(model, state({ sel: 0 })), "", shotSrc, esc);
    // Pay's 30×40 box scaled to the 24px-tall column; Cancel's 25×10 box to the 120×24 column's height.
    expect(html).toContain('class="strcrop strsh-0" aria-hidden="true" style="width:18.0px;height:24.0px;background-size:60.0px 120.0px;background-position:-6.0px -12.0px"');
    expect(html).toContain('class="strcrop strsh-0" aria-hidden="true" style="width:60.0px;height:24.0px;background-size:240.0px 480.0px;background-position:-120.0px -240.0px"');
    expect(html).not.toMatch(/class="strcrop[^>]*base64/);
    expect(html).toContain('<style>.strsh-0{background-image:url("data:image/png;base64,AAAA")}</style>');
  });

  test("no crop column without a screenshot to cut from, and a source that could break out of its rule is dropped", () => {
    expect(stringsTabHtml(model, resolveStringsSelection(model, state({ sel: 0 })), "", () => "", esc)).not.toContain("strcrop");
    const html = stringsTabHtml(model, resolveStringsSelection(model, state({ sel: 0 })), "", () => 'x")}body{display:none', esc);
    expect(html).not.toContain("<style>");
    expect(html).not.toContain("strcrop");
  });

  test("a crop comes only from a place the string was on screen", () => {
    const tucked = buildStringsModel([
      screen(0, [str("Footer", { visible: false }), str("Pay", { visible: false, bounds: [0, 0, 100, 50] })]),
      screen(1, [str("Pay", { bounds: [10, 20, 40, 60] })]),
    ], { "run_0.png": "data:image/png;base64,AAAA", "run_1.png": "data:image/png;base64,BBBB" });
    const bothShots = (key: string) => (key ? "data:image/png;base64,AAAA" : "");
    const crops = (vis: StringsViewState["vis"]) => stringsTabHtml(tucked, resolveStringsSelection(tucked, state({ vis })), "", bothShots, esc).match(/class="strcrop strsh-(\d)"/g);
    expect(crops("hidden")).toBeNull();
    expect(crops("all")).toEqual(['class="strcrop strsh-1"']);
  });

  test("By screen crops only from the selected screen, even where the string is on screen elsewhere", () => {
    const tucked = buildStringsModel([
      screen(0, [str("Pay", { visible: false, bounds: [0, 0, 100, 50] })]),
      screen(1, [str("Pay", { bounds: [10, 20, 40, 60] })]),
    ], { "run_0.png": 1, "run_1.png": 1 });
    const shots = () => "data:image/png;base64,AAAA";
    const cropsOn = (screenId: number) => stringsTabHtml(tucked, resolveStringsSelection(tucked, state({ mode: "screen", screen: screenId, vis: "all" })), "", shots, esc).match(/class="strcrop strsh-(\d)"/g);
    expect(cropsOn(0)).toBeNull();
    expect(cropsOn(1)).toEqual(['class="strcrop strsh-1"']);
  });

  test("a full-width label keeps the row's height and is cut at the column's width, from its start", () => {
    const wide = buildStringsModel([{ captureId: "run_0.png", deviceWidth: 1080, deviceHeight: 2340, strings: [str("Choose a payment method", { bounds: [0, 600, 1080, 660] })] }], { "run_0.png": 1 });
    const html = stringsTabHtml(wide, resolveStringsSelection(wide, state({ sel: 0 })), "", () => "data:image/png;base64,AAAA", esc);
    // 60px tall → scaled by 24/60, so the 1080px width would be 432px; the column shows its first 120.
    expect(html).toContain('class="strcrop strsh-0" aria-hidden="true" style="width:120.0px;height:24.0px;background-size:432.0px 936.0px;background-position:0.0px -240.0px"');
  });

  test("a page that mounts the crops' stylesheet itself gets rows that use it, and no copy in the markup", () => {
    const crops = stringCrops(model, shotSrc);
    expect(crops.css).toBe('.strsh-0{background-image:url("data:image/png;base64,AAAA")}');
    const view = resolveStringsSelection(model, state({ sel: 0 }));
    const html = stringsTabHtml(model, view, "", shotSrc, esc, () => null, { ...crops, mounted: true });
    expect(html).not.toContain("<style>");
    expect(html).toContain('class="strcrop strsh-0"');
    // The crops come from what was worked out ahead, not from the screenshots passed alongside.
    expect(stringsTabHtml(model, view, "", shotSrc, esc, () => null, { sources: new Map(), css: "", mounted: true })).not.toContain("strcrop");
  });

  test("escapes string text wherever it lands", () => {
    const hostile = buildStringsModel([screen(0, [str('<img src=x onerror="1">')])], {});
    const html = stringsTabHtml(hostile, resolveStringsSelection(hostile, state()), '"><b>', () => "", esc);
    expect(html).not.toContain("<img src=x");
    expect(html).not.toContain('"><b>');
  });

  test("a run with no strings says so", () => {
    expect(stringsTabHtml(buildStringsModel([], {}), state(), "", shotSrc, esc)).toContain("This run recorded no strings.");
  });
});
