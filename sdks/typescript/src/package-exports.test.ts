// Pins the package's `exports` map against reality: every declared subpath must actually
// resolve to a loadable module.
//
// Why this needs a test at all: nothing else in the build reads the `exports` map. A
// trailmap in a workspace resolves `@trailblaze/scripting/*` through the per-trailmap
// tsconfig `paths` glob that `trailblaze check` emits, which points straight at the
// extracted `dist/` files — so a subpath can be added to `src/` and shipped in `dist/`
// while the `exports` entry is missing or misspelled, and every trailmap keeps working.
// The consumers that DO read the map are the ones that install the SDK as a package: the
// `file:`-linked example app, and the SDK's own tooling (this suite self-references
// through it). Both fail far from the cause.
//
// Deliberately data-driven off `package.json` rather than a hard-coded list, so a new
// subpath added to the map is covered the moment it lands, and a subpath whose target
// file is renamed out from under it fails here.

import { expect, test } from "bun:test";
import packageJson from "../package.json";

const subpaths = Object.keys(packageJson.exports);

test("the exports map declares at least the known subpaths", () => {
  // A floor, not an exhaustive list — this guards against a truncated / malformed map
  // making the loop below vacuous (zero iterations would otherwise pass silently).
  expect(subpaths).toEqual(expect.arrayContaining([".", "./in-process", "./sub-process", "./testing", "./matcher"]));
});

test.each(subpaths)("the %s subpath resolves to a loadable module", async (subpath) => {
  const specifier = subpath === "."
    ? packageJson.name
    : `${packageJson.name}/${subpath.slice(2)}`;
  const module = await import(specifier);
  expect(module).toBeDefined();
  // A module object with no exported names would mean the entry resolved to something
  // empty (e.g. a `.d.ts` loaded as runtime), which is the failure mode a bare
  // "did it import" check misses.
  expect(Object.keys(module).length).toBeGreaterThan(0);
});
