package xyz.block.trailblaze.yaml

import java.io.File
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TrailYamlValidatorTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  @Test
  fun `findAllTrailYamlFiles ignores comment-only trailblaze_yaml workspace anchor`() {
    val workspace = tempFolder.newFolder("workspace")
    val workspaceConfigFile = File(workspace, "trails/config/trailblaze.yaml").also {
      it.parentFile!!.mkdirs()
    }
    workspaceConfigFile.writeText(
      """
      # workspace anchor
      # config lives next door
      """.trimIndent(),
    )
    val sampleTrailFile = File(workspace, "trails/sample.trail.yaml").also {
      it.parentFile!!.mkdirs()
    }
    sampleTrailFile.writeText(
      """
      name: Sample trail
      steps: []
      """.trimIndent(),
    )

    val discovered = TrailYamlValidator.findAllTrailYamlFiles(File(workspace, "trails"))

    assertEquals(listOf(sampleTrailFile), discovered)
  }

  @Test
  fun `findAllTrailYamlFiles ignores populated project config trailblaze_yaml`() {
    val workspace = tempFolder.newFolder("workspace")
    val workspaceConfigFile = File(workspace, "trails/config/trailblaze.yaml").also {
      it.parentFile!!.mkdirs()
    }
    workspaceConfigFile.writeText(
      """
      llm:
        defaults:
          model: openai/gpt-4.1
      targets:
        - id: sampleapp
          display_name: Sample App
      """.trimIndent(),
    )
    val sampleTrailFile = File(workspace, "trails/sample.trail.yaml").also {
      it.parentFile!!.mkdirs()
    }
    sampleTrailFile.writeText(
      """
      name: Sample trail
      steps: []
      """.trimIndent(),
    )

    val discovered = TrailYamlValidator.findAllTrailYamlFiles(File(workspace, "trails"))

    assertEquals(listOf(sampleTrailFile), discovered)
  }

  @Test
  fun `findAllTrailYamlFiles keeps nested trailblaze_yaml trail definitions`() {
    val workspace = tempFolder.newFolder("workspace")
    val workspaceConfigFile = File(workspace, "trails/config/trailblaze.yaml").also {
      it.parentFile!!.mkdirs()
    }
    workspaceConfigFile.writeText("# workspace config")
    val flowsDir = File(workspace, "trails/flows").apply { mkdirs() }
    val trailblazeYaml = File(flowsDir, "trailblaze.yaml").apply {
      writeText(
        """
        - config:
            title: Root trail
            platform: android
            driver: DEFAULT_ANDROID
        - prompts:
          - step: Take a snapshot of the current screen
        """.trimIndent(),
      )
    }

    val discovered = TrailYamlValidator.findAllTrailYamlFiles(File(workspace, "trails"))

    assertEquals(listOf(trailblazeYaml), discovered)
  }
}
