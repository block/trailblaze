package xyz.block.trailblaze.docs

import ai.koog.agents.core.tools.annotations.LLMDescription
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.toolcalls.ResolvedTargetToolDetailRenderer
import xyz.block.trailblaze.toolcalls.ResolvedTargetToolDetailRenderer.ToolDetail
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass

/**
 * Focused tests for [TargetToolBaselineGenerator]. The full `generate()` walks the
 * framework classpath (`AppTargetYamlLoader.discoverConfigs()` + companions) which is
 * exercised end-to-end by `./gradlew :internal-docs-generator:run` + CI's
 * static-checks `git diff --exit-code` assertion. This file pins the part of the
 * generator that *can* be tested without that setup: the per-tool sidecar orphan-prune
 * behavior, which is the dogfood contract with the workspace `ResolvedTargetReportEmitter`
 * — stale emitter-owned sidecars must be removed, hand-authored siblings must survive.
 */
class TargetToolBaselineGeneratorTest {

  private val tempDirs = mutableListOf<File>()

  @AfterTest
  fun cleanup() {
    tempDirs.forEach { it.deleteRecursively() }
    tempDirs.clear()
  }

  private fun newDir(name: String): File {
    val parent = createTempDirectory("target-baseline-test").toFile()
    tempDirs += parent
    return File(parent, name).apply { mkdirs() }
  }

  @Test
  fun `pruneStaleSidecars deletes emitter-owned files not in the keep set`() {
    val sidecarDir = newDir("sidecars")
    val stale = File(sidecarDir, "stale_tool.md").apply {
      writeText(
        """
        ${ResolvedTargetToolDetailRenderer.GENERATED_BANNER}
        # stale
        """.trimIndent(),
      )
    }
    val current = File(sidecarDir, "current_tool.md").apply {
      writeText(
        """
        ${ResolvedTargetToolDetailRenderer.GENERATED_BANNER}
        # current
        """.trimIndent(),
      )
    }

    TargetToolBaselineGenerator(generatedDir = newDir("ignored"))
      .pruneStaleSidecars(sidecarDir = sidecarDir, keepNames = setOf("current_tool.md"))

    assertFalse("stale emitter-owned sidecar should be deleted") { stale.exists() }
    assertTrue("current emitter-owned sidecar should be preserved") { current.exists() }
  }

  @Test
  fun `pruneStaleSidecars leaves hand-authored files alone even if not in keep set`() {
    val sidecarDir = newDir("sidecars")
    val handAuthored = File(sidecarDir, "design_notes.md").apply {
      writeText("# Hand-authored — must survive\n\nLong-form design notes that don't belong to any tool.\n")
    }

    TargetToolBaselineGenerator(generatedDir = newDir("ignored"))
      .pruneStaleSidecars(sidecarDir = sidecarDir, keepNames = emptySet())

    assertTrue("hand-authored file without the banner must survive") { handAuthored.exists() }
  }

  @Test
  fun `pruneStaleSidecars is a no-op when the sidecar directory does not exist`() {
    // Catches the early-return path so it never throws if a target has never had any
    // tools (e.g. a newly added target that hasn't been generated yet).
    val nonexistent = File(newDir("parent"), "tools")
    TargetToolBaselineGenerator(generatedDir = newDir("ignored"))
      .pruneStaleSidecars(sidecarDir = nonexistent, keepNames = emptySet())
    // No exception thrown is the assertion; reaching here means the no-op path holds.
    assertFalse("dir should still not exist after a no-op prune") { nonexistent.exists() }
  }

