package xyz.block.trailblaze.cli.inprocess

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MakeTestApkCommandTest {

  @get:Rule val temp = TemporaryFolder()

  @Test
  fun `a host with no SDK source tree still resolves the scripting SDK, from the framework JAR`() {
    // The whole point of this command is that a team can run it with the app's APK, a key, and no
    // framework checkout. An installed CLI is a JAR and a launcher — no `sdks/typescript` to walk
    // up to and no TRAILBLAZE_SDK_DIR — so with only the source-tree tier, the one thing that
    // could not be bundled is a `--trailmap` of TypeScript, which is what most teams have.
    val cacheRoot = File(temp.root, "sdk-cache")

    val entry = MakeTestApkCommand.resolveInProcessSdkEntry(sourceTreeEntry = null, cacheRoot = cacheRoot)

    assertThat(entry).isNotNull()
    assertThat(entry!!.isFile).isTrue()
    assertThat(entry.length() > 0).isTrue()
    assertThat(entry.canonicalFile).isEqualTo(File(cacheRoot, "dist/index.js").canonicalFile)
  }

  @Test
  fun `a reachable SDK source tree wins over the extracted copy`() {
    // Extraction is the fallback, not the answer. A framework developer editing the SDK has to see
    // their edit in the bundle, and a cached extract of the shipped copy would hide it.
    val sourceEntry = File(temp.newFolder(), "in-process.ts").apply { writeText("export {}\n") }

    val entry = MakeTestApkCommand.resolveInProcessSdkEntry(
      sourceTreeEntry = sourceEntry,
      cacheRoot = File(temp.root, "unused-cache"),
    )

    assertThat(entry).isEqualTo(sourceEntry)
    assertThat(File(temp.root, "unused-cache").exists()).isEqualTo(false)
  }
}
