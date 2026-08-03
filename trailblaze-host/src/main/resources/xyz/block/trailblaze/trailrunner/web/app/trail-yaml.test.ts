// Contract tests for the Trail Runner web YAML producers (app/trail-yaml.js). The browser feeds
// these js-yaml (a UMD global); here we shim `window.jsyaml` with Bun's built-in YAML so the tests
// run with no npm dependency, then PARSE each producer's output and assert on the resulting object.
// We assert the observable contract — the produced document is the UNIFIED map shape (`config:` +
// `trail:`), never the legacy v1 list — not the exact string formatting.
import { describe, expect, test } from "bun:test";

// Bun.YAML.stringify/parse stand in for js-yaml's dump/load. dump's options arg is ignored (the
// producers pass lineWidth/noRefs, which only affect formatting; we assert on the parsed object).
// `window.TM` is the real trail-model.js (loaded before trail-yaml.js in the browser) — the
// normalizers reference it to read the unified single-file shape.
import TM from "./screens/trail-model.js";
(globalThis as any).window = {
  TM,
  jsyaml: {
    dump: (o: unknown) => (Bun as any).YAML.stringify(o),
    load: (s: string) => (Bun as any).YAML.parse(s),
  },
};

import TY from "./trail-yaml.js";
const parse = (s: string) => (Bun as any).YAML.parse(s);

describe("buildRecordedTrailYaml", () => {
  const steps = [
    { text: "Open settings", yaml: "- tools:\n  - tapOn: { text: Settings }" },
    { text: "", label: "pressBack", yaml: "- tools:\n  - pressBack: {}" },
    { text: "Confirm the title shows", verify: true, yaml: "" },
  ];

  test("emits the unified map shape, never a v1 list", () => {
    const doc = parse(TY.buildRecordedTrailYaml("My trail", "sample", "Android", steps));
    expect(Array.isArray(doc)).toBe(false);
    expect(doc.config.title).toBe("My trail");
    expect(doc.config.target).toBe("sample");
    expect(Array.isArray(doc.trail)).toBe(true);
  });

  test("keys per-step recordings by the lowercased platform classifier", () => {
    const doc = parse(TY.buildRecordedTrailYaml("t", "sample", "Android", steps));
    expect(doc.trail[0].step).toBe("Open settings");
    expect(doc.trail[0].recording.android).toEqual([{ tapOn: { text: "Settings" } }]);
  });

  test("a tools-only step (no NL text) synthesizes its intent from the label", () => {
    const doc = parse(TY.buildRecordedTrailYaml("t", "sample", "android", steps));
    expect(doc.trail[1].step).toBe("pressBack");
    expect(doc.trail[1].recording.android).toEqual([{ pressBack: {} }]);
  });

  test("a verify step keeps its verb and carries no recording when it has no tools", () => {
    const doc = parse(TY.buildRecordedTrailYaml("t", "sample", "android", steps));
    expect(doc.trail[2].verify).toBe("Confirm the title shows");
    expect(doc.trail[2].recording).toBeUndefined();
  });

  test("a recording with no producible steps stays a unified map with an explicit empty trail", () => {
    // Degenerate case: an empty step, or all-tools-only steps with a blank platform classifier.
    // Must emit `trail: []` (an array), not a bare config-only doc — the web model classifies a
    // missing/null trail as legacy, but an empty array as unified.
    const doc = parse(TY.buildRecordedTrailYaml("t", "sample", "android", [{ text: "", label: "", yaml: "" }]));
    expect(Array.isArray(doc)).toBe(false);
    expect(doc.trail).toEqual([]);
  });
});

describe("buildPromptTrailYaml", () => {
  test("prepend steps are recording-keyed by classifier; objective is a plain step", () => {
    const yaml = TY.buildPromptTrailYaml("t", "sample", "ANDROID", "Buy a coffee", [
      { label: "Clear app data", tool: "clearAppData", args: { appId: "com.example" } },
    ]);
    const doc = parse(yaml);
    expect(Array.isArray(doc)).toBe(false);
    expect(doc.trail[0].step).toBe("Clear app data");
    expect(doc.trail[0].recording.android).toEqual([{ clearAppData: { appId: "com.example" } }]);
    expect(doc.trail[1].step).toBe("Buy a coffee");
    expect(doc.trail[1].recording).toBeUndefined();
  });
});

