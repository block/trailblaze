// TYPE-LEVEL REGRESSION TEST — NOT A RUNTIME TEST.
//
// This file is never executed. Every reference below either type-only or `void`'d so
// even if the file were imported it would be a no-op. The "test runner" is
// `tsc --noEmit`. A regression looks like either of:
//
//   - A happy-path line stops compiling — someone dropped a runtime global from the
//     curated set, or narrowed its declared shape. tsc reports the line and the
//     build fails.
//   - A `@ts-expect-error` line starts compiling — a DOM-only global (`document`,
//     `window`, etc.) leaked into the curated set. tsc reports TS2578 "unused
//     directive" and the build fails — exactly the leakage we want to catch.
//
// In other words: flipping safe and unsafe is symmetrical, and tsc enforces both
// directions.
//
// WHY IT LIVES UNDER playwright-native/.../playwrightSample/tools/:
//
//   This sample trailmap hosts the canonical "unit-test fixture" for the per-trailmap TS
//   authoring surface — the sister files `_type_smoke.ts` (mobile/typed-tools) and
//   `_type_smoke_web.ts` (web/typed-tools) cover the `client.tools.X(...)` surface;
//   this file covers the orthogonal property that ambient runtime globals from the
//   SDK declaration bundle resolve. Type-level smoke files live next to the surface
//   they test (the consumer-side `.d.ts` resolution), not in the trailblaze-host
//   JVM test source set.
//
// WHY THIS FILE EXISTS:
//
//   The hand-authored `sdks/typescript/runtime-globals.d.ts` is appended to the SDK
//   declaration bundle by [TrailblazeSdkDtsBundlePlugin.appendRuntimeGlobals]. From
//   a trailmap's perspective, importing anything from `@trailblaze/scripting` pulls the
//   bundle in — which makes the `declare global { ... }` block in the appendix
//   visible — so the expressions below compile cleanly without a per-trailmap
//   `globals.d.ts` shim. Without a fixture, an accidental shrink of
//   `runtime-globals.d.ts` would slip through unnoticed until a real trailmap hit the
//   missing global.
//
// CI WIRING:
//
//   Per PR #3219 (post-Phase-B), `pr_validate_ts_tooling.sh` discovers per-trailmap
//   tool dirs by the framework-emitted `tools/tsconfig.json` (not the pre-trailmap
//   `tools/package.json` it used to look for), so this file IS now CI-backed —
//   every PR runs `tsc --noEmit` against the trailmap's generated tsconfig and any
//   regression in this fixture fails the build.
//
//   Dev-side, the canonical author-facing entry is `./trailblaze check
//   playwrightSample` (added by PR #3217), which spawns the bundled tsc against
//   the same generated tsconfig CI uses. Bare `tsc --noEmit` in this dir still
//   works as a fallback if an author already has TypeScript on their PATH.
//
//   The sister files (`_type_smoke.ts`, `_type_smoke_web.ts`) still carry the
//   stale pre-#3219 "KNOWN GAP" prose; updating them is out of scope for this
//   PR — file a follow-up to harmonize all three.

// The import is load-bearing: TypeScript only ingests a module's `declare global`
// block when the module is in the program. A trailmap's real tool file always imports
// from `@trailblaze/scripting` for `tool` / `z`; this fixture mirrors that contract.
import { z } from "@trailblaze/scripting";
void z;

// === Happy path: every curated global MUST compile. ===

// ---- URL / URLSearchParams ----
const u = new URL("https://example.com/path?q=1");
const host: string = u.hostname;
const path: string = u.pathname;
const params: URLSearchParams = u.searchParams;
void host; void path; void params;

const sp = new URLSearchParams({ a: "b" });
const got: string | null = sp.get("a");
sp.append("c", "d");
const merged: string = sp.toString();
void got; void merged;

// ---- AbortController / AbortSignal ----
const controller = new AbortController();
const sig: AbortSignal = controller.signal;
const aborted: boolean = sig.aborted;
// Listener with explicit `ev` arg locks in the AbortSignalEventMap shape — a zero-arg
// arrow type-checks against the declaration but doesn't exercise the event-typed
// callback contract authors actually care about.
sig.addEventListener("abort", (ev) => {
  void ev.type;
});
controller.abort();
void aborted;

// Static factories — `new AbortSignal()` is intentionally NOT declared on the
// constructor (host runtimes throw "Illegal constructor"). Authors who want a
// standalone signal go through the static factories or `controller.signal`.
const timeoutSignal = AbortSignal.timeout(1000);
const abortedSignal = AbortSignal.abort("reason");
const compositeSignal = AbortSignal.any([timeoutSignal, abortedSignal]);
void compositeSignal;

// ---- console ----
console.log("log");
console.info("info");
console.warn("warn");
console.error("error");
console.debug("debug");

// ---- Timers ----
// `setTimeout` returns an opaque `TimeoutHandle` (not `number`) — see the kdoc on
// `runtime-globals.d.ts` for why. The handle is only useful as input to
// `clearTimeout`; numeric comparisons or property access would type-error.
const handle = setTimeout(() => {}, 100);
clearTimeout(handle);

// ---- DOMException ----
const ex = new DOMException("bad", "InvalidStateError");
const exName: string = ex.name;
const exCode: number = ex.code;
void exName; void exCode;

// ---- fetch / Headers / Request / Response ----
const headers = new Headers({ "x-trace": "1" });
headers.set("x-other", "2");
async function exerciseFetch(): Promise<string> {
  const res = await fetch("https://example.com", {
    method: "GET",
    headers,
    signal: sig,
  });
  if (!res.ok) throw new Error(`http ${res.status}`);
  return res.text();
}
void exerciseFetch;

// === Regression coverage: each `@ts-expect-error` MUST stay errored. ===
// If any of these compile clean, the curated runtime-globals list has accidentally
// widened (DOM lib leaked in, or a load-bearing-narrow declaration was loosened).
// tsc fires TS2578 "unused directive" on each one and the build fails.

// @ts-expect-error `document` is DOM-only — not in the curated runtime globals
void document.querySelector("a");

// @ts-expect-error `window` is DOM-only — not in the curated runtime globals
void window.location;

// @ts-expect-error `navigator` is DOM-only — not in the curated runtime globals
void navigator.userAgent;

// @ts-expect-error `localStorage` is DOM-only — not in the curated runtime globals
void localStorage.getItem("k");

// @ts-expect-error `AbortSignal` is intentionally not constructible — host runtimes
// throw "Illegal constructor". The `new ()` overload is omitted from the declared
// var on purpose; this directive must stay errored to catch a regression that adds
// it back.
void new AbortSignal();

// @ts-expect-error `TimeoutHandle` is opaque — numeric comparison would type-check
// against `number` but crash on Node, so the type system blocks it. Keeping this
// directive errored proves the opaque-handle contract is intact.
void (setTimeout(() => {}, 0) > 0);

// Marks this file as a TS module — see sister files for the kdoc rationale.
export {};
