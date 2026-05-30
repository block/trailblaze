# trailblaze-scripting

**In-process JS (QuickJS).** Embeds QuickJS inside the Trailblaze JVM and evaluates
JavaScript source synchronously. No subprocess, no IPC, no MCP — the script runs
in the same process as the agent and can call back into Trailblaze tools directly
via a host-injected `trailblaze.execute(name, params)` binding.

This is the sibling runtime to `:trailblaze-scripting-subprocess`. Pick between
them based on execution model:

| | `:trailblaze-scripting` | `:trailblaze-scripting-subprocess` |
|---|---|---|
| Execution | In-process QuickJS | Out-of-process bun/node subprocess |
| Transport | Direct Kotlin ↔ JS binding | MCP JSON-RPC over stdio |
| JS runtime | `dokar3/quickjs-kt` | Real `@modelcontextprotocol/sdk` |
| Host requirement | Just the JVM classpath | `bun` or `node`+`tsx` on `PATH` |
| Can run on-device | Yes (planned — PR A5 bundle path) | No (can't spawn subprocesses) |

## What's here today

- `TrailblazeScriptEngine` — the QuickJS host. Evaluates a JS source string, exposes
  an `input` global (memory + params), and optionally a `trailblaze.execute()`
  callback that dispatches synchronously back into Kotlin.
- `ScriptTrailblazeTool` — implements the legacy `script: { source: "..." }`
  inline-scripting tool from Decision 038 PRs A / A2. Marked
  `isRecordable = false`: the expanded primitive tool calls record, not the
  wrapper, so recorded trails replay without a JS engine.

## Status

The inline `script: { source }` shape is being deprecated in favor of the
`tools:` declarative path on trailmap manifests, where each named tool resolves to
a sibling `<trailmap>/tools/<name>.yaml` descriptor. Runtime is selected per
descriptor: `runtime: subprocess` (or a `.js`/`.mjs`/`.cjs` entrypoint) routes
through `:trailblaze-scripting-subprocess` for full Node APIs; the default
in-process runtime is QuickJS via `:trailblaze-quickjs-tools`. `requiresHost:
true` is a separate on-device visibility gate, not a runtime selector.

## References

- `docs/devlog/2026-04-20-scripted-tools-execution-model.md` — Decision 038.
- `docs/devlog/2026-04-20-scripted-tools-toolset-consolidation.md` — Option-2
  amendment that moved the recommended authoring path to the subprocess module.
- `docs/devlog/2026-04-20-scripted-tools-a2-sync-execute.md` — the
  `trailblaze.execute()` callback design.
