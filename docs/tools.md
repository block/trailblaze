---
title: Trailblaze Tools
---

# Trailblaze Tools

Trailblaze ships a catalog of framework tools (`tap`, `inputText`, `pressKey`,
`assertVisible`, …) that cover most UI automation. When your app or test surface needs
something the catalog doesn't have, you add **custom tools** to a workspace trailmap.

There are two flavors of custom tool:

1. **Scripted (TypeScript) tool** — the recommended path for almost everyone. Declare
   inputs and result shape as TypeScript interfaces and write the handler against the
   typed `ctx.tools.<name>(args)` surface. One `.ts` file per tool; no per-tool YAML
   descriptor. This covers everything from a thin wrapper ("press back twice") to a
   command with branching, retries, or async coordination. Every tool in the trailmap's
   `tools/` folder is on its target — no `trailmap.yaml` entry needed; add a name to the
   trailmap's `exports:` to share it with trailmaps that depend on it.

   Start with [Scripted Tools (TypeScript)](scripted-tools-typed-authoring.md) for the
   canonical authoring shape and the worked iOS Contacts + Wikipedia examples; see
   [Trailmaps](trailmaps.md) for the manifest wiring and the per-suffix file
   conventions.

2. **Kotlin-backed tool** (`@TrailblazeToolClass`-annotated class + `*.tool.yaml` in
   `class:` mode) — advanced / host-side state only. Use when you need to read host-side
   state that the scripting SDK doesn't expose today (a host
   process handle, a JVM-only library, a private internal API). Two files: the Kotlin
   class lives in your own Kotlin source tree; a sibling `*.tool.yaml` descriptor
   registers the FQN under the framework's tool registry.

   ```kotlin
   @Serializable
   @TrailblazeToolClass("web_navigate")
   @LLMDescription("Navigate the browser to a URL, or go back/forward in browser history.")
   class PlaywrightNativeNavigateTool(
     @param:LLMDescription("GOTO navigates to a URL, BACK/FORWARD moves through browser history.")
     val action: NavigationAction = NavigationAction.GOTO,
     @param:LLMDescription("The URL to navigate to. Required when action is GOTO.")
     val url: String = "",
     override val reasoning: String? = null,
   ) : PlaywrightExecutableTool, ReasoningTrailblazeTool {
     override suspend fun executeWithPlaywright(
       page: Page,
       context: TrailblazeToolExecutionContext,
     ): TrailblazeToolResult { /* ... */ }
   }
   ```

   ```yaml
   # trails/config/trailmaps/<id>/tools/web_navigate.tool.yaml  (on the classpath alongside the class)
   id: web_navigate
   class: xyz.block.trailblaze.playwright.tools.PlaywrightNativeNavigateTool
   ```

   `id:` must match the `@TrailblazeToolClass` name — that's the dispatch identifier the
   agent and the trailmap loader use. The `@LLMDescription` annotations on the class and
   each parameter become the agent-facing schema (description + parameter docs); the
   executable interface (`PlaywrightExecutableTool`, an Android equivalent, etc.) is
   where the host-side work happens. Framework tools live under their owning
   `trails/config/trailmaps/<id>/tools/` directory — e.g. the `PlaywrightNative*Tool`
   classes under `trailblaze-playwright/src/main/java/xyz/block/trailblaze/playwright/tools/`
   ship their `*.tool.yaml` descriptors at `trails/config/trailmaps/web/tools/`. Workspace
   authors can also drop a `*.tool.yaml` at `<workspace>/trails/config/tools/` to override
   or extend the framework set by id.

If you're not sure which flavor fits, default to the scripted TypeScript path. Falling
back to Kotlin is straightforward once you've hit a case where the typed authoring
surface doesn't reach.

A `*.tool.yaml` with a `tools:` list of other tool calls (the retired YAML-composed
flavor) no longer loads; write it as a TypeScript tool instead.

## Branching in a recorded trail: `runIf`

