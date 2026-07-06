package xyz.block.trailblaze.trailrunner

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.llm.config.FilesystemConfigResourceSource
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression coverage for the Trail Runner trailhead-picker gap: a scripted (`.ts`) tool that
 * declares an inline `trailhead: { to / dynamic }` block in its spec object (the TS equivalent of a
 * `*.trailhead.yaml` sidecar, see PR #4404) used to be invisible to [TrailmapCatalogBuilder.build] —
 * only YAML sidecars populated `TrailmapEntry.trailheads`, so any tool migrated off a sidecar onto
 * the inline form disappeared from the web UI's "Configure recording" / Record trailhead dropdown
 * even though it kept working everywhere else (CLI `toolbox trailheads`, desktop app).
 */
class TrailmapCatalogBuilderTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private fun sourceFor(configDir: File) = FilesystemConfigResourceSource(configDir)

  @Test
  fun `a scripted tool with an inline trailhead block is unioned into trailheads`() {
    val configDir = tempFolder.newFolder("config")
    val toolsDir = File(configDir, "trailmaps/demo/tools").apply { mkdirs() }
    File(toolsDir, "demo_launchAppSignedIn.ts").writeText(
      """
        |import { trailblaze } from "@trailblaze/scripting";
        |
        |export const demo_launchAppSignedIn = trailblaze.tool(
        |  {
        |    supportedPlatforms: ["android"],
        |    requiresContext: true,
        |    trailhead: { to: "demo/home" },
        |  },
        |  async (_input, ctx) => "ok",
        |);
      """.trimMargin(),
    )

    val catalog = TrailmapCatalogBuilder.build(sourceFor(configDir))
    val demo = catalog.single { it.id == "demo" }

    assertTrue(
      demo.tools.any { it.name == "demo_launchAppSignedIn" },
      "expected demo_launchAppSignedIn in tools, got ${demo.tools}",
    )
    assertTrue(
      demo.trailheads.any { it.name == "demo_launchAppSignedIn" },
      "expected demo_launchAppSignedIn to also be unioned into trailheads, got ${demo.trailheads}",
    )
  }

  @Test
  fun `a scripted tool with no trailhead block is not treated as a trailhead`() {
    val configDir = tempFolder.newFolder("config-no-trailhead")
    val toolsDir = File(configDir, "trailmaps/demo2/tools").apply { mkdirs() }
    File(toolsDir, "demo2_someTool.ts").writeText(
      """
        |import { trailblaze } from "@trailblaze/scripting";
        |
        |export const demo2_someTool = trailblaze.tool(
        |  { supportedPlatforms: ["android"], requiresContext: true },
        |  async (_input, ctx) => "ok",
        |);
      """.trimMargin(),
    )

    val catalog = TrailmapCatalogBuilder.build(sourceFor(configDir))
    val demo2 = catalog.single { it.id == "demo2" }

    assertTrue(demo2.tools.any { it.name == "demo2_someTool" })
    assertEquals(emptyList(), demo2.trailheads)
  }

  @Test
  fun `an existing yaml-sourced trailhead keeps rendering unchanged alongside an inline one`() {
    val configDir = tempFolder.newFolder("config-mixed")
    val trailheadsDir = File(configDir, "trailmaps/demo3/trailheads").apply { mkdirs() }
    File(trailheadsDir, "demo3_yamlTrailhead.trailhead.yaml").writeText(
      """
        |description: "existing sidecar trailhead"
        |trailhead:
        |  to: "demo3/home"
        |tools: []
      """.trimMargin(),
    )
    val toolsDir = File(configDir, "trailmaps/demo3/tools").apply { mkdirs() }
    File(toolsDir, "demo3_inlineTrailhead.ts").writeText(
      """
        |import { trailblaze } from "@trailblaze/scripting";
        |
        |export const demo3_inlineTrailhead = trailblaze.tool(
        |  {
        |    supportedPlatforms: ["android"],
        |    requiresContext: true,
        |    trailhead: { to: "demo3/other" },
        |  },
        |  async (_input, ctx) => "ok",
        |);
      """.trimMargin(),
    )

    val catalog = TrailmapCatalogBuilder.build(sourceFor(configDir))
    val demo3 = catalog.single { it.id == "demo3" }

    val trailheadNames = demo3.trailheads.map { it.name }
    assertTrue(
      trailheadNames.containsAll(listOf("demo3_yamlTrailhead", "demo3_inlineTrailhead")),
      "expected both the yaml-sourced and inline trailheads, got $trailheadNames",
    )
  }
}
