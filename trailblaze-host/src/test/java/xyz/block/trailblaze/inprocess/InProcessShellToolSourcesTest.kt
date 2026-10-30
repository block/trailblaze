package xyz.block.trailblaze.inprocess

import assertk.assertThat
import assertk.assertions.isEqualTo
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Which `.ts` tools a shell packages as in-process, judged by the descriptor that names each one. */
class InProcessShellToolSourcesTest {

  @get:Rule val temp = TemporaryFolder()

  private fun trailmap(vararg files: Pair<String, String>): File = temp.newFolder("app").apply {
    files.forEach { (path, text) -> File(this, path).apply { parentFile.mkdirs() }.writeText(text) }
  }

  private val toolBody = "export const foo = trailblaze.tool(async () => \"ok\");\n"

  @Test
  fun `a same-basename yaml for another script does not make a tool subprocess`() {
    val dir = trailmap(
      "tools/foo.ts" to toolBody,
      "tools/foo.yaml" to "script: ./bar.ts\nname: barTool\nruntime: subprocess\n",
      "tools/bar.ts" to "export async function run() { return \"ok\" }\n",
    )

    assertThat(InProcessShellPackager.carriesTypeScriptToolSource(listOf(dir))).isEqualTo(true)
  }

  @Test
  fun `a subprocess descriptor keeps its script out of the shell`() {
    val dir = trailmap(
      "tools/card.yaml" to "script: ./reader.ts\nname: cardReader\nruntime: subprocess\n",
      "tools/reader.ts" to toolBody,
    )

    assertThat(InProcessShellPackager.carriesTypeScriptToolSource(listOf(dir))).isEqualTo(false)
  }

  @Test
  fun `a yaml that is not a valid descriptor does not claim its script`() {
    val dir = trailmap(
      "tools/broken.yaml" to "script: ./bar.ts\nname: []\nruntime: subprocess\n",
      "tools/bar.ts" to toolBody,
    )

    assertThat(InProcessShellPackager.carriesTypeScriptToolSource(listOf(dir))).isEqualTo(true)
  }

  @Test
  fun `a quoted script path with spaces names its script`() {
    val dir = trailmap(
      "tools/legacy.yaml" to "script: \"./legacy helper.ts\"\nname: legacyHelper\nruntime: subprocess\n",
      "tools/legacy helper.ts" to toolBody,
    )

    assertThat(InProcessShellPackager.carriesTypeScriptToolSource(listOf(dir))).isEqualTo(false)
  }

  @Test
  fun `a block tools list after a comment still describes its legacy script`() {
    val dir = trailmap(
      "tools/legacy.yaml" to "tools: # grouped actions\n  - name: grouped\n",
      "tools/legacy.ts" to "export async function grouped() { return \"ok\" }\n",
    )

    assertThat(InProcessShellPackager.carriesTypeScriptToolSource(listOf(dir))).isEqualTo(true)
  }

  @Test
  fun `an absolute script path names its script`() {
    val dir = trailmap("tools/legacy.ts" to "export async function run() { return \"ok\" }\n")
    File(dir, "tools/legacy.yaml").writeText("script: ${File(dir, "tools/legacy.ts").absolutePath}\nname: legacy\n")

    assertThat(InProcessShellPackager.carriesTypeScriptToolSource(listOf(dir))).isEqualTo(true)
  }

  @Test
  fun `a double-quoted script path has its escapes decoded`() {
    val dir = trailmap(
      "tools/legacy.yaml" to "script: \"./legacy\\u0020helper.ts\"\nname: legacy\n",
      "tools/legacy helper.ts" to "export async function run() { return \"ok\" }\n",
    )

    assertThat(InProcessShellPackager.carriesTypeScriptToolSource(listOf(dir))).isEqualTo(true)
  }

  @Test
  fun `a block-style script path names its script`() {
    val dir = trailmap(
      "tools/legacy.yaml" to "script: >-\n  ./legacy.ts\nname: legacy\n",
      "tools/legacy.ts" to "export async function run() { return \"ok\" }\n",
    )

    assertThat(InProcessShellPackager.carriesTypeScriptToolSource(listOf(dir))).isEqualTo(true)
  }

  @Test
  fun `a block-style subprocess descriptor keeps its script out of the shell`() {
    val dir = trailmap(
      "tools/card.yaml" to "script: |-\n  ./reader.ts\nname: cardReader\nruntime: subprocess\n",
      "tools/reader.ts" to toolBody,
    )

    assertThat(InProcessShellPackager.carriesTypeScriptToolSource(listOf(dir))).isEqualTo(false)
  }

  @Test
  fun `an optional field set to null is absent`() {
    val dir = trailmap(
      "tools/legacy.yaml" to "script: ./legacy.ts\nname: legacy\ntools: null\n",
      "tools/legacy.ts" to "export async function run() { return \"ok\" }\n",
    )

    assertThat(InProcessShellPackager.carriesTypeScriptToolSource(listOf(dir))).isEqualTo(true)
  }

  @Test
  fun `a subprocess descriptor with a null name keeps its script out of the shell`() {
    val dir = trailmap(
      "tools/card.yaml" to "script: ./reader.ts\nname: ~\nruntime: subprocess\n",
      "tools/reader.ts" to toolBody,
    )

    assertThat(InProcessShellPackager.carriesTypeScriptToolSource(listOf(dir))).isEqualTo(false)
  }
}
