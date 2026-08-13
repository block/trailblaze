// Cross-platform parity tests for the Kotlin/JS-compiled selector engine, run under
// `bun test` by the `verifySelectorEngineParity` Gradle task (wired into this module's
// `check`). Two independent locks:
//
// 1. **Matcher-parity fixture** (`sdks/typescript/src/matcher/matcher-parity-fixtures.json`):
//    the shared behavioral contract for text-pattern matching, already consumed by the Kotlin
//    JVM resolver's MatcherParityFixturesTest and the TS matcher's matcher-parity.test.ts.
//    This suite is its third consumer — every case runs through the REAL compiled resolver
//    (via `resolveSelector` against one-node trees), which exercises the Kotlin/JS
//    `selectorPatternRegexMatches` actual: `\Q...\E` quote-section translation, leading
//    inline flags, dotAll, toRegexSafe degrade, and the literal-equality fallback.
//
// 2. **Golden corpus** (`expected-analysis.txt`): full selector analysis + tap resolution for
//    every node of the committed hierarchies, produced by the REAL daemon classes on the JVM
//    (SelectorEngineParityGoldenTest in :trailblaze-models jvmTest, regen instructions there).
//    The engine's output must be byte-identical — same strategies, same match sets, same
//    centers, same serialized selector text (`\Q...\E` escaping included).
//
// The suite goes through the typed wrapper (src/typescript/selector-engine.ts), so the
// generated-bindings boundary is exercised too.
import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";
import { loadSelectorEngine } from "../src/typescript/selector-engine";

// Evaluate the IIFE bundle (installs globalThis.TrailblazeSelectorEngine), then load the
// typed wrapper over it.
await import("../build/dist/trailblaze-selector-engine.min.js");
const engine = loadSelectorEngine();
if (engine == null) {
  throw new Error(
    "Selector engine bundle not found — run `./gradlew :trailblaze-selector-engine-js:bundleSelectorEngine` first " +
      "(the verifySelectorEngineParity Gradle task does this automatically).",
  );
}

const dir = new URL(".", import.meta.url).pathname;

interface ParityCase {
  name: string;
  pattern: string;
  text: string;
  nativeMatches: boolean;
  maestroMatches: boolean;
}

// One-node trees in the two dialect shapes. Matching semantics are dialect-of-the-selector,
// so a native (androidAccessibility) selector must be resolved against an
// androidAccessibility node and a Maestro-shape (androidMaestro) selector against an
// androidMaestro node — mirroring how MatcherParityFixturesTest drives the JVM resolver.
function nativeTree(text: string): object {
  return {
    nodeId: 1,
    bounds: { left: 0, top: 0, right: 100, bottom: 50 },
    driverDetail: { class: "androidAccessibility", text, className: "android.widget.TextView" },
  };
}

function maestroTree(text: string): object {
  return {
    nodeId: 1,
    bounds: { left: 0, top: 0, right: 100, bottom: 50 },
    driverDetail: { class: "androidMaestro", text, className: "android.widget.TextView" },
  };
}

describe("matcher-parity fixture through the compiled resolver", () => {
  const fixture = JSON.parse(
    readFileSync(`${dir}/../../sdks/typescript/src/matcher/matcher-parity-fixtures.json`, "utf-8"),
  ) as { cases: ParityCase[] };
  // Guard against a silently-emptied fixture making this suite vacuous.
  expect(fixture.cases.length).toBeGreaterThan(30);

  for (const c of fixture.cases) {
    test(`native: ${c.name}`, () => {
      const result = engine.resolveSelector(nativeTree(c.text), {
        androidAccessibility: { textRegex: c.pattern },
      });
      // A thrown error is surfaced as {error, matchCount: 0} — assert it's absent so a
      // matchCount of 0 provably means "the resolver declined", not "the engine blew up".
      expect(result.error ?? null).toBeNull();
      expect(result.matchCount).toBe(c.nativeMatches ? 1 : 0);
    });
    test(`maestro: ${c.name}`, () => {
      const result = engine.resolveSelector(maestroTree(c.text), {
        androidMaestro: { textRegex: c.pattern },
      });
      expect(result.error ?? null).toBeNull();
      expect(result.matchCount).toBe(c.maestroMatches ? 1 : 0);
    });
  }
});

