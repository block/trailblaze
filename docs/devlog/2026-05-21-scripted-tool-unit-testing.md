---
title: "Unit-testing scripted tools without a device"
type: devlog
date: 2026-05-21
---

# Unit-testing scripted tools without a device

## What landed

`*.test.ts` files next to a `.ts` scripted tool now run via `./trailblaze test <pack>` —
no daemon, no device, no MCP roundtrip. The deliverable is three coupled pieces:

1. **`@trailblaze/scripting/testing` SDK subpath.** New `sdks/typescript/src/testing.ts`
   exports `createMockClient()` (records every `client.callTool(name, args)` and lets a
   test register a canned response via `client.stub(toolName, { textContent,
   errorMessage })` — `errorMessage` non-empty makes the call throw the same shape the
   real client throws on a tool-side failure) and `createMockContext({ platform,
   target, memory })` (returns a `TrailblazeContext` with a no-op logger and explicit
   target / memory shape, no on-device runtime needed).

2. **Test runner.** `trailblaze test [<pack-id>|--all]` walks the pack's `tools/` tree
   for `*.test.ts` files and shells out to `bun test`. Pack discovery mirrors
   `typecheck` (walk-up from caller cwd, or `--all`, or explicit pack id); the
   subprocess timeout is governed by `TRAILBLAZE_TEST_TIMEOUT_MS` with a 5-minute
   default and a 1-minute lower clamp.

3. **Runtime resolution path.** The SDK ships both `dist/testing.d.ts` (for tsc) AND
   `dist/testing.js` (for bun's runtime resolution via the per-pack tsconfig `paths`
   mapping). The `.js` is a plain esbuild transpile of `src/testing.ts`, not a bundle
   — testing.ts has zero runtime imports from the rest of the SDK (only type-only
   imports), so the transpiled output is self-contained. `TrailblazeSdkDtsBundlePlugin`
   now generates and byte-verifies all three artifacts (`index.d.ts`, `testing.d.ts`,
   `testing.js`) in lockstep.

The canonical sample test sits next to a real tool:
`examples/playwright-native/.../playwrightSample_web_openFixtureAndVerifyText.test.ts`
(rooted at the OSS tree) asserts the tool dispatches `web_navigate` then
`web_verify_text_visible` with the expected args, and that module defaults apply when
args are omitted.

## Why a separate `test` subcommand instead of folding into `typecheck`

The earlier sketch had `trailblaze typecheck` also run `bun test` after tsc passed —
"one command, one answer." That conflates two failure modes the author cares about
separately. A tool body can be type-clean and logically broken (test catches it,
typecheck doesn't); an author iterating on test assertions doesn't want to pay tsc's
setup cost on every `bun test` run. Keeping them split also matches how `bun test`
itself works — no implicit tsc step.

The cost is two commands instead of one. Mitigated by parallel CLI shapes (same
walk-up + `--all` discovery, same exit-code conventions) so muscle memory carries
over.

## Why ship a transpiled `.js` instead of the `.ts` source

The first attempt was to ship `dist/testing.ts` (the raw source) directly. Bun
would resolve `@trailblaze/scripting/testing` via tsconfig `paths` and execute the
`.ts`. That works at runtime, but tsc's module resolution under
`moduleResolution: "Bundler"` tries extensions in order `.ts` → `.tsx` → `.d.ts` → `.js`,
so `dist/testing.ts` would shadow `dist/testing.d.ts` for type-checking and tsc would
try (and fail) to resolve the source's `./client.js` / `./context.js` imports against
a dist directory that doesn't ship those files.

Shipping `.js` instead means tsc sees `.d.ts` (correct types) and bun sees `.js`
(executable runtime), both reachable from the same `paths` entry with no ambiguity.
The byte-diff gate in `verifyTrailblazeSdkDtsBundle` extends to the `.js` too — a
hand-edit to `testing.js` triggers the same CI failure path as a stale `.d.ts`.

## Why testing.ts has no runtime SDK imports

Originally `testing.ts` imported `noopLogger` from `./logger.js`. That made
`testing.ts` non-self-contained: any consumer of the runtime `.js` would need the
sibling SDK files reachable too. Inlining a 4-line `mockNoopLogger` (`debug` / `info`
/ `warn` / `error` no-ops typed as `TrailblazeLogger`) removes the entanglement
entirely.

Beyond the runtime resolution win, this is good test-isolation hygiene — a regression
in the production logger or client can't reach into a mock by construction. The cost
is that adding a method to `TrailblazeLogger` requires updating `mockNoopLogger` in
lockstep; the bundled `.d.ts` would still type-check OK against a stale mock, so this
is the kind of drift a `*.test.ts` file would catch first (a test that calls
`ctx.logger.trace(...)` would `TypeError` at runtime).

## What needed `baseUrl` and what didn't

The per-pack tsconfig the framework already emits maps `@trailblaze/scripting/*` to a
`../../../../.trailblaze/sdk/dist/*` glob path. Confirmed via bun's actual resolution
that `../`-prefixed paths resolve fine WITHOUT `baseUrl`, but unprefixed relative
paths (`sdk2/*`) silently fail to resolve. The production emitter always uses
`../`-prefixed paths (the pack lives inside the workspace; the SDK lives at the
workspace root), so no change to `PerPackTsconfigEmitter` was needed — but if a
future change makes that emitter resolve the SDK to a sibling-relative path, expect
to add `baseUrl: "."` to keep bun's resolution happy.

## Out of scope (intentionally)

- **`.js`-authored test files.** The runner discovery glob is `*.test.ts` only. A
  pack that authors tools in `.js` can still write tests in `.ts` — the import
  surface is type-checked either way. Adding `*.test.js` would require deciding what
  `allowJs` behavior we want for tests, separate from what we want for tool source.
- **Watch mode.** `bun test --watch` is a single flag away; we deliberately don't
  expose it through `trailblaze test` yet because the CLI loop for the daemon is
  what runs in CI, and watch mode is local-dev-only ergonomics. Add when an author
  actually asks.
- **Coverage.** Same reasoning — `bun test --coverage` works fine if invoked
  directly from inside a pack's `tools/` dir, but isn't wired through the
  `trailblaze test` flag surface yet.
