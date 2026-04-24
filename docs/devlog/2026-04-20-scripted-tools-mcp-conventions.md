---
title: "Scripted Tools — MCP Extension Conventions"
type: decision
date: 2026-04-20
---

# Scripted Tools: MCP Extension Conventions

> **Phase numbering updated 2026-04-20 per the Option-2 amendment** in
> `2026-04-20-scripted-tools-toolset-consolidation.md`. PR A3 is now the single
> host-side subprocess MCP path (absorbs the former PR A4 subprocess work).
> On-device QuickJS bundle is now **PR A5**. This conventions doc is consumed
> by both — same conventions, same TS source, two deployment modes.

Shared reference for the three MCP-spec extension points scripted-tool authoring
uses to carry Trailblaze-specific semantics while staying MCP-wire-compatible.

**Design principle**: everything here lives in official MCP extension points
(`annotations`, `_meta`, `arguments`). A non-Trailblaze MCP client consuming the
same tools just ignores the `trailblaze*` fields per standard MCP unknown-field
tolerance rules. Portability in both directions: write once, run as a Trailblaze
tool AND as a stand-alone MCP server.

## 1. Tool metadata via `_meta["trailblaze/*"]`

### Problem

`TrailblazeToolClass` carries flags that don't have a direct MCP counterpart:

- `isForLlm` — should the LLM see this tool, or is it an internal primitive?
- `isRecordable` — should invocations appear in trail recordings?
- `requiresHost` — does this tool need the Trailblaze agent to be running
  in **host-agent mode** (i.e., `TrailblazeConfig.preferHostAgent=true`)?
  Set to `true` for tools that use host-only APIs (raw `fetch`, `fs`,
  `child_process`, etc., or subprocess-only sources like Python MCP
  servers). The agent-mode is orthogonal to driver capability: an
  `android-ondevice-*` driver can run with either a host-agent or an
  on-device-agent; `requiresHost: true` tools only register for the
  former.
- `supportedDrivers` — array of driver yamlKeys the tool supports (e.g.,
  `["android-ondevice-accessibility", "ios-host"]`). Matches the values
  of `TrailblazeDriverType.yamlKey`. Absent/empty = all drivers. Finer-
  grained than `supportedPlatforms` — use this when a tool is specific
  to a particular driver rather than to a platform group.
- `supportedPlatforms` — coarser grouping: array of `"IOS"` / `"ANDROID"`
  / `"WEB"`. Absent/empty = all platforms (unrestricted). Think of
  platforms as "a group of drivers" — this selects any driver whose
  `platform` matches. Mirrors the platform-first filtering pattern
  Kotlin tools use on `@TrailblazeToolClass(platforms = [...])`.
- `toolset` — pushes this tool into the named toolset. String (toolset
  id). Absent = the tool goes into the global session registry and must
  be pulled in by a toolset YAML's `tools: List<String>` field (existing
  mechanism). Present = the tool joins the named toolset at registration
  time (if the toolset exists, becomes an additional contributor;
  otherwise, Trailblaze implicitly creates a minimal toolset record).
  Single-valued for MVP; plural deferred until a concrete use case
  surfaces.
- `requiresContext` *(optional, UX-only)* — does this tool require Trailblaze's
  `_trailblazeContext` envelope to function? Used only for hiding Trailblaze-only
  tools from pure-MCP-client tool surfaces and giving the LLM a hint. Runtime
  correctness does **not** depend on this flag — the tier is declared by the
  handler's signature (see § 2 below).

### Convention

Encode these as **vendor-prefixed keys inside MCP's standard `_meta`
field on `Tool`**, using the `trailblaze/<name>` format the MCP spec
reserves for vendor extensions:

```typescript
import { Server } from "@modelcontextprotocol/sdk/server/index.js";

const server = new Server(
  { name: "my-tools", version: "1.0.0" },
  { capabilities: { tools: {} } },
);

server.registerTool(
  {
    name: "fetchUserInfo",
    description: "Fetch user info from the backend API",
    inputSchema: { /* … */ },
    annotations: {
      // Standard MCP hints stay here.
      readOnlyHint: true,
    },
    _meta: {
      // Trailblaze-specific namespace rides in _meta.
      "trailblaze/isForLlm": true,
      "trailblaze/isRecordable": true,
      "trailblaze/requiresHost": true,                                 // needs host-agent mode
      "trailblaze/supportedDrivers": ["android-ondevice-accessibility"], // driver-specific
      "trailblaze/supportedPlatforms": ["ANDROID"],                    // coarser: any Android driver
      "trailblaze/toolset": "auth",                                    // pushes this tool into the `auth` toolset
      "trailblaze/requiresContext": true,                              // optional; hides this tool under pure MCP clients
    },
  },
  async ({ userId, _trailblazeContext }) => { /* … */ },
);
```

