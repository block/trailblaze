---
title: Typed Authoring for Scripted Tools
---

# Typed Authoring for Scripted Tools

This page describes the **typed authoring surface** for scripted tools — a
TypeScript-first declaration pattern that lets you express a tool's inputs and result
shape as plain TypeScript interfaces. The authoring surface is stable; the daemon-time
runtime registration, dispatch path, and per-trailmap `client.d.ts` codegen are all wired.

This is the recommended authoring shape for new scripted tools. The older
full-YAML + `export async function` shape is still supported — documented as the
[Legacy YAML + `export async function` Reference](scripted_tools.md) for trailmaps
that haven't been migrated yet — but the typed surface in this page **supersedes**
it for new tools. It expresses everything the legacy YAML descriptor used to,
directly in TypeScript, while keeping the YAML descriptor as a tiny `_meta:`-carrying
anchor.

## The shape

A typed-author tool is a single `.ts` declaration that names the inputs and result shape.
This is the canonical example from the `ios-contacts` worked-example trailmap:

```ts
import { trailblaze } from "@trailblaze/scripting";
import { ensureContactsRoot, nonEmptyString, textIsVisible } from "./contacts_ios_shared";

const DEFAULT_QUERY = "John";

export interface SearchContactsArgs {
  /** Query to type into the contacts list's pull-down search field. */
  query?: string;
  /**
   * Row text the tool taps after typing the query. Defaults to `query`. Pass
   * this explicitly when the query is a partial prefix of the row's visible
   * label (e.g. `query="alb"` + `rowText="Albert Einstein"`).
   */
  rowText?: string;
  /**
   * When true (default), taps the first visible matching row to open the
   * contact's detail screen.
   */
  openFirstResult?: boolean;
}

/**
 * Search the iOS Contacts list for a name and (optionally) open the first
 * matching contact. Use this whenever the task is to search Contacts for a
 * person, look up a contact by name, find someone in Contacts, or jump to a
 * known contact's detail screen.
 */
export const contacts_ios_searchContacts = trailblaze.tool<SearchContactsArgs>(
  async (input, ctx) => {
    const query = nonEmptyString(input?.query, DEFAULT_QUERY);
    const rowText = nonEmptyString(input?.rowText, query);
    const openFirstResult = input?.openFirstResult !== false;

    await ensureContactsRoot(ctx);
    await ctx.tools.swipe({ direction: "DOWN" });
    await ctx.tools.tapOnElementWithText({ text: "Search" });
    await ctx.tools.inputText({ text: query });

    if (!openFirstResult) {
      return `Typed "${query}" into Contacts search and stopped (no result tapped).`;
    }
    if (await textIsVisible(ctx, "No Results")) {
      throw new Error(
        `contacts_ios_searchContacts: query "${query}" returned no results.`,
      );
    }
    await ctx.tools.tapOnElementWithText({ text: rowText });
    return `Searched for "${query}" and opened the row matching "${rowText}".`;
  },
);
```

Consumers — whether a sibling tool in the same trailmap, or another trailmap that has imported
the per-trailmap generated `client.d.ts` — see a fully typed entry on the
`tools.<name>(args)` namespace. The namespace surfaces on both authoring contexts: as
`ctx.tools.<name>(args)` inside a typed handler (the modern composition surface, shown
below), and as `client.tools.<name>(args)` on a raw `TrailblazeClient` for legacy or
direct-API callers. The two are aliases for the same Proxy — pick whichever your
handler's signature gives you.

```ts
const message = await ctx.tools.contacts_ios_searchContacts({
  query: "Albert Einstein",
  openFirstResult: true,
});
// `message` is typed as `string` — the analyzer derived it from the tool's <I, O> shape.
```

The `<I, O>` type parameters are the source of truth: the input interface becomes the
tool's parameter schema, the output type becomes the tool's typed result, and the
exported `const`'s TSDoc becomes the registered description.

The analyzer requires **named type references** for both type parameters — inline
primitives (`<MyInput, string>`) are rejected. When the return is a plain string
message, omit the second type argument and rely on the default (`string`). The
worked example above uses `<SearchContactsArgs>` for exactly that reason.

## What this looks like in practice

