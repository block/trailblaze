package xyz.block.trailblaze.host

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.ios.tools.IosOpenAppTrailblazeTool
import xyz.block.trailblaze.toolcalls.REDACTED_TOOL_ARG_PLACEHOLDER
import xyz.block.trailblaze.toolcalls.commands.MaestroTrailblazeTool
import xyz.block.trailblaze.yaml.createTrailblazeYaml

/** The session-start copy of a submitted trail, which is persisted and shown in reports. */
class SensitiveTrailYamlTest {

  private val trailblazeYaml = createTrailblazeYaml(setOf(IosOpenAppTrailblazeTool::class, MaestroTrailblazeTool::class))

  private fun persistedCopy(yaml: String): String {
    val items = trailblazeYaml.decodeTrailOrToolEnvelope(
      yaml,
      deviceClassifiers = listOf(TrailblazeDeviceClassifier("ios")),
    )
    return trailblazeYaml.persistedTrailYaml(yaml, items)
  }

  @Test
  fun `a recorded launch argument is masked and the rest of the trail is kept`() {
    val copy = persistedCopy(
      """
      config: {}
      trail:
        - step: Open the app signed in
          recording:
            ios:
              - ios_openApp:
                  appId: com.example.app
                  arguments:
                    SessionToken: fixture-session-token-7f3a
                    skipPermissionsSetup: true
      """.trimIndent(),
    )

    assertFalse(copy.contains("fixture-session-token-7f3a"), copy)
    assertTrue(copy.contains("com.example.app"), copy)
    assertTrue(copy.contains("skipPermissionsSetup: true"), copy)
  }

  @Test
  fun `another device's recording is masked too, since the whole document is kept`() {
    val copy = persistedCopy(
      """
      config: {}
      trail:
        - step: Open the app signed in
          recording:
            ios:
              - ios_openApp:
                  appId: com.example.app
                  arguments:
                    SessionToken: fixture-ios-token-7f3a
            android:
              - ios_openApp:
                  appId: com.example.app
                  arguments:
                    SessionToken: fixture-android-token-9b2c
      """.trimIndent(),
    )

    assertFalse(copy.contains("fixture-ios-token-7f3a"), copy)
    assertFalse(copy.contains("fixture-android-token-9b2c"), copy)
  }

  @Test
  fun `a value written verbatim once and escaped elsewhere is replaced by the mask`() {
    val copy = persistedCopy(
      """
      config: {}
      trail:
        - step: Open the app signed in
          recording:
            ios:
              - ios_openApp:
                  appId: com.example.app
                  arguments:
                    SessionToken: 'fixture"session-token-7f3a'
              - ios_openApp:
                  appId: com.example.app
                  arguments:
                    SessionToken: "fixture\"session-token-7f3a"
      """.trimIndent(),
    )

    assertEquals(REDACTED_TOOL_ARG_PLACEHOLDER, copy)
  }

  @Test
  fun `a single tool call, as the MCP bridge submits it, is masked`() {
    val copy = persistedCopy(
      """
      - ios_openApp:
          appId: com.example.app
          arguments:
            SessionToken: fixture-session-token-7f3a
      """.trimIndent(),
    )

    assertFalse(copy.contains("fixture-session-token-7f3a"), copy)
  }

  @Test
  fun `a value YAML escapes is replaced by the mask`() {
    val copy = persistedCopy(
      """
      - ios_openApp:
          appId: com.example.app
          arguments:
            SessionToken: "fixture\"session-token-7f3a"
      """.trimIndent(),
    )

    assertEquals(REDACTED_TOOL_ARG_PLACEHOLDER, copy)
  }

  @Test
  fun `a trail with nothing secret is kept as written`() {
    val yaml = """
      - ios_openApp:
          appId: com.example.app
          arguments:
            SessionToken: "{{session_token}}"
    """.trimIndent()

    assertEquals(yaml, persistedCopy(yaml))
  }

  @Test
  fun `a maestro launch argument that is a memory reference is kept, a literal one is masked`() {
    val copy = persistedCopy(
      """
      config: {}
      trail:
        - step: Open the app signed in
          recording:
            ios:
              - mobile_maestro:
                  commands:
                    - launchApp:
                        appId: com.example.app
                        arguments:
                          SessionToken: "{{session_token}}"
                          DeviceToken: fixture-device-token-4d1e
      """.trimIndent(),
    )

    assertTrue(copy.contains("{{session_token}}"), copy)
    assertFalse(copy.contains("fixture-device-token-4d1e"), copy)
  }
}