  @Test
  fun `pruneOrphanedTargets deletes the page and sidecars of a target that is no longer generated`() {
    val targetsDir = newDir("targets")
    fun page(id: String) = File(targetsDir, "TARGET_$id.md").apply {
      writeText("# Target: $id\n\n${DocsGenerator.THIS_DOC_IS_GENERATED_MESSAGE}\n")
    }
    fun sidecar(id: String) = File(targetsDir, "$id/tools/tap.md").apply {
      parentFile.mkdirs()
      writeText("${ResolvedTargetToolDetailRenderer.GENERATED_BANNER}\n# tap\n")
    }
    val keptPage = page("kept")
    val keptSidecar = sidecar("kept")
    val orphanPage = page("gone")
    val orphanSidecar = sidecar("gone")

    TargetToolBaselineGenerator(generatedDir = newDir("ignored"))
      .pruneOrphanedTargets(targetsDir = targetsDir, keepIds = setOf("kept"))

    assertFalse("orphaned target page should be deleted") { orphanPage.exists() }
    assertFalse("orphaned target directory should be deleted") { File(targetsDir, "gone").exists() }
    assertFalse("orphaned sidecar should be deleted") { orphanSidecar.exists() }
    assertTrue("current target page should be preserved") { keptPage.exists() }
    assertTrue("current target sidecar should be preserved") { keptSidecar.exists() }
  }

  @Test
  fun `pruneOrphanedTargets leaves hand-authored files alone`() {
    val targetsDir = newDir("targets")
    // Titled like a generated page, but without the generated banner.
    val handPage = File(targetsDir, "TARGET_notes.md").apply { writeText("# Target: Notes\n\nHand-authored.\n") }
    val handSidecar = File(targetsDir, "notes/tools/design.md").apply {
      parentFile.mkdirs()
      writeText("# Hand-authored\n")
    }

    TargetToolBaselineGenerator(generatedDir = newDir("ignored"))
      .pruneOrphanedTargets(targetsDir = targetsDir, keepIds = emptySet())

    assertTrue("a TARGET_ file without the generated banner must survive") { handPage.exists() }
    assertTrue("a sidecar without the generated banner must survive") { handSidecar.exists() }
  }

  private fun scripted(config: InlineScriptToolConfig) = ToolDetail.Scripted(
    name = config.name,
    config = config,
    originTrailmapId = "t",
    consumerTrailmapId = "t",
  )

  @Test
  fun `llmDescriptionTokens counts the tool description plus every parameter description`() {
    val config = InlineScriptToolConfig(
      script = "./tools/t.ts",
      name = "t",
      description = "a".repeat(10),
      inputSchema = JsonObject(
        mapOf(
          "properties" to JsonObject(
            mapOf(
              "first" to JsonObject(mapOf("description" to JsonPrimitive("b".repeat(5)))),
              "second" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
            ),
          ),
        ),
      ),
    )

    // 10 + 5 characters, rounded up to whole tokens at four characters each.
    assertEquals(4, TargetToolBaselineGenerator(generatedDir = newDir("ignored")).llmDescriptionTokens(scripted(config)))
  }

  @Test
  fun `llmDescriptionTokens is null for a tool hidden from the LLM`() {
    val generator = TargetToolBaselineGenerator(generatedDir = newDir("ignored"))
    val hidden = InlineScriptToolConfig(script = "./tools/t.ts", name = "t", description = "text", surfaceToLlm = false)
    val hiddenByMeta = hidden.copy(
      surfaceToLlm = true,
      meta = JsonObject(mapOf("trailblaze/surfaceToLlm" to JsonPrimitive(false))),
    )

    assertNull(generator.llmDescriptionTokens(scripted(hidden)))
    assertNull(generator.llmDescriptionTokens(scripted(hiddenByMeta)))
    assertEquals(1, generator.llmDescriptionTokens(scripted(hidden.copy(surfaceToLlm = true))))
  }

  @Test
  fun `llmDescriptionTokens counts the field descriptions of a nested object parameter`() {
    val detail = ToolDetail.ClassBacked(name = "nestedFixture", kclass = NestedFixtureTool::class)

    // "aaaa" + "cccc" + the nested field's "bbbb": 12 characters, 3 tokens.
    assertEquals(3, TargetToolBaselineGenerator(generatedDir = newDir("ignored")).llmDescriptionTokens(detail))
  }
}

@Serializable
data class NestedFixtureItem(@property:LLMDescription("bbbb") val name: String)

@Serializable
@TrailblazeToolClass("nestedFixture")
@LLMDescription("aaaa")
data class NestedFixtureTool(
  @param:LLMDescription("cccc") val items: List<NestedFixtureItem>,
) : TrailblazeTool
