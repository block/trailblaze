// Cross-language behavioral contract for text-pattern matching.
//
// `matcher-parity-fixtures.json` is the single source of truth for expected matching
// behavior, consumed by BOTH the Kotlin resolver's test suite (MatcherParityFixturesTest
// in trailblaze-models jvmTest) and this file. Every case runs through the REAL
// `resolve()` — a node displaying `text`, a selector with `textRegex: pattern` — so a
// semantic drift in either implementation fails that side's suite.
//
// To change matching semantics: update both implementations AND the fixture in the
// same change. Never encode new semantics in only one language's tests.

import { expect, test } from "bun:test";

import { selectors } from "../generated/selectors.js";
import fixtures from "./matcher-parity-fixtures.json";
import { resolve } from "./resolver.js";
import type { TrailblazeNode } from "./trailblaze-node.js";

// Every case runs through BOTH dialects (native shape asserting `nativeMatches`, Maestro
// shape asserting `maestroMatches`) and, within each dialect, TWO different match fields.
// All `*Regex` fields of a shape share the one `matchesPattern`, so this locks the
// semantics as field-uniform: a future per-field fork of the matching logic fails here.
type ParityCase = (typeof fixtures.cases)[number];

const FIELDS = [
  {
    name: "native/textRegex",
    detail: (text: string) => ({ class: "androidAccessibility", text }) as const,
    selector: (pattern: string) => selectors.androidAccessibility({ textRegex: pattern }),
    expected: (c: ParityCase) => c.nativeMatches,
  },
  {
    name: "native/contentDescriptionRegex",
    detail: (text: string) =>
      ({ class: "androidAccessibility", contentDescription: text }) as const,
    selector: (pattern: string) =>
      selectors.androidAccessibility({ contentDescriptionRegex: pattern }),
    expected: (c: ParityCase) => c.nativeMatches,
  },
  {
    name: "maestro/textRegex",
    detail: (text: string) => ({ class: "androidMaestro", text }) as const,
    selector: (pattern: string) => selectors.androidMaestro({ textRegex: pattern }),
    expected: (c: ParityCase) => c.maestroMatches,
  },
  {
    name: "maestro/accessibilityTextRegex",
    detail: (text: string) =>
      ({ class: "androidMaestro", accessibilityText: text }) as const,
    selector: (pattern: string) =>
      selectors.androidMaestro({ accessibilityTextRegex: pattern }),
    expected: (c: ParityCase) => c.maestroMatches,
  },
  // iosMaestro carries the same MAESTRO dialect as androidMaestro — exercised explicitly so
  // its dialect wiring cannot silently revert to native while all other tests stay green.
  {
    name: "maestro/iosMaestro.textRegex",
    detail: (text: string) => ({ class: "iosMaestro", text }) as const,
    selector: (pattern: string) => selectors.iosMaestro({ textRegex: pattern }),
    expected: (c: ParityCase) => c.maestroMatches,
  },
] as const;

for (const c of fixtures.cases) {
  for (const field of FIELDS) {
    test(`parity: ${c.name} [${field.name}]`, () => {
      const target: TrailblazeNode = {
        nodeId: 2,
        children: [],
        bounds: { left: 0, top: 0, right: 100, bottom: 50 },
        driverDetail: field.detail(c.text),
      };
      const root: TrailblazeNode = {
        nodeId: 1,
        children: [target],
        bounds: { left: 0, top: 0, right: 200, bottom: 100 },
        driverDetail: { class: "androidAccessibility" },
      };
      const result = resolve(root, field.selector(c.pattern));
      const matched = result.kind === "singleMatch";
      const expectedMatch = field.expected(c);
      expect(
        matched,
        `pattern=[${c.pattern}] text=[${c.text}] expected matches=${expectedMatch}, got ${matched}`,
      ).toBe(expectedMatch);
    });
  }
}
