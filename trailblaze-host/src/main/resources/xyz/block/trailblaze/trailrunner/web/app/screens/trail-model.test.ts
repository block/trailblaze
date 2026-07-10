// Pure-logic tests for the unified trail step-matrix model (app/screens/trail-model.js). No browser,
// no DOM, no yaml lib for the core cases (the model is parsed-object in / parsed-object out). The E2E
// block at the end lowers REAL on-disk unified trail files (parsed with Bun's built-in YAML) and
// asserts a full-fidelity round-trip — proving the model holds against production data, not fixtures.
//
// Run: `bun test app/screens/trail-model.test.ts` from the web/ directory.
import { describe, expect, test } from "bun:test";
import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
// trail-model.js dual-exports via module.exports; bun interops the CJS default import.
import TM from "./trail-model.js";

describe("unifiedDocToMatrix", () => {
  test("lowers a config/trailhead/trail mapping to the matrix shape", () => {
    const doc = {
      config: { title: "Login", devices: { "android-phone": "ANDROID_ONDEVICE_ACCESSIBILITY", "ios-iphone": "IOS_HOST" } },
      trailhead: { step: "Launch", recording: { "android-phone": { launchApp: { appId: "x" } } } },
      trail: [
        { step: "Tap Sign in", recording: { "android-phone": [{ tapOn: { text: "Sign in" } }], "ios-iphone": [{ tapOn: { text: "Sign in" } }] } },
        { verify: "See the dashboard" },
      ],
    };
    const m = TM.unifiedDocToMatrix(doc);
    expect(m.platforms).toEqual(["android-phone", "ios-iphone"]);
    expect(m.trailhead.kind).toBe("trailhead");
    expect(m.trailhead.recording["android-phone"]).toEqual([{ name: "launchApp", body: { appId: "x" } }]);
    expect(m.steps.map((s: any) => s.kind)).toEqual(["step", "verify"]);
    expect(m.steps[0].recording["ios-iphone"]).toEqual([{ name: "tapOn", body: { text: "Sign in" } }]);
    expect(m.steps[1].recording).toEqual({});
  });

  test("returns null for a v1 list and for junk", () => {
    expect(TM.unifiedDocToMatrix([{ config: {} }, { prompts: [] }])).toBeNull();
    expect(TM.unifiedDocToMatrix("nope")).toBeNull();
    expect(TM.unifiedDocToMatrix(null)).toBeNull();
    expect(TM.unifiedDocToMatrix({ config: {} })).toBeNull(); // has config but no trail/trailhead
  });

  test("surfaces a recording-only platform not declared in config.devices", () => {
    const m = TM.unifiedDocToMatrix({ config: {}, trail: [{ step: "x", recording: { web: [{ tapOn: {} }] } }] });
    expect(m.platforms).toEqual(["web"]);
  });

  test("preserves unknown per-step keys in extra", () => {
    const m = TM.unifiedDocToMatrix({ config: {}, trail: [{ step: "x", skip: { web: "flaky" } }] });
    expect(m.steps[0].extra).toEqual({ skip: { web: "flaky" } });
  });

  test("platform columns: config.devices order first, then recording-only platforms appended", () => {
    const m = TM.unifiedDocToMatrix({
      config: { devices: { a: "D", b: "D" } },
      trail: [{ step: "x", recording: { b: [{ tapOn: {} }], c: [{ tapOn: {} }] } }],
    });
    expect(m.platforms).toEqual(["a", "b", "c"]);
  });

  test("a `prompt:` key supplies the step text (lenient parse)", () => {
    const m = TM.unifiedDocToMatrix({ config: {}, trail: [{ prompt: "do the thing" }] });
    expect(m.steps[0].text).toBe("do the thing");
    expect(m.steps[0].kind).toBe("step");
  });
});