The `examples/ios-contacts/trails/config/trailmaps/contacts/tools/` directory (under this
OSS tree) is the worked-example directory referenced throughout this page. Every tool in
that trailmap — `contacts_ios_openApp`, `contacts_ios_openContact`, `contacts_ios_searchContacts`,
`contacts_ios_createContact`, `contacts_ios_deleteContact`,
`contacts_ios_dismissKeyboardIfPresent`, `contacts_ios_addPhoneNumber`,
`contacts_ios_verifyContactStructure`, `contacts_ios_searchAndVerify` — is authored
against this typed surface, paired with a sibling YAML and a shared helper module
(`contacts_ios_shared.ts`) that takes `ctx: ToolContext` directly.

The ios-contacts trailmap ships in **mode 2** (full YAML + typed `.ts`) rather than the
lighter mode-3 (meta-only YAML + typed `.ts`). Reason: this trailmap is built by the
Gradle plugin's `bundleTrailblazeTrailmap` task, which doesn't yet admit meta-only
descriptors (see "What's not wired yet" below). Once the build-time bundler is wired
into the analyzer, the descriptors collapse to `script:` + `_meta:` only; the `.ts`
half doesn't change. Each YAML in the trailmap carries a short comment pointing at this
constraint so a future cleanup PR has the breadcrumb.

When in doubt about how a particular shape (typed input + message return, no-input + typed
return, composition tool that delegates to sibling tools, etc.) translates to the typed
surface, read the corresponding `contacts_ios_*` tool — they cover the full spread.

## Common shapes

Both type parameters default — `TInput = Record<string, never>` (no args), `TResult = string`
(message return) — so the lightest possible authoring shape is just:

```ts
import { trailblaze } from "@trailblaze/scripting";

export const ping = trailblaze.tool(async () => "pong");
```

For typed input but a message return (the most common shape for action tools that drive
the UI and report what they did), omit only the second type argument:

```ts
import { trailblaze } from "@trailblaze/scripting";

interface SearchInput {
  query: string;
}

export const search = trailblaze.tool<SearchInput>(async (input, ctx) => {
  await ctx.tools.tapOnElementWithText({ text: "Search" });
  await ctx.tools.inputText({ text: input.query });
  return `Searched for "${input.query}".`;
});
```

The full `<MyInput, MyOutput>` form is reserved for tools that return structured data.
The analyzer applies the same defaults the SDK does — 0 type args → empty input schema
+ `result: string`; 1 type arg → typed input + `result: string`; 2 type args → both
typed.

### Typed result, no input

> **Migration note.** Prior guidance recommended importing `EmptyInput` from
> `@trailblaze/scripting` as the canonical no-input marker. The analyzer can't yet
> resolve cross-module type-only imports back to a named root type, so declare a
> local empty interface instead (pattern shown below). The `EmptyInput` export still
> type-checks at the SDK boundary — only the analyzer-side schema lookup misses it.
> This limitation will lift in a future analyzer revision.

