package xyz.block.trailblaze.docs

import ai.koog.agents.core.tools.annotations.LLMDescription
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.llm.config.workspaceLayeredConfigResourceSource
import xyz.block.trailblaze.toolcalls.ResolvedTargetToolDetailRenderer.ToolDetail
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass

/**
 * Focused tests for [TargetToolBaselineGenerator]. The full `generate()` walks the
 * framework classpath (`AppTargetYamlLoader.discoverConfigs()` + companions) which is
 * exercised end-to-end by `./gradlew :internal-docs-generator:run` + CI's
 * static-checks `git diff --exit-code` assertion. This file pins the part of the
 * generator that *can* be tested without that setup: the token estimate and stale-file pruning.
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

  private fun scripted(config: InlineScriptToolConfig) = ToolDetail.Scripted(
    name = config.name,
    config = config,
    originTrailmapId = "t",
    consumerTrailmapId = "t",
  )

  @Test
  fun `pruning removes stale generated files and keeps hand-written ones`() {
    val dir = newDir("targets")
    val header = TargetToolBaselineGenerator.GENERATED_HEADER
    File(dir, "kept.md").writeText("$header\n\n# kept\n")
    File(dir, "stale.md").writeText("$header\n\n# stale\n")
    File(dir, "notes.md").writeText("# Hand-written notes\n")

    TargetToolBaselineGenerator(generatedDir = newDir("ignored")).pruneStaleFiles(dir, keep = setOf("kept.md"))

    assertEquals(setOf("kept.md", "notes.md"), dir.list()!!.toSet())
  }

  /**
   * A workspace target, written the way `trailblaze compile` writes it: under `dist/targets/`, with
   * its scripted tool's absolute path.
   */
  private fun workspaceWithTarget(): File {
    val root = newDir("workspace").canonicalFile
    val script = File(root, "trailblaze-config/trailmaps/acme/tools/acme_hello.ts")
      .apply { parentFile.mkdirs(); writeText("// fixture\n") }
    File(root, "trailblaze-config/dist/targets/acme.yaml").apply {
      parentFile.mkdirs()
      writeText(
        """
        id: "acme"
        display_name: "Acme"
        platforms:
          "android":
            tool_sets:
            - "core_interaction"
        tools:
        - script: "${script.absolutePath}"
          name: "acme_hello"
          description: "Say hello."
          inputSchema:
            "type": "object"
            "properties": {}
        """.trimIndent(),
      )
    }
    return root
  }

  @Test
  fun `a workspace baseline describes only the named targets, with workspace-relative script paths`() {
    val root = workspaceWithTarget()
    val out = newDir("surface")
    val header = "<!-- GENERATED FILE. DO NOT EDIT. Regenerate with: fixture -->"
    // Written by an earlier run with another regeneration command: still the generator's to delete.
    File(out, "gone.md").writeText("<!-- GENERATED FILE. DO NOT EDIT. Regenerate with: old --out -->\n")

    TargetToolBaselineGenerator(
      generatedDir = out,
      resourceSource = workspaceLayeredConfigResourceSource(File(root, "trailblaze-config")),
      onlyTargetIds = setOf("acme"),
      scriptPathRoot = root,
      regenerateWith = "fixture",
    ).generate(targetsDir = out)

    val readme = File(out, TargetToolBaselineGenerator.INDEX_FILE_NAME).readText()
    val targetsSection = readme.substringAfter("## Targets").substringBefore("##")
    assertEquals(listOf("- [`acme`](acme.md) — Acme"), targetsSection.lines().filter { it.isNotBlank() })
    val acme = File(out, "acme.md").readText()
    assertEquals(header, acme.lines().first())
    assertFalse(File(out, "gone.md").exists(), "a generated file for a removed trailmap survived")
    assertTrue("./trailblaze-config/trailmaps/acme/tools/acme_hello.ts" in acme, acme)
    out.listFiles()!!.forEach { assertFalse(root.absolutePath in it.readText(), "${it.name} leaks the workspace path") }
  }

  @Test
  fun `naming a target that is not discovered fails rather than writing a partial baseline`() {
    val root = workspaceWithTarget()
    val error = assertFailsWith<IllegalArgumentException> {
      TargetToolBaselineGenerator(
        generatedDir = newDir("surface"),
        resourceSource = workspaceLayeredConfigResourceSource(File(root, "trailblaze-config")),
        onlyTargetIds = setOf("acme", "missing"),
      ).generate()
    }
    assertTrue("missing" in error.message.orEmpty(), error.message)
  }

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