describe("tool body fidelity (scalar / list / empty args)", () => {
  test("a scalar or list tool body survives the round-trip (regression: was coerced to {})", () => {
    const doc = { config: {}, trail: [{ step: "x", recording: { web: [{ wait: 500 }, { swipe: [1, 2, 3] }] } }] };
    const m = TM.unifiedDocToMatrix(doc);
    expect(m.steps[0].recording.web).toEqual([{ name: "wait", body: 500 }, { name: "swipe", body: [1, 2, 3] }]);
    expect(TM.matrixToUnifiedDoc(m)).toEqual(doc);
  });

  test("null and empty-object args both normalize to {} (the runtime's no-args form)", () => {
    const m = TM.unifiedDocToMatrix({ config: {}, trail: [{ step: "x", recording: { web: [{ pressBack: null }, { home: {} }] } }] });
    expect(m.steps[0].recording.web).toEqual([{ name: "pressBack", body: {} }, { name: "home", body: {} }]);
    expect(TM.matrixToUnifiedDoc(m).trail[0].recording.web).toEqual([{ pressBack: {} }, { home: {} }]);
  });
});

describe("round-trip: matrixToUnifiedDoc(unifiedDocToMatrix(doc)) is semantically identity", () => {
  const cases: Record<string, any> = {
    "trailhead + steps + multi-platform": {
      config: { title: "T", devices: { "android-phone": "ANDROID_ONDEVICE_ACCESSIBILITY", web: "PLAYWRIGHT_NATIVE" } },
      trailhead: { step: "Launch", recording: { "android-phone": { launchApp: { appId: "a" } } } },
      trail: [
        { step: "One", recording: { "android-phone": [{ tapOn: { text: "1" } }, { inputText: { text: "hi" } }], web: [{ tapOn: { text: "1" } }] } },
        { verify: "Two" },
        { step: "Three", recording: { web: [{ scroll: {} }] } },
      ],
    },
    "no trailhead": {
      config: { title: "N", devices: { web: "PLAYWRIGHT_NATIVE" } },
      trail: [{ step: "solo", recording: { web: [{ tapOn: { text: "go" } }] } }],
    },
    "step with extra key": {
      config: { devices: { web: "PLAYWRIGHT_NATIVE" } },
      trail: [{ step: "x", skip: { web: "why" }, recording: { web: [{ tapOn: {} }] } }],
    },
  };
  for (const [name, doc] of Object.entries(cases)) {
    test(name, () => {
      const out = TM.matrixToUnifiedDoc(TM.unifiedDocToMatrix(doc));
      expect(out).toEqual(doc);
    });
  }

  test("a verify step round-trips as `verify:`, not `step:`", () => {
    const doc = { config: {}, trail: [{ verify: "it worked" }] };
    const out = TM.matrixToUnifiedDoc(TM.unifiedDocToMatrix(doc));
    expect(out.trail[0]).toEqual({ verify: "it worked" });
    expect("step" in out.trail[0]).toBe(false);
  });

  test("an empty recording never emits a `recording:` key", () => {
    const out = TM.matrixToUnifiedDoc(TM.unifiedDocToMatrix({ config: {}, trail: [{ step: "x" }] }));
    expect("recording" in out.trail[0]).toBe(false);
  });

  test("an explicit `classifier: []` no-op survives the round-trip (regression: was dropped on save)", () => {
    // `android-tablet: []` means "author deliberately recorded nothing for this device class" —
    // distinct from an absent key, which can fall back to a broader family recording (`android:`)
    // at closest-wins resolution time. Dropping it on save would make tablets replay tools that
    // were intentionally suppressed.
    const doc = {
      config: { devices: { android: "ANDROID_ONDEVICE_ACCESSIBILITY", "android-tablet": "ANDROID_ONDEVICE_ACCESSIBILITY" } },
      trail: [{ step: "x", recording: { android: [{ tapOn: { text: "go" } }], "android-tablet": [] } }],
    };
    const out = TM.matrixToUnifiedDoc(TM.unifiedDocToMatrix(doc));
    expect(out).toEqual(doc);
    expect(out.trail[0].recording["android-tablet"]).toEqual([]);
    // ...while a platform that was never recorded for the step stays absent.
    expect("web" in out.trail[0].recording).toBe(false);
  });

  test("`recordable: false` is dropped when the step gains tools (the pair is a parser-rejected contradiction)", () => {
    // The Kotlin parser hard-rejects `recordable: false` alongside a non-empty recording, and saves
    // aren't validated server-side — so the serializer must resolve the conflict in favor of the
    // recording the user just added.
    const m = TM.unifiedDocToMatrix({ config: {}, trail: [{ step: "x", recordable: false }] });
    m.steps[0].recording.web = [{ name: "tapOn", body: { text: "go" } }];
    m.platforms.push("web");
    const out = TM.matrixToUnifiedDoc(m);
    expect("recordable" in out.trail[0]).toBe(false);
    expect(out.trail[0].recording.web).toEqual([{ tapOn: { text: "go" } }]);
    // ...but `recordable: false` with only explicit no-op recordings is valid and round-trips.
    const noop = TM.unifiedDocToMatrix({ config: {}, trail: [{ step: "y", recordable: false, recording: { web: [] } }] });
    expect(TM.matrixToUnifiedDoc(noop).trail[0].recordable).toBe(false);
  });

  test("a trailhead never emits an empty classifier (its classifier must be exactly one tool map)", () => {
    const m = TM.unifiedDocToMatrix({
      config: { devices: { web: "PLAYWRIGHT_NATIVE" } },
      trailhead: { step: "launch" },
      trail: [{ step: "x" }],
    });
    m.trailhead.recording.web = [];
    const out = TM.matrixToUnifiedDoc(m);
    expect("recording" in out.trailhead).toBe(false);
  });
});

