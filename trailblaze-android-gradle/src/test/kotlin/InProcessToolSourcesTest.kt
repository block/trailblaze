import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for [inProcessToolSources] — the configuration-time discovery that decides which
 * `<name>.ts` in a trailmap's `tools/` dir get pre-compiled into an on-device QuickJS bundle.
 *
 * The descriptor-less branch is the one that regressed in PR #3981: that change moved several
 * launch-step tools to TypeScript-only descriptors (deleting their `.yaml` sidecars) but did not
 * teach this discovery to recognize them, so they were silently dropped from the bundle and failed
 * at runtime with "Unknown framework tool". These tests lock both shapes — sidecar and
 * descriptor-less — plus the exclusions (helper modules, subprocess tools, `.test.ts`, `.d.ts`).
 */
class InProcessToolSourcesTest {

  private val dir: File = Files.createTempDirectory("tools").toFile()

  @AfterTest fun cleanup() {
    dir.deleteRecursively()
  }

  private fun write(name: String, content: String) = File(dir, name).writeText(content)

  private fun discoveredNames(): List<String> = inProcessToolSources(dir).map { it.name }

  private fun discoveredRelPaths(): List<String> =
    inProcessToolSources(dir).map { it.relativeTo(dir).invariantSeparatorsPath }

  private val toolBody = "export const x = trailblaze.tool<Foo>({ supportedPlatforms: [\"android\"] }, async () => \"ok\")\n"

  @Test fun `includes a tool with a non-subprocess yaml sidecar`() {
    write("withSidecar.ts", toolBody)
    write("withSidecar.yaml", "name: withSidecar\nruntime: inProcess\n")
    assertEquals(listOf("withSidecar.ts"), discoveredNames())
  }

  @Test fun `includes a descriptor-less tool that declares trailblaze_tool inline`() {
    // The #3981 regression case: no sibling .yaml, registration is inline in the .ts.
    write("sidecarless.ts", toolBody)
    assertEquals(listOf("sidecarless.ts"), discoveredNames())
  }

  @Test fun `excludes a shared helper module that never calls trailblaze_tool`() {
    write("app_shared.ts", "export function textSelector(t: string) { return { t } }\n")
    assertEquals(emptyList(), discoveredNames())
  }

  @Test fun `excludes a subprocess tool gated by its yaml sidecar`() {
    // Subprocess is declared ONLY via the YAML sidecar's `runtime: subprocess` — the inline
    // `trailblaze.tool(...)` spec (`TrailblazeTypedToolSpec`) has no `runtime` field, so a
    // descriptor-less `.ts` is always in-process. This branch is the sole subprocess gate.
    write("subprocessYaml.ts", toolBody)
    write("subprocessYaml.yaml", "name: subprocessYaml\nruntime: subprocess\n")
    assertEquals(emptyList(), discoveredNames())
  }

  @Test fun `includes a descriptor-less tool whose comment mentions runtime subprocess`() {
    // Regression: discovery used to grep the `.ts` text for `runtime: subprocess` to gate out
    // subprocess tools. But a descriptor-less `.ts` can't declare that (the inline spec has no
    // `runtime` field), so the phrase only ever appeared in a comment — here, one explaining the
    // tool does NOT pin it. That false match dropped the tool's on-device bundle: advertised in the
    // target config but no `.bundle.js` in the APK, failing at runtime with "Unknown tool … not
    // registered". A descriptor-less tool's comments must not gate it out.
    write(
      "commentMentionsSubprocess.ts",
      "// Bundled on-device because it does NOT pin `runtime: subprocess`.\n" +
        "/* A sibling with `runtime: subprocess` would be host-only. */\n" +
        toolBody,
    )
    assertEquals(listOf("commentMentionsSubprocess.ts"), discoveredNames())
  }

  @Test fun `excludes test fixtures and type declarations`() {
    write("foo.test.ts", toolBody)
    write("foo.d.ts", toolBody)
    assertEquals(emptyList(), discoveredNames())
  }

  @Test fun `returns entries sorted by name`() {
    write("bTool.ts", toolBody)
    write("aTool.ts", toolBody)
    assertEquals(listOf("aTool.ts", "bTool.ts"), discoveredNames())
  }

  @Test fun `discovers tools organized into subfolders, sorted by tools-relative path`() {
    // Tools reorganized into `tools/<category>/` (an internal/client/ios/... platform layout) must
    // still be found; the old flat `listFiles()` walk missed them. Sibling-yaml gate is checked in
    // the file's OWN directory.
    File(dir, "client").mkdirs()
    File(dir, "ios").mkdirs()
    write("client/launchClientRoute.ts", toolBody)
    write("ios/signInViaUI.ts", toolBody)
    write("rootTool.ts", toolBody)
    File(dir, "ios/subprocessy.yaml").writeText("name: subprocessy\nruntime: subprocess\n")
    write("ios/subprocessy.ts", toolBody) // gated out by its same-dir subprocess sidecar
    assertEquals(listOf("client/launchClientRoute.ts", "ios/signInViaUI.ts", "rootTool.ts"), discoveredRelPaths())
  }

  @Test fun `assetPathFor preserves the tools-relative subpath so it matches the runtime resolver`() {
    assertEquals(
      "trails/config/trailmaps/myapp/tools/client/launchClientRoute.bundle.js",
      assetPathFor("myapp", "client/launchClientRoute"),
    )
  }
}
