package xyz.block.trailblaze.scripting

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.config.InlineScriptToolConfig

/**
 * Tests the route-1 gating in [HostScriptedToolLauncher] across the three environments: (1) esbuild +
 * the in-process SDK source (a real checkout) → live-bundle every tool, never consulting a precompiled
 * classpath bundle, so a staged bundle can't shadow a live edit; (2) esbuild WITHOUT the SDK source (a
 * binary user with their own esbuild) → live-bundle, falling back to a precompiled classpath bundle
 * per tool when the live-bundle can't resolve `@trailblaze/scripting`; (3) esbuild absent (the
 * installed uber-JAR daemon) → serve each tool from its precompiled classpath bundle. A tool with no
 * bundle from its route is loudly skipped. Also covers the rollback guard that disposes
 * already-created registrations when a later one fails.
 *
 * The classpath is stubbed via an injected byte loader and extraction is redirected to a per-test
 * [TemporaryFolder], so these run with no real JAR, no esbuild, and no shared state. They assert the
 * observable contract — which route is chosen, which classpath path is consulted, that the extracted
 * bundle bytes match the classpath resource, and that a failed later registration disposes the ones
 * already built — using a synthetic trailmap id (`fixtureapp`) so no real classpath resource is hit.
 */
class HostScriptedToolLauncherPrecompiledBundleTest {

  @get:Rule val extractRoot = TemporaryFolder()

  private val toolsDir = "trails/config/trailmaps/fixtureapp/tools"

  /**
   * The core precompiled-resolution behavior: given a tool whose repo-relative `script:` has a
   * sibling `.bundle.js` on the classpath, [HostScriptedToolLauncher.resolvePrecompiledBundle]
   * derives the canonical `trails/config/trailmaps/<id>/tools/<name>.bundle.js` path, loads it, and
   * returns an extracted on-disk file whose bytes match the classpath bundle.
   */
  @Test
  fun `precompiled bundle for a script resolves from the classpath with matching bytes`() {
    val scriptTs = "$toolsDir/fixtureapp_launch.ts"
    val bundlePath = "$toolsDir/fixtureapp_launch.bundle.js"
    val bundleJs = "(() => { globalThis.__trailblazeTools = {}; })();\n"
    val classpath = mapOf(bundlePath to bundleJs.toByteArray())

    val resolved = HostScriptedToolLauncher.resolvePrecompiledBundle(
      script = scriptTs,
      loadClasspathResourceBytes = { classpath[it] },
      extractRoot = extractRoot.root,
    )

    requireNotNull(resolved) { "expected a precompiled bundle to resolve for $scriptTs" }
    assertTrue(resolved.isFile, "expected an extracted, on-disk bundle at ${resolved.absolutePath}")
    assertEquals(bundleJs, resolved.readText(), "extracted bundle bytes must match the classpath resource")
    assertEquals("fixtureapp_launch.bundle.js", resolved.name)
  }

  /**
   * A baked `targets/<id>.yaml` carries a longer repo-root-relative `script:` prefix; the resolver
   * must still anchor on the trailmaps segment and derive the same `.bundle.js` classpath path.
   */
  @Test
  fun `precompiled bundle resolves from a long repo-relative script path`() {
    val scriptTs = "$toolsDir/fixtureapp_launch.ts"
    val bakedLongPath = "some-module/src/commonMain/resources/$scriptTs"
    val bundlePath = "$toolsDir/fixtureapp_launch.bundle.js"
    val bundleJs = "/* bundle */\n"
    val classpath = mapOf(bundlePath to bundleJs.toByteArray())

    val resolved = HostScriptedToolLauncher.resolvePrecompiledBundle(
      script = bakedLongPath,
      loadClasspathResourceBytes = { classpath[it] },
      extractRoot = extractRoot.root,
    )

    requireNotNull(resolved)
    assertEquals(bundleJs, resolved.readText())
  }

  /**
   * A second session extracting the same bundle re-uses the already-extracted, complete file rather
   * than rewriting it — the content-addressed reuse that (with the atomic move) keeps a concurrent
   * reader from ever seeing a half-written bundle.
   */
  @Test
  fun `re-resolving the same bundle reuses the extracted file`() {
    val scriptTs = "$toolsDir/fixtureapp_launch.ts"
    val bundlePath = "$toolsDir/fixtureapp_launch.bundle.js"
    val classpath = mapOf(bundlePath to "/* bundle */\n".toByteArray())

    val first = HostScriptedToolLauncher.resolvePrecompiledBundle(
      script = scriptTs,
      loadClasspathResourceBytes = { classpath[it] },
      extractRoot = extractRoot.root,
    )
    val second = HostScriptedToolLauncher.resolvePrecompiledBundle(
      script = scriptTs,
      loadClasspathResourceBytes = { classpath[it] },
      extractRoot = extractRoot.root,
    )

    requireNotNull(first)
    requireNotNull(second)
    assertEquals(first, second, "the same bundle content must extract to the same reused path")
  }