TypeScript generic defaults are positional, so an author who wants a typed result with
**no** input can't skip the first type argument — it has to be spelled. The analyzer
currently requires every type-parameter reference to resolve against a type declared
in the same file (cross-module type-only imports aren't yet followed), so for now
declare a local empty interface and pass it explicitly:

```ts
import { trailblaze } from "@trailblaze/scripting";

interface ReadAppMetadataInput { /* intentionally empty */ }

interface AppMetadata {
  version: string;
  buildNumber: number;
}

export const readAppMetadata = trailblaze.tool<ReadAppMetadataInput, AppMetadata>(
  async (_, ctx) => {
    // ...read from device, return structured value
    return { version: "1.0.3", buildNumber: 142 };
  },
);
```

The SDK exports an `EmptyInput` marker (= `Record<string, never>`) for the same
shape; it's accepted at the type-check boundary today but the analyzer's
`ts-json-schema-generator` can't resolve cross-module imports back to a named root
type. A future analyzer revision will follow imports; until then, declaring the
empty interface locally is the supported pattern.

When the result is also a plain string (no input, string return), drop both type
parameters entirely:

```ts
export const contacts_ios_dismissKeyboardIfPresent = trailblaze.tool(
  async (_input, ctx) => {
    // ...
    return "Dismissed iOS keyboard via Cancel chip.";
  },
);
```

The `contacts_ios_dismissKeyboardIfPresent` tool in the worked-example trailmap uses
exactly this shape.

### Arrays as input or output

Inline `T[]` in a type parameter slot is rejected — the analyzer requires named type
references for the top-level slots. Wrap arrays in a type alias and the analyzer's
`ts-json-schema-generator` walks them as the expected JSON Schema:

```ts
interface Item {
  id: string;
  label: string;
}

// Named alias is the required idiom for a top-level array slot:
type ItemList = Item[];

export const listItems = trailblaze.tool<EmptyInput, ItemList>(async () => [/* ... */]);
```

Arrays as **fields inside an interface** don't need the alias dance — they're walked
through whatever surrounding interface holds them:

```ts
interface SearchInput {
  query: string;
  filters: string[];  // array as a property — no alias needed
}
```

Top-level array **inputs** technically work via the same named-alias pattern but
generally aren't useful: MCP tool arguments arrive as a named-property object on the
wire (`tools/call → arguments: { ... }`), so a top-level array would have nothing to
key on. Prefer wrapping in an object with a named field.

## TSDoc convention

TSDoc on the exported `const` becomes the tool's registered description. TSDoc on each
input field becomes that field's JSON Schema `description`. Write them the way you'd
write any other JSDoc:

```ts
/**
 * Search the corporate directory for a user by employee ID.
 */
interface FindUserInput {
  /** Six-digit employee ID. Leading zeros required. */
  userId: string;

  /** Whether to include archived (former-employee) records. Defaults to `false`. */
  includeArchived?: boolean;
}
```

These comments flow through the analyzer to:

- the tool's registered `description` (surfaced to the LLM in tool-call prompting), and
- the per-trailmap `client.d.ts` as JSDoc on the corresponding `TrailblazeToolMap` entry,
  so a consumer trailmap hovering over `ctx.tools.findUser(...)` sees the original prose.

## What's wired

- The `trailblaze.tool<I, O>(handler)` authoring surface. At runtime the helper returns
  a 3-arg adapter `(args, ctx, client) => Promise<TResult>` that bridges the legacy
  synthesized wrapper's call shape onto the author's typed `(input, ToolContext)`
  handler. The bare-function form is the ONLY accepted shape — the SDK does not
  maintain a transitional `{ handler }` spec object.
- The `TrailblazeToolMap` entry shape — every entry is now
  `{ args: <ArgsShape>; result: <ResultShape> }`, with the `client.tools.<name>(args)`
  namespace deriving both halves automatically.
- **Per-trailmap `client.d.ts` emitter consumes the static analyzer.** At daemon startup
  (and `trailblaze compile`), every trailmap's `tools/` directory is walked by
  `ScriptedToolDefinitionAnalyzer`. Each `export const X = trailblaze.tool<I, O>(handler)`
  declaration is matched against the trailmap's runtime-registered scripted tools, and the
  analyzer's JSON Schemas for `I` and `O` are serialized to TypeScript type literals
  that drop into the emitted `client.d.ts` verbatim. Per-field TSDoc round-trips intact.
- **Typed handlers dispatch through the existing wrapper.** A `.ts` file authored as
  `export const X = trailblaze.tool<I, O>(async (input, ctx) => ...)` registers and
  dispatches identically to the legacy `export async function X(args, ctx, client)`
  shape — the adapter that `trailblaze.tool<I, O>` returns is itself a 3-arg function,
  and the synthesized wrapper from `DaemonScriptedToolBundler` invokes it with
  `(args, ctx, client)` exactly as before. Inside the adapter, `ctx.tools` is built
  from `client.tools` so the author's typed handler composes other tools through the
  same `tools.<name>(args)` Proxy as legacy authors.
- **Workspace SDK ships a runtime `dist/index.js`.** Per-trailmap tsconfigs resolve the
  `paths` stem `dist/index` — bun loads the `.js` for runtime, tsc loads the
  `.d.ts` for type-checking. The two-bundle consolidation (single runtime artifact
  shared between QuickJS and host-subprocess paths) is a tracked follow-up.
- Analyzer subprocess behavior is tunable via environment variables on
  `ScriptedToolDefinitionAnalyzer` (`TRAILBLAZE_SDK_DIR`, `TRAILBLAZE_SDK_PACKAGE`,
  `TRAILBLAZE_TOOL_ANALYZER_TIMEOUT_SECONDS`) — see the **Scripted-tool analyzer
  configuration** table in the repo root `CLAUDE.md` for the full set.
- The analyzer's degradation contract is preserved: a daemon that can't resolve `bun`
  on PATH (or whose SDK directory hasn't run `bun install`) still emits
  per-trailmap `client.d.ts` files — just without the typed `result` upgrades. Tools whose
  authoring shape is the legacy `export async function foo(...)` keep emitting via the
  existing YAML `inputSchema:` decomposition with `result: string`.

## Two authoring modes

Going forward, scripted tools can be authored in one of three shapes — pick the one
that matches what you want to express:

1. **Legacy `export async function` + full YAML descriptor.** Author writes
   `export async function foo(args, ctx, client)` and a sibling `<name>.yaml`
   declaring `name:`, `inputSchema:`, `description:`. No type-surface upgrade; the
   YAML is the source of truth. Continues to work unchanged for trailmaps that haven't
   been migrated.

2. **Typed `trailblaze.tool<I, O>(handler)` + full YAML descriptor.** Author writes
   `export const foo = trailblaze.tool<I, O>(async (input, ctx) => ...)` and pairs it
   with a sibling `<name>.yaml` declaring `name:`, `inputSchema:`, `description:`. The
   runtime registers from the YAML; the per-trailmap `client.d.ts` upgrades the typed
   surface from the analyzer. Use this when you want the typed authoring ergonomics
   today but also need the Gradle plugin's build-time `.d.ts` emission to pick the
   tool up (the build-time bundler doesn't yet read the analyzer).

3. **Typed `trailblaze.tool<I, O>(handler)` + meta-only YAML (recommended for
   daemon-runtime authoring; gated by build-time bundler support).** Author writes the
   typed `.ts` declaration; the YAML carries only `script:` and `_meta:`. The runtime
   registers via analyzer enrichment — `name:`, `inputSchema:`, and `description:` all
   flow from the `.ts`'s typed declaration + TSDoc. The JVM host paths
   (`WorkspaceCompileBootstrap`, `AppTargetDiscovery`, `CompileCommand`,
   `TrailblazeHostYamlRunner`) wire the enrichment automatically when `bun` +
   `ts-json-schema-generator` are available.

   Trailmaps that go through the Gradle plugin's `bundleTrailblazeTrailmap` task (e.g. the
   `ios-contacts` worked-example trailmap) must stay on mode 2 today — the build-time
   bundler doesn't yet read analyzer enrichment, so a meta-only descriptor fails the
   "scripted tool name 'X' referenced in target.tools: but no descriptor with that
   name was discovered" check. Mode 3 is daemon-runtime-only until the bundler is
   wired (deferred follow-up, see "What's not wired yet" below).

In modes 2 and 3, the analyzer's TSDoc on the exported `const` wins over any
YAML-derived description when both are present, because the TS source is canonical.

**The authoring surface is dispatch-path-agnostic.** All three modes work for both
in-process QuickJS tools (the default, `requiresHost: false`) AND sub-process host-only
tools (`requiresHost: true`, used when a tool needs Node-only APIs like `node:fs`). The
typed `trailblaze.tool<I, O>(handler)` shape is not coupled to a runtime — it's just an
authoring convenience. The runtime decision is per-tool, made via the `_meta:` overlay,
and is invisible to the typed handler itself.

## Migrating an existing tool

The mechanical conversion below documents the *target* shape. Existing tools authored
against the legacy `export async function` shape can be flipped to the typed shape
file-by-file. The `ios-contacts` example trailmap went through this migration; treat its
tools as the worked references.

Before:

```ts
import type { TrailblazeClient, TrailblazeContext } from "@trailblaze/scripting";
import { ensureContactsRoot, nonEmptyString, requireSessionContext } from "./contacts_ios_shared";

export interface SearchContactsArgs { /* ... */ }

export async function contacts_ios_searchContacts(
  args: SearchContactsArgs,
  ctx: TrailblazeContext | undefined,
  client: TrailblazeClient,
): Promise<string> {
  requireSessionContext(ctx);
  // ... body uses client.tools.X(...)
}
```

After:

```ts
import { trailblaze } from "@trailblaze/scripting";
import { ensureContactsRoot, nonEmptyString } from "./contacts_ios_shared";

export interface SearchContactsArgs { /* unchanged */ }

export const contacts_ios_searchContacts = trailblaze.tool<SearchContactsArgs>(
  async (input, ctx) => {
    // requireSessionContext drops out — the typed `ToolContext` is always provided.
    await ensureContactsRoot(ctx);
    await ctx.tools.swipe({ direction: "DOWN" });
    // ... body uses ctx.tools.X(...)
  },
);
```

Three other mechanical changes go with the rewrite:

- **Shared helpers**: refactor the trailmap's shared-helper module so helpers accept
  `ctx: ToolContext` directly. Drop any `requireSessionContext` guard — the typed
  surface guarantees `ctx`. `contacts_ios_shared.ts` is the worked example.

- **YAML descriptor**: this is where the decision tree forks.
  - **If your trailmap does NOT go through the Gradle `bundleTrailblazeTrailmap` task** (most
    workspace trailmaps — the daemon discovers them directly): collapse the sibling YAML
    to `script:` + `_meta:` only. The analyzer derives `name:`, `description:`, and
    `inputSchema:` from the `.ts` declaration.

    ```yaml
    # Mode 3 — meta-only:
    script: ./contacts_ios_searchContacts.ts
    _meta:
      trailblaze/supportedPlatforms: [ios]
      trailblaze/requiresContext: true
    ```

  - **If your trailmap DOES go through `bundleTrailblazeTrailmap`** (the `ios-contacts`
    worked-example trailmap is the canonical case — its `build.gradle.kts` declares the
    bundler task): keep the full YAML (`name:`, `description:`, `inputSchema:`)
    alongside the typed `.ts`. The build-time bundler doesn't yet read analyzer
    enrichment, so a meta-only descriptor fails the "no descriptor with that name
    was discovered" check at build time. The `.ts` still drives the typed surface
    that the per-trailmap `client.d.ts` codegen emits. Once the bundler is wired
    (deferred), trailmaps in this category can collapse to mode 3.

- **Unit tests**: legacy test files that drive the tool via
  `myTool(args, ctx, client)` continue to work — the adapter returned by
  `trailblaze.tool<I, O>` is still a 3-arg callable, and its internal forwarding to
  `(input, ToolContext)` is transparent to callers.

## What's *not* wired yet

- **Build-time codegen for meta-only YAML.** Three of the four `SISTER-IMPL-TAG:
  trailmap-scripted-tool-discovery` sites (`DaemonScriptedToolBundler`,
  `TrailblazeTrailmapBundler`, `TrailblazeBundledConfigTasks`) don't yet admit meta-only
  descriptors. The runtime loader (`TrailblazeProjectConfigLoader`) does, and the
  daemon-time bundler reads pre-resolved `InlineScriptToolConfig`s from the loader so
  it picks up the enrichment for free. Build-time codegen — Gradle `.d.ts` generation
  and bundled-config YAML emission — currently fails on meta-only descriptors; until
  it's wired, mode-3 authoring is daemon-runtime-only. Mode 2 (full YAML + typed `.ts`)
  remains the workaround for trailmaps that need build-time emission.

- **Selector grammar codegen.** Selector shapes used inside tool inputs (the
  `androidAccessibility` / `iosHost` selector grammars) aren't yet emitted as
  importable TypeScript types — they're surfaced today as opaque `Record<string,
  unknown>` slots on the tool's `args` shape. Tracked separately on the selector-grammar
  codegen track.

- **Ajv runtime validation.** Strict validation of inputs against the codegen-emitted
  schema is deferred. Today's runtime relies on the YAML-declared schema for arg
  shape, which Kotlin tooling validates on dispatch. When this lands, the analyzer's
  schema becomes the wire-side validation source too.

- **`requires: "quickjs"` strict mode.** A future opt-in mode where authors mark a tool
  as runtime-strict (must dispatch in-process QuickJS, fail loudly on host-subprocess
  fallback) is on the roadmap. Today the runtime falls back transparently between the
  two engines based on `requiresHost:`; strict mode would harden the contract for tools
  that have engine-specific assumptions.

- **Two-bundle consolidation.** The runtime ships two SDK bundles today —
  `trailblaze-sdk-bundle.js` (IIFE for QuickJS) and `dist/index.js` (ESM-ish for
  host-subprocess). A future PR will consolidate to a single runtime artifact shared
  between both engines. The authoring-side type surface (`dist/index.d.ts`) is already
  the single source of typing.

- **Existing Kotlin-defined tools** continue to use `@TrailblazeToolClass` as before.
  The typed authoring surface is purely about the TS side; nothing about the Kotlin
  tool registration changes.

## How this lands incrementally

The typed surface and codegen landed first; mechanical trailmap migrations follow as each
trailmap adopts the typed shape. Look for devlogs under `devlog/` directory tagged with
`scripting-sdk` or `typed-authoring` for the current plan and progress notes. The
[Trail YAML unified-format devlog](devlog/2026-05-22-trail-yaml-unified-syntax.md) covers
adjacent work moving the framework toward a more declarative authoring story.

Authoring against the typed surface today is forward-compatible with each follow-up as
it lands — you won't need to revisit existing typed declarations to pick up the
build-time codegen, strict-mode dispatch, or ajv validation once those PRs ship.
