// Fixture for `QuickJsToolDispatchBenchmark`: SDK-authored tools, bundled the production in-process
// way, so each timing includes the SDK's per-call wrapper (envelope parse, context build, result
// normalization). Every body is as close to empty as the shape allows, so the timings are overhead.
// `bench_noop` is the benchmark's Kotlin tool; `ctx.tools.<name>` reaches it over the host binding.

import { trailblaze } from "@trailblaze/scripting";

type Tools = Record<string, (args: Record<string, unknown>) => Promise<unknown>>;

function helper(n: number): number {
  return n + 1;
}

export const bench_tsNoop = trailblaze.tool<Record<string, never>>(async () => "ok");

export const bench_tsCallsKotlin = trailblaze.tool<Record<string, never>>(async (_input, ctx) =>
  String(await (ctx.tools as unknown as Tools).bench_noop({})),
);

export const bench_tsCallsKotlin10 = trailblaze.tool<Record<string, never>>(async (_input, ctx) => {
  for (let i = 0; i < 10; i++) {
    const result = await (ctx.tools as unknown as Tools).bench_noop({});
    if (result !== "ok") throw new Error(`bench_noop returned ${String(result)}`);
  }
  return "ok";
});

export const bench_tsCallsHelper10 = trailblaze.tool<Record<string, never>>(async () => {
  let n = 0;
  for (let i = 0; i < 10; i++) n = helper(n);
  return String(n);
});
