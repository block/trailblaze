---
title: "Write Tools in TypeScript: a Scripted Tool Costs a Fifth of a Millisecond"
type: decision
date: 2026-09-29
---

# Write Tools in TypeScript: a Scripted Tool Costs a Fifth of a Millisecond

## Summary

Write new tools in TypeScript, not Kotlin. A TypeScript tool runs in-process in QuickJS, and a call
costs about 0.2 ms more than the same tool written in Kotlin. A device tap or assertion takes
hundreds of milliseconds, so the difference never shows up in a run. What you get for it is a
tool that ships with its trailmap and doesn't need a framework build.

## The numbers

`QuickJsToolDispatchBenchmark` (in `trailblaze-quickjs-tools`) times the real dispatch path. The
TypeScript tools are written with the SDK (`trailblaze.tool(...)`) and bundled the way production
bundles them, so the SDK's per-call wrapper is included. Every tool body is empty, so each row is
overhead. Median of 5,000 calls across two quiet runs on an M-series Mac:

```text
Kotlin tool, dispatched by name ██████                               0.01 ms
TypeScript tool                 ██████████████                       0.20 ms
TypeScript + 1 Kotlin call      ██████████████                       0.21 ms
TypeScript + 10 Kotlin calls    ███████████████                      0.31 ms
TypeScript + 10 TS helpers      ██████████████                       0.19 ms
Start /usr/bin/true (once)      ██████████████████████████           20 ms
Start node -e 0 (once)          █████████████████████████████        60 ms
One device tap (typical)        █████████████████████████████████    hundreds of ms
                                     |     |     |     |     |     |
ms per call, log scale               0.01  0.1   1     10    100   1000
```

Every row but the device tap is measured; the tap is there for scale. The two process starts are
paid once by a subprocess runtime, not on every call.

- **Entering and leaving QuickJS is the only real cost:** about 0.19 ms per tool call over the same
  Kotlin tool. About 0.02 ms of that is the SDK wrapper.
- **Composing Kotlin tools from TypeScript is cheap:** about 0.01 ms per `ctx.tools.<name>(...)` call.
- **Helper functions are free.** They're plain JavaScript calls inside the engine.
- **Session start is small too.** Loading a tool bundle into its engine takes 0.9 ms, and each
  scripted tool gets its own engine, so 50 tools cost about 45 ms once.
- **In-process means no process to start.** A subprocess tool runtime pays 20–60 ms once to start,
  then an inter-process round trip on every call, which this benchmark doesn't measure.

To reproduce:

```bash
./gradlew :trailblaze-quickjs-tools:jvmTest --tests '*QuickJsToolDispatchBenchmark*' \
  -Dtrailblaze.benchmark=true -i
```

It measures the JVM host. On-device QuickJS wasn't measured.

## Tips

- **Write it in TypeScript.** Use Kotlin only when the tool *is* a primitive the sandbox lacks: a
  socket, a binary HTTP body, sensitive memory. Even then, keep the Kotlin to that primitive and
  write the rest in TypeScript on top of it.
- **Register a tool only when trails or many callers need it.** Every registered tool is listed in
  every session's tool registry. Plumbing that one or two tools share is an exported function they
  import.
- **Host work goes through `exec`, HTTP through `fetch`.** Between them they cover most of what
  Kotlin tools used to be written for.
- **Keep the framework to primitives.** Helpers belong in the trailmap that uses them
  ([Framework provides primitives; helpers are not framework](2026-06-22-framework-primitives-helpers-compose.md)).

## Example: read an app's saved preference

Reading a value an iOS simulator app saved looks like a job for a dedicated Kotlin tool. It's a
TypeScript helper built on `exec`:

```typescript
import type { ToolContext } from "@trailblaze/scripting";

/** Reads one key an iOS simulator app saved to its preferences. Throws if the app or key is missing. */
export async function readIosAppPreference(ctx: ToolContext, appId: string, key: string): Promise<string> {
  const udid = ctx.device?.instanceId;
  if (!udid) throw new Error("This session has no simulator.");
  const container = String(await ctx.tools.exec({
    argv: ["xcrun", "simctl", "get_app_container", udid, appId, "data"],
  })).trim();
  const plist = `${container}/Library/Preferences/${appId}.plist`;
  return String(await ctx.tools.exec({
    argv: ["/usr/libexec/PlistBuddy", "-c", `Print :${key}`, plist],
  })).trim();
}
```

The tool that needs the value imports the helper. Nothing new is registered, and the framework
gains no surface. `exec` fails on a non-zero exit, so a missing app or key throws. It also returns
stderr merged with stdout, so a production version should check the output's shape before
trusting it.
