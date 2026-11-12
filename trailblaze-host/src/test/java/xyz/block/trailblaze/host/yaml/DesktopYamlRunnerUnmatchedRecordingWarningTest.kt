package xyz.block.trailblaze.host.yaml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier

/**
 * A step with recordings, none for the device it runs on, goes to the AI. The runner must say so
 * on the run's own output, naming the step, the keys it has and the keys the device looked for.
 */
class DesktopYamlRunnerUnmatchedRecordingWarningTest {

  private val iphone = listOf(TrailblazeDeviceClassifier("ios"), TrailblazeDeviceClassifier("iphone"))

  private val trail =
    """
    config:
      title: Mixed recordings
    trail:
      - step: "Open the dashboard"
        recording:
          android-phone:
            - launchApp: { appId: com.example }
          ios-ipad:
            - launchApp: { appId: com.example }
      - step: "Describe what is on screen"
      - step: "Go back"
        recording:
          ios:
            - launchApp: { appId: com.example }
    """.trimIndent()

  @Test
  fun `a step recorded only for other devices is named with its keys and this device's chain`() {
    val warnings = DesktopYamlRunner.unmatchedRecordingWarnings(trail, iphone, null)
    assertEquals(1, warnings.size)
    val warning = warnings.single()
    assertTrue(warning, warning.contains("Step 1 \"Open the dashboard\""))
    assertTrue(warning, warning.contains("[android-phone, ios-ipad]"))
    assertTrue(warning, warning.contains("ios-iphone, ios"))
  }

  @Test
  fun `plain-language steps and matched steps raise no warning`() {
    val matched =
      """
      config:
        title: Plain
      trail:
        - step: "Describe what is on screen"
        - step: "Go back"
          recording:
            ios:
              - launchApp: { appId: com.example }
      """.trimIndent()
    assertEquals(emptyList<String>(), DesktopYamlRunner.unmatchedRecordingWarnings(matched, iphone, null))
  }

  @Test
  fun `a step whose key matched but held only control markers is not called a key miss`() {
    // Its key is right; it needs re-recording, which the lowering's own log line says.
    val markerOnly =
      """
      config:
        title: Marker only
      trail:
        - step: "Open the dashboard"
          recording:
            ios:
              - objectiveStatus:
                  explanation: done
                  status: COMPLETED
      """.trimIndent()
    assertEquals(emptyList<String>(), DesktopYamlRunner.unmatchedRecordingWarnings(markerOnly, iphone, null))
  }

  @Test
  fun `a local browser matches web-browser recordings and raises no warning`() {
    val dashboard =
      """
      config:
        title: Dashboard
      trail:
        - step: "Open the dashboard"
          recording:
            web-browser:
              - launchApp: { appId: com.example }
      """.trimIndent()
    assertEquals(
      emptyList<String>(),
      DesktopYamlRunner.unmatchedRecordingWarnings(dashboard, listOf(TrailblazeDeviceClassifier("web")), null),
    )
  }
}
