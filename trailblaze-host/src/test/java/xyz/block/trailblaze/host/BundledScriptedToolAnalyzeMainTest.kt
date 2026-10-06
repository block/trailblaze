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
  fun `helper modules, declarations and tests are not selected`() {
    write("helpers.ts", "export function shared() {}\n")
    write("types.d.ts", twoExports)
    write("card_reader.test.ts", twoExports)

    assertEquals(emptyList(), selected())
  }
}
