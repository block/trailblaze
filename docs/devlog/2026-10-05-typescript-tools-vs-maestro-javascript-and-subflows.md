---
title: "Trailblaze vs Maestro: TypeScript Tools, Subflows and Maestro MCP"
type: devlog
date: 2026-10-05
---

# Trailblaze vs Maestro: TypeScript Tools, Subflows and Maestro MCP

## Summary

Maestro and Trailblaze split the work in opposite directions. In Maestro, **the flow YAML acts and
JavaScript calculates**: scripts compute values and make HTTP calls, while taps, branches, loops and
reuse are YAML commands (`runFlow`, `when`, `repeat`, `retry`). In Trailblaze, **a TypeScript tool
does both**: it taps, reads the screen, branches, loops and calls APIs, and the trail stays a flat
list of steps. Both now expect a coding agent to write tests: Maestro through
[Maestro MCP](https://maestro.dev/mcp), Trailblaze through its CLI and `trailblaze mcp`. The
difference is what gets saved. A Maestro flow is the YAML the agent wrote. A Trailblaze trail is
natural-language steps, with a per-device recording under each step that can be re-recorded from
the prose. This entry compares the two as of October 2026. It also corrects three points from
the [April comparison](2026-04-21-maestro-scripting-and-control-flow-comparison.md) that have
since changed: how replay works, whether tools can write memory, and HTTP access.

## Side by side

| | Maestro (JavaScript + `runFlow`) | Trailblaze (TypeScript tools) |
|---|---|---|
| **Coding-agent loop** | Maestro MCP ships in the CLI. The agent inspects the screen, runs inline YAML until the journey works, then saves it as a flow file. Tools: `run` (inline YAML, files or a directory), `inspect_screen`, `take_screenshot`, `list_devices`, `cheat_sheet`, plus Maestro Cloud runs. | `trailblaze mcp`, or the CLI (`snapshot`, `tool`, `step`, `verify`, `session save`). The agent drives the device step by step and saves a trail. |
| **What is saved** | The flow YAML. The intent behind it stays in the chat that wrote it. | Prose steps, each with a recording of the tool calls per device. The recording's selectors are platform-specific, because hint text, text and accessibility text differ on each platform. |
| **Can a script drive the UI?** | No. JS returns values, and only YAML commands act on the screen. | Yes. `ctx.tools.<name>(args)` calls any tool, including other TypeScript tools. |
| **Unit of reuse** | A flow file, run with `runFlow: file:` (or inline `commands:`). | A named tool. Pure-YAML composed tools cover static sequences; trailhead and shortcut tools cover setup and navigation. |
| **Parameters** | An `env:` map of strings, read as `${NAME}`. | A TypeScript interface. The framework derives a JSON Schema from it, and a wrong tool name or argument fails `trailblaze check` before any run. |
| **Results** | Written to the global `output` object. | The tool returns a string or a typed object, and can also write `ctx.memory`. |
| **Shared state** | `output`: global, any step reads and writes it. | `ctx.memory`: each tool call's writes are buffered and saved only if that call succeeds. A nested tool that already succeeded keeps its writes, even if the outer tool fails later. |
| **Conditionals** | `when: visible / notVisible / platform / true:` on `runFlow` and other commands. | A normal `if` inside the tool. `findSelectorMatches` reads the screen without acting. Trail YAML has no `when:`, and an AI-driven run doesn't need one: the agent sees what is on screen and handles it. Only replay, which runs without the LLM, needs a conditional. When repeated runs show that something is optional, a coding agent wraps that recorded step in a conditional tool so replay checks for it each run. |
| **Loops and retry** | `repeat: times / while`, `retry: maxRetries`. | `for`, `while` and `try`/`catch` inside a tool. There is no loop or retry step in trail YAML. |
| **Waiting** | `extendedWaitUntil`, `waitForAnimationToEnd`. | `findSelectorMatches` and the assert tools take `timeoutMs`. There are also `wait`, `waitForChange` and `scrollUntilTextIsVisible`. |
| **Setup and teardown hooks** | `onFlowStart`, `onFlowComplete`. | A `trailhead:` step: one deterministic setup tool per device. Trail YAML has no completion hook. A subprocess tool can register cleanup with `ctx.session.registerCleanup`, which runs when the session ends, even after a failure. |
| **HTTP** | Built-in `http.get/post/put/delete`. | WHATWG `fetch`, which also works inside the Android test APK. |
| **Host processes and native code** | None. | `exec` runs host processes, and `runtime: subprocess` gives a tool Bun with Node APIs; both are host-only. `ctx.host.<name>()` calls Kotlin helpers wherever the script runs, including inside the Android test APK. |
| **Splitting code across files** | No `require` or npm. A script run with `runScript` (usually in `onFlowStart`) can store functions on `output`, such as `output.utils`, and later scripts call them from there. | ES imports between your own files, such as a `*_shared.ts` helper module. Node APIs need the subprocess runtime. |
| **Random and fake data** | `faker` in JavaScript, plus `inputRandom*` commands that type a random value. | `inputTextRandom` types a random value (prefix, digits, suffix; an email with `hex: true` and `suffix: "@example.com"`) and can remember it for later steps. `rememberRandomValue` makes one without typing it. Anything richer is a few lines of TypeScript in a tool. |
| **Unit tests** | None. You exercise a script by running its flow. | A `*.test.ts` file next to the tool, using a mock client and context, with no device. `trailblaze check` runs it. |
| **Editor support** | Plain JavaScript. | Generated `.d.ts` files autocomplete every tool the trailmap can call. |
| **AI** | `assertWithAI`, `extractTextWithAI` and `assertNoDefectsWithAI` run through Maestro Cloud and are optional by default. | Every tool's TSDoc is its LLM description. The agent picks tools to carry out prose steps, so one tool serves both AI runs and recorded replay. |
| **Replay** | The flow re-runs exactly as written, with no LLM. A step that breaks fails the run. | Also no LLM. The recording keeps only the top-level tool call and its arguments, and replay re-runs the script, so its branches are re-evaluated on every run. With self-heal on (opt-in, host-driven runs), a failing recorded step goes to the agent, which carries out the prose, and the new calls are saved. The in-process Android test driver replays recordings only. |
| **What a test can observe** | The screen. A script can make its own HTTP calls, but it can't see the app's traffic. | The screen, plus network calls and app event streams captured into the session where they are available. Web network capture is built in. Android needs the opt-in mitmproxy capture (emulators, API 34+). Analytics and other app events need the app or a plugin to write an event stream. The built-in in-run network assertion is web-only (`web_assertNetworkEvent`), and a trailmap can add its own. The report shows these records alongside the steps, and surveys and LLM analysis read them after the run. |
| **Reports** | `--format junit`, `html` or `html-detailed`, plus a `--test-output-dir` of screenshots, video, `commands.json` and per-flow logs. The hosted results dashboard is Maestro Cloud. | Each run is a session that exports, or that CI publishes, as one `.zip` holding its logs, screenshots, tool calls, LLM transcript and any captured network calls and events. The open-source [report viewer](https://block.github.io/trailblaze/report-viewer/) renders it in the browser: drop the file and nothing is uploaded, or share a link with `?zip=<url>` when the archive is on the viewer's own site or its host allows cross-origin reads. Several zips render as one report. `trailblaze viewer` writes your own copy of the viewer, and `trailblaze report` exports a self-contained HTML file, an animated WebP or a storyboard ([details](../reports.md#open-one-of-your-own-sessions-in-the-browser)). |
| **Secrets** | Passed as env values. | `sensitiveArgNames` masks a tool's declared top-level arguments in the tool-execution log. It doesn't cover tool results, anything a handler writes to stderr, or the arguments of a tool called directly over MCP or the CLI ([details](../scripted-tools-typed-authoring.md#keeping-a-credential-out-of-the-session-log)). Host functions never log their arguments or results. |
| **Device farms** | Needs a host running Maestro next to the device. Runs on Maestro Cloud, natively on BrowserStack, and elsewhere through workarounds such as AWS Device Farm's custom test environment. | Android trails can run inside the device as an ordinary instrumentation test, so any farm that runs instrumentation tests can run them, with no host attached. iOS and web trails run from a host. |
| **Where code runs** | Maestro's JS engine (GraalJS by default). | QuickJS in the daemon for iOS, web and host Android; QuickJS inside the test APK for on-device Android. Each call adds [about 0.2 ms](2026-09-29-typescript-tools-cost-a-fifth-of-a-millisecond.md) on the JVM host; the on-device cost hasn't been measured. |

## The same job both ways

The job: seed a cart through the API, open the app, dismiss a promo sheet if one appears, then
check that the cart shows the item.

**Maestro.** The logic is split across three files: a script, a subflow and its caller.

```javascript
// scripts/seedCart.js
const res = http.post("https://staging.example.com/carts", {
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify({ sku: SKU }),
});
output.itemName = json(res.body).items[0].name;
```

```yaml
# flows/open_seeded_cart.yaml
appId: com.example.shop
---
- runScript:
    file: ../scripts/seedCart.js
    env:
      SKU: ${SKU}
- launchApp
- runFlow:
    when:
      visible: "Not now"
    commands:
      - tapOn: "Not now"
- tapOn: "Cart"
- assertVisible: ${output.itemName}
```

```yaml
# caller
- runFlow:
    file: flows/open_seeded_cart.yaml
    env:
      SKU: "TSHIRT-M"
```

**Trailblaze.** The part a person writes is the step:

```yaml
trail:
  - step: Open a cart holding a medium T-shirt
```

The agent picks a tool for that step and records the call. One tool does the whole job:

```ts
import { trailblaze, type ToolContext, type TrailblazeNodeSelector } from "@trailblaze/scripting";

export interface OpenSeededCartArgs {
  /** SKU to put in the cart before opening it. */
  sku: string;
}

/** Seed a cart with one item through the API, open the app, and show the cart. */
export const shop_openSeededCart = trailblaze.tool<OpenSeededCartArgs>(
  { supportedPlatforms: ["android"], requiresContext: true },
  async (input, ctx) => {
    const res = await fetch("https://staging.example.com/carts", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ sku: input.sku }),
    });
    if (res.status !== 201) throw new Error(`Seeding the cart failed: HTTP ${res.status}`);
    const itemName: string = (await res.json()).items[0].name;

    await ctx.tools.launchApp({ appId: "com.example.shop", launchMode: "FORCE_RESTART" });
    if (await isVisible(ctx, "Not now")) {
      await tap(ctx, "Not now");
    }
    await tap(ctx, "Cart");
    if (!(await isVisible(ctx, itemName, 5_000))) {
      throw new Error(`The cart doesn't show "${itemName}".`);
    }

    ctx.memory.set("cartItem", itemName); // later steps can use {{cartItem}}
    return `Opened a cart holding "${itemName}".`;
  },
);

/** Matches an element whose whole text is `text`. Escaped, so API text is never read as a regex. */
function exactText(text: string): TrailblazeNodeSelector {
  const escaped = text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  return { androidAccessibility: { textRegex: `^${escaped}$` } };
}

async function tap(ctx: ToolContext, text: string): Promise<void> {
  await ctx.tools.tapOnElementBySelector({ reason: `Tap "${text}".`, nodeSelector: exactText(text) });
}

/** Without `timeoutMs`, answers from the current screen; with it, waits up to that long. */
async function isVisible(ctx: ToolContext, text: string, timeoutMs?: number): Promise<boolean> {
  const [matches] = await ctx.tools.findSelectorMatches({ selectors: [exactText(text)], timeoutMs });
  return matches.length > 0;
}
```

The Maestro version has no typed parameters, and you can only test it by running it on a device.
If the API call fails, `json(res.body)` throws an error that mentions neither the HTTP status nor
the cart. The Trailblaze version gets a test next to it that stubs `findSelectorMatches` and
checks both branches without a device.

## Where Maestro is better

- **One format for people and agents.** A person can hand-write a subflow with no types, no build
  step and no tests, and Maestro MCP has the agent write the same YAML. Trailblaze has two layers
  to learn: prose steps, and the recordings and tools under them.
- **Maestro MCP is the headline product.** It ships in the CLI, works with any MCP-capable coding
  agent, and runs flows on Maestro Cloud from the chat.
- **Loops, retries and teardown are built into the YAML.** `repeat`, `retry` and `onFlowComplete`
  need no code. Trailblaze trail YAML has none of the three.

## Where Trailblaze is better

- **The test keeps its intent.** A step says what it is for ("dismiss the promo if it appears"),
  so a broken recording can be healed or re-recorded from it. An agent-written Maestro flow keeps
  only the commands, so fixing it means re-deriving what it was meant to do.
- **Scripts can act.** Logic and UI actions live in the same function, so one tool can own a whole
  workflow. In Maestro, the same workflow is split across JS for values, YAML for actions and
  `output` to pass data between them.
- **Mistakes are caught before a run.** A misspelled tool name, a wrong argument or a missing
  field fails type-checking. Maestro finds the same mistakes partway through a run.
- **Tools have unit tests that need no device.** A mock client records every call a tool makes,
  so both sides of a branch get tested without a device.
- **Tests see more than the screen.** Network calls and app events can land in the session: web
  capture is built in, while Android capture and app events need opting in or app-side
  instrumentation. Those records give the report and post-run analysis their context, and on web a
  tool can assert that a request was made. Maestro's checks stop at what is on screen.
- **A run is one file that anyone can open, free.** A session exports as one `.zip` holding the whole run, and the
  open-source report viewer renders it in a browser with nothing uploaded, the same model as
  Playwright's trace viewer. A project needs no hosted service to look at its results: drop a
  downloaded CI archive on the viewer, or share a `?zip=` link to one published on the viewer's own
  site or on a host that allows cross-origin reads. Maestro's local output is a JUnit or HTML file
  and a folder of artifacts; its hosted results view is Maestro Cloud.
- **Any Android device farm can run it.** Running inside the device as an instrumentation test means
  no Maestro-specific farm support is needed, and no host has to sit next to the device.
- **One tool serves the AI and replay.** The description the LLM reads and the step that replays
  come from the same file.
- **Tools reach further.** `fetch` and Kotlin host functions work on an Android device. Host
  processes and a full Node runtime are available when the tool runs on the host.
- **Failed tools leave memory clean, and declared secrets are masked.** A tool call's memory writes
  are kept only when that call succeeds. Arguments named in `sensitiveArgNames` are masked in the
  tool-execution log; the table lists what that doesn't cover.

## What changed since the April comparison

- **Replay re-runs the tool, not the taps inside it.** The April entry said the recording captured
  the primitive calls a script made and replayed those. Today, only the outermost call is recorded
  (`TrailblazeAgentContext.kt`; nested `ctx.tools` calls are still logged, but marked
  non-recordable), and replay runs the script again. This lets a recorded conditional re-check its
  condition on every run. Without that, an "if the toggle is off, turn it on" step got recorded as
  a blind tap that flipped the toggle each run and failed about half the time.
- **Tools can write memory.** `ctx.memory.set` and `ctx.memory.delete` replaced the read-only API,
  and each call's writes are saved only when that call succeeds.
- **HTTP shipped as `fetch`**, in the daemon and in the Android test APK.
- **New since April:** `exec`, host functions
  ([Host Functions Are Not Tools](2026-10-04-host-functions-are-not-tools.md)), unit tests with a
  mock client, and typed tools that need no YAML file.
- **Still missing:** [`runTrail`](2026-04-21-run-trail-tool-proposal.md) has not been built.
  Trailhead tools, shortcut tools and composed tools cover most of what people use `runFlow` for.

## Open questions

- **Retry.** A tool can retry with `try`/`catch`, but there is no shared helper, so each trailmap
  writes its own. Consider adding one to the SDK, with a limit on how long retries may run.
- **Teardown.** Trails have no equivalent of `onFlowComplete`. Only subprocess tools can register
  session cleanup, so other cleanup waits for the next run's `trailhead:`. Is there a case where
  that's too late?
