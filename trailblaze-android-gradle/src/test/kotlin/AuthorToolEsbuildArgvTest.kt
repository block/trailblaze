import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [authorToolEsbuildArgv] — the esbuild command line `BundleAuthorToolsTask`
 * hands the on-device (QuickJS) author-tool bundler.
 *
 * These exist because the plugin's functional tests point `esbuildBinary` at a stub file and
 * never launch a real esbuild, so the argv was previously unpinned: a flag that stopped being
 * passed would break the shape of every on-device bundle with no test failure anywhere. The
 * argv is also a lockstep contract with `DaemonScriptedToolBundler.runEsbuild` (the daemon-side
 * equivalent), and the daemon side is the half that has an end-to-end esbuild test.
 */
class AuthorToolEsbuildArgvTest {

  private val tmp: File = Files.createTempDirectory("author-tool-argv-test").toFile()

  @AfterTest fun cleanup() {
    tmp.deleteRecursively()
  }

  /** SDK layout with the slim in-process entry, optionally with the matcher subpath beside it. */
  private fun sdkEntry(withMatcher: Boolean): File {
    val src = File(tmp, "sdks/typescript/src").apply { mkdirs() }
    val entry = File(src, "in-process.ts").apply { writeText("export const trailblaze = {};\n") }
    if (withMatcher) {
      File(src, "matcher").mkdirs()
      File(src, "matcher/index.ts").writeText("export const resolve = () => {};\n")
    }
    return entry
  }

  private fun argv(withMatcher: Boolean): List<String> = authorToolEsbuildArgv(
    esbuild = File(tmp, "esbuild"),
    wrapperFile = File(tmp, "wrapper.ts"),
    scriptingSdkEntry = sdkEntry(withMatcher),
    stdioStubFile = File(tmp, "stdio-stub.ts"),
    output = File(tmp, "out/tool.bundle.js"),
  )

  @Test
  fun `aliases the matcher subpath alongside the package when the SDK ships it`() {
    // esbuild's `--alias` is a PACKAGE alias and rewrites subpaths with it, so the package
    // alias alone resolves `@trailblaze/scripting/matcher` to `<slim entry>.ts/matcher` — not a
    // directory — and the bundle fails before the tool is ever registered. Both aliases have to
    // be on the command line for a matcher-importing tool to bundle at all.
    val flags = argv(withMatcher = true)
    val subpathAlias = flags.single { it.startsWith("--alias:@trailblaze/scripting/matcher=") }
    val packageAlias = flags.single { it == "--alias:@trailblaze/scripting=${File(tmp, "sdks/typescript/src/in-process.ts").absolutePath}" }

    assertTrue(
      subpathAlias.endsWith("/src/matcher/index.ts"),
      "the subpath alias must point at the matcher entry; got: $subpathAlias",
    )
    // The subpath target must NOT be derived by appending to the package target — that is the
    // exact broken resolution this alias exists to prevent.
    assertFalse(
      subpathAlias.contains("in-process.ts/"),
      "the subpath alias must not resolve through the slim entry file; got: $subpathAlias",
    )
    assertTrue(packageAlias.isNotEmpty())
  }

  @Test
  fun `omits the matcher alias when the SDK layout has no matcher entry`() {
    // A missing matcher entry means the subpath doesn't exist to import. Aliasing it anyway
    // would name a nonexistent file on the command line; the author's import should be what
    // fails, and tools that don't use the matcher must keep bundling.
    val flags = argv(withMatcher = false)
    assertTrue(
      flags.none { it.startsWith("--alias:@trailblaze/scripting/matcher=") },
      "expected no matcher alias; got: $flags",
    )
    assertTrue(
      flags.any { it.startsWith("--alias:@trailblaze/scripting=") },
      "the package alias must still be passed; got: $flags",
    )
  }

  @Test
  fun `pins the flag set the on-device QuickJS runtime requires`() {
    // Each of these is load-bearing for a bundle QuickJS can evaluate (iife because the
    // runtime's script-mode evaluation rejects `import`/`export`; neutral + main-fields to
    // avoid Node-only resolution; es2020 as the syntax ceiling). Asserted as a set so a
    // dropped flag fails here rather than on a device.
    val flags = argv(withMatcher = true)
    listOf(
      "--bundle",
      "--platform=neutral",
      "--format=iife",
      "--target=es2020",
      "--main-fields=module,main",
      "--external:node:process",
    ).forEach { flag ->
      assertTrue(flags.contains(flag), "expected $flag on the esbuild command line; got: $flags")
    }
    // esbuild takes the binary first and the entry second, positionally — a reordering here
    // would make it bundle the wrong file.
    assertEquals(File(tmp, "esbuild").absolutePath, flags[0])
    assertEquals(File(tmp, "wrapper.ts").absolutePath, flags[1])
    assertTrue(flags.last() == "--outfile=${File(tmp, "out/tool.bundle.js").absolutePath}")
  }
}