describe("buildRunnableToolYaml / buildTrailheadRunYaml", () => {
  test("buildRunnableToolYaml wraps a step's tools in a single unified step", () => {
    const doc = parse(TY.buildRunnableToolYaml("tap", "- tools:\n  - tapOn: { text: OK }", "iOS"));
    expect(doc.config.title).toBe("Run: tap");
    expect(doc.trail[0].recording.ios).toEqual([{ tapOn: { text: "OK" } }]);
  });

  test("returns null with a blank platform classifier (nothing to key the recording by)", () => {
    expect(TY.buildRunnableToolYaml("tap", "- tools:\n  - tapOn: {}", "")).toBeNull();
    expect(TY.buildTrailheadRunYaml("th", null, { appId: "x" }, "")).toBeNull();
  });

  test("buildToolListRunYaml wraps a raw tool list in a single unified step keyed by classifier", () => {
    // The Trailmaps "Run" tab and Trail Detail cell runner hold the tool list directly. The produced
    // doc is the UNIFIED map shape (config + trail with per-classifier recording) the run-tool
    // endpoint decodes — never the legacy v1 list, which the endpoint no longer parses.
    const doc = parse(TY.buildToolListRunYaml("Charge", [{ tapOn: { text: "OK" } }], "android-phone"));
    expect(Array.isArray(doc)).toBe(false);
    expect(doc.config.title).toBe("Run: Charge");
    expect(doc.trail[0].recording["android-phone"]).toEqual([{ tapOn: { text: "OK" } }]);
  });

  test("buildToolListRunYaml returns null with no tools or a blank classifier", () => {
    expect(TY.buildToolListRunYaml("x", [], "android")).toBeNull();
    expect(TY.buildToolListRunYaml("x", [{ tapOn: {} }], "")).toBeNull();
  });

  test("buildTrailheadRunYaml keys the launch tool by classifier and expands dot-path args", () => {
    const doc = parse(TY.buildTrailheadRunYaml("signedIn", null, { "creds.email": "a@b.com" }, "android"));
    expect(doc.trail[0].recording.android).toEqual([{ signedIn: { creds: { email: "a@b.com" } } }]);
  });
});

describe("mergeBlazeYaml always writes the unified map shape", () => {
  const fields = { title: "New", target: "sample", platform: "android", objective: "Do it", steps: [{ kind: "do", text: "tap" }] };

  test("from an existing v1 LIST input", () => {
    const v1 = "- config:\n    title: Old\n    priority: high\n- prompts:\n  - text: whatever";
    const doc = parse(TY.mergeBlazeYaml(v1, fields));
    expect(Array.isArray(doc)).toBe(false);
    expect(doc.config.title).toBe("New");
    expect(doc.config.priority).toBe("high"); // unmodeled config preserved
    expect(doc.config.metadata.objective).toBe("Do it");
    expect(doc.trail).toEqual([{ step: "tap" }]);
  });

  test("from an existing unified MAP input, preserving trailhead + unmodeled config", () => {
    const unified = "config:\n  title: Old\n  driver: foo\ntrailhead:\n  - launch: {}\ntrail:\n  - step: x";
    const doc = parse(TY.mergeBlazeYaml(unified, fields));
    expect(Array.isArray(doc)).toBe(false);
    expect(doc.config.driver).toBe("foo");
    expect(doc.trailhead).toEqual([{ launch: {} }]);
    expect(doc.trail).toEqual([{ step: "tap" }]);
  });
});

// UnifiedTrailConfig has no `platform` field (it's a v1-only key); platform is carried by the
// classifier keys under each step's `recording:`. The producers must NOT emit `config.platform`.
describe("no config.platform in the unified output", () => {
  test("buildRecordedTrailYaml / buildBlazeYaml / buildPromptTrailYaml omit platform", () => {
    const rec = parse(TY.buildRecordedTrailYaml("t", "sample", "Android", [{ text: "x", yaml: "- tools:\n  - tapOn: {}" }]));
    expect(rec.config.platform).toBeUndefined();
    expect(rec.trail[0].recording.android).toBeDefined(); // platform still carried by the classifier
    expect(parse(TY.buildBlazeYaml("t", "sample", "android", "obj", [{ kind: "do", text: "x" }], {})).config.platform).toBeUndefined();
    expect(parse(TY.buildPromptTrailYaml("t", "sample", "android", "obj", [])).config.platform).toBeUndefined();
  });

  test("mergeBlazeYaml strips a platform an existing v1 file carried", () => {
    const v1 = "- config:\n    title: Old\n    platform: android\n- prompts:\n  - text: y";
    const doc = parse(TY.mergeBlazeYaml(v1, { title: "New", target: "sample", platform: "android", steps: [] }));
    expect(doc.config.platform).toBeUndefined();
  });
});