  /**
   * When no precompiled bundle is on the classpath, the resolver returns null so the caller (in
   * installed-JAR mode) loudly skips the tool, and (in dev mode) never reaches it at all.
   */
  @Test
  fun `no precompiled bundle on classpath returns null`() {
    val resolved = HostScriptedToolLauncher.resolvePrecompiledBundle(
      script = "$toolsDir/fixtureapp_launch.ts",
      loadClasspathResourceBytes = { null },
      extractRoot = extractRoot.root,
    )
    assertNull(resolved)
  }

  /** A blank `script:` yields null rather than throwing, so a degenerate config can't crash launch. */
  @Test
  fun `blank script returns null`() {
    val resolved = HostScriptedToolLauncher.resolvePrecompiledBundle(
      script = "   ",
      loadClasspathResourceBytes = { error("must not be consulted for a blank script") },
      extractRoot = extractRoot.root,
    )
    assertNull(resolved)
  }

  /**
   * Anti-staleness invariant (a real SDK-source checkout): with esbuild AND the in-process SDK source
   * present, every tool routes to [InlineToolRoute.LiveBundle] with `allowPrecompiledFallback = false`
   * — so a bundle failure surfaces rather than falling back — and the precompiled classpath is never
   * consulted at plan time even for a tool that DOES have one. The injected resolver throws if
   * touched, proving a staged bundle can never shadow a developer's live `.ts` edit.
   */
  @Test
  fun `esbuild with SDK source routes to live bundling with no precompiled fallback`() {
    val launch = inlineTool("fixtureapp_launch")
    val seed = inlineTool("fixtureapp_seed")

    val route = HostScriptedToolLauncher.planInlineToolRoute(
      tools = listOf(launch, seed),
      esbuildPresent = true,
      sdkSourcePresent = true,
      resolvePrecompiled = { error("precompiled classpath must not be consulted when live-bundling is possible") },
    )

    val live = assertIs<HostScriptedToolLauncher.InlineToolRoute.LiveBundle>(route)
    assertFalse(live.allowPrecompiledFallback, "a real SDK-source checkout must not fall back to a staged bundle")
    assertEquals(listOf(launch, seed), live.tools)
  }

  /**
   * esbuild present but the in-process SDK source absent (a binary user with a global
   * esbuild running their own `target.tools:`) routes to [InlineToolRoute.LiveBundle] WITH
   * `allowPrecompiledFallback = true` — the tool is still live-bundled (its `@trailblaze/scripting`
   * resolves from the user's `node_modules`), NOT skipped for lack of a classpath bundle; only a
   * live-bundle that fails falls back to a precompiled bundle. The precompiled classpath is not
   * consulted at plan time on this route, so the injected resolver throws if touched.
   */
  @Test
  fun `esbuild without SDK source routes to live bundling with precompiled fallback`() {
    val launch = inlineTool("fixtureapp_launch")

    val route = HostScriptedToolLauncher.planInlineToolRoute(
      tools = listOf(launch),
      esbuildPresent = true,
      sdkSourcePresent = false,
      resolvePrecompiled = { error("precompiled classpath must not be consulted at plan time on the live route") },
    )

    val live = assertIs<HostScriptedToolLauncher.InlineToolRoute.LiveBundle>(route)
    assertTrue(live.allowPrecompiledFallback, "a binary user with no SDK source must be allowed a precompiled fallback")
    assertEquals(listOf(launch), live.tools)
  }

