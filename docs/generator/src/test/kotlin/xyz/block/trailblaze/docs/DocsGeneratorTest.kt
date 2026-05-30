package xyz.block.trailblaze.docs

import ai.koog.agents.core.tools.annotations.LLMDescription
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import xyz.block.trailblaze.config.ToolYamlConfig
import xyz.block.trailblaze.config.TrailblazeToolParameterConfig
import xyz.block.trailblaze.toolcalls.ResolvedTargetToolDetailRenderer
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass

/**
 * Focused tests for [DocsGenerator] — the OSS framework per-tool docs pipeline.
 *
 * Direct integration with the catalog walk (`ToolYamlLoader.discoverAndLoadAll()`) requires
 * the framework classpath at runtime, which is exercised end-to-end by
 * `./gradlew :docs:generator:run` + CI's `git diff --exit-code` assertion. This file pins
 * the pieces of the generator that *can* be tested without a full classpath: the
 * `surfaceToLlm` surface gate that decides which tools land in the framework reference
 * docs, and the file shape produced by `createPageForCommand` and
 * `createPageForYamlDefinedTool`.
 */
class DocsGeneratorTest {

  // ── Fixtures ────────────────────────────────────────────────────────────────────────

  @Serializable
  @LLMDescription("Surfaced to the LLM, should appear in framework docs.")
  @TrailblazeToolClass("visibleFixture", surfaceToLlm = true)
  private class VisibleFixture : TrailblazeTool

  @Serializable
  @LLMDescription("Hidden from the LLM, must not get a docs page.")
  @TrailblazeToolClass("hiddenFixture", surfaceToLlm = false)
  private class HiddenFixture : TrailblazeTool

  /**
   * Fixture with NO `@TrailblazeToolClass` annotation — represents a class on the
   * classpath that's misconfigured (annotation forgotten, or the dep got bumped and the
   * annotation moved). The framework docs generator must NOT throw when it encounters
   * such a class; it must log + skip so the rest of the docs build still completes.
   */
  @Serializable
  @LLMDescription("Missing the @TrailblazeToolClass annotation entirely.")
  private class UnannotatedFixture : TrailblazeTool

  private val tempDirs = mutableListOf<File>()

  @AfterTest
  fun cleanup() {
    tempDirs.forEach { it.deleteRecursively() }
    tempDirs.clear()
  }

  private fun newDir(name: String): File {
    val parent = createTempDirectory("docs-generator-test").toFile()
    tempDirs += parent
    return File(parent, name).apply { mkdirs() }
  }

  private fun newGenerator(): Pair<DocsGenerator, File> {
    val generatedDir = newDir("generated")
    val functionsDir = File(generatedDir, "functions").apply { mkdirs() }
    return DocsGenerator(generatedFunctionsDocsDir = functionsDir) to functionsDir
  }

  // ── Surface gate ────────────────────────────────────────────────────────────────────

  @Test
  fun `createPageForCommand emits a page for a visible tool and returns the filename`() {
    val (gen, functionsDir) = newGenerator()
    val fileName = gen.createPageForCommand(VisibleFixture::class)
    assertTrue("expected a filename, got null") { fileName == "visibleFixture.md" }
    val emitted = File(functionsDir, "custom/visibleFixture.md")
    assertTrue("expected the .md file on disk") { emitted.exists() }
    val md = emitted.readText()
    assertTrue("emitter banner present") {
      md.startsWith(ResolvedTargetToolDetailRenderer.GENERATED_BANNER)
    }
    assertTrue("description present") { md.contains("Surfaced to the LLM, should appear in framework docs.") }
    assertTrue("framework header origin") { md.contains("Trailblaze framework tool reference") }
    assertTrue("framework regen hint") {
      md.contains("Regenerate with: ./gradlew :docs:generator:run")
    }
  }

  @Test
  fun `createPageForCommand skips a tool gated by surfaceToLlm=false`() {
    val (gen, functionsDir) = newGenerator()
    val fileName = gen.createPageForCommand(HiddenFixture::class)
    assertTrue("expected null filename for hidden tool, got $fileName") { fileName == null }
    val skipped = File(functionsDir, "custom/hiddenFixture.md")
    assertFalse("hidden tool must not produce a file") { skipped.exists() }
  }

