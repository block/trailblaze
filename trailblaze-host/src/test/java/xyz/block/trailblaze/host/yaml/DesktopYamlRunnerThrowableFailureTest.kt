package xyz.block.trailblaze.host.yaml

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.model.PromptExecutor
import java.io.File
import java.util.ServiceConfigurationError
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.HostYamlRunResult
import xyz.block.trailblaze.host.driver.DeviceListingVisibility
import xyz.block.trailblaze.host.driver.HostDriverDescriptor
import xyz.block.trailblaze.host.driver.HostDriverDescriptorRegistry
import xyz.block.trailblaze.host.driver.HostDeviceInventory
import xyz.block.trailblaze.host.driver.HostRunDeps
import xyz.block.trailblaze.host.driver.HostScreenStateDeps
import xyz.block.trailblaze.host.minimalDeviceManager
import xyz.block.trailblaze.http.DynamicLlmClient
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import xyz.block.trailblaze.llm.TrailblazeReferrer
import xyz.block.trailblaze.model.DesktopAppRunYamlParams
import xyz.block.trailblaze.model.TrailExecutionResult
import xyz.block.trailblaze.model.TrailblazeConfig
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.ui.TrailblazeAnalytics

class DesktopYamlRunnerThrowableFailureTest {

  @Test
  fun `a nonfatal throwable from host execution reaches onComplete as failed`() = runBlocking {
    val result = runHostFailure(ServiceConfigurationError(null))

    assertContains(result.errorMessage.orEmpty(), ServiceConfigurationError::class.java.simpleName)
  }

  @Test
  fun `a virtual machine error reaches onComplete as failed before rethrow`() {
    val failureMessage = "JVM is compromised"
    val failure = TestVirtualMachineError(failureMessage)
    val executionResult = TrailExecutionResult.Failed(failureMessage)
    var completedResult: TrailExecutionResult? = null

    val thrown =
      assertFailsWith<TestVirtualMachineError> {
        DesktopYamlRunner.completeProcessFatalFailure(
          fatalFailure = failure,
          executionResult = executionResult,
          onComplete = { completedResult = it },
        )
      }

    assertSame(failure, thrown)
    assertEquals(executionResult, completedResult)
  }

  @Test
  fun `a callback failure is suppressed on the virtual machine error`() {
    val failure = TestVirtualMachineError("JVM is compromised")
    val callbackFailure = IllegalStateException("Completion callback failed")

    val thrown =
      assertFailsWith<TestVirtualMachineError> {
        DesktopYamlRunner.completeProcessFatalFailure(
          fatalFailure = failure,
          executionResult = TrailExecutionResult.Failed(failure.message.orEmpty()),
          onComplete = { throw callbackFailure },
        )
      }

    assertSame(failure, thrown)
    assertEquals(listOf(callbackFailure), thrown.suppressed.toList())
  }

  private suspend fun runHostFailure(failure: Throwable): TrailExecutionResult.Failed {
    val tempDir = File.createTempFile("desktop-runner-throwable-", "").also {
      it.delete()
      it.mkdirs()
    }
    try {
      val device =
        TrailblazeConnectedDeviceSummary(
          trailblazeDriverType = TrailblazeDriverType.IOS_HOST,
          instanceId = "desktop-runner-throwable-ios",
          description = "Desktop runner throwable test",
        )
      val descriptor =
        object : HostDriverDescriptor {
          override val driverTypes = setOf(TrailblazeDriverType.IOS_HOST)
          override val listingVisibility = DeviceListingVisibility.ADDRESSABLE_NOT_LISTED

          override suspend fun discoverDevices(
            inventory: HostDeviceInventory
          ): List<TrailblazeConnectedDeviceSummary> = listOf(device)

          override suspend fun runYaml(
            deps: HostRunDeps,
            params: RunOnHostParams,
          ): HostYamlRunResult = throw failure

          override suspend fun screenState(
            driverType: TrailblazeDriverType,
            deviceId: TrailblazeDeviceId,
            deps: HostScreenStateDeps,
          ): ScreenState? = error("screen capture is not part of this test")
        }
      val deviceManager =
        minimalDeviceManager(
          tempDir = tempDir,
          registry = HostDriverDescriptorRegistry(setOf(descriptor)),
        )
      val runner =
        DesktopYamlRunner(
          trailblazeDeviceManager = deviceManager,
          trailblazeAnalytics = TrailblazeAnalytics.NoOp,
          trailblazeHostAppTargetProvider = {
            TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget
          },
          dynamicLlmClientProvider = { noLlm },
          logsDirProvider = { File(tempDir, "logs") },
        )
      val completion = CompletableDeferred<TrailExecutionResult>()

      runner.runYaml(
        DesktopAppRunYamlParams(
          forceStopTargetApp = false,
          runYamlRequest =
            RunYamlRequest(
              testName = "desktop runner throwable",
              yaml = "",
              trailFilePath = null,
              targetAppName = null,
              useRecordedSteps = false,
              trailblazeDeviceId = device.trailblazeDeviceId,
              trailblazeLlmModel = testModel,
              config = TrailblazeConfig(),
              referrer = TrailblazeReferrer.YAML_TAB,
            ),
          targetTestApp = null,
          onProgressMessage = {},
          onConnectionStatus = {},
          additionalInstrumentationArgs = emptyMap(),
          onComplete = { completion.complete(it) },
          noLogging = true,
          captureVideo = false,
          captureLogcat = false,
          captureIosLogs = false,
        )
      )

      return assertIs<TrailExecutionResult.Failed>(completion.await())
    } finally {
      tempDir.deleteRecursively()
    }
  }

  private class TestVirtualMachineError(message: String) : VirtualMachineError(message)

  private val noLlm =
    object : DynamicLlmClient {
      override fun createPromptExecutor(): PromptExecutor = error("no LLM in this test")

      override fun createLlmClient(): LLMClient = error("no LLM in this test")
    }

  private val testModel =
    TrailblazeLlmModel(
      trailblazeLlmProvider = TrailblazeLlmProvider(id = "test", display = "Test"),
      modelId = "test-model",
      inputCostPerOneMillionTokens = 0.0,
      outputCostPerOneMillionTokens = 0.0,
      contextLength = 1000,
      maxOutputTokens = 1000,
      capabilityIds = emptyList(),
    )
}
