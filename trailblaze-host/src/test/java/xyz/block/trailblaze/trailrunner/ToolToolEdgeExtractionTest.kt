package xyz.block.trailblaze.trailrunner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit-tests the pure tool->tool edge extractor that powers the "Used by other tools" section.
 * Pure over (flavor, source) — no file IO — so it pins the two real composition patterns: a scripted
 * tool dispatching via `ctx.tools.<id>` and a Kotlin orchestrator dispatching via
 * `invokeFrameworkTool`. We assert the extracted id SET (the observable contract), not how the regex
 * walks the source.
 */
class ToolToolEdgeExtractionTest {

  @Test
  fun `scripted tool edges come from ctx_tools dispatches`() {
    val source =
      """
      export const myapp_ios_signInViaUI = trailblaze.tool(SPEC, async (args, ctx) => {
        const appId = (ctx.target?.resolveAppId() ?? "").trim();
        await ctx.tools.myapp_ios_prepareEventCaptureIfEnabled({ appId });
        await ctx.tools.mobile_maestro({ commands: [] });
      });
      """.trimIndent()
    val edges = ToolCatalogBuilder.referencedToolIdsIn(ToolFlavor.SCRIPTED, source, "myapp_ios_signInViaUI")
    assertEquals(
      setOf("myapp_ios_prepareEventCaptureIfEnabled", "mobile_maestro"),
      edges,
    )
  }

  @Test
  fun `kotlin orchestrator edges come from invokeFrameworkTool dispatches`() {
    val source =
      """
      class MyAppAndroidSignInTool {
        suspend fun run(ctx: ToolExecutionContext) {
          ctx.invokeFrameworkTool(toolName = "myapp_android_enterCredentials", args = mapOf())
          ctx.invokeFrameworkTool(toolName = "myapp_android_waitForHome")
        }
      }
      """.trimIndent()
    val edges = ToolCatalogBuilder.referencedToolIdsIn(ToolFlavor.KOTLIN, source, "myapp_android_signInViaUI")
    assertEquals(setOf("myapp_android_enterCredentials", "myapp_android_waitForHome"), edges)
  }

  @Test
  fun `a tool never lists itself as an edge`() {
    val source = "await ctx.tools.myapp_self({}); await ctx.tools.myapp_other({});"
    val edges = ToolCatalogBuilder.referencedToolIdsIn(ToolFlavor.SCRIPTED, source, "myapp_self")
    assertEquals(setOf("myapp_other"), edges)
  }

  @Test
  fun `yaml-declarative tools contribute no edges`() {
    val source = "id: launchMyApp\nclass: some.Class\ntools:\n  - mobile_maestro: {}"
    assertTrue(ToolCatalogBuilder.referencedToolIdsIn(ToolFlavor.YAML, source, "launchMyApp").isEmpty())
  }

  @Test
  fun `dispatches mentioned only in comments or docstrings are not edges`() {
    // Mirrors openUrl.ts: a `//` comment and a block comment each mention a ctx.tools dispatch the
    // implementation never makes; only the real call (mobile_maestro) is an edge.
    val source =
      """
      /**
       * Opens a URL. Historically this also did a `ctx.tools.android_adbShell(...)` step.
       */
      export const openUrl = trailblaze.tool(SPEC, async (args, ctx) => {
        // NOTE: we no longer call ctx.tools.android_grantPermissions(...) here.
        await ctx.tools.mobile_maestro({ commands: [] });
      });
      """.trimIndent()
    val edges = ToolCatalogBuilder.referencedToolIdsIn(ToolFlavor.SCRIPTED, source, "openUrl")
    assertEquals(setOf("mobile_maestro"), edges)
  }

  @Test
  fun `a bare ctx_tools property reference without a call is not an edge`() {
    // Only an actual dispatch (trailing `(`) counts — a value read / property access doesn't.
    val source = "const fns = ctx.tools; await ctx.tools.myapp_real({});"
    val edges = ToolCatalogBuilder.referencedToolIdsIn(ToolFlavor.SCRIPTED, source, "caller")
    assertEquals(setOf("myapp_real"), edges)
  }

  // --- registeredKotlinCallerEdgesFrom: caller edges for toolset/trailhead-registered Kotlin tools
  // that have no .tool.yaml (so they aren't catalog entries). Side effects injected so the
  // exclude/empty/compose logic is testable without the live catalog or filesystem.

  private val signInSource =
    """
    class MyAppAndroidSignInTool {
      suspend fun run(ctx: ToolExecutionContext) {
        ctx.invokeFrameworkTool(toolName = "myapp_android_enterCredentials")
        ctx.invokeFrameworkTool(toolName = "myapp_android_waitForHome")
      }
    }
    """.trimIndent()

  @Test
  fun `registered kotlin orchestrator without a tool-yaml contributes caller edges`() {
    val edges = ToolCatalogBuilder.registeredKotlinCallerEdgesFrom(
      registered = listOf("myapp_android_signInViaUI" to "x.MyAppAndroidSignInTool"),
      excludeIds = emptySet(),
      sourceFor = { fqn -> if (fqn == "x.MyAppAndroidSignInTool") signInSource else null },
    )
    assertEquals(
      mapOf("myapp_android_signInViaUI" to setOf("myapp_android_enterCredentials", "myapp_android_waitForHome")),
      edges,
    )
  }

  @Test
  fun `registered caller edges drop excluded ids, sourceless tools, and no-dispatch tools`() {
    val edges = ToolCatalogBuilder.registeredKotlinCallerEdgesFrom(
      registered = listOf(
        "myapp_android_signInViaUI" to "x.MyAppAndroidSignInTool", // excluded below
        "myapp_inputText" to "x.MyAppInputTextTool",               // source resolves but dispatches nothing
        "missing" to "x.NotOnDisk",                              // no source
      ),
      excludeIds = setOf("myapp_android_signInViaUI"),
      sourceFor = { fqn ->
        when (fqn) {
          "x.MyAppAndroidSignInTool" -> signInSource
          "x.MyAppInputTextTool" -> "class MyAppInputTextTool { fun run() { /* no dispatch */ } }"
          else -> null
        }
      },
    )
    assertTrue(edges.isEmpty(), "expected no edges (excluded / no-dispatch / sourceless); got $edges")
  }
}
