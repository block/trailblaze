package xyz.block.trailblaze.host

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import java.io.File
import org.junit.Test
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.host.TrailblazeHostYamlRunner.trailDirectory
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import xyz.block.trailblaze.llm.TrailblazeReferrer
import xyz.block.trailblaze.model.TrailblazeConfig

/**
 * Locks the DERIVATION every host agent anchors trail-relative paths with — the half
 * [xyz.block.trailblaze.MaestroTrailblazeAgentWorkingDirectoryTest] cannot see, since that one
 * starts from a `workingDirectory` already in hand.
 *
 * Both halves are needed: the agent test proves the value reaches every tool context, and this
 * one proves the runner computes the right value to hand it. Deleting `workingDirectory =` from
 * the agent construction sites is caught by neither in isolation, which is why the sites share
 * this single helper rather than repeating the expression.
 */
class TrailblazeHostYamlRunnerTrailDirectoryTest {

  @Test
  fun `an absolute trail path anchors to the trail's own directory`() {
    val request = runYamlRequest("/tmp/trailblaze-trail-source-1234/trails/k1/speech-8.trail.yaml")

    assertThat(request.trailDirectory())
      .isEqualTo(File("/tmp/trailblaze-trail-source-1234/trails/k1"))
  }

  @Test
  fun `no trail file means no anchor`() {
    assertThat(runYamlRequest(null).trailDirectory()).isNull()
  }

  /**
   * A bare filename has no parent, so there is nothing to anchor to and tools fall back to the
   * process CWD. Pinned because it is the one input that silently reproduces the pre-fix
   * behaviour — worth being deliberate rather than incidental.
   */
  @Test
  fun `a bare filename has no directory to anchor to`() {
    assertThat(runYamlRequest("speech-8.trail.yaml").trailDirectory()).isNull()
  }

  private fun runYamlRequest(trailFilePath: String?) = RunYamlRequest(
    testName = "test",
    yaml = "",
    trailFilePath = trailFilePath,
    targetAppName = null,
    useRecordedSteps = false,
    trailblazeDeviceId = TrailblazeDeviceId(
      instanceId = "fake-instance-id",
      trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
    ),
    trailblazeLlmModel = TrailblazeLlmModel(
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