describe("normalizeTrailDoc reads both shapes into { config, prompts, trailhead, toolsItems }", () => {
  test("unified map: trail steps → prompts; per-classifier recording flattened to recording.tools", () => {
    const doc = parse(
      "config:\n  title: T\ntrail:\n  - step: Open\n    recording:\n      android:\n        - tapOn: { text: Settings }\n  - verify: Shows",
    );
    const n = TY.normalizeTrailDoc(doc);
    expect(n.config.title).toBe("T");
    expect(n.prompts[0].step).toBe("Open");
    expect(n.prompts[0].recording.tools).toEqual([{ tapOn: { text: "Settings" } }]);
    expect(n.prompts[1].verify).toBe("Shows");
    expect(n.prompts[1].recording).toBeUndefined();
  });

  test("legacy v1 list: config + prompts + trailhead + tools items preserved", () => {
    const doc = parse(
      "- config:\n    title: T\n- trailhead:\n  - launch: {}\n- tools:\n  - clearState: {}\n- prompts:\n  - step: Do\n    recording:\n      tools:\n        - tapOn: {}",
    );
    const n = TY.normalizeTrailDoc(doc);
    expect(n.config.title).toBe("T");
    expect(n.trailhead).toEqual([{ launch: {} }]);
    expect(n.toolsItems).toEqual([[{ clearState: {} }]]);
    expect(n.prompts[0].recording.tools).toEqual([{ tapOn: {} }]);
  });

  test("config-only unified doc (no trail/trailhead) reads config with empty prompts", () => {
    const n = TY.normalizeTrailDoc(parse("config:\n  title: T\n  target: sample"));
    expect(n.config.target).toBe("sample");
    expect(n.prompts).toEqual([]);
  });
});

describe("applyRecordingEdit patches a step's recording in the file's own shape", () => {
  test("unified map: sets trail[i].recording[classifier], preserving config + other steps", () => {
    const doc = parse("config:\n  title: T\ntrail:\n  - step: A\n  - step: B");
    const r = TY.applyRecordingEdit(doc, 0, [{ tapOn: { text: "OK" } }], { kind: "do", text: "A" }, "android");
    expect(r.value.config.title).toBe("T");
    expect(r.value.trail[0].recording).toEqual({ android: [{ tapOn: { text: "OK" } }] });
    expect(r.value.trail[1].recording).toBeUndefined();
  });

  test("unified map: empty tools removes the classifier (and the recording when it empties)", () => {
    const doc = parse("config:\n  title: T\ntrail:\n  - step: A\n    recording:\n      android:\n        - tapOn: {}");
    const r = TY.applyRecordingEdit(doc, 0, [], { kind: "do", text: "A" }, "android");
    expect(r.value.trail[0].recording).toBeUndefined();
  });

  test("unified map: promptIndex null appends a new recorded step", () => {
    const doc = parse("config:\n  title: T\ntrail: []");
    const r = TY.applyRecordingEdit(doc, null, [{ pressBack: {} }], { kind: "do", text: "Back" }, "ios");
    expect(r.value.trail).toEqual([{ step: "Back", recording: { ios: [{ pressBack: {} }] } }]);
    expect(TY.applyRecordingEdit(parse("config: {}\ntrail: []"), null, [], null, "ios").noop).toBe(true);
  });

  test("v1 list: keeps the historical recording.tools edit", () => {
    const doc = parse("- config:\n    title: T\n- prompts:\n  - step: A");
    const r = TY.applyRecordingEdit(doc, 0, [{ tapOn: {} }], { kind: "do", text: "A" }, "android");
    const pItem = r.value.find((it: any) => it && Array.isArray(it.prompts));
    expect(pItem.prompts[0].recording).toEqual({ tools: [{ tapOn: {} }] });
  });
});
