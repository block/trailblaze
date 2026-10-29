package xyz.block.trailblaze.host

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which `.ts` files the bundled-config analyzer treats as descriptor-less tools. It must agree
 * with the runtime loader: a `.ts` any YAML descriptor names in `script:` belongs to that YAML,
 * whatever the YAML file is called, so none of its exports register a second time here.
 */
class BundledScriptedToolAnalyzeMainTest {

  private val toolsDir: File = createTempDirectory("analyze-main-tools").toFile()

  @AfterTest
  fun cleanup() {
    toolsDir.deleteRecursively()
  }

  private fun write(path: String, content: String) =
    File(toolsDir, path).apply { parentFile.mkdirs() }.writeText(content)

  private fun selected(): List<String> =
    BundledScriptedToolAnalyzeMain.descriptorlessToolSources(toolsDir)
      .map { it.relativeTo(toolsDir).invariantSeparatorsPath }

  private val twoExports = """
    export const connectReader = trailblaze.tool({}, async () => ({ content: [] }));
    export const insertCard = trailblaze.tool({}, async () => ({ content: [] }));
  """.trimIndent()

  @Test
  fun `a bare multi-export ts with no YAML is selected`() {
    write("card_reader.ts", twoExports)

    assertEquals(listOf("card_reader.ts"), selected())
  }

  @Test
  fun `a ts named by a differently named YAML is not selected`() {
    write("card_reader.ts", twoExports)
    write("connect_only.yaml", "script: ./card_reader.ts\nname: connectReader\n")

    assertEquals(emptyList(), selected())
  }

  @Test
  fun `a YAML in a subdirectory covers the script it names relative to itself`() {
    write("shared/card_reader.ts", twoExports)
    write("android/connect_only.yaml", "script: ../shared/card_reader.ts\nname: connectReader\n")

    assertEquals(emptyList(), selected())
  }

  @Test
  fun `a YAML that does not decode covers nothing`() {
    write("card_reader.ts", twoExports)
    write("broken.yaml", "script: [unterminated\n")

    assertEquals(listOf("card_reader.ts"), selected())
  }

  @Test
  fun `meta-only and name-only YAMLs are described from their script, keeping what they declare`() {
    write("card_reader.ts", "export const connectReader = trailblaze.tool({}, async () => ({ content: [] }));\n")
    write("card_reader.yaml", "script: ./card_reader.ts\n_meta:\n  trailblaze/requiresHost: true\n")
    write("named.ts", "export const named = trailblaze.tool({}, async () => ({ content: [] }));\n")
    write("named.yaml", "script: ./named.ts\nname: named\n")
    // A complete descriptor needs nothing from its script.
    write("full.ts", "export const full = trailblaze.tool({}, async () => ({ content: [] }));\n")
    write("full.yaml", "script: ./full.ts\nname: full\ndescription: Fully described.\n")

    val partial = BundledScriptedToolAnalyzeMain.enrichableDescriptors(toolsDir)

    assertEquals(listOf("card_reader.yaml", "named.yaml"), partial.map { it.first.name })
    assertEquals(setOf("trailblaze/requiresHost"), partial.first().second.meta?.keys)
    assertEquals("named", partial.last().second.name)
    // The YAML owns its script, so the `.ts` is not also picked up as a bare tool.
    assertEquals(emptyList(), selected())
  }

  @Test
  fun `a same-named YAML that does not decode leaves the ts selected`() {
    write("card_reader.ts", twoExports)
    write("card_reader.yaml", "script: [unterminated\n")

    assertEquals(listOf("card_reader.ts"), selected())
  }

  @Test
  fun `helper modules, declarations and tests are not selected`() {
    write("helpers.ts", "export function shared() {}\n")
    write("types.d.ts", twoExports)
    write("card_reader.test.ts", twoExports)

    assertEquals(emptyList(), selected())
  }
}
