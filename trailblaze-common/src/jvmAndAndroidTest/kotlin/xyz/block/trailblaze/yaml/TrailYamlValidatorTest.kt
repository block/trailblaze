package xyz.block.trailblaze.yaml

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TrailYamlValidatorTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  @Test
  fun `validateTrailFile accepts a unified trail with recordings without device classifiers`() {
    // Regression guard: a unified `trail.yaml` WITH recordings must validate even though the
    // validator has no device classifiers. `decodeTrail()` would throw its no-classifiers guard
    // here (it lowers for a specific device); validation must use the format-native
    // `decodeTrailDocument()`. This is the exact case that failed the trail-YAML validation gate
    // once `isTrailFile` started discovering unified files.
    val trailFile = tempFolder.newFile("trail.yaml")
    trailFile.writeText(
      """
      config:
        id: x/y
        target: x
        devices:
          android: ANDROID_ONDEVICE_ACCESSIBILITY
      trail:
        - step: Tap something
          recording:
            android:
              - tapOnPoint:
                  x: 1
                  y: 2
      """.trimIndent(),
    )

    val issue = TrailYamlValidator.validateTrailFile(trailFile)

    assertNull(issue, "unified trail with recordings must validate; got: ${issue?.errorMessage}")
  }

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
