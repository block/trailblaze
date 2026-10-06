package xyz.block.trailblaze.host

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.TrailblazeHostYamlRunner.trailSessionMetadata
import xyz.block.trailblaze.host.rules.HostTrailblazeLoggingRule
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import xyz.block.trailblaze.llm.TrailblazeReferrer
import xyz.block.trailblaze.model.TrailblazeConfig

/** A tool reads the trail it serves off the session its execution context provides. */
class TrailblazeHostYamlRunnerTrailSessionMetadataTest {

  @Test
  fun `a session started for a trail carries the trail's config id`() {
    val trailYaml =
      """
      config:
        id: add-item
      trail:
        - step: Add an item
      """
        .trimIndent()

    assertEquals("add-item", trailIdSeenBySessionTools(runYamlRequest(trailYaml)))
  }

  @Test
  fun `a trail recording tools this host has not registered still yields its config id`() {
    val trailYaml =
      """
      config:
        id: add-item
      trailhead:
        step: Launch the app signed in
        recording:
          android-phone:
            app_navigateToTrailhead:
              account:
                type: persona
                persona: example-user
      trail:
        - step: Add an item
          recording:
            android-phone:
              - app_addItem:
                  quantity: 1
      """
        .trimIndent()

    assertEquals("add-item", runYamlRequest(trailYaml).trailSessionMetadata().trailId)
  }

  @Test
  fun `a trail without a config id leaves the session without one`() {
    val trailYaml =
      """
      config:
        title: Add an item
      trail:
        - step: Add an item
      """
        .trimIndent()

    assertNull(runYamlRequest(trailYaml).trailSessionMetadata().trailId)
  }

  @Test
  fun `YAML that does not decode leaves the session without a trail id instead of throwing`() {
    assertNull(runYamlRequest("config: [unclosed").trailSessionMetadata().trailId)
  }

  private fun trailIdSeenBySessionTools(request: RunYamlRequest): String? {
    val logsDir = Files.createTempDirectory("host-trail-session-metadata-").toFile()
    try {
      val loggingRule =
        HostTrailblazeLoggingRule(
          trailblazeDeviceInfoProvider = {
            TrailblazeDeviceInfo(
              trailblazeDeviceId = request.trailblazeDeviceId,
              trailblazeDriverType = TrailblazeDriverType.IOS_HOST,
              widthPixels = 390,
              heightPixels = 844,
            )
          },
          logsDir = logsDir,
        )
      var trailId: String? = null
      runBlocking {
        TrailblazeHostYamlRunner.executeTrailSession(
          loggingRule = loggingRule,
          overrideSessionId = null,
          testName = request.testName,
          deviceLabel = "ios-host:simulated-ios",
          sendSessionEndLog = false,
          onProgressMessage = {},
          screenshotProvider = { error("No screenshot needed for this test") },
          metadata = request.trailSessionMetadata(),
        ) { session ->
          // Tool execution contexts resolve the session through the logging rule.
          trailId = loggingRule.session?.metadata?.trailId
          session.sessionId
        }
      }
      return trailId
    } finally {
      logsDir.deleteRecursively()
    }
  }

  private fun runYamlRequest(yaml: String) =
    RunYamlRequest(
      testName = "test",
      yaml = yaml,
      trailFilePath = null,
      targetAppName = null,
      useRecordedSteps = false,
      trailblazeDeviceId =
        TrailblazeDeviceId(
          instanceId = "simulated-ios",
          trailblazeDevicePlatform = TrailblazeDevicePlatform.IOS,
        ),
      trailblazeLlmModel =
        TrailblazeLlmModel(
          trailblazeLlmProvider = TrailblazeLlmProvider(id = "test", display = "Test"),
          modelId = "test-model",
          inputCostPerOneMillionTokens = 0.0,
          outputCostPerOneMillionTokens = 0.0,
          contextLength = 1000,
          maxOutputTokens = 1000,
          capabilityIds = emptyList(),
        ),
      config = TrailblazeConfig(),
      referrer = TrailblazeReferrer(id = "test", display = "Test"),
    )
}
