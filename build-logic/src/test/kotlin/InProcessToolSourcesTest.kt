import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for [TrailblazeTrailmapToolBundlesPlugin.inProcessToolSources] — the configuration-time
 * discovery that decides which `<name>.ts` in a trailmap's `tools/` dir get pre-compiled into an
 * on-device QuickJS bundle.
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

  private fun discoveredNames(): List<String> =
    TrailblazeTrailmapToolBundlesPlugin.inProcessToolSources(dir).map { it.name }

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

  @Test fun `excludes a subprocess tool whether gated by yaml or inline spec`() {
    write("subprocessYaml.ts", toolBody)
    write("subprocessYaml.yaml", "name: subprocessYaml\nruntime: subprocess\n")
    write(
      "subprocessInline.ts",
      "export const x = trailblaze.tool<Foo>({ runtime: \"subprocess\" }, async () => \"ok\")\n",
    )
    assertEquals(emptyList(), discoveredNames())
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
}
