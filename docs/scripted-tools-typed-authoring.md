---
title: Scripted Tools (TypeScript)
---

# Scripted Tools (TypeScript)

A **scripted tool** is a custom Trailblaze tool authored in TypeScript that drops into a
**trailmap** (a directory of Trailblaze configuration that groups a target app with the
tools that drive it) with no Kotlin code, no Gradle build, and no per-tool YAML
descriptor. You declare the tool's inputs (and optional structured result) as TypeScript
interfaces, write the handler against the typed `ctx.tools.<name>(args)` composition
surface, and the framework takes care of the rest — schema extraction, IDE typings, MCP
registration, dispatch.

This page is the per-tool reference. **If you're starting from zero, read
[Your First Trailmap](your-first-trailmap.md) first** — it walks an empty directory all
the way to a passing run, and that walkthrough is the natural lead-in to the material
on this page. Come back here for the per-tool authoring details once your workspace is
running.

### Vocabulary cheat sheet

You'll see these terms throughout the page; here's the cheat sheet so you can read
straight through without context-switching:

| Term | What it means |
|---|---|
| **target** | An app under test (iOS Contacts, your team's web checkout flow). |
| **trailmap** | A directory that groups one target with the tools, system prompt, and trails that drive it (`trails/config/trailmaps/<id>/`). |
| **trail** | A `.yaml` test file (`blaze.yaml` + optional `<device>.trail.yaml` recording) that exercises a target. |
| **`trailblaze` CLI** | The single binary you run. On first invocation it boots a local **daemon** that holds the device session and dispatches tools. Restart the daemon when you change a trailmap; everything else (typings, schema extraction, test runs) flows through the CLI. |
| **driver** | The framework's adapter for one platform — `ios-host` for iOS Simulator, `playwright-native` for browsers, etc. A target picks which drivers it supports under `platforms.<p>.drivers:`. |
| **host vs on-device** | "Host" is the developer machine running the daemon and CLI. "On-device" is the emulator / simulator / browser the trailmap drives. Tools default to running on-host; the `requiresHost: true` spec flag enforces that for tools that need Node-only APIs. |
| **QuickJS** | The embedded JavaScript engine the daemon uses to dispatch `.ts` tools in-process (no subprocess fork, no Node `node_modules`). Tools needing full Node-compatible APIs opt into a Bun subprocess via `requiresHost: true`. |
| **MCP** | Model Context Protocol — the wire protocol the daemon uses to advertise tools to the agent. Scripted tools register through this automatically; you never touch the protocol directly. |

See [Getting Started](getting_started.md) for install + first-device pairing.

The worked references throughout this page are the two example trailmaps in the OSS tree:

- **[`examples/ios-contacts/`](https://github.com/block/trailblaze/tree/main/examples/ios-contacts)**
  — drives Apple's built-in iOS Contacts app through 9 scripted tools (search, open,
  create, delete, verify, plus a composition example). See
  [`README`](https://github.com/block/trailblaze/blob/main/examples/ios-contacts/README.md)
  for the workspace-level walkthrough.
- **[`examples/wikipedia/`](https://github.com/block/trailblaze/tree/main/examples/wikipedia)**
  — drives live `en.wikipedia.org` through Playwright Native with 9 scripted tools
  (search, articles, language switching, banner dismissal, structure verification, plus
  a composition example). See
  [`README`](https://github.com/block/trailblaze/blob/main/examples/wikipedia/README.md)
  for the workspace-level walkthrough.

Both trailmaps ship the canonical shape this page documents: one `.ts` per tool, no
sibling YAML, typed inputs, TSDoc-as-description, composition via `ctx.tools`.

## The shape

A scripted tool is a single `.ts` file containing a `trailblaze.tool<I, O>(spec, handler)`
export. The export name IS the tool name the LLM will see. Everything the framework needs
— the tool's input schema, its result shape, its description, its platform/driver gates —
is derived from the `.ts` file itself: the type parameters, the spec object, and the TSDoc
above the binding.

The example below imports a few helpers (`SELECTORS`, `articleUrl`, `nonEmptyString`)
from `./wikipedia_shared` — that's a sibling module **you write**, not a framework
import. Every trailmap that has more than one tool ends up wanting a `*_shared.ts`
helper to keep selectors and constants out of individual tool bodies; see
[Shared helpers](#shared-helpers) below for what goes in it. The framework treats it as
a plain TypeScript module — it has no `trailblaze.tool(...)` export, so it doesn't
register as a tool.

```ts
// examples/wikipedia/trails/config/trailmaps/wikipedia/tools/wikipedia_web_openArticle.ts
import { trailblaze } from "@trailblaze/scripting";
import { SELECTORS, articleUrl, nonEmptyString } from "./wikipedia_shared";  // ← author-written helpers

export interface OpenArticleArgs {
  /** Title of the Wikipedia article to open. Defaults to "Wikipedia". */
  title?: string;
}

/**
 * Open a Wikipedia article by title. Use this whenever the task is to
 * navigate to a specific article — e.g. "open the Albert Einstein article",
 * "go to the Python article". Asserts the destination's #firstHeading is
 * visible.
 */
export const wikipedia_web_openArticle = trailblaze.tool<OpenArticleArgs>(
  { supportedPlatforms: ["web"], requiresContext: true },
  async (input, ctx) => {
    const title = nonEmptyString(input.title, "Wikipedia");

    await ctx.tools.web_navigate({ action: "GOTO", url: articleUrl(title) });
    await ctx.tools.web_verifyElementVisible({ ref: SELECTORS.firstHeading });

    return `Opened "${title}".`;
  },
);
```

That's the whole tool. There is no `wikipedia_web_openArticle.yaml`, no `inputSchema:`
block to maintain in two places, no `name:` to keep in sync. The export name is
`wikipedia_web_openArticle`; the framework registers a tool by exactly that name.

Read the full file:
[`wikipedia_web_openArticle.ts`](https://github.com/block/trailblaze/blob/main/examples/wikipedia/trails/config/trailmaps/wikipedia/tools/wikipedia_web_openArticle.ts).

### What the framework derives from this one file

| From | Becomes |
|---|---|
| The export name (`wikipedia_web_openArticle`) | The tool's dispatchable identifier; an entry on `ctx.tools` for sibling tools to compose; the name listed under `target.tools:` in `trailmap.yaml`. |
| The TSDoc above `export const` | The tool's LLM-facing description (and IDE hover text on `ctx.tools.wikipedia_web_openArticle(...)`). |
| The `<I, O>` type parameters | The tool's input JSON Schema and (when O is given) typed result shape. Per-field TSDoc on each interface property becomes that field's JSON Schema `description`. |
| The spec object (`{ supportedPlatforms, requiresContext, ... }`) | Registration gates and metadata hints carried into the runtime tool descriptor. |
| The handler body | The runtime behavior. |

### Three overloads, simplest-to-richest

The most common form is **#1 (typed input, string return)** — that's what every UI-driving
tool in the worked examples uses. The others trade pieces away or add structured returns
when the tool's contract calls for them.

```ts
// 1. Typed input, string return — the workhorse for "drive UI, report what happened".
//    This is what 90%+ of scripted tools end up looking like.
export const search = trailblaze.tool<SearchArgs>(
  { supportedPlatforms: ["web"], requiresContext: true },
  async (input, ctx) => { /* ... */ return `Searched for "${input.query}".`; },
);

// 2. Typed input AND typed result — when the tool returns structured data callers
//    consume programmatically (rare; usually you want a human-readable string).
export const getAppMetadata = trailblaze.tool<EmptyInput, AppMetadata>(
  async (_, ctx) => ({ version: "1.0.3", buildNumber: 142 }),
);

// 3. No input, string return — the trivial smoke-test form. Defaults to
//    TInput = {}, TResult = string.
export const ping = trailblaze.tool(async () => "pong");
```

The spec object is also optional — pass it as the first positional argument when the tool
needs gating (`supportedPlatforms`, `requiresContext`, etc.); omit it for a bare-handler
shape:

```ts
// Bare-handler — no spec.
trailblaze.tool<Args>(async (input, ctx) => { ... });

// With spec.
trailblaze.tool<Args>(
  { supportedPlatforms: ["ios"], requiresContext: true },
  async (input, ctx) => { ... },
);
```

The analyzer requires **named type references** for the `<I, O>` slots — inline
primitives like `<{q: string}, string>` are rejected. Declare an interface (or import
`EmptyInput` for the no-input case) and pass its name.

## Wiring a tool into a trailmap

A trailmap is the unit that groups a target with the tools that drive it. Layout:

```
my-workspace/trails/config/
├── trailblaze.yaml                          # workspace anchor
└── trailmaps/
    └── wikipedia/
        ├── trailmap.yaml                    # target manifest (display_name, platforms, tools list)
        ├── wikipedia-system-prompt.md       # optional LLM tool-selection guidance
        └── tools/
            ├── wikipedia_shared.ts          # shared helpers (selectors, label constants)
            ├── wikipedia_web_openArticle.ts # one tool per file
            ├── wikipedia_web_searchAndOpenFirstResult.ts
            └── ...
```

The trailmap manifest lists each tool by **bare export name** under `target.tools:`:

```yaml
# examples/wikipedia/trails/config/trailmaps/wikipedia/trailmap.yaml
id: wikipedia
target:
  display_name: Wikipedia (en)
  system_prompt_file: wikipedia-system-prompt.md
  tools:
    - wikipedia_web_openMainPage
    - wikipedia_web_openArticle
    - wikipedia_web_searchAndOpenFirstResult
    - wikipedia_web_verifyArticleStructure
    # … more …
  platforms:
    web:
      drivers: [playwright-native, playwright-electron]
      tool_sets:
        - web_core
        - web_verification
        - memory
```

The trailmap loader walks `tools/` for every `.ts` that exports a `trailblaze.tool(...)`
declaration; the names listed under `target.tools:` decide which of those tools are
**advertised to the agent** for this target. Files in `tools/` that are imported as
helpers (`wikipedia_shared.ts`) but don't export a tool are ignored by the loader. Tools
that DO export but aren't listed under `target.tools:` (e.g. typed-demo or
work-in-progress drafts) live in the candidate pool but stay invisible to the agent
until you add their name to the list.

See [Trailmaps](trailmaps.md) for the full manifest schema (dependencies, defaults,
toolsets, waypoints).

## Typed inputs

The input interface is the source of truth for the parameter schema. Per-field TSDoc
becomes per-field JSON Schema `description`. The analyzer also picks up JSDoc tags like
`@default` and turns them into schema defaults — the runtime fills missing fields from
those defaults before the handler sees `input`:

```ts
export interface SearchAndOpenFirstResultArgs {
  /** Query to type into the search box. */
  query?: string;
  /** Heading text to assert on the opened article. Defaults to `query`. */
  expectedHeading?: string;
  /** Submit the search form (default true). */
  openFirstResult?: boolean;
}
```

The runtime validates `args` against the derived schema before the handler runs. If the
agent sends a malformed payload (wrong type, missing required field), the dispatch fails
fast with a `ValidationError` envelope that names the offending fields — the LLM can
self-correct on the next round without crashing inside the handler.

For typed structured returns, declare a second type parameter. Both type parameters
must be **named type references** — the analyzer rejects inline type literals (e.g.
`<{title: string}, ArticleMetadata>`) in the `<I, O>` slots, so declare an interface
for the input even when it has one field:

```ts
export interface DescribeArticleArgs {
  title: string;
}

export interface ArticleMetadata {
  title: string;
  lengthBytes: number;
}

export const wikipedia_web_describeArticle = trailblaze.tool<DescribeArticleArgs, ArticleMetadata>(
  { supportedPlatforms: ["web"], requiresContext: true },
  async (input, ctx) => {
    // ... read the page ...
    return { title: input.title, lengthBytes: 12345 };
  },
);
```

Arrays as **interface fields** flow through the analyzer normally
(`filters: string[]`). Arrays as the **top-level type parameter** need a named alias
(`type ItemList = Item[]`) — the analyzer requires a named root type for the `<I, O>`
slots.

For the no-input case, import `EmptyInput`:

```ts
import { trailblaze, type EmptyInput } from "@trailblaze/scripting";

export const wikipedia_web_openMainPage = trailblaze.tool<EmptyInput>(
  { supportedPlatforms: ["web"], requiresContext: true },
  async (_, ctx) => { /* ... */ return "Opened main page."; },
);
```

## The spec object

The spec carries the framework hints that used to live under `_meta: { trailblaze/... }`
in the legacy YAML descriptor. Every field is optional:

| Field | Purpose |
|---|---|
| `supportedPlatforms?: ("web" \| "android" \| "ios" \| "desktop")[]` | Registration gate. Tool is only registered for sessions whose platform matches. Empty/omitted = all platforms. |
| `requiresContext?: boolean` | UX hint surfaced in tool catalogs — "this tool needs a live device session to be useful" (e.g. it dispatches UI tools that won't work without a connected emulator / browser). Not a registration filter; informational only. |
| `requiresHost?: boolean` | Registration gate. Skip registering on-device QuickJS runs. Use when the tool needs Node-only APIs (`node:fs`, `node:child_process`, file locks) — the on-device runtime can't reach them. |
| `supportedDrivers?: string[]` | Registration gate, finer-grained than `supportedPlatforms`. Use when a tool depends on driver-specific capabilities (e.g. `"playwright-native"` only). |

There is **no `description` field on the spec.** The tool's description lives in the
TSDoc above the `export const` binding — that's the single source of truth the analyzer
reads, and forcing it into TSDoc keeps the IDE-hover text and the LLM-facing description
identical by construction.

A tool whose spec is `{ supportedPlatforms: ["web"], requiresContext: true }` is the
canonical shape for a UI-driving web tool. Mobile UI tools use
`{ supportedPlatforms: ["ios"], requiresContext: true }` or `["android"]`. Cross-platform
helpers omit `supportedPlatforms` entirely.

## Composing other tools via `ctx.tools.<name>(args)`

The second argument to every handler is the `ToolContext`. Its `tools` namespace is a
typed Proxy that exposes every tool reachable from this trailmap:

- **Framework primitives** brought in by the trailmap's `platforms.<p>.tool_sets:`
  declarations (e.g. `web_core` → `web_navigate`, `web_click`, `web_type`).
- **Sibling scripted tools** in this trailmap's own `target.tools:` list.
- **Transitively-inherited scripted tools** that this trailmap's `dependencies:` publish
  via their own `exports:` field.

Calls are typed by the per-trailmap `trailblaze-client.d.ts` (regenerated on every
`trailblaze check` and on every daemon-aware command):

```ts
// Compose framework primitives:
await ctx.tools.web_type({ ref: SELECTORS.searchInput, text: query });
await ctx.tools.web_click({ ref: SELECTORS.searchSubmit });
await ctx.tools.web_verifyElementVisible({ ref: SELECTORS.firstHeading });

// Compose your own sibling scripted tools (cross-tool composition):
await ctx.tools.contacts_ios_searchContacts({
  query: "Albert Einstein",
  openFirstResult: true,
});
```

The proxy throws on failure — if the inner call fails, `ctx.tools.<name>(...)` throws an
`Error` you can `try`/`catch`. An unknown name is a `tsc` compile error (the typed
surface deliberately omits a generic `callTool` so authors can't bypass type-checking).

### Worked composition example

[`contacts_ios_searchAndVerify`](https://github.com/block/trailblaze/blob/main/examples/ios-contacts/trails/config/trailmaps/contacts/tools/contacts_ios_searchAndVerify.ts)
is the canonical composition demo — it doesn't dispatch any iOS primitive directly,
just delegates to two sibling scripted tools and assembles their behaviors into one
higher-level workflow:

```ts
export const contacts_ios_searchAndVerify = trailblaze.tool<SearchAndVerifyArgs>(
  { supportedPlatforms: ["ios"], requiresContext: true },
  async (input, ctx) => {
    const query = nonEmptyString(input?.query, "John Appleseed");
    const expectedName = nonEmptyString(input?.expectedName, query);
    const requireFields = filterNonEmptyStrings(input?.requireFields);

    await ctx.tools.contacts_ios_searchContacts({
      query,
      rowText: expectedName,
      openFirstResult: true,
    });

    await ctx.tools.contacts_ios_verifyContactStructure({
      name: expectedName,
      requireFields,
    });

    return requireFields.length > 0
      ? `Searched for "${query}", opened "${expectedName}", and verified fields [${requireFields.join(", ")}].`
      : `Searched for "${query}", opened "${expectedName}", and verified detail screen.`;
  },
);
```

The agent sees one tool-call worth of latency — the whole composition runs inside one
QuickJS invocation. Each sub-tool's selector knowledge, retry behavior, and assertion
shape stays in one place; the wrapper just chooses which primitives to run.

## Worked examples

### Wikipedia: a typed web tool with conditional UI

[`wikipedia_web_searchAndOpenFirstResult.ts`](https://github.com/block/trailblaze/blob/main/examples/wikipedia/trails/config/trailmaps/wikipedia/tools/wikipedia_web_searchAndOpenFirstResult.ts)
covers the common shape of an action tool that types into a form, optionally submits,
and verifies the destination. `nonEmptyString`, `ensureOn`, and `isWikipediaHostname`
below come from the trailmap's `wikipedia_shared.ts` helper module — typical helpers
for a shared module — see [Shared helpers](#shared-helpers).

```ts
export interface SearchAndOpenFirstResultArgs {
  /** Query to type into the search box. */
  query?: string;
  /** Heading text to assert on the opened article. Defaults to `query`. */
  expectedHeading?: string;
  /** Submit the search form (default true). */
  openFirstResult?: boolean;
}

/**
 * Search Wikipedia from the header search box. Use this whenever the task
 * is to search Wikipedia for something — e.g. "search for Albert Einstein",
 * "look up Python on Wikipedia", "find articles about Mount Everest"...
 */
export const wikipedia_web_searchAndOpenFirstResult = trailblaze.tool<SearchAndOpenFirstResultArgs>(
  { supportedPlatforms: ["web"], requiresContext: true },
  async (input, ctx) => {
    const query = nonEmptyString(input.query, "Trailblazer");
    const expectedHeading = nonEmptyString(input.expectedHeading, query);
    const openFirstResult = input.openFirstResult !== false;

    await ensureOn(ctx, isWikipediaHostname, WIKIPEDIA_MAIN_PAGE);

    await ctx.tools.web_type({ ref: SELECTORS.searchInput, text: query });

    if (!openFirstResult) {
      return `Typed "${query}" into Wikipedia search and stopped (no submit).`;
    }

    await ctx.tools.web_click({ ref: SELECTORS.searchSubmit });
    await ctx.tools.web_verifyElementVisible({ ref: SELECTORS.firstHeading });
    await ctx.tools.web_verifyTextVisible({ text: expectedHeading });

    return `Searched for "${query}" and verified result heading "${expectedHeading}".`;
  },
);
```

### iOS Contacts: a typed mobile tool with structured branching

[`contacts_ios_verifyContactStructure.ts`](https://github.com/block/trailblaze/blob/main/examples/ios-contacts/trails/config/trailmaps/contacts/tools/contacts_ios_verifyContactStructure.ts)
illustrates a verification tool whose contract depends on which fields the caller
demands:

```ts
export interface VerifyContactStructureArgs {
  /** Contact name to assert in the navbar / heading. */
  name?: string;
  /**
   * Optional list of additional field labels the contact must surface — common
   * values: "phone", "mobile", "email", "home", "work". Empty list (the
   * default) skips the field-presence assertions.
   */
  requireFields?: string[];
}

/**
 * Verify the currently-open iOS contact detail screen conforms to an expected
 * shape — name heading visible, plus an optional list of required field
 * labels ("phone", "email", "home", etc.)...
 */
export const contacts_ios_verifyContactStructure = trailblaze.tool<VerifyContactStructureArgs>(
  { supportedPlatforms: ["ios"], requiresContext: true },
  async (input, ctx) => {
    const name = nonEmptyString(input?.name, DEFAULT_NAME);
    const requireFields = filterNonEmptyStrings(input?.requireFields);

    await ctx.tools.assertVisibleWithAccessibilityText({ accessibilityText: name });

    if (requireFields.length === 0) {
      return `Verified contact "${name}" detail screen rendered.`;
    }

    const missing: string[] = [];
    for (const field of requireFields) {
      if (!(await textIsVisible(ctx, field))) {
        missing.push(field);
      }
    }
    if (missing.length > 0) {
      throw new Error(
        `contacts_ios_verifyContactStructure: contact "${name}" missing fields: ${missing.join(", ")}.`,
      );
    }
    return `Verified contact "${name}" with fields [${requireFields.join(", ")}].`;
  },
);
```

### Shared helpers

Every trailmap with more than one tool ends up wanting a shared helper module — a
single place to keep label constants, selector definitions, and small validators. Both
example trailmaps follow the same convention:

- iOS Contacts:
  [`contacts_ios_shared.ts`](https://github.com/block/trailblaze/blob/main/examples/ios-contacts/trails/config/trailmaps/contacts/tools/contacts_ios_shared.ts)
  — exports a `LABELS` frozen object with every accessibility label, plus
  `ensureContactsRoot`, `textIsVisible`, `nonEmptyString`, `filterNonEmptyStrings`.
- Wikipedia:
  [`wikipedia_shared.ts`](https://github.com/block/trailblaze/blob/main/examples/wikipedia/trails/config/trailmaps/wikipedia/tools/wikipedia_shared.ts)
  — exports a `SELECTORS` frozen object with every CSS/ARIA selector, plus
  `ensureOn`, `elementIsVisible`, `articleUrl`, and friends.

Helpers take `ctx: ToolContext` directly (no `client` argument) and live in
`*_shared.ts` so the trailmap loader skips them at registration time (they have no
`trailblaze.tool(...)` export).

## Tool descriptions: what the LLM actually sees

The TSDoc on each exported `const` is the **only** way the LLM learns what your tool
does. Two non-obvious rules:

- **Don't write "USE THIS TOOL FOR X."** The LLM picks tools by matching the prompt
  against the description's prose. Telling it to "use" the tool reduces the description
  to a single keyword and loses the surrounding context. Describe what the tool *does*
  and include the task patterns it matches — "Search Wikipedia for X / look up Y on
  Wikipedia / find articles about Z" — not "USE THIS WHEN SEARCHING."
- **Match real user phrasing.** If a trail says "open Albert Einstein's contact" but the
  tool description only mentions "navigate to a contact's detail screen," the LLM may
  not connect them. Include the synonyms (open, view, navigate to, look up) that real
  prompts will use.

The same care applies to per-field TSDoc. A `defaults to "John"` note in the field's
docstring becomes the LLM's hint that the field is optional and what the implicit
default looks like.

Per-target **system prompts** complement tool descriptions for the cases where the
agent needs a nudge to prefer your scripted tool over an inline expansion to raw
primitives. Set the path on `target.system_prompt_file:` in `trailmap.yaml` — examples:
[`wikipedia-system-prompt.md`](https://github.com/block/trailblaze/blob/main/examples/wikipedia/trails/config/trailmaps/wikipedia/wikipedia-system-prompt.md),
[`contacts-ios-system-prompt.md`](https://github.com/block/trailblaze/blob/main/examples/ios-contacts/trails/config/trailmaps/contacts/contacts-ios-system-prompt.md).

## IDE typings — `trailblaze check`

Trailblaze emits per-trailmap typings so `ctx.tools.<name>(args)` autocompletes in your
editor with no hand-authored config. Run once after cloning (or after a `trailmap.yaml`
edit):

```bash
trailblaze check
```

That command:

1. Resolves the trailmap graph and emits per-target rolled-up YAML at
   `trails/config/dist/targets/<id>.yaml`.
2. Vendors the workspace SDK at `<workspace>/.trailblaze/sdk/dist/index.d.ts` — a single
   rolled-up `.d.ts` that `tsc` resolves through the per-trailmap tsconfig.
3. Emits per-trailmap typed bindings at `<trailmap>/tools/trailblaze-client.d.ts` —
   exhaustive types for every tool the runtime knows about (framework primitives,
   trailmap-local scripted tools, transitively-inherited dependencies' `exports:`).
4. Writes framework-managed `<trailmap>/tools/tsconfig.json` + `<trailmap>/.gitignore` so
   the editor picks up the typings with no manual setup.

After this, hover any `ctx.tools.<name>` call in `tools/*.ts` — the IDE shows the typed
signature and the original TSDoc. Mistype a name or pass the wrong arg shape and `tsc`
flags it at compile time.

The daemon also fires this pipeline on every aware command (`trailblaze step` / `ask` /
`verify` / `trail` / `session` / `app start`) — on a fresh clone it primes everything
automatically; on subsequent runs a content hash makes it a sub-millisecond no-op.

**No `bun install` required.** The SDK is delivered as a single rolled-up `.d.ts`
resolved through path mapping, not through `node_modules`. Per-trailmap `package.json`
files are no longer needed for type-checking — the entire scaffolding is framework-managed.

## Testing your tool

Pair every `.ts` tool with a sibling `<name>.test.ts` file and tests run through the
mock client + mock context from `@trailblaze/scripting/testing` — no daemon, no device,
no MCP roundtrip:

```bash
trailblaze check                  # materialize + tsc + bun test
trailblaze check --no-typecheck   # tests only, skip tsc
```

The mock helpers satisfy the handler signature:

- **`createMockClient()`** — returns a client whose `tools` proxy records every
  `ctx.tools.<name>(args)` call into `client.calls`. Tests assert call order + arg
  shapes against that array. `client.stub(toolName, { textContent, errorMessage })`
  registers a canned response — a non-empty `errorMessage` makes the call throw with
  production's wording, which exercises `try/catch` recovery branches.
- **`createMockContext({ platform, sessionId?, target?, memory? })`** — returns a
  context with a no-op logger and sensible test defaults.

Worked composition test from iOS Contacts —
[`contacts_ios_searchAndVerify.test.ts`](https://github.com/block/trailblaze/blob/main/examples/ios-contacts/trails/config/trailmaps/contacts/tools/contacts_ios_searchAndVerify.test.ts):

```ts
import { describe, expect, test } from "bun:test";
import { createMockClient, createMockContext } from "@trailblaze/scripting/testing";

import { contacts_ios_searchAndVerify } from "./contacts_ios_searchAndVerify";

describe("contacts_ios_searchAndVerify", () => {
  test("dispatches searchContacts then verifyContactStructure with the forwarded args", async () => {
    const client = createMockClient();
    const ctx = createMockContext({ platform: "ios" });

    await contacts_ios_searchAndVerify(
      { query: "Apple Inc.", requireFields: ["phone", "email"] },
      ctx,
      client,
    );

    // Two cross-tool dispatches, in order. Neither sub-tool is unrolled —
    // each sub-tool has its own dedicated test file.
    expect(client.calls.map((c) => c.tool)).toEqual([
      "contacts_ios_searchContacts",
      "contacts_ios_verifyContactStructure",
    ]);
    expect(client.calls[0]?.args).toMatchObject({
      query: "Apple Inc.",
      rowText: "Apple Inc.",
      openFirstResult: true,
    });
  });
});
```

Other patterns worth copying live in the same `tools/` directory:

- [`contacts_ios_searchContacts.test.ts`](https://github.com/block/trailblaze/blob/main/examples/ios-contacts/trails/config/trailmaps/contacts/tools/contacts_ios_searchContacts.test.ts)
  — single-tool sequence asserting call order + arg shapes + the "No Results"
  conditional branch.
- [`contacts_ios_verifyContactStructure.test.ts`](https://github.com/block/trailblaze/blob/main/examples/ios-contacts/trails/config/trailmaps/contacts/tools/contacts_ios_verifyContactStructure.test.ts)
  — per-field probe loop + `client.stub` for fault injection.

**Preconditions.** `bun` on PATH (install from https://bun.sh) and a one-time
`trailblaze check` so `tools/tsconfig.json` exists; the CLI's pre-flight surfaces a
directed error pointing back to `trailblaze check` if the file is missing.

## Runtime: QuickJS in-process by default

`.ts` files dispatch through the daemon's in-process QuickJS runtime — no subprocess
fork, sub-millisecond invocation, no `node_modules` resolution. The SDK ships curated
runtime globals (`URL`, `fetch`, `AbortController`, `console`); Node-flavored built-ins
(`node:fs`, `node:child_process`, `node:os`) are not present.

If your tool needs full Node-compatible APIs — `node:fs`, persistent state, native
modules — opt into the **host subprocess** runtime by setting `requiresHost: true` on
the spec. The framework spawns a Bun subprocess for that tool's invocations; the rest of
your trailmap stays in-process.

```ts
export const myapp_writeArtifact = trailblaze.tool<WriteArtifactArgs>(
  { requiresHost: true },
  async (input, ctx) => {
    const fs = await import("node:fs/promises");
    await fs.writeFile(input.path, input.body);
    return `Wrote ${input.path}.`;
  },
);
```

`requiresHost: true` also hides the tool from on-device sessions — the on-device runner
skips registration entirely. Composition with other tools still works (the proxy routes
through the same daemon), so a host-only tool can call into in-process tools and vice
versa.

## When you still need a sibling YAML

The canonical shape is `.ts`-only. A sibling `<name>.yaml` is still supported in a
handful of escape-hatch cases:

- **Legacy `export async function` tools.** Trailmaps authored before the typed surface
  landed pair each `.ts` with a full descriptor YAML (`name:`, `description:`,
  `inputSchema:`). They keep working unchanged; see
  [Scripted Tools — Legacy Reference](scripted_tools.md) for the schema. Migrate when
  convenient.
- **Multi-tool files.** A `.ts` that exports multiple `trailblaze.tool(...)`
  declarations needs at most one YAML per export to register multiple names against the
  same source file. Most authors keep one tool per file and don't hit this.
- **Build-time bundler interop.** Trailmaps consumed by the Gradle plugin's
  `bundleTrailblazeTrailmap` task (rare — used today by the bundled `clock` trailmap)
  currently need a full descriptor YAML alongside the typed `.ts`. The daemon-time path
  reads the analyzer directly and doesn't need the YAML; the build-time bundler will
  catch up in a follow-up.

If you don't recognize yourself in those three cases, you don't need a YAML — write the
`.ts` and you're done.

## Common errors

| Error | What's wrong |
|---|---|
| `Tool not registered: foo (registered tools: alpha, beta)` | The tool wasn't in the runtime registry when the dispatch fired. Either you didn't list it under `target.tools:` in `trailmap.yaml`, or the daemon hasn't been restarted since you added the file. Re-run `trailblaze check` and restart the daemon. |
| `ValidationError: tool 'foo' received invalid arguments — /query: must be string` | The agent sent a payload that doesn't match the interface. Often a sign the TSDoc on a field needs sharper guidance, or that a field marked required should be optional. The LLM self-corrects on the next round. |
| `esbuild failed (exit 1) bundling scripted-tool source /path/to/foo.ts` | Syntax error or unresolved import in your `.ts`. The full esbuild stderr follows in the same message. |
| `Trailmap '<id>': target.tools: listed '<path>.tool.yaml', but .tool.yaml files are pure-YAML composed tools that auto-discover...` | You put a pure-YAML tool path under `target.tools:`. That list is for scripted tools only. Drop the entry; the YAML tool auto-discovers. See [Trailmaps → Tool flavors](trailmaps.md#tool-flavors-which-kind-do-i-write). |
| `Scripted tool 'X' must export a function with that exact name. Found: undefined.` | The export name doesn't match what `target.tools:` is asking for. Either rename the export or update the manifest entry. |
| `client.tools.<X>` is a `tsc` error in the IDE | The per-trailmap `trailblaze-client.d.ts` is stale or missing. Run `trailblaze check` — the codegen rewrites bindings against the current registry. |

## Where to go next

- **[Your First Trailmap](your-first-trailmap.md)** — workspace-level walkthrough from
  empty directory to running tool.
- **[Trailmaps](trailmaps.md)** — manifest schema, dependencies + defaults, tool
  flavors, discovery + precedence.
- **[`examples/ios-contacts/README`](https://github.com/block/trailblaze/blob/main/examples/ios-contacts/README.md)**
  / **[`examples/wikipedia/README`](https://github.com/block/trailblaze/blob/main/examples/wikipedia/README.md)**
  — the worked references this page draws from, with their own quick-starts and CI
  notes.
- **[Publishing a Trailmap](publishing-a-trailmap.md)** — when you want to share your
  trailmap with other teams as a vendored bundle or an npm package.
- **[`@trailblaze/scripting` Authoring Vision](devlog/2026-04-22-scripting-sdk-authoring-vision.md)**
  — the conceptual background on why scripted tools exist and how they fit into the
  agent loop.
