// Smoke-test fixture — a real `trailblaze.tool()`-authored tool that exercises the full
// `ctx.memory` surface (`interpolate` / `get` / `has` / `keys`). This is the *reproduction seed*
// for the on-device scripted-tool smoke gate: it is bundled the exact way production bundles an
// on-device tool (esbuild against the SLIM `@trailblaze/scripting` in-process profile + the
// synthesized wrapper template), then run through the REAL QuickJS engine.
//
// Why this fixture exists: a scripted tool that called `ctx.memory.interpolate(...)` passed
// `bun test` (the SDK test mock provides a fully working memory), `tsc`, and the module unit
// suites — yet crashed at replay on the real on-device QuickJS runtime with
// `TypeError: ... is not a function` because the on-device context carries `memory` as a raw
// `Record<string,string>` snapshot with no methods. The mock was more capable than the real
// runtime, and no PR gate ran the actual bundle on the real engine. `QuickJsOnDeviceMemorySmokeTest`
// closes that gap: it dispatches this fixture through `QuickJsTrailblazeTool.execute(...)` with a
// production-shaped context and asserts the handler completes (no JS crash) AND that each memory
// method returned the right answer — so the gate catches both a re-broken wrap and a silently
// no-op one.
//
// Keep this fixture SDK-surface-only: no Node APIs, no `ctx.tools.*` device calls (the smoke test
// stubs no device), just the memory surface a non-`requiresHost` on-device tool is allowed to use.

import { trailblaze } from "@trailblaze/scripting";

interface MemoryProbeInput {
  /** A memory key present in the session's non-sensitive memory (read back via `get`/`has` and used
   *  to build the interpolation token IN THIS HANDLER — see below). */
  lookupKey: string;
}

export const smoke_memoryProbe = trailblaze.tool<MemoryProbeInput>(async (input, ctx) => {
  const key = input.lookupKey;

  // Build the `{{key}}` / `${key}` tokens HERE, inside the QuickJS handler, from the plain key —
  // do NOT accept a pre-built token as an arg. `QuickJsTrailblazeTool.execute` pre-interpolates
  // recorded args against full memory (`interpolateVariablesInJson`) BEFORE the bundle runs, so a
  // token passed as an arg would already be resolved by the time it reached here. Then a regressed
  // `ctx.memory.interpolate` that merely returns its input would still look correct — the exact
  // silent no-op this gate must catch. Constructing the token in-handler means the ONLY thing that
  // can resolve it is the on-device `ctx.memory.interpolate`: if it no-ops, `interpolated` stays
  // literally `Hi {{firstName}} / ${firstName}` and the test's assertion fails. `key` itself carries
  // no token, so the host-side arg interpolation leaves it untouched.
  const interpolated = ctx.memory.interpolate("Hi {{" + key + "}} / ${" + key + "}");
  const got = ctx.memory.get(key) ?? "(absent)";
  const has = ctx.memory.has(key);
  const keys = [...ctx.memory.keys()].sort().join(",");

  // Exercise the WRITE path too: a `ctx.memory.set(...)` must flush back into the host AgentMemory
  // so a subsequent tool's `ctx.memory.get(...)` sees it. On the on-device / in-process QuickJS
  // path this write was silently dropped before the memory-delta flush fix (the write-then-read
  // hand-off between two scripted tools). The smoke test asserts the host memory reflects both the
  // set and the delete after `QuickJsTrailblazeTool.execute` returns.
  ctx.memory.set("smoke_probe_wrote", got);
  // Delete a key the host seeded (so the drained delta actually emits a deletion the host applies —
  // deleting a key that was never in the snapshot is a no-op the SDK correctly omits).
  ctx.memory.delete("smoke_probe_seeded_to_delete");

  return `interpolated=${interpolated}|got=${got}|has=${has}|keys=${keys}`;
});