  /**
   * Installed-JAR route: when esbuild is ABSENT, a tool WITH a precompiled bundle is served from it
   * and a sibling WITHOUT one lands in the loud-skip set (`unresolved`) — with no esbuild there is no
   * way to live-bundle it.
   */
  @Test
  fun `esbuild absent serves precompiled tools and routes the rest to the loud skip`() {
    val resolvedTool = inlineTool("fixtureapp_launch")
    val skipped = inlineTool("fixtureapp_new")
    val bundle = File(extractRoot.root, "fixtureapp_launch.bundle.js").apply { writeText("x") }
    val classpath = mapOf(resolvedTool.script to bundle)

    val route = HostScriptedToolLauncher.planInlineToolRoute(
      tools = listOf(resolvedTool, skipped),
      esbuildPresent = false,
      sdkSourcePresent = false,
      resolvePrecompiled = { classpath[it.script] },
    )

    val precompiledOnly = assertIs<HostScriptedToolLauncher.InlineToolRoute.PrecompiledOnly>(route)
    assertEquals(listOf(resolvedTool), precompiledOnly.plan.resolved.map { it.config })
    assertEquals(listOf(bundle), precompiledOnly.plan.resolved.map { it.bundleFile })
    assertEquals(listOf(skipped), precompiledOnly.plan.unresolved)
  }

  /**
   * The binary-user route's per-tool split ([planInlineToolBundles] over the `live ?: precompiled`
   * resolver the caller composes): a tool whose live-bundle succeeds registers from the FRESH bundle,
   * a tool whose live-bundle failed falls back to its precompiled classpath bundle, and a tool with
   * neither lands in the loud-skip set — a skip that stays LOCAL to that tool (its siblings still
   * register), never a session abort.
   */
  @Test
  fun `binary-user split prefers live, falls back to precompiled, skips (not aborts) when neither`() {
    val live = inlineTool("fixtureapp_launch")
    val fallback = inlineTool("fixtureapp_seed")
    val neither = inlineTool("fixtureapp_new")
    val liveBundle = File(extractRoot.root, "fixtureapp_launch.bundle.js").apply { writeText("live") }
    val precompiledBundle = File(extractRoot.root, "fixtureapp_seed.bundle.js").apply { writeText("precompiled") }
    // `live` live-bundles; `fallback` has no live bundle but a precompiled one; `neither` has none.
    val liveBundled = mapOf<InlineScriptToolConfig, File?>(live to liveBundle, fallback to null, neither to null)
    val precompiled = mapOf(fallback.script to precompiledBundle)

    val plan = HostScriptedToolLauncher.planInlineToolBundles(
      tools = listOf(live, fallback, neither),
    ) { tool -> liveBundled[tool] ?: precompiled[tool.script] }

    assertEquals(listOf(live, fallback), plan.resolved.map { it.config })
    assertEquals(listOf(liveBundle, precompiledBundle), plan.resolved.map { it.bundleFile })
    assertEquals(
      listOf(neither),
      plan.unresolved,
      "a tool with neither a live nor precompiled bundle is skipped locally (siblings still resolve), not aborted",
    )
  }

  /**
   * Rollback guard: when a LATER registration in a mixed set fails to build, every registration
   * already created is disposed (best-effort) and the original failure propagates — so an aborted
   * launch never strands a QuickJS engine.
   */
  @Test
  fun `a create that fails midway disposes every registration already built`() = runBlocking {
    val disposed = mutableListOf<String>()
    val boom = RuntimeException("third registration failed")

    val thrown = assertFailsWith<RuntimeException> {
      HostScriptedToolLauncher.registerWithRollback<String, FakeRegistration>(
        produce = { listOf("a", "b", "c") },
        create = { id -> if (id == "c") throw boom else FakeRegistration(id) },
        commit = { error("commit must not run once a create has failed") },
        dispose = { disposed += it.id },
      )
    }

    assertEquals(boom, thrown)
    assertEquals(listOf("a", "b"), disposed, "the two registrations built before the failure must be disposed")
  }

  /**
   * The commit itself failing (e.g. a name collision at `addDynamicTools`) rolls back the whole set —
   * every registration created is disposed before the failure propagates.
   */
  @Test
  fun `a commit failure disposes the whole created set`() = runBlocking {
    val disposed = mutableListOf<String>()
    val collision = IllegalStateException("Dynamic tool 'b' already registered")

    val thrown = assertFailsWith<IllegalStateException> {
      HostScriptedToolLauncher.registerWithRollback<String, FakeRegistration>(
        produce = { listOf("a", "b") },
        create = { FakeRegistration(it) },
        commit = { throw collision },
        dispose = { disposed += it.id },
      )
    }

    assertEquals(collision, thrown)
    assertEquals(listOf("a", "b"), disposed)
  }

  private data class FakeRegistration(val id: String)

  private fun inlineTool(name: String): InlineScriptToolConfig =
    InlineScriptToolConfig(script = "$toolsDir/$name.ts", name = name)
}
