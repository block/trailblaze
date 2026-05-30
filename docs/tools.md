---
title: Trailblaze Tools
---

# Trailblaze Tools

Trailblaze ships a catalog of framework tools (`tap`, `inputText`, `pressBack`,
`assertVisible`, …) that cover most UI automation. When your app or test surface needs
something the catalog doesn't have, you add **custom tools** to a workspace trailmap.

There are three flavors of custom tool. Pick by what kind of body you need to write:

1. **Scripted (TypeScript) tool** — the recommended path for almost everyone. Declare
   inputs and result shape as TypeScript interfaces, write the handler against the typed
   `ctx.tools.<name>(args)` surface, and pair it with a sibling YAML descriptor. Use this
   when the agent needs a custom command with branching, retries, async coordination, or
   anything beyond raw YAML composition. Listed in `trailmap.yaml`'s `target.tools:`.

   Start with [Typed Authoring for Scripted Tools](scripted-tools-typed-authoring.md) for
   the canonical authoring shape; see [Trailmaps](trailmaps.md) for the manifest wiring
   and the per-suffix file conventions.

2. **Pure-YAML composed tool** (`<trailmap>/tools/<id>.tool.yaml`) — a declarative
   composition that substitutes parameters into existing framework tool calls. No code.
   Auto-discovered; reference it by name from a toolset. Use for thin wrappers like
   "press back twice" or "open the search URL for `<topic>`" — the cases where adding a
   TypeScript handler would be more ceremony than the wrapper deserves. See
   [Trailmaps — Tool YAML file suffixes](trailmaps.md#tool-yaml-file-suffixes--toolyaml-shortcutyaml-trailheadyaml).

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

For the catalog of tools actually visible to a given trailmap — including framework
built-ins resolved from its `platforms.<p>.tool_sets:`, its own scripted tools, and any
tools its dependencies publish via `exports:` — look at the per-trailmap declaration
rollup emitted by `trailblaze check` at `<trailmap>/tools/trailblaze-client.d.ts`. That
file is the live, scoped source of truth: every entry has a typed signature, both
YAML-defined and class-backed tools are represented, and the surface matches exactly
what `client.tools.<name>(args)` autocompletes to inside that trailmap's scripted tool
sources. There is no global cross-trailmap aggregator — per-trailmap slicing keeps
autocomplete pollution out of your IDE and matches the runtime dispatch surface
one-to-one.