A recording replays a fixed list of tool calls, so a step like "dismiss the welcome popup if it
appears" would replay as an unconditional tap, which fails on runs where the popup doesn't show.
Wrap such steps in `runIf`. It is recorded as written and re-checks its condition on every replay:

```yaml
- step: Dismiss the welcome popup if it appears
  recording:
    android-phone:
      - runIf:
          condition:
            selector:
              androidAccessibility:
                textRegex: Not now
          then:
            - tapOnElementBySelector:
                nodeSelector:
                  androidAccessibility:
                    textRegex: Not now
```

- `condition` is either `{ selector: ... }`, which is true when the selector matches something on
  screen, or `{ tool: { <toolName>: <args> } }`, which is true when that tool call succeeds.
  Conditions are pass or fail, not values: whatever the tool returns is ignored. A tool that runs
  and fails makes the condition false. A tool that cannot run at all (an unknown name, a misspelled
  argument) fails the step instead, so a typo never reads as "no".
- `negate: true` flips the condition.
- `then` runs when the condition is true and `else`, which is optional, when it is false. Both
  take a list of tool calls in the same shape as a recording, and either can contain another `runIf`.
- The whole `runIf` is checked before anything runs, branches not taken included. An unknown key
  (`els:`), a malformed condition, or a `runIf` nested more than two deep fails the step up front.

Use `runIf` for one-off conditionals, so a check that only one trail needs doesn't become a tool
in the shared catalog. Nesting a `runIf` inside another's branch is allowed, two deep. Once the
same branch appears in a second trail, write a [scripted tool](scripted-tools-typed-authoring.md)
instead: it can be named, tested, and reused. `runIf` has no `else if` or `switch`.

`runIf` is available on every target for Android, iOS and web sessions. It is not available on
Compose or Revyl sessions, and a `selector` condition needs a view hierarchy, so drivers that only
capture screenshots must use a `tool` condition. The agent never sees `runIf`, so it is missing from
`trailblaze toolbox` and you write it into recordings yourself. Scripted tools can call it too,
via `ctx.tools.runIf(...)`.

## Launch arguments

`ios_openApp` treats values inside `arguments` as confidential and masks them in tool logs. The
real values still reach the app at launch; execution and transport serialization retain them.
Native iOS launch failures also mask arguments if the device transport repeats them in its error
output.

Use whole-value memory references for launch arguments that need to survive recording. Literal
and mixed-template arguments are masked in logs; recording generation refuses the whole session
recording because it cannot replay a redacted placeholder or safely omit the launch from a later
action sequence. A matching value elsewhere in authored text is masked only when it was also
authored literally in the launch argument.
For direct MCP `step(tools: ...)` on native iOS, the outer log cannot verify that a launch
reference resolved, so saving the interactive step may refuse the recording. Put the launch in
a trail or scripted tool when it must be replayed.

Nested framework calls are excluded from recordings while their tool logs remain available.
Recordability alone does not hide data: keep credentials inside the sign-in tool and avoid
printing them, returning them to the model, or storing them as ordinary memory.

## Browsing the live tool catalog

Once you've added a tool — or you're trying to figure out what's already available
for a given target — there are two ways to inspect the live tool catalog the agent
sees:

- **From the CLI:** `trailblaze toolbox -d <platform> --target <trailmap-id>` lists
  every tool the agent sees for that target, with descriptions. Add `--search <substring>`
  to filter (substring match on tool name and description).
- **In your IDE:** open any `.ts` file under `<trailmap>/tools/` and start typing
  `ctx.tools.` — autocomplete pulls from the per-trailmap declaration rollup at
  `<trailmap>/tools/trailblaze-client.d.ts`. That file is emitted by
  `trailblaze check` (and re-emitted on every daemon-aware command); if it's missing,
  run `trailblaze check` once.

Either surface includes framework built-ins resolved from the trailmap's
`platforms.<p>.tool_sets:`, its own scripted tools, and any tools its dependencies
publish via their `exports:` field. There's no global cross-trailmap aggregator —
per-trailmap slicing keeps autocomplete pollution out of your IDE and matches the
runtime dispatch surface one-to-one.
