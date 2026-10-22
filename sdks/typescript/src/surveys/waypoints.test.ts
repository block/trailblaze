// Behavioral contract for compiling waypoint YAML into node selectors and matching hierarchies.
// Every assertion is on what a waypoint author observes: which nodes a selector accepts, which
// screens a session is said to have visited.

import { describe, expect, test } from "bun:test";
import { mkdirSync, mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";

import { runSurvey } from "./run-one.js";
import type { SurveySession } from "./session.js";
import { survey } from "./survey.js";
import type { LogRecord } from "./types.js";
import {
  classifierChain,
  loadYamlWaypoints,
  matchNode,
  matchScreen,
  mergeWaypoints,
  resolveSpec,
  screensOf,
  selectorFromYaml,
  repositoryPath,
  repositoryRoot,
  sessionChain,
  type HierarchyNode,
  type ScreenSpec,
  type Waypoint,
  waypointFindings,
} from "./waypoints.js";

/** The SDK package root, which `source` paths in these tests are relative to. */
const SDK_ROOT = resolve(import.meta.dir, "../..");
const heading = { className: "heading", text: "Powering businesses the world over" };
const link = { className: "link", text: "2023 10-K Annual Report" };

describe("web waypoint selectors", () => {
  test("an ARIA role and name compile to the role and text the driver logs for a web node", () => {
    const sel = selectorFromYaml({ web: { ariaRole: "heading", ariaNameRegex: "Powering businesses.*" } });
    expect(matchNode(heading, sel)).toBe(true);
    expect(matchNode(link, sel)).toBe(false);
    expect(matchNode({ className: "heading", text: "Something else" }, sel)).toBe(false);
  });

  test("an anchor the logged hierarchy cannot confirm matches nothing, so its waypoint never matches every screen", () => {
    const sel = selectorFromYaml({ web: { dataTestId: "paywall-hero-image" } });
    expect(matchNode(heading, sel)).toBe(false);
    expect(matchScreen({ required: [{ description: "", selector: sel }], forbidden: [] }, { children: [heading, link] })).toBe(false);
  });

  test("a heading level or test id alongside a role narrows the anchor, so it is not checked on the role alone", () => {
    // The logged web hierarchy has a node's ARIA role and name and nothing else, so neither field
    // can be confirmed. Checking only the role and name would answer a wider anchor than the one
    // written down — every heading on the page, not the H2 the author picked out.
    for (const extra of [{ headingLevel: 2 }, { dataTestId: "hero" }]) {
      const sel = selectorFromYaml({ web: { ariaRole: "heading", ariaNameRegex: "Powering businesses.*", ...extra } });
      expect(matchNode(heading, sel)).toBe(false);
    }
  });

  test("a forbidden anchor nobody can check sinks the whole screen, instead of quietly excluding nothing", () => {
    // A forbidden anchor that matches nothing never excludes anything, so the waypoint would claim
    // the very screens its author wrote it to rule out.
    const spec = {
      required: [{ description: "", selector: selectorFromYaml({ web: { ariaRole: "heading", ariaNameRegex: "Powering businesses.*" } }) }],
      forbidden: [{ description: "", selector: selectorFromYaml({ web: { dataTestId: "paywall-hero-image" } }) }],
    };
    expect(matchScreen(spec, { children: [heading, link] })).toBe(false);
    // Nested the same way: a child condition nobody can check is no more checkable than a top-level one.
    const nested = selectorFromYaml({ web: { ariaRole: "form" }, containsChild: { web: { dataTestId: "cc-number" } } });
    expect(matchScreen({ required: [{ description: "", selector: nested }], forbidden: [] }, { children: [{ className: "form", children: [heading] }] })).toBe(false);
  });

  test("a selector in a dialect this matcher does not know is an error, not a match-everything", () => {
    expect(() => selectorFromYaml({ flutter: { key: "x" } })).toThrow(/no conditions/);
  });

  test("a selector mixing dialects from more than one platform family is an error, not silent last-wins", () => {
    // Each dialect branch assigns the same `out.text`/`out.resourceId` fields; without the guard the
    // iosMaestro branch runs after androidAccessibility's and silently overwrites it, compiling a
    // matcher that checks iOS's pattern for what is nominally an Android anchor.
    expect(() => selectorFromYaml({ androidAccessibility: { textRegex: "Sign in" }, iosMaestro: { textRegex: "Ingresar" } })).toThrow(/more than one platform/);
  });

  test("androidAccessibility and compose are the same family, so combining them still merges as before", () => {
    expect(() => selectorFromYaml({ androidAccessibility: { textRegex: "Sign in" }, compose: { textRegex: "Ignored" } })).not.toThrow();
  });
});

describe("dialect case rules", () => {
  test("Maestro-dialect patterns match case-insensitively, as the runtime resolves them", () => {
    expect(matchNode({ text: "Sign In" }, selectorFromYaml({ iosMaestro: { textRegex: "sign in" } }))).toBe(true);
  });

  test("Android accessibility patterns stay case-sensitive", () => {
    expect(matchNode({ text: "Sign In" }, selectorFromYaml({ androidAccessibility: { textRegex: "sign in" } }))).toBe(false);
    expect(matchNode({ text: "Sign In" }, selectorFromYaml({ androidAccessibility: { textRegex: "Sign In" } }))).toBe(true);
  });

  test("a text anchor is matched against one resolved string, text first, as every driver resolves it", () => {
    // The drivers match `textRegex` against a single resolved value — `text`, and the accessibility
    // label only when there is no `text`. Testing both fields would answer `More` for this node and
    // report a screen no device run ever matched.
    const both = { text: "Other", accessibilityText: "More" };
    expect(matchNode(both, selectorFromYaml({ iosMaestro: { textRegex: "More" } }))).toBe(false);
    expect(matchNode(both, selectorFromYaml({ iosMaestro: { textRegex: "Other" } }))).toBe(true);
    // With no `text` the label IS the resolved value, so an anchor written against it still matches.
    expect(matchNode({ accessibilityText: "More" }, selectorFromYaml({ iosMaestro: { textRegex: "More" } }))).toBe(true);
  });

  test("a Maestro pattern still has to match the whole value, not one line of it", () => {
    const sel = selectorFromYaml({ iosMaestro: { textRegex: "ok" } });
    expect(matchNode({ text: "book\nok" }, sel)).toBe(false);
    expect(matchNode({ text: "ok\nbook" }, sel)).toBe(false);
    expect(matchNode({ text: "OK" }, sel)).toBe(true);
  });
});

describe("patterns the runtime accepts as literals", () => {
  test("an anchor written as the literal price it is on screen matches it, though no regex could", () => {
    // `$` is an end-of-input anchor, so `/$5.00/` compiles and can never match anything. The
    // runtime's fields are regex-OR-literal, which is the only reason authors get to write this.
    const sel = selectorFromYaml({ androidAccessibility: { textRegex: "$5.00" } });
    expect(matchNode({ text: "$5.00" }, sel)).toBe(true);
    expect(matchNode({ text: "$6.00" }, sel)).toBe(false);
  });

  test("a pattern that does not compile is a literal, not a load failure", () => {
    const sel = selectorFromYaml({ androidAccessibility: { textRegex: "Total (" } });
    expect(matchNode({ text: "Total (" }, sel)).toBe(true);
    expect(matchNode({ text: "Total" }, sel)).toBe(false);
  });

  test("an invalid Maestro pattern degrades to a case-insensitive literal, as Maestro compiled it", () => {
    const sel = selectorFromYaml({ iosMaestro: { textRegex: "Subtotal (" } });
    expect(matchNode({ text: "subtotal (" }, sel)).toBe(true);
  });
});

describe("which block a device uses", () => {
  const spec = (text: string): ScreenSpec => ({ required: [{ description: text, selector: { text } }], forbidden: [] });
  const wp = (byClassifier: Record<string, ScreenSpec>): Waypoint => ({ id: "x", description: "", source: "s", byClassifier });

  test("a dedicated device's run does not use the android block, though it reports platform Android", () => {
    const chain = classifierChain(["acme", "handheld"]);
    expect(chain).toEqual(["acme-handheld", "acme", "handheld", "all"]);
    expect(chain).not.toContain("android");
    expect(resolveSpec(wp({ android: spec("Phone") }), chain)).toBeUndefined();
  });

  test("a phone run still uses the android block", () => {
    expect(resolveSpec(wp({ android: spec("Phone") }), classifierChain(["android", "phone"]))?.classifier).toBe("android");
  });

  test("the closest block on the lineage wins, so a device-specific screen beats its family's", () => {
    const resolved = resolveSpec(wp({ android: spec("Phone"), "android-tablet": spec("Tablet") }), classifierChain(["android", "tablet"]));
    expect(resolved?.classifier).toBe("android-tablet");
  });

  test("an `all` block speaks for every device, and loses to any classifier that is declared", () => {
    expect(resolveSpec(wp({ all: spec("Anywhere") }), classifierChain(["acme", "handheld"]))?.classifier).toBe("all");
    expect(resolveSpec(wp({ all: spec("Anywhere"), acme: spec("Handheld") }), classifierChain(["acme", "handheld"]))?.classifier).toBe("acme");
  });

  test("a session that logged no classifiers falls back to its platform, so older runs still resolve", () => {
    expect(sessionChain({ platform: "ios" })).toEqual(["ios", "all"]);
    expect(sessionChain({ deviceClassifiers: ["ios", "iphone"], platform: "ios" })[0]).toBe("ios-iphone");
  });

  test("a block that declares only an example inherits the family's anchors instead of wiping them", () => {
    // The waypoint format lets a tablet block carry just a reference screenshot. Taking the closest
    // block whole would leave a tablet run with no anchors at all — a waypoint that then reports
    // every screen the session captured as this screen.
    const sparse: ScreenSpec = { required: [], forbidden: [] };
    const resolved = resolveSpec(wp({ android: spec("Phone"), "android-tablet": sparse }), classifierChain(["android", "tablet"]));
    expect(resolved?.spec.required.map((a) => a.description)).toEqual(["Phone"]);
    expect(matchScreen(resolved!.spec, { children: [{ text: "Phone" }] })).toBe(true);
    expect(matchScreen(resolved!.spec, { children: [{ text: "Somewhere else" }] })).toBe(false);
  });

  test("a forbidden list resolves on its own, so a device block can add one without restating the anchors", () => {
    const forbidsOnly: ScreenSpec = { required: [], forbidden: [{ description: "Offline", selector: { text: "Offline" } }] };
    const resolved = resolveSpec(wp({ android: spec("Phone"), "android-tablet": forbidsOnly }), classifierChain(["android", "tablet"]));
    expect(resolved?.spec.required.map((a) => a.description)).toEqual(["Phone"]);
    expect(matchScreen(resolved!.spec, { children: [{ text: "Phone" }] })).toBe(true);
    expect(matchScreen(resolved!.spec, { children: [{ text: "Phone" }, { text: "Offline" }] })).toBe(false);
  });

  test("a waypoint that ends up with nothing required matches nothing, not every screen", () => {
    const resolved = resolveSpec(wp({ android: { required: [], forbidden: [] } }), classifierChain(["android", "phone"]));
    expect(resolved?.classifier).toBe("android");
    expect(matchScreen(resolved!.spec, { children: [{ text: "anything at all" }] })).toBe(false);
  });
});

describe("hierarchy relations", () => {
  const wrapper: HierarchyNode = { className: "View", children: [{ className: "TextView", text: "Deep" }] };
  const parent: HierarchyNode = { className: "Toolbar", children: [wrapper] };

  test("containsChild is a direct child, as in the runtime: a label nested under a wrapper does not make the grandparent match", () => {
    const sel = { className: "Toolbar", containsChild: { text: "Deep" } };
    expect(matchNode(parent, sel)).toBe(false);
    expect(matchNode(wrapper, { className: "View", containsChild: { text: "Deep" } })).toBe(true);
    expect(matchNode({ className: "Toolbar", children: [{ text: "Deep" }] }, sel)).toBe(true);
  });
});

describe("the screens of a session", () => {
  const A: HierarchyNode = { children: [{ text: "Sign in" }] };
  const B: HierarchyNode = { children: [{ text: "Welcome" }] };
  const record = (type: string, timeMs: number, log: Record<string, unknown>): LogRecord =>
    ({ kind: "log", type, index: timeMs, file: `${timeMs}_${type}.json`, timeMs, log } as unknown as LogRecord);
  const session = (records: LogRecord[]): SurveySession =>
    ({ objectives: { all: () => [] }, trailblazeLogs: { filter: (q: { type: string }) => records.filter((r) => r.type === q.type) } }) as unknown as SurveySession;

  test("the runner's terminal snapshot counts as a screen, so the screen the last action produced is not lost", () => {
    const screens = screensOf(
      session([
        record("MaestroDriverLog", 1000, { viewHierarchy: A, screenshotFile: "s1.webp", action: { class: "maestro.orchestra.TapOnElementCommand" } }),
        record("TrailblazeSnapshotLog", 3000, { viewHierarchy: B, screenshotFile: "s2.webp", displayName: "final_screenshot" }),
      ]),
    );
    expect(screens.map((s) => [s.hierarchy, s.action, s.screenshot])).toEqual([
      [A, "TapOnElementCommand", "s1.webp"],
      [B, "final_screenshot", "s2.webp"],
    ]);
  });

  test("a waypoint finding says it is one, so a legacy survey's spec feature and kind cannot refile it", async () => {
    const signIn: Waypoint = { id: "app/sign-in", description: "", source: "s", byClassifier: { android: { required: [{ description: "Sign in", selector: { text: "Sign in" } }], forbidden: [] } } };
    const run = { ...session([record("MaestroDriverLog", 1000, { viewHierarchy: A })]), id: "s1", summary: { platform: "android" } } as unknown as SurveySession;
    // A survey written before findings were free-form, whose spec defaulted a feature and a kind.
    const legacy = survey({ feature: "app.screens", kind: "signal" } as never, (s) => waypointFindings(s, [signIn]));
    const [finding] = await runSurvey(legacy, run, "screens");
    expect(finding?.data).toMatchObject({ waypoint: "app/sign-in", kind: "waypoint", feature: "app.screens" });
  });

  test("a snapshot of the screen the driver just captured is the same visit, not a second one", () => {
    const screens = screensOf(
      session([
        record("MaestroDriverLog", 1000, { viewHierarchy: A, screenshotFile: "s1.webp" }),
        record("TrailblazeSnapshotLog", 3000, { viewHierarchy: structuredClone(A), screenshotFile: "s2.webp", displayName: "final_screenshot" }),
      ]),
    );
    expect(screens.map((s) => s.screenshot)).toEqual(["s1.webp"]);
  });

  test("a screenshot url from an untrusted host is dropped, so the site never fetches it", () => {
    // `screenshotFile` is a driver log field, not operator input; trusting any https URL there would
    // let a malformed or compromised log make the site fetch an arbitrary host and publish the
    // response. Only the hosts the caller names are recognized.
    const screens = screensOf(
      session([
        record("MaestroDriverLog", 1000, { viewHierarchy: A, screenshotFile: "https://results.example.com/artifacts/s1.webp" }),
        record("MaestroDriverLog", 2000, { viewHierarchy: B, screenshotFile: "https://evil.example.com/s2.webp" }),
        record("MaestroDriverLog", 3000, { viewHierarchy: A, screenshotFile: "https://ci.example.org/builds/1/artifacts/s3.webp" }),
      ]),
      { screenshotHosts: ["results.example.com", "ci.example.org"] },
    );
    expect(screens.map((s) => s.screenshotUrl)).toEqual([
      "https://results.example.com/artifacts/s1.webp",
      undefined,
      "https://ci.example.org/builds/1/artifacts/s3.webp",
    ]);
  });

  test("a results host is trusted only when the caller names it", () => {
    const screens = screensOf(session([record("MaestroDriverLog", 1000, { viewHierarchy: A, screenshotFile: "https://results.example.com/artifacts/s1.webp" })]));
    expect(screens.map((s) => [s.screenshot, s.screenshotUrl])).toEqual([["s1.webp", undefined]]);
  });
});

describe("YAML waypoint definitions", () => {
  const fixtures = resolve(import.meta.dir, "fixtures");

  test("a committed web waypoint compiles, and its source is relative to the root it was given", () => {
    const [wp, ...rest] = loadYamlWaypoints(fixtures, SDK_ROOT);
    expect(rest).toEqual([]);
    expect(wp!.id).toBe("fixture/web/about");
    expect(wp!.source).toBe("src/surveys/fixtures/web-about.waypoint.yaml");
    const web = wp!.byClassifier.web!;
    expect(web.required.map((a) => a.selector.className)).toEqual(["heading", "link"]);
    expect(matchScreen(web, { children: [heading, link] })).toBe(true);
    expect(matchScreen(web, { children: [heading, link, { className: "textbox", text: "Email" }] })).toBe(false);
    const toolbar = wp!.byClassifier.android!.required[0]!.selector;
    expect(matchNode({ className: "android.widget.Toolbar", children: [{ text: "About" }] }, toolbar)).toBe(true);
    expect(matchNode({ className: "android.widget.Toolbar", children: [{ children: [{ text: "About" }] }] }, toolbar)).toBe(false);
  });

  test("a block keyed by a classifier that is not a platform loads, and only its own device uses it", () => {
    const wp = loadYamlWaypoints(fixtures, SDK_ROOT)[0]!;
    expect(Object.keys(wp.byClassifier).sort()).toEqual(["acme-handheld", "android", "web"]);
    expect(resolveSpec(wp, sessionChain({ deviceClassifiers: ["acme", "handheld"], platform: "android" }))?.classifier).toBe("acme-handheld");
    expect(resolveSpec(wp, sessionChain({ deviceClassifiers: ["android", "phone"], platform: "android" }))?.classifier).toBe("android");
  });
});

describe("one waypoint per id", () => {
  const wp = (id: string, source: string): Waypoint => ({ id, description: id, source, byClassifier: { ios: { required: [], forbidden: [] } } });

  test("a TypeScript definition replaces the YAML one that shares its id, so one screen is one waypoint", () => {
    const merged = mergeWaypoints([wp("app/sign-in/email", "a.waypoint.yaml"), wp("app/other", "b.waypoint.yaml")], [wp("app/sign-in/email", "waypoints.survey.ts")]);
    expect(merged.map((w) => [w.id, w.source])).toEqual([
      ["app/other", "b.waypoint.yaml"],
      ["app/sign-in/email", "waypoints.survey.ts"],
    ]);
  });

  test("two definitions of one id in the same layer are an error, not a silently doubled screen", () => {
    expect(() => mergeWaypoints([wp("app/x", "a.waypoint.yaml"), wp("app/x", "b.waypoint.yaml")])).toThrow(/defined twice.*a\.waypoint\.yaml and b\.waypoint\.yaml/);
  });
});

describe("repository-relative sources", () => {
  // A trailmap's surveys are loaded from whichever repository staged the trailmap, so the root a
  // `source` is relative to has to be found from the file, not assumed from where the SDK sits.
  const repo = mkdtempSync(join(tmpdir(), "waypoints-repo-"));
  mkdirSync(join(repo, "trailmaps/app/waypoints"), { recursive: true });
  writeFileSync(join(repo, ".git"), "gitdir: elsewhere\n");

  test("the root is the closest ancestor holding .git, a worktree's file included", () => {
    expect(repositoryRoot(join(repo, "trailmaps/app/waypoints"))).toBe(repo);
    expect(repositoryPath(join(repo, "trailmaps/app/surveys/waypoints.survey.ts"))).toBe("trailmaps/app/surveys/waypoints.survey.ts");
  });

  test("outside any repository a path is returned as given", () => {
    const loose = mkdtempSync(join(tmpdir(), "waypoints-loose-"));
    // The temp dir itself may sit inside a checkout on some machines; only assert when it does not.
    if (repositoryRoot(loose) === undefined) expect(repositoryPath(join(loose, "x.ts"))).toBe(join(loose, "x.ts"));
  });
});