// ── E2E against real on-disk unified trail files ─────────────────────────────────────────────────
// Walks the repo's trails/ tree, parses each .trail.yaml with Bun's built-in YAML, and for every
// UNIFIED file asserts the FULL model is stable across a doc->model->doc->model round-trip. Unlike a
// count-only check, comparing the two models proves no config key, step, per-step `extra`, tool name,
// or tool body is lost or mangled. This is the model exercised against production data.
describe("E2E: real trails/ tree", () => {
  // Resolve the checkout root by its `.git` marker (a dir in a normal clone, a FILE in a git
  // worktree) — NOT the first ancestor with a trails/ dir, because a nested module can have its own
  // trails/ dir of legacy fixtures (no unified files) that would silently match nothing.
  const repoRoot = (() => {
    let d = import.meta.dir;
    for (let i = 0; i < 20; i++) {
      if (existsSync(join(d, ".git")) && existsSync(join(d, "trails"))) return d;
      d = join(d, "..");
    }
    return null;
  })();

  const isUnifiedDoc = (doc: any) =>
    doc && typeof doc === "object" && !Array.isArray(doc) && (Array.isArray(doc.trail) || doc.trailhead);

  const yamlFiles: string[] = [];
  const walk = (dir: string, depth: number) => {
    if (depth > 12) return;
    for (const e of readdirSync(dir, { withFileTypes: true })) {
      if (e.name.startsWith(".")) continue;
      const p = join(dir, e.name);
      if (e.isDirectory()) walk(p, depth + 1);
      else if (e.name.endsWith(".trail.yaml")) yamlFiles.push(p);
    }
  };
  if (repoRoot) walk(join(repoRoot, "trails"), 0);

  test("found trail files to exercise", () => {
    expect(repoRoot).not.toBeNull();
    expect(yamlFiles.length).toBeGreaterThan(0);
  });

  test("every unified file round-trips with full model fidelity", () => {
    let unifiedCount = 0;
    for (const f of yamlFiles) {
      let doc: any;
      try { doc = (Bun as any).YAML.parse(readFileSync(f, "utf8")); } catch { continue; }
      if (!isUnifiedDoc(doc)) continue;
      const m1 = TM.unifiedDocToMatrix(doc);
      expect(m1, `lower ${f}`).not.toBeNull();
      const m2 = TM.unifiedDocToMatrix(TM.matrixToUnifiedDoc(m1));
      // Full structural equality: catches any dropped/mangled config, step, extra, tool name or body.
      expect(m2, `full round-trip fidelity for ${f}`).toEqual(m1);
      unifiedCount++;
    }
    // The tree is known to contain unified trails; guard against the filter silently matching none.
    expect(unifiedCount).toBeGreaterThan(0);
    console.log(`[trail-model E2E] exercised ${unifiedCount} unified files of ${yamlFiles.length} total`);
  });
});
