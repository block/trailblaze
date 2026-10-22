package xyz.block.trailblaze.config

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assume.assumeTrue
import xyz.block.trailblaze.config.project.TrailblazeProjectConfigLoader
import xyz.block.trailblaze.scripting.AnalyzerScriptedToolEnrichment
import xyz.block.trailblaze.scripting.MetaOnlyDescriptorTestFixture

/**
 * Sharing a TypeScript tool across trailmaps: the pack lists a `.ts` tool under its own
 * `target.platforms.android.tools:` and exports it; a consumer that only declares the dependency
 * gets it in its runtime tools, still scoped to android. The platform scope and description come
 * from the tool's own spec and TSDoc, so this runs through the real analyzer.
 */
class ExportedScriptedToolAcrossTrailmapsTest {

  private val root: File = createTempDirectory("exported-scripted-tool").toFile()

  @AfterTest
  fun cleanup() {
    root.deleteRecursively()
  }

  @Test
  fun `consumer inherits a dependency's exported platform-scoped TypeScript tool`() {
    val enrichment = AnalyzerScriptedToolEnrichment.resolveFromEnvironment()
    assumeTrue(MetaOnlyDescriptorTestFixture.ANALYZER_UNAVAILABLE_SKIP_MESSAGE, enrichment != null)

    val packDir = File(root, "trailmaps/pack").apply { mkdirs() }
    File(packDir, "trailmap.yaml").writeText(
      """
      id: pack
      exports:
        - packAndroidTool
      target:
        display_name: Pack
        platforms:
          android:
            tools:
              - packAndroidTool
      """.trimIndent(),
    )
    File(packDir, "tools").mkdirs()
    File(packDir, "tools/packAndroidTool.ts").writeText(
      """
      import { trailblaze } from "@trailblaze/scripting";

      /** Android-only tool published by the pack. */
      export const packAndroidTool = trailblaze.tool(
        { supportedPlatforms: ["android"] },
        async () => "ok",
      );
      """.trimIndent(),
    )
    val appDir = File(root, "trailmaps/myapp").apply { mkdirs() }
    File(appDir, "trailmap.yaml").writeText(
      """
      id: myapp
      dependencies:
        - pack
      target:
        display_name: My App
        platforms:
          android:
            app_ids: [com.example.myapp]
      """.trimIndent(),
    )
    val config = File(root, "trailblaze.yaml").apply { writeText("targets:\n  - myapp\n") }

    val resolved = TrailblazeProjectConfigLoader.loadResolvedRuntime(
      config,
      includeClasspathTrailmaps = false,
      scriptedToolEnrichment = enrichment,
    )!!
    val app = resolved.targets.single { it.id == "myapp" }

    val inherited = app.tools.orEmpty().single { it.name == "packAndroidTool" }
    assertEquals("Android-only tool published by the pack.", inherited.description)
    assertEquals(
      listOf("android"),
      (inherited.meta?.get("trailblaze/supportedPlatforms") as JsonArray).map { (it as JsonPrimitive).content },
    )
  }
}
