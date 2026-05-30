# Wikipedia Example — a canonical Trailblaze target trailmap for web

A worked end-to-end example of using Trailblaze to test a real web
application. Drives **live `en.wikipedia.org`** through the Playwright
Native driver. The trailmap ships:

- **9 scripted tools** (in TypeScript) covering search, language switching,
  random article, main-page section verification, banner dismissal, article
  structure assertions, plus a composition example.
- **28 trails** under `trails/wikipedia/` exercising those tools
  + the built-in `web_*` toolset, with `tags` for selective runs and one
  `skip:` example for opting out gracefully.
- **A target-scoped system prompt** that teaches the LLM when to reach for
  the scripted tools instead of inline-expanding to `web_*` primitives.
- **CLI-first runtime** — every trail runs via `trailblaze run …`, the
  same path an end user takes. There is no JUnit harness.

If you're building a target trailmap for your own site, this is the reference
shape to copy.

## Why use Trailblaze for web instead of raw Playwright?

You're already using Playwright under the hood. Trailblaze adds two things:

1. **Natural-language trails.** A test like "search Wikipedia for Albert
   Einstein and verify the article rendered" stays readable to a PM, QA, or
   support engineer who doesn't write Playwright. The LLM resolves it
   against the live DOM each run.
2. **Per-target scripted tools.** When you DO want determinism for hot
   paths, you write a TypeScript tool once, advertise its task pattern to
   the LLM via the system prompt, and let trails compose it by name. The
   selector knowledge stays in one place; the trail just describes intent.

In practice you'll write **both**: NL trails for breadth, scripted tools for
the 5-10 workflows you run every build.

---

## Quick start (first green test in 60 seconds)

**Prerequisites**

