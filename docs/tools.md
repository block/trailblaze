---
title: Trailblaze Tools
---

# Trailblaze Tools

Trailblaze ships a catalog of framework tools (`tap`, `inputText`, `pressBack`,
`assertVisible`, …) that cover most UI automation. When your app or test surface needs
something the catalog doesn't have, you add **custom tools** to a workspace trailmap.

There are three flavors of custom tool. Pick by what kind of body you need to write:

1. **Scripted (TypeScript) tool** — the recommended path for almost everyone. Declare
   inputs and result shape as TypeScript interfaces and write the handler against the
   typed `ctx.tools.<name>(args)` surface. One `.ts` file per tool; no per-tool YAML
   descriptor. Use this when the agent needs a custom command with branching, retries,
   async coordination, or anything beyond raw YAML composition. Tools are listed by
   bare export name in `trailmap.yaml`'s `target.tools:`.

   Start with [Scripted Tools (TypeScript)](scripted-tools-typed-authoring.md) for the
   canonical authoring shape and the worked iOS Contacts + Wikipedia examples; see
   [Trailmaps](trailmaps.md) for the manifest wiring and the per-suffix file
   conventions.

2. **Pure-YAML composed tool** (`<trailmap>/tools/<id>.tool.yaml`) — a declarative
   composition that substitutes parameters into existing framework tool calls. No code.
   Auto-discovered; reference it by name from a toolset. Use for thin wrappers like
   "press back twice" or "open the search URL for `<topic>`" — the cases where adding a
   TypeScript handler would be more ceremony than the wrapper deserves. See
   [Trailmaps — Tool YAML file suffixes](trailmaps.md#tool-yaml-file-suffixes-toolyaml-shortcutyaml-trailheadyaml).

3. **Kotlin-backed tool** (`@TrailblazeToolClass`-annotated class + `*.tool.yaml` in
   `class:` mode) — advanced / host-side state only. Use when you need to read host-side
   state that neither YAML composition nor the scripting SDK exposes today (a host
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
back to pure-YAML or Kotlin is straightforward once you've hit a case where the typed
authoring surface doesn't reach.

## Browsing the live tool catalog

Once you've added a tool — or you're trying to figure out what's already available
for a given target — there are two ways to inspect the live tool catalog the agent
sees:

- **From the CLI:** `trailblaze toolbox -d <platform> --target <trailmap-id>` lists
  every tool the agent sees for that target, with descriptions. Add `--search <prefix>`
  to filter.
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