A `@trailblaze/scripting` TypeScript types package extends MCP's `Tool`
type with a properly-typed `_meta` shape (helper `TrailblazeToolMeta`)
so authors get editor autocomplete + type errors on misspellings. Zero
runtime cost — pure types stripped at ts→js compile.

#### Why `_meta` and not `annotations`

Earlier drafts of this doc located these fields on `Tool.annotations`
instead. Implementation found that **neither public SDK accepts custom
annotation fields** — they'd be dropped or rejected before Trailblaze
ever sees them:

- **Kotlin SDK** ([tools.kt:159 @ 0.11.1](https://github.com/modelcontextprotocol/kotlin-sdk/blob/0.11.1/kotlin-sdk-core/src/commonMain/kotlin/io/modelcontextprotocol/kotlin/sdk/types/tools.kt)):
  `ToolAnnotations` is a strict `@Serializable data class` with exactly
  five fields (`title`, `readOnlyHint`, `destructiveHint`,
  `idempotentHint`, `openWorldHint`). `McpJson` runs with
  `ignoreUnknownKeys = true` ([jsonUtils.kt @ 0.11.1](https://github.com/modelcontextprotocol/kotlin-sdk/blob/0.11.1/kotlin-sdk-core/src/commonMain/kotlin/io/modelcontextprotocol/kotlin/sdk/types/jsonUtils.kt)),
  so any `trailblaze*` annotation fields round-trip the wire but are
  silently dropped on the client side before Trailblaze code can read
  them.
- **TypeScript SDK** ([schemas.ts:1241 @ `bdfd7f0`](https://github.com/modelcontextprotocol/typescript-sdk/blob/bdfd7f0/packages/core/src/types/schemas.ts#L1241)):
  `ToolAnnotationsSchema = z.object({...})` with no `.loose()` /
  `.catchall()` — the author's `registerTool` call rejects extra fields
  at the schema boundary. Publishing `annotations.trailblazeRequiresHost`
  fails Zod validation before anything is sent.

`Tool._meta` is the spec's intended vendor-extension slot and is typed
as a loose JSON object on both sides: `JsonObject?` in Kotlin (arbitrary
keys preserved), `z.optional(z.looseObject({}))` in TS (extra keys
accepted). Using the `trailblaze/` prefix matches the MCP spec's
`<vendor-prefix>/<name>` convention for `_meta` keys. Same fields, same
semantics — relocated to the slot that actually round-trips.

### Enforcement

- `_meta["trailblaze/requiresHost"]: true` → tool is skipped when the
  Trailblaze agent runs in on-device-agent mode (i.e., when
  `TrailblazeConfig.preferHostAgent=false` and the session's driver
  supports running the agent on-device). In host-agent mode the tool
  registers normally. Independent from driver capability — a driver can
  run either with a host-agent or an on-device-agent; this flag gates
  at the agent-mode level, not the driver level.
- `_meta["trailblaze/supportedDrivers"]: [...]` → registered only when
  the session's active driver's yamlKey is in the list (matches
  `TrailblazeDriverType.yamlKey`). Absent/empty = all drivers.
- `_meta["trailblaze/supportedPlatforms"]: [...]` → registered only when
  the session's active platform is in the list (`IOS` / `ANDROID` /
  `WEB`). Absent/empty = all platforms. Coarser than `supportedDrivers`;
  platforms are "groups of drivers."
- `_meta["trailblaze/toolset"]: "id"` → pushes the tool into the named
  toolset at registration time. If the toolset exists (from a toolset
  YAML, another source, etc.), the tool becomes an additional
  contributor. If it doesn't exist, Trailblaze implicitly creates a
  minimal toolset record. Authors can compose one `.ts` file's tools
  into multiple toolsets by tagging different tools with different
  values. When absent, the tool goes into the global session registry
  and must be pulled by a toolset YAML's `tools: List<String>` to be
  included in that toolset.
- `_meta["trailblaze/isForLlm"]: false` → tool is available in the
  registry but not surfaced to the LLM tool-selection prompt.
- `_meta["trailblaze/isRecordable"]: false` → invocations aren't
  serialized into trail recordings (intended for wrapper / delegating
  tools).
- `_meta["trailblaze/requiresContext"]: true` → pure metadata. Trailblaze
  does not read it (the handler signature is the runtime discriminator);
  it exists so non-Trailblaze MCP clients and LLMs can treat
  Trailblaze-only tools appropriately.

**Dynamic filtering is always also available.** Authors who want env-var
gates, feature flags, license checks, etc. can filter inside their own
`tools/list` handler — Trailblaze honors whatever the subprocess returns.
`_meta` tagging is the recommended declarative path; dynamic filtering
is the escape hatch.

Any flags absent default to the same defaults as `TrailblazeToolClass`
(all `true` except `requiresHost`, `supportedDrivers` /
`supportedPlatforms` (absent = all), `toolset` (absent = global registry
only, pull-based), and `requiresContext`, all of which default to "no
restriction" / `false` / the global-registry default as appropriate).

## 2. Execution context via `_trailblazeContext` in arguments

### Problem

Scripted tools need access to session memory (user ID, environment, stashed
values) and device info (platform, dimensions, driver type). MCP's
`CallToolRequest.params.arguments` is the only data channel the tool's handler
sees — there's no MCP notion of per-call ambient context.

### Convention

Trailblaze injects a reserved `_trailblazeContext` key into the arguments map
when invoking scripted tools:

```typescript
server.setRequestHandler(CallToolRequestSchema, async (req) => {
  const { _trailblazeContext, ...userArgs } = req.params.arguments;
  const platform = _trailblazeContext?.device.platform;  // "IOS" | "ANDROID" | "WEB"
  const userId = _trailblazeContext?.memory.userId;
  // userArgs holds only what the LLM provided
});
```

Shape:

```typescript
type TrailblazeContext = {
  memory: Record<string, unknown>;  // unknown for forward-compat with typed memory
  device: {
    platform: "IOS" | "ANDROID" | "WEB";
    widthPixels: number;
    heightPixels: number;
    driverType: string;
  };
};
```

### Why reserved key rather than ambient globals

In PR A's inline `script:` tool, memory/params live on a `globalThis.input`
global. That only works because the inline form doesn't use the MCP SDK — it's
a single `function() { ... }` body.

Once we move to MCP SDK toolsets (A3/A4), globals stop being the right shape:
the SDK owns the handler dispatch, and request data should ride on the request
itself. The `_trailblazeContext` convention keeps data flow MCP-shaped end-to-end.

### inputSchema does NOT advertise `_trailblazeContext`

The LLM never sees the underscored context key. It's injected by Trailblaze,
not populated by the LLM. The tool's declared `inputSchema` describes only
what the LLM should provide — the context envelope rides alongside, invisible
to the LLM's tool-use loop.

### Portability

A Node-deployed version of the same script running under some other MCP client
just gets `_trailblazeContext === undefined` and handles it accordingly (nullish
coalesce, defaults, etc.). Authors writing portable tools handle this with
optional access: `_trailblazeContext?.device?.platform ?? "ANDROID"`.

### Tier 1 vs. Tier 2 — declared by method signature, not a flag

The author's handler signature is the runtime discriminator for whether a tool
is portable (works under any MCP client) or Trailblaze-aware (needs the
envelope).

**Tier 1 — portable.** Handler uses only declared input params. Works
identically under Trailblaze, Claude Desktop, Cursor, any MCP client.

```typescript
server.registerTool(
  { name: "search", inputSchema: { /* …query… */ } },
  async ({ query }) => {
    return { content: [{ type: "text", text: await search(query) }], isError: false };
  },
);
```

**Tier 2 — Trailblaze-aware.** Handler destructures `_trailblazeContext` off
args; reads `device`/`memory` or calls back into `trailblaze.execute(...)`.
Under a pure MCP client the field is `undefined`; the handler handles that
path — typically a clean `isError: true` or a graceful degrade.

```typescript
server.registerTool(
  { name: "memorizeCurrentScreen", inputSchema: { /* … */ } },
  async ({ label, _trailblazeContext }) => {
    if (!_trailblazeContext) {
      return {
        content: [{ type: "text", text: "Requires Trailblaze host context." }],
        isError: true,
      };
    }
    const { device, memory } = _trailblazeContext;
    // …
  },
);
```

The `@trailblaze/scripting` TS types package types `_trailblazeContext?:
TrailblazeContext` as an optional field on the handler's args type so authors
get autocomplete + the narrowing flows through naturally.

No required tag accompanies the tier. An optional
`_meta: { "trailblaze/requiresContext": true }` is documented in § 1 for
UX purposes only.

## 3. Error signaling via `isError`, extensible via `_meta.trailblaze`

### Problem

Kotlin's `TrailblazeToolResult` is a sealed hierarchy with semantic variants:
`Success`, `Error.ExceptionThrown`, `Error.MissingRequiredArgs`,
`Error.MaestroValidationError`, `Error.FatalError`, etc. MCP's native result
shape is flatter: `{ content, isError, _meta? }` — one boolean for success vs
failure.

### Convention (minimum viable, ship now)

Use MCP's native `isError` boolean. No Trailblaze-specific extension required
to start:

```typescript
// Success
return { content: [{ type: "text", text: "User data fetched" }], isError: false };

// Error (any kind — LLM gets the message in content)
return { content: [{ type: "text", text: "API request failed: timeout" }], isError: true };
```

Trailblaze maps these to `TrailblazeToolResult` as:

- `isError: false` → `Success(message = content[0].text)`
- `isError: true` → `Error.ExceptionThrown(errorMessage = content[0].text)`
- Handler throws unhandled → SDK produces `isError: true`; same mapping.

That's the whole MVP. Authors who've never heard of Trailblaze can write
working tools using standard MCP semantics.

### Convention (rich variants, future extension)

Later, authors who want to signal specific variants (to get `FatalError`
abort-the-trail semantics, or `MissingRequiredArgs` with structured retry data)
use MCP's `_meta` field:

```typescript
import { success, fatalError, missingRequiredArgs } from "@trailblaze/scripting/result";

return fatalError("Device is disconnected");
// produces:
// {
//   content: [{ type: "text", text: "Device is disconnected" }],
//   isError: true,
//   _meta: { trailblaze: { variant: "FatalError" } }
// }
```

Trailblaze reads `_meta.trailblaze.variant` if present and constructs the
matching sealed-class instance; otherwise falls back to the MVP mapping.

`_meta` is MCP's official extensibility escape hatch for implementation-specific
metadata. It rides on responses without affecting protocol semantics, and
non-Trailblaze clients ignore unknown keys. Portability preserved.

### Why `_meta` and not a custom content type

MCP's `content` array is for LLM-readable output. The LLM should see
human-readable messages, not JSON blobs of variant discriminators. `_meta` is
the explicit implementation-side-channel — exactly the right fit.

## 4. Tool naming and global uniqueness

### Scope

This section applies to **every tool source Trailblaze registers from**:
Kotlin `@TrailblazeToolClass`, YAML-defined tools (Decision 037), and
JS/TS subprocess MCP servers (this decision). All three contribute into
one flat registry that trails and the LLM reference.

### Authoritative naming convention

Trailblaze already has a formal tool-naming convention —
[**Decision 014: Tool Naming Convention**](2026-01-14-tool-naming-convention.md).
That document is authoritative for tool-name structure across every
source. The rules in brief:

| Category | Format | Example |
| :--- | :--- | :--- |
| Universal primitive | `{verbNoun}` | `tap`, `scroll`, `inputText` |
| Platform primitive | `{platform}_{verbNoun}` | `ios_clearKeychain`, `android_pressSystemBack` |
| Org-wide | `org_{verbNoun}` | `org_mockServer` |
| Org-wide + platform | `org_{platform}_{verbNoun}` | `org_ios_configureTestUser` |
| App-specific | `{app}_{verbNoun}` | `checkout_applyCoupon`, `myapp_launchAppSignedIn` |
| App + platform | `{app}_{platform}_{verbNoun}` | `myapp_ios_scroll` |

Underscore separators (dots aren't supported in OpenAI function names);
device type (phone/tablet) is runtime context, not a name element;
versioning via `_v2` / `_v3` suffix on breaking changes. Decision 014
has the complete rules including reserved prefixes validated at build
time.

**Subprocess MCP tool names follow the same convention.** If a subprocess
advertises a tool named `myapp_ios_login`, it registers under that name.
Decision 014's reserved-prefix validation is documented but **not yet
enforced in code** — Kotlin-backed and YAML-defined tools rely on author
discipline today. When a reserved-prefix validator lands (across all
tool sources, not just subprocess), subprocess tools will pass through
the same check. Until then, "reserved prefix on a subprocess tool" is
an author-discipline concern, not a registration-time error.

### Global uniqueness and direct authoring

**Tool names are globally unique across all sources and authored
directly.** The name a source advertises — Kotlin
`@TrailblazeToolClass(name = …)`, YAML tool `id`, subprocess MCP server's
`tools/list` entry — **is** the name it registers under. No mechanical
transforms at the registration layer: no prefixing, no renaming, no
namespacing. If two contributors claim the same name, that's a
registration error; the author edits the source to resolve.

This is a deliberate choice for **deterministic, recordable, replayable
behavior**. A trail that records `- myapp_login:` must map to exactly
one handler, today and next year. Transforms at the registration layer
would be a hidden mutation between what the author advertises and what
the trail records, and would make old recordings ambiguous the day an
overlay changed. Advertised name = final name eliminates that class of
bug entirely.

Compare with the MCP-client default (Claude Desktop / Cursor) which
auto-namespaces as `server__toolname`. MCP clients adopt that convention
because they aggregate untrusted 3rd-party servers with uncoordinated
naming. Trailblaze assumes **curated, first-party authoring** — the team
running the trails owns (or consciously vets) every tool source. Authors
handle collisions by coordinating names in source per Decision 014's
category prefixes, not by layering transforms at registration.

### What this means in practice

- Authors follow Decision 014's categories and prefix their tools
  appropriately at the source. A subprocess MCP server for an app's
  login tools uses `{app}_{verbNoun}` names (e.g.,
  `myapp_logInWithEmail`); a universal primitive like `scroll` stays
  unprefixed.
- 3rd-party MCP servers with generic names (`list`, `get`, `search`)
  that would collide with reserved prefixes or in-house tools: **fork
  or wrap** the server so the advertised names match Decision 014's
  categories. Don't plug a server with collision-prone names into the
  registry and hope for the best. Trailblaze is curatorial about tool
  sources, not automatic.
- Renaming a tool is a breaking change for recordings. Decision 014's
  versioning (`_v2` / `_v3` suffix) is the documented path — version
  deliberately, migrate recordings, don't do it casually.

### Collision matrix

| Scenario | Result |
|---|---|
| Two tools from any sources share a name | Error at registration; author renames one source to resolve per Decision 014 |
| Tool moved between sources (Kotlin ↔ JS/TS) keeping its name | Trails unchanged; re-register with the new source |
| Subprocess advertises a name that violates Decision 014 (e.g., collides with a reserved prefix) | Author-discipline today (no reserved-prefix validator exists yet — same as for Kotlin tools). Will be a registration error when a validator lands across all sources. |

### Future consideration

If a concrete use case emerges for mechanical namespace shifting (e.g.,
adopting a specific 3rd-party MCP ecosystem convention), this section
could add an overlay mechanism in an additive, backward-compatible way
— Decision 014's category-based naming remains the default. **For MVP:
advertised name = registered name, no overlay.**

## Package layout

A single `@trailblaze/scripting` TypeScript package exports:

- **Types** (`@trailblaze/scripting`) — `TrailblazeTool` (extended MCP `Tool`
  with a typed `_meta` shape for the `trailblaze/*` vendor keys),
  `TrailblazeContext`, variant types.
- **Result helpers** (`@trailblaze/scripting/result`) — `success()`,
  `error()`, `fatalError()`, `missingRequiredArgs()`, etc., all producing
  MCP-compliant `CallToolResult` objects with the `_meta.trailblaze` envelope.

Authors install it as a dev-dep (types-only) or a runtime dep (if they use
the result helpers). It never runs any Trailblaze code at build-time — it's
just types + tiny pure-JS object-constructors.

## References

- [Scripted Tools Execution Model (QuickJS + Synchronous Host Bridge)](2026-04-20-scripted-tools-execution-model.md) — Decision 038 umbrella.
- [Scripted Tools PR A2 — Synchronous Tool Execution from JS](2026-04-20-scripted-tools-a2-sync-execute.md) — PR A2, whose JS result mirror uses the minimum (`isError`-only) shape.
- [Scripted Tools PR A3 — MCP SDK Subprocess Toolsets](2026-04-20-scripted-tools-mcp-subprocess.md) — PR A3 (post-Option-2 merge), consumes these conventions via bun/node subprocess.
- [Scripted Tools PR A5 — MCP Toolsets Bundled for On-Device](2026-04-20-scripted-tools-on-device-bundle.md) — PR A5 (was PR A4 pre-consolidation), consumes these conventions via on-device QuickJS bundle.
- [Scripted Tools — Toolset Consolidation & Revised Sequencing](2026-04-20-scripted-tools-toolset-consolidation.md) — consolidation + Option-2 amendments; current authoritative phase table.
- [Decision 014: Tool Naming Convention](2026-01-14-tool-naming-convention.md) — authoritative for tool-name structure (categories, reserved prefixes, versioning). [§ 4](#4-tool-naming-and-global-uniqueness) summarizes and extends to the subprocess source.
- MCP specification — `Tool.annotations`, `CallToolRequest.params.arguments`, `CallToolResult._meta` are all spec-standard extension points.
