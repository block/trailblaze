package xyz.block.trailblaze.host.yaml

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.model.PromptExecutor
import java.io.File
import java.util.ServiceConfigurationError
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertFalse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
import xyz.block.trailblaze.toolcalls.TrailExecution
import xyz.block.trailblaze.logs.model.SessionId

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

  @Test
  fun `unlogged completion waits for every lease finalizer`() = runBlocking {
    val releaseStarted = CompletableDeferred<Unit>()
    val allowRelease = CountDownLatch(1)
    val completionObserved = CompletableDeferred<Unit>()
    val finalizationObserved = CompletableDeferred<Unit>()
    val released = mutableListOf<String>()
    val run = async(Dispatchers.Default) {
      runHostExecution(
        onCompletion = { completionObserved.complete(Unit) },
        onExecutionFinished = { finalizationObserved.complete(Unit) },
      ) {
        val execution = requireNotNull(TrailExecution.current())
        execution.invokeOnCompletion {
          releaseStarted.complete(Unit)
          check(allowRelease.await(60, TimeUnit.SECONDS))
          released += "first lease"
        }
        execution.invokeOnCompletion { released += "second lease" }
        HostYamlRunResult(sessionId = SessionId("unlogged-session"))
      }
    }
    try {
      releaseStarted.await()
      // The standalone CLI needs both signals: the result alone permits an early JVM exit.
      assertEquals(true, completionObserved.isCompleted)
      assertFalse(finalizationObserved.isCompleted)
      allowRelease.countDown()
      assertIs<TrailExecutionResult.Success>(run.await())
      assertEquals(true, finalizationObserved.isCompleted)
      assertEquals(listOf("first lease", "second lease"), released)
    } finally {
      allowRelease.countDown()
      run.join()
    }
  }

  private suspend fun runHostFailure(failure: Throwable): TrailExecutionResult.Failed =
    assertIs<TrailExecutionResult.Failed>(runHostExecution { throw failure })

  private suspend fun runHostExecution(
    onCompletion: () -> Unit = {},
    onExecutionFinished: () -> Unit = {},
    execute: suspend () -> HostYamlRunResult,
  ): TrailExecutionResult {
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
          ): HostYamlRunResult = execute()

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
      val finished = CompletableDeferred<Unit>()

      val job = runner.runYaml(
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
          additionalInstrumentationArgs = { emptyMap() },
          onComplete = { onCompletion(); completion.complete(it) },
          noLogging = true,
          captureVideo = false,
          captureLogcat = false,
          captureIosLogs = false,
        ),
        onExecutionFinished = { onExecutionFinished(); finished.complete(Unit) },
      )

      val result = completion.await()
      finished.await()
      job.join()
      return result
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