describe("golden corpus matches the JVM output byte-for-byte", () => {
  interface Node {
    nodeId?: number;
    bounds?: { left: number; top: number; right: number; bottom: number };
    children?: Node[];
  }
  function walk(n: Node, out: Node[] = []): Node[] {
    out.push(n);
    (n.children ?? []).forEach((c) => walk(c, out));
    return out;
  }

  // Recompute the corpus with the compiled engine, in the exact line format the JVM golden
  // test writes. Uses the RAW string surface (not the wrapper) because the comparison is
  // byte-level on the serialized JSON.
  const raw = (globalThis as { TrailblazeSelectorEngine?: any }).TrailblazeSelectorEngine!;
  const lines: string[] = [];
  // Keep this list in sync with SelectorEngineParityGoldenTest.PARITY_TREES: one captured
  // androidAccessibility pair plus one small hand-built tree per remaining generator dialect,
  // so all six dialects in the bundle are byte-compared, not just compiled.
  for (const name of [
    "tree.json",
    "tree-large.json",
    "tree-compose.json",
    "tree-web.json",
    "tree-ios-axe.json",
    "tree-ios-maestro.json",
    "tree-android-maestro.json",
  ]) {
    const treeJson = readFileSync(`${dir}/${name}`, "utf-8");
    const nodes = walk(JSON.parse(treeJson) as Node);
    for (const n of nodes) {
      const id = n.nodeId ?? 0;
      lines.push(`${name}\t${id}\t${raw.computeSelectorAnalysis(treeJson, String(id))}`);
    }
    for (const n of nodes) {
      if (!n.bounds) continue;
      // Math.trunc matches Kotlin Int division (truncation toward zero, incl. negatives).
      const x = Math.trunc((n.bounds.left + n.bounds.right) / 2);
      const y = Math.trunc((n.bounds.top + n.bounds.bottom) / 2);
      lines.push(`tap\t${name}\t${x},${y}\t${raw.resolveTapTarget(treeJson, x, y)}`);
    }
  }
  const expected = readFileSync(`${dir}/expected-analysis.txt`, "utf-8").trimEnd().split("\n");

  test("same number of corpus lines", () => {
    expect(lines.length).toBe(expected.length);
  });

  test("every line identical to the JVM-produced golden", () => {
    const mismatches: string[] = [];
    for (let i = 0; i < Math.min(lines.length, expected.length); i++) {
      if (lines[i] !== expected[i]) {
        mismatches.push(`line ${i + 1}:\n  js : ${lines[i]}\n  jvm: ${expected[i]}`);
      }
    }
    if (mismatches.length > 0) {
      throw new Error(
        `${mismatches.length} corpus line(s) differ between the compiled JS engine and the JVM golden.\n` +
          `First 3:\n${mismatches.slice(0, 3).join("\n")}\n` +
          `If the engine's behavior changed intentionally, regenerate the golden on the JVM side:\n` +
          `  ./gradlew :trailblaze-models:jvmTest --tests "*SelectorEngineParityGoldenTest*" -Dtrailblaze.updateSelectorEngineGolden=true`,
      );
    }
  });
});

describe("typed wrapper surface", () => {
  test("computeSelectorAnalysis returns typed options with a best selector", () => {
    const treeJson = readFileSync(`${dir}/tree.json`, "utf-8");
    const analysis = engine.computeSelectorAnalysis(treeJson, 5);
    expect(analysis.error ?? null).toBeNull();
    expect(analysis.options.length).toBeGreaterThan(0);
    const best = analysis.options.find((o) => o.isBest);
    expect(best).toBeDefined();
    expect(best!.matchCount).toBe(1);
    expect(best!.selector).toBeDefined();
  });

  test("resolveTapTarget round-trips a tap to a selector", () => {
    const treeJson = readFileSync(`${dir}/tree.json`, "utf-8");
    const tree = JSON.parse(treeJson) as { children?: unknown[] };
    const tap = engine.resolveTapTarget(tree, 540, 1678);
    expect(tap.error ?? null).toBeNull();
    expect(tap.targetNodeId).toBeGreaterThan(0);
    expect(tap.selector).toBeDefined();
  });

  test("loadSelectorEngine returns null when the bundle is absent", () => {
    expect(loadSelectorEngine({})).toBeNull();
  });
});
