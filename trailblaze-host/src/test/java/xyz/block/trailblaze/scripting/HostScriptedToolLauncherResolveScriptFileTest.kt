package xyz.block.trailblaze.scripting

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Tests [HostScriptedToolLauncher.resolveScriptFile]'s classpath-resource fallback — the JAR-mode
 * daemon path where a target-declared scripted tool's `.ts` source is NOT on the workspace
 * filesystem but IS bundled in the uber JAR at classpath path
 * `trails/config/trailmaps/<id>/tools/<name>.ts`.
 *
 * The classpath is stubbed via injected `loadClasspathResource` / `listClasspathToolScripts` lambdas
 * (the production defaults route through `ClasspathResourceDiscovery`) and extraction is redirected
 * to a per-test [TemporaryFolder], so these run with no real JAR, no esbuild, and no shared state.
 * They assert the observable contract: which file `resolveScriptFile` returns, that its content
 * matches the classpath source, and that sibling `.ts` modules are extracted next to it so
 * downstream esbuild/bun relative-import resolution can find them.
 *
 * Every fixture uses a synthetic trailmap id (`fixtureapp`) with no `.ts` on disk anywhere above
 * the module CWD, so the CWD walk-up (step 2 of resolveScriptFile) misses and the classpath
 * fallback (step 3) is exercised deterministically regardless of the checkout layout.
 */
class HostScriptedToolLauncherResolveScriptFileTest {

  @get:Rule val extractRoot = TemporaryFolder()

  private val toolsDir = "trails/config/trailmaps/fixtureapp/tools"

  /**
   * The exact failure the fix targets: a baked `targets/<id>.yaml` declares a repo-root-relative
   * `script:` (`some-module/src/commonMain/resources/trails/config/trailmaps/<id>/tools/<name>.ts`)
   * that does not exist on disk in JAR mode, so the CWD walk-up misses. The classpath fallback
   * anchors on the `trails/config/trailmaps/` segment, loads the source from the classpath, and
   * returns an existing extracted file whose bytes match the classpath resource.
   */
  @Test
  fun `long repo-relative script resolves via classpath when not on disk`() {
    val toolTs = "$toolsDir/fixtureapp_launch.ts"
    val bakedLongPath = "some-module/src/commonMain/resources/$toolTs"
    val classpath = mapOf(toolTs to "export const fixtureapp_launch = 1;\n")

    val resolved = HostScriptedToolLauncher.resolveScriptFile(
      script = bakedLongPath,
      loadClasspathResource = { classpath[it] },
      listClasspathToolScripts = { emptySet() },
      classpathExtractRoot = extractRoot.root,
    )

    assertTrue(resolved.isFile, "expected an extracted, on-disk file at ${resolved.absolutePath}")
    assertEquals(
      classpath.getValue(toolTs),
      resolved.readText(),
      "extracted file content must match the classpath resource",
    )
    assertEquals("fixtureapp_launch.ts", resolved.name)
  }

  /**
   * A `.ts` that imports a sibling module must have that sibling extracted alongside it, or esbuild's
   * directory-relative import resolution fails at bundle time. Assert the sibling lands next to the
   * entry with its classpath content intact.
   */
  @Test
  fun `sibling ts modules are extracted next to the entry for import resolution`() {
    val entryTs = "$toolsDir/fixtureapp_reader.ts"
    val sharedTs = "$toolsDir/fixtureapp_shared.ts"
    val classpath = mapOf(
      entryTs to "import { x } from \"./fixtureapp_shared.ts\"; export const fixtureapp_reader = x;\n",
      sharedTs to "export const x = 1;\n",
    )

    val resolved = HostScriptedToolLauncher.resolveScriptFile(
      script = entryTs,
      loadClasspathResource = { classpath[it] },
      listClasspathToolScripts = { setOf("fixtureapp_reader.ts", "fixtureapp_shared.ts") },
      classpathExtractRoot = extractRoot.root,
    )

    assertTrue(resolved.isFile)
    val sibling = File(resolved.parentFile, "fixtureapp_shared.ts")
    assertTrue(sibling.isFile, "sibling module must be extracted next to the entry")
    assertEquals(classpath.getValue(sharedTs), sibling.readText())
  }

  /**
   * When the script isn't a classpath-bundled trailmap tool (no `trails/config/trailmaps/` anchor),
   * the fallback declines and `resolveScriptFile` returns the direct `File(script)` — a non-existent
   * file, so the bundler surfaces its clear "Scripted-tool source not found" error rather than
   * silently succeeding.
   */
  @Test
  fun `unanchored script returns the direct not-found file`() {
    val script = "some/other/place/tool.ts"
    val resolved = HostScriptedToolLauncher.resolveScriptFile(
      script = script,
      loadClasspathResource = { null },
      listClasspathToolScripts = { emptySet() },
      classpathExtractRoot = extractRoot.root,
    )
    assertFalse(resolved.isFile)
    assertEquals(File(script).path, resolved.path)
  }

  /**
   * The anchor exists but the specific `.ts` isn't on the classpath (e.g. a stale `target.tools:`
   * entry): the fallback must decline (return the direct not-found file) rather than extract an empty
   * dir and hand back a phantom path.
   */
  @Test
  fun `anchored script missing from classpath declines the fallback`() {
    val missingTs = "$toolsDir/fixtureapp_typo.ts"
    val resolved = HostScriptedToolLauncher.resolveScriptFile(
      script = missingTs,
      loadClasspathResource = { null },
      listClasspathToolScripts = { emptySet() },
      classpathExtractRoot = extractRoot.root,
    )
    assertFalse(resolved.isFile)
    assertEquals(File(missingTs).path, resolved.path)
  }

  /**
   * Bundled targets reference scripts nested in `tools/` subdirectories (e.g. `tools/internal/…`,
   * `tools/android/…`). The fallback must anchor on the `<id>/tools` ROOT — accepting the nested
   * script rather than rejecting it, extracting the tools tree with subdirectory structure preserved
   * (so a helper imported from another subdir resolves on disk), and returning the requested script
   * at its nested path.
   */
  @Test
  fun `nested-subdirectory scripts resolve and extract with structure preserved`() {
    val nestedTs = "$toolsDir/internal/fixtureapp_nested.ts"
    val helperTs = "$toolsDir/host/fixtureapp_helper.ts"
    val classpath = mapOf(
      nestedTs to "import { h } from \"../host/fixtureapp_helper.ts\"; export const n = h;\n",
      helperTs to "export const h = 1;\n",
    )

    val resolved = HostScriptedToolLauncher.resolveScriptFile(
      script = "some-module/src/commonMain/resources/$nestedTs",
      loadClasspathResource = { classpath[it] },
      // The recursive lister returns paths relative to the tools ROOT, preserving subdirectories.
      listClasspathToolScripts = { setOf("internal/fixtureapp_nested.ts", "host/fixtureapp_helper.ts") },
      classpathExtractRoot = extractRoot.root,
    )

    assertTrue(resolved.isFile, "nested script must resolve, not be rejected")
    assertEquals(classpath.getValue(nestedTs), resolved.readText())
    assertTrue(
      resolved.path.replace(File.separatorChar, '/').endsWith("internal/fixtureapp_nested.ts"),
      "the nested subdirectory path must be preserved in the returned file",
    )
    // The cross-subdirectory helper must be extracted WITH its parent dir created (the P2 gap).
    val helper = File(extractRoot.root, "fixtureapp/tools/host/fixtureapp_helper.ts")
    assertTrue(helper.isFile, "a sibling in a different subdir must extract with its parent dir created")
    assertEquals(classpath.getValue(helperTs), helper.readText())
  }
}