  @Test
  fun `createPageForCommand skips a class with no @TrailblazeToolClass annotation instead of throwing`() {
    // Regression for the lead-dev review finding: pre-fix,
    // `toolKClass.trailblazeToolClassAnnotation()` would throw `error()` on an unannotated
    // class, aborting the entire OSS docs build. With the defensive `runCatching` wrapper,
    // the misconfigured class is logged + skipped and the rest of the catalog still gets
    // rendered.
    val (gen, functionsDir) = newGenerator()
    val fileName = gen.createPageForCommand(UnannotatedFixture::class)
    assertTrue("expected null filename for unannotated class, got $fileName") { fileName == null }
    val skipped = File(functionsDir, "custom/unannotatedFixture.md")
    assertFalse("unannotated class must not produce a file") { skipped.exists() }
    // And the surface gate must continue to work for a properly-annotated class even
    // after the bad one was encountered — proving "one bad class doesn't break the rest."
    val visibleFileName = gen.createPageForCommand(VisibleFixture::class)
    assertTrue("expected visible fixture to still render") { visibleFileName == "visibleFixture.md" }
    assertTrue("visible fixture file should exist") {
      File(functionsDir, "custom/visibleFixture.md").exists()
    }
  }

  // ── YAML-defined tools ──────────────────────────────────────────────────────────────

  @Test
  fun `createPageForYamlDefinedTool emits a YAML-flavored doc with id, description, and parameters`() {
    // The YAML-defined render path is hit when a framework tool is composed in
    // `tools:` mode (no Kotlin class) — e.g. `trails/config/tools/eraseText.tool.yaml`.
    // Surface gate, idempotency, and class-backed rendering already have coverage; this
    // case pins the YAML branch so a future regression in `ToolDetail.YamlDefined`
    // serialization doesn't only surface via the integration `:docs:generator:run`.
    val config = ToolYamlConfig(
      id = "eraseText",
      description = "Erase characters from the focused text field.",
      parameters = listOf(
        TrailblazeToolParameterConfig(
          name = "charactersToErase",
          type = "integer",
          required = false,
          description = "Number of characters to erase.",
        ),
      ),
      // `toolsList` is required to put the config in `tools:` mode — the YAML-defined
      // descriptor builder rejects `class:` mode here. A no-op JSON object is enough to
      // satisfy validation; the test focuses on doc emission, not tool execution.
      toolsList = listOf(buildJsonObject { put("noop", JsonPrimitive(true)) }),
    )
    val (gen, functionsDir) = newGenerator()
    val fileName = gen.createPageForYamlDefinedTool(config)
    assertTrue("expected eraseText.md returned, got $fileName") { fileName == "eraseText.md" }
    val emitted = File(functionsDir, "custom/eraseText.md")
    assertTrue("expected the YAML-defined .md file on disk") { emitted.exists() }
    val md = emitted.readText()
    assertTrue("banner present") { md.startsWith(ResolvedTargetToolDetailRenderer.GENERATED_BANNER) }
    assertTrue("description from YAML present") {
      md.contains("Erase characters from the focused text field.")
    }
    assertTrue("source kind labels as YAML-defined") { md.contains("- Kind: YAML-defined") }
    assertTrue("tool id row present") { md.contains("- Tool id: `eraseText`") }
    assertTrue("optional parameter row rendered with type + description") {
      md.contains("### Optional parameters") &&
        md.contains("- `charactersToErase` — `integer`") &&
        md.contains("Number of characters to erase.")
    }
    assertTrue("framework header origin") { md.contains("Trailblaze framework tool reference") }
  }

  @Test
  fun `createPageForCommand is idempotent across repeated calls on unchanged input`() {
    val (gen, functionsDir) = newGenerator()
    gen.createPageForCommand(VisibleFixture::class)
    val outFile = File(functionsDir, "custom/visibleFixture.md")
    val firstMtime = outFile.lastModified()
    // Second call should be a no-op write (content-hash compare in
    // ResolvedTargetIdempotentWrite); mtime must not move.
    Thread.sleep(10) // ensure mtime resolution would catch a write
    gen.createPageForCommand(VisibleFixture::class)
    assertTrue("mtime should not change on no-op re-emit") {
      outFile.lastModified() == firstMtime
    }
  }
}