- The **`trailblaze` CLI** on your `PATH`. See
  [Getting Started](../../docs/getting_started.md) for the install steps. Paths
  in the commands below are relative to wherever you cloned this example tree —
  the simplest setup is to clone [`block/trailblaze`](https://github.com/block/trailblaze)
  and `cd` to the repo root.
- **The Playwright Native driver** ships with Trailblaze — no separate `npm install` /
  `npx playwright install` step needed for this example; the first run downloads the
  Chromium build into the workspace.
- **Network access to `en.wikipedia.org`.** This trailmap drives the live site;
  proxy + corporate firewall users should confirm reachability first.

> **Run all commands below from the OSS repo root** (the parent of `examples/` and
> `trails/`). The `$PWD/examples/...` path and the `trails/wikipedia/...` invocation
> both anchor there.

```bash
# 1. From the repo root, point the daemon at this trailmap's config dir.
#    This is what registers the wikipedia_web_* tools when the daemon boots.
export TRAILBLAZE_CONFIG_DIR=$PWD/examples/wikipedia/trails/config

# 2. Stop any existing daemon (so it re-reads the config dir) and start
#    fresh. Source builds rebuild here automatically.
trailblaze app --stop
trailblaze app --headless & disown

# 3. Confirm the scripted tools registered. You should see 9 entries:
trailblaze toolbox --device web --target wikipedia --search wikipedia_

# 4. Run a single trail end-to-end.
trailblaze run trails/wikipedia/test-search-einstein \
  --device web/playwright-native
```

The first run takes ~10s (browser launch + 2 LLM rounds). Expect
`Results: 1 passed, 0 failed`. If you got that, you're set up. Skip to
**Anatomy of a tool** to see how the wiring works.

**The `--device` string.** Step 3 uses `web` (the platform); step 4 uses
`web/playwright-native` (platform + driver). The shorter form picks the
default driver for the platform; the longer form pins one driver — useful
in CI / scripts where you want determinism. `trailblaze device list`
shows the full set of `<platform>/<driver>` pairs reachable from your
machine.

**If the run hangs or reports `Daemon unreachable after 30 consecutive poll
failures`**, see [Known issues](#known-issues) — it's a known CLI flake on
long LLM rounds. The daemon itself is fine; re-run the trail.

---

## Anatomy of a scripted tool

Every scripted tool is a single `.ts` file under
`trails/config/trailmaps/wikipedia/tools/` — no sibling YAML, no separate
descriptor to maintain. The trailmap loader auto-discovers every `.ts`
that exports a `trailblaze.tool(...)` declaration; the names listed under
`target.tools:` in `trailmap.yaml` decide which of those are advertised to
the agent. Here's `wikipedia_web_openArticle` broken into the parts that
matter. (`@trailblaze/scripting` is the SDK the daemon synthesizes into the
workspace at `trails/.trailblaze/sdk/` — no `npm install` needed.)

```ts
// wikipedia_web_openArticle.ts
import { trailblaze } from "@trailblaze/scripting";
import { SELECTORS, articleUrl, nonEmptyString } from "./wikipedia_shared";

export interface OpenArticleArgs {
  /** Title of the Wikipedia article to open. Defaults to "Wikipedia". */
  title?: string;
}

/**
 * Open a Wikipedia article by title. Use this whenever the task is to
 * navigate to a specific article — e.g. "open the Albert Einstein
 * article". Asserts the destination's #firstHeading is visible.
 */
export const wikipedia_web_openArticle = trailblaze.tool<OpenArticleArgs>(
  { supportedPlatforms: ["web"], requiresContext: true },  // ← spec object: gates + hints
  async (input, ctx) => {                                  // ← typed handler
    const title = nonEmptyString(input.title, "Wikipedia");

    await ctx.tools.web_navigate({ action: "GOTO", url: articleUrl(title) });
    await ctx.tools.web_verifyElementVisible({ ref: SELECTORS.firstHeading });

    return `Opened "${title}".`;       // ← string return surfaces to the LLM as the tool result
  },
);
```

What the framework derives from this one file:

| From | Becomes |
|---|---|
| Export name `wikipedia_web_openArticle` | The dispatchable tool name + an entry on `ctx.tools` for sibling tools. |
| TSDoc above `export const` | The LLM-facing description. |
| The `<OpenArticleArgs>` type parameter | The tool's input JSON Schema (with per-field TSDoc carried through). |
| The spec object `{ supportedPlatforms, requiresContext }` | Runtime registration gates and metadata hints. |
| The handler body | The runtime behavior. |

Key conventions to copy:

1. **Selectors live in `wikipedia_shared.ts`** as a frozen `SELECTORS`
   constant — never inlined in tool bodies. If Wikipedia changes the
   `#firstHeading` id tomorrow, you change one constant, not 9 files.
2. **Helpers are shared too** — `nonEmptyString`, `tryOrFalse`,
   `elementIsVisible`, `ensureOn`. Every tool imports from
   `./wikipedia_shared`.
3. **All dispatch goes through `ctx.tools.X(args)`** — typesafe;
   `tsc` flags typos and bad arg shapes at compile time. The exported
   `ToolContext` type deliberately omits a generic `callTool`, so
   unknown tool names surface as compile errors, not runtime "tool not
   registered" failures.
4. **Return a short human-readable string.** The LLM sees this as the
   tool result; keep it informative ("Opened X" beats "ok"). For typed
   structured returns, declare a second type parameter:
   `trailblaze.tool<MyInput, MyOutput>(spec, handler)`.
5. **Throw on failure.** Don't swallow — the framework treats a thrown
   `Error` as the tool failing, which is what you want for the agent to
   see and react to.

---

## Recipe: add your own scripted tool

One file, then restart the daemon. Concrete steps:

```bash
TOOL=wikipedia_web_myNewTool
TRAILMAP=examples/wikipedia/trails/config/trailmaps/wikipedia

# 1. Body — what the tool does. The TSDoc on the exported `const` is what
#    the LLM reads as the tool's description; the spec object's
#    `supportedPlatforms` / `requiresContext` flags carry runtime hints.
cat > $TRAILMAP/tools/${TOOL}.ts <<'TS'
import { trailblaze } from "@trailblaze/scripting";

export interface MyNewToolArgs {
  /** <describe> */
  someArg?: string;
}

/**
 * <One paragraph in plain English, including the task patterns the LLM
 * should match against. NO "USE THIS TOOL" — describe what it DOES.>
 */
export const wikipedia_web_myNewTool = trailblaze.tool<MyNewToolArgs>(
  { supportedPlatforms: ["web"], requiresContext: true },
  async (input, ctx) => {
    // ... do work via ctx.tools.X(args) ...
    return "Did the thing.";
  },
);
TS

# 2. Register in trailmap.yaml — add `wikipedia_web_myNewTool` under target.tools.
$EDITOR $TRAILMAP/trailmap.yaml

# 3. Restart the daemon so the new tool is discovered + bindings regenerate.
trailblaze app --stop && trailblaze app --headless & disown

# 4. Confirm registration + open the .ts file in your IDE; `ctx.tools.`
#    autocomplete should now include your new tool.
trailblaze toolbox --device web --target wikipedia --search myNewTool
```

For reference, here's what `trailmap.yaml`'s `target.tools:` list looks like — bare
export names, one per line:

```yaml
# trails/config/trailmaps/wikipedia/trailmap.yaml (excerpt)
id: wikipedia
target:
  display_name: Wikipedia (en)
  system_prompt_file: wikipedia-system-prompt.md
  tools:
    - wikipedia_web_openMainPage
    - wikipedia_web_searchAndOpenFirstResult
    - wikipedia_web_myNewTool        # ← your new tool, by export name
    # ... others ...
  platforms:
    web:
      drivers: [playwright-native, playwright-electron]
      tool_sets: [web_core, web_verification, memory]
```

That's the whole loop. If autocomplete doesn't pick up the new tool, run
`trailblaze check --workspace examples/wikipedia/trails/config`
manually — that's the per-trailmap `trailblaze-client.d.ts` regeneration step the daemon
fires on restart.

### Tool-description discipline (load-bearing)

The TSDoc on each exported `const` is read by the LLM at session start
as the **only** way it learns what your tool does. Two non-obvious rules:

- **Don't say "USE THIS TOOL FOR X".** The LLM picks tools by matching its
  understanding of the task against the description. Telling it to "use"
  the tool reduces the description to a single keyword and loses the
  surrounding context. Describe what the tool *does* and include the task
  patterns it matches — "Search Wikipedia for X / look up Y on Wikipedia
  / find articles about Z" — not "USE THIS WHEN SEARCHING".
- **Match real user phrasing.** If a trail says "search Wikipedia for
  Python" but the tool description only mentions "query the search
  endpoint", the LLM may not connect them. Include the synonyms (search,
  look up, find articles about, etc.) that real prompts will use.

You can sanity-check picking by running a trail in `--verbose` mode and
watching which tool the agent chose for each step.

---

## What a trail actually looks like

A trail directory's `blaze.yaml` is the source of truth — a natural-language
step the agent resolves against the live page. Here's
[`test-search-einstein/blaze.yaml`](../../trails/wikipedia/test-search-einstein/blaze.yaml)
verbatim:

```yaml
- config:
    title: "Wikipedia: Search for Albert Einstein"
    platform: web
    driver: PLAYWRIGHT_NATIVE
    target: wikipedia
    tags: [smoke, search]
- prompts:
    - step: Search Wikipedia for "Albert Einstein" and verify the resulting article page shows the heading "Albert Einstein".
```

The agent sees the trailmap's `target.tools:` (which includes the scripted
`wikipedia_web_searchAndOpenFirstResult` and `wikipedia_web_verifyArticleStructure`)
and the system prompt at `wikipedia-system-prompt.md` nudges it toward those
tools when a step matches their task patterns. Run it:

```bash
trailblaze run trails/wikipedia/test-search-einstein --device web/playwright-native
```

After a passing run, the CLI auto-saves a fresh `<device>.trail.yaml`
alongside the `blaze.yaml` — that's the recording artifact you commit for
deterministic replay. **One caveat for this example specifically:**
scripted-tool calls (`wikipedia_web_*`) currently don't dispatch from a
saved `web.trail.yaml` recording — the Playwright agent rejects them at
replay (tracked under [Known issues](#known-issues)). 5/28 trails here
carry recordings — the ones that exercise pure `web_*` built-ins; the
rest stay NL.

## Recording vs natural-language: when to use which

Each trail directory has a `blaze.yaml` (the source of truth). If a
`web.trail.yaml` is also present, the CLI replays it deterministically
instead of going through the LLM. **Use the decision tree:**

| Property of the trail                                  | NL (`blaze.yaml` only) | Recorded (`+ web.trail.yaml`) |
|--------------------------------------------------------|------------------------|-------------------------------|
| Should pass on any reasonable Wikipedia state          | ✅ preferred           | risky — recording fixes one DOM |
| You want zero LLM cost on every run                    | ❌ ~$0.02-0.05/run     | ✅ free after recording      |
| The DOM under test changes daily (e.g. featured content)| ✅ stays robust       | ❌ will drift                |
| You need stable CI signal across hundreds of runs       | depends on flakiness  | ✅ if the selectors are stable|
| The trail composes scripted (`wikipedia_web_*`) tools  | ✅ works fine          | ⚠ see "known issues" below   |

The 5 recorded trails in this repo are the ones that hit structural
anchors stable across days (`test-article-shakespeare`,
`test-language-switch-spanish`, the 3 main-page section trails).

**To re-record a trail**: delete its `web.trail.yaml` and re-run the
`blaze.yaml`. The CLI auto-saves a fresh recording next to the source on
a passing run.

---

## Filtering trails with `tags`

Every trail's `config:` block carries a `tags:` list. `--tags` filters by them.

```bash
# Smoke pass (fast, deterministic, must-pass on every build):
trailblaze run trails/wikipedia --device web --tags smoke

# Anything in the i18n or article suites:
trailblaze run trails/wikipedia --device web --tags i18n,article

# Long-tail nightly:
trailblaze run trails/wikipedia --device web --tags slow
```

Conventions used in this example:

| Tag           | Meaning |
|---------------|---------|
| `smoke`       | Fast, deterministic, must-pass on every run. |
| `main-page`   | Asserts something about `wiki/Main_Page`. |
| `nav`         | Exercises in-page navigation primitives (Random article, hamburger menu). |
| `search`      | Drives the header search box. |
| `article`     | Loads a specific article and asserts its shape. |
| `i18n`        | Crosses language subdomains. |
| `slow`        | Multi-step assertions; skip during local iteration. |
| `flaky`       | Known to be timing-sensitive; usually paired with a `skip:` reason. |

### Skipping a trail with a written reason

A trail's `config:` block can carry `skip: "reason..."` to opt out of every
run until the reason is removed. Better than commenting trails out or
maintaining a separate exclusion list — the reason is committed alongside
the trail and shows up in `--verbose` output.

```yaml
- config:
    title: "Wikipedia: Autocomplete suggestions visible while typing search"
    tags: [search, flaky]
    skip: "Autocomplete popup is timing-sensitive; remove `skip:` once the suggestion-list probe lands."
- prompts:
    - step: …
```

To run a skipped trail anyway, delete its `skip:` line (or set it to an
empty string). The CLI treats blank values as "not skipped".

---

## Pattern catalog — when to look at which trail

A few trails are specifically chosen as teaching examples. Read these
when you're authoring new tools or trails for your own target:

| Pattern | Trail | What it shows |
|---------|-------|---------------|
| Simple NL → built-in `web_*` tools | `test-main-page-loads` | Smallest possible trail; agent uses `web_*` directly. |
| NL → scripted tool (LLM picks it) | `test-search-einstein` | System prompt nudges the agent toward `searchAndOpenFirstResult` without naming it. |
| Direct scripted-tool dispatch | `test-custom-search` | Trail prompts name the tool explicitly + pass typed args. Maximum determinism short of a recording. |
| Composition (tool calls tool) | `test-search-multi-topic` | Exercises `wikipedia_web_searchAndVerify`, which internally calls two other scripted tools. |
| Data-driven shape | `test-search-multi-topic` | Same workflow across multiple queries — copy-paste the prompt block + change the data. |
| Recorded deterministic replay | `test-article-shakespeare` | `blaze.yaml` + `web.trail.yaml` — replay path skips the LLM entirely. |
| Conditional UI (banner present?) | `test-main-page-featured-article` | Exercises `dismissBannerIfPresent`, which no-ops cleanly when no banner is shown. |
| Branch coverage on a feature flag | `test-article-short-no-refs` + `test-article-references-section` | Pair covers both branches of `verifyArticleStructure`'s `requireReferences` flag. |
| Graceful skip | `test-search-autocomplete` | Shows the `skip: "reason..."` config field. |

---

## IDE autocomplete on `input` / `ctx`

Trailmap tools are TypeScript only — the bundler rejects `.js` / `.mjs` / `.cjs`
sources so authors can't fall back to a subprocess runtime that would lose
the typesafe contract.

```bash
trailblaze check --workspace examples/wikipedia/trails/config
```

That writes two artifacts (both gitignored as derived output):

- **Workspace SDK** at `trails/.trailblaze/sdk/` — the `@trailblaze/scripting`
  source the per-trailmap `tsconfig.json` resolves through path mapping. Ships
  curated runtime globals (`URL`, `fetch`, `AbortController`, `console`).
- **Per-trailmap `trailblaze-client.d.ts`** at `<trailmap>/tools/trailblaze-client.d.ts` —
  exhaustive bindings for every tool the runtime registry knows about
  (framework built-ins + trailmap-local scripted tools + Kotlin tools surfaced
  through `tool_sets:` declared in `trailmap.yaml`).

Open any `.ts` file in `tools/` and hover `input` / `ctx` to confirm the
types resolve. Mistype a tool name or pass the wrong arg shape and `tsc`
flags it at compile time.

To run the bundled `tsc` against your trailmap:

```bash
trailblaze check wikipedia
```

---

## Testing your tools

Pair every `.ts` tool with a sibling `*.test.ts` file and run them through the mock
client + mock context from `@trailblaze/scripting/testing`. Tests execute without a
daemon or a device:

```bash
trailblaze check wikipedia
```

(Per-trailmap unit tests run as the third phase of `trailblaze check`, after materialize +
tsc.)

This trailmap ships scripted tools but no `.test.ts` files yet — for working examples of
single-tool sequences, multi-step workflows, and `client.stub(...)` recovery-branch
tests, see the [playwright-native trailmap's `.test.ts`
samples](../playwright-native/trails/config/trailmaps/playwrightSample/tools/) and the
canonical authoring tutorial's
[Testing your tool](../../docs/scripted_tools.md#testing-your-tool) section.

---

## Runtime: QuickJS (in-process)

`.ts` files route through the daemon's QuickJS in-process runtime. No
subprocess fork, no Node APIs available beyond the curated globals the SDK
ships. Sub-millisecond dispatch; every tool call goes through
`ctx.tools.X(args)`.

---

## Troubleshooting

**Daemon won't start** — check `trailblaze app --status`, then look at
`~/.trailblaze/daemon.log` for the last few hundred lines. The `daemon-<pid>.pid`
file under `~/.trailblaze/` is the canonical location; stale entries from
previous crashes are safe to delete.

**Tool not registering / "tool not found at dispatch"** —

1. Confirm the file is named exactly `<toolName>.ts` under `tools/` and
   exports a `trailblaze.tool(...)` declaration whose export name matches.
2. Confirm the bare tool name is listed under `target.tools:` in
   `trailmap.yaml`. Names omitted from that list are loaded by the bundler but
   not advertised to the agent for this target.
3. Restart the daemon after editing anything under
   `trails/config/trailmaps/<trailmap>/`. Trailmaps are discovered at boot.

**IDE shows red squiggles on `ctx.tools.X(args)`** — run
`trailblaze check --workspace <path-to-your-config>` to regenerate the per-trailmap
`trailblaze-client.d.ts`. If that fails, check `trailblaze app --status` — the
daemon writes the SDK + `trailblaze-client.d.ts` on every restart.

**"Daemon unreachable after 30 consecutive poll failures"** — known CLI
flake on long agent rounds. See **Known issues** below.

**Tool calls that worked live now fail on replay** — `wikipedia_web_*`
scripted tools currently don't dispatch from a `web.trail.yaml` recording.
See **Known issues** below.

---

## Known issues

These are tracked framework gaps. The example works around them today; the
canonical shape will improve when these land.

- **Scripted-tool replay-dispatch gap.** When a `web.trail.yaml` recording
  captures a call to `wikipedia_web_*`, the Playwright agent rejects it at
  replay as `OtherTrailblazeTool` (the dispatcher only handles `web_*`
  built-ins). Until fixed, only trails that exercise pure `web_*` tools
  get useful recordings. That's why 23/28 trails here stay NL-only.
- **CLI poll-timeout on long LLM rounds.** `trailblaze run …` can
  return `FAILED: Daemon unreachable after 30 consecutive poll failures`
  while the daemon is still healthy and the session is making progress.
  The session artifacts under `logs/<session>/` are still written; you
  can replay or inspect them after. Workaround: re-run; not deterministic.

---

## Layout reference

```
examples/wikipedia/
├── README.md                                 ← you are here
├── build.gradle.kts                          ← only runs `trailblaze.bundle`
└── trails/config/
    ├── trailblaze.yaml                       ← workspace anchor
    └── trailmaps/wikipedia/
        ├── trailmap.yaml                         ← target manifest
        ├── wikipedia-system-prompt.md        ← LLM tool-selection guidance
        └── tools/                            ← scripted tool sources
            ├── tsconfig.json                 ← framework-managed (extends workspace base)
            ├── trailblaze-client.d.ts        ← framework-managed (typed `ctx.tools` bindings)
            ├── wikipedia_shared.ts           ← helpers reused by every tool
            └── wikipedia_web_*.ts            ← one .ts per scripted tool

trails/wikipedia/
├── test-main-page-*/                         ← main-page sections + smoke
├── test-search-*/                            ← search flows (NL + scripted)
├── test-article-*/                           ← article-shape assertions
├── test-language-*/                          ← i18n / language switcher
├── test-random-article/                      ← Random article navigation
├── test-back-navigation/                     ← Browser-history nav
├── test-footer-privacy-link/                 ← Footer scrolling assertion
├── test-wiki-logo-visible/                   ← Header logo presence
└── test-custom-*/                            ← typed scripted-tool composition
```

## CI

CI runs the same commands a developer does — there's no separate harness.
The benchmark pipeline scripts under `scripts/.../runway/` set up the
daemon, fan out via `trailblaze run`, and summarize results. Look at
those if you're wiring a similar trailmap into your own CI.
