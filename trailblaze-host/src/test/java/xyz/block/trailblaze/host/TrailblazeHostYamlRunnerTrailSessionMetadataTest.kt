package xyz.block.trailblaze.host

import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertNotSame
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertIs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.client.SessionMetadata
import xyz.block.trailblaze.toolcalls.TrailExecution
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

  @Test
  fun `reused recording gets fresh execution custody through runtime and app cleanup`() = runBlocking {
    val logsDir = Files.createTempDirectory("host-execution-custody-").toFile()
    val recording = SessionId("shared_recording")
    var previous: TrailExecution? = null
    try {
      for (trailId in listOf("first-trail", "second-trail")) {
        val request = runYamlRequest("config: {id: $trailId}")
        val loggingRule = HostTrailblazeLoggingRule(
          trailblazeDeviceInfoProvider = { TrailblazeDeviceInfo(
            trailblazeDeviceId = request.trailblazeDeviceId,
            trailblazeDriverType = TrailblazeDriverType.IOS_HOST,
            widthPixels = 390, heightPixels = 844,
          ) }, logsDir = logsDir,
        )
        lateinit var owner: TrailExecution
        val events = mutableListOf<String>()
        TrailblazeHostYamlRunner.executeTrailSession(
          loggingRule = loggingRule,
          overrideSessionId = recording,
          testName = "shared recording", deviceLabel = "test",
          sendSessionEndLog = false, onProgressMessage = {},
          screenshotProvider = { error("No screenshot") },
          metadata = SessionMetadata(trailId = trailId),
          cleanup = {
            withContext(NonCancellable + Dispatchers.IO) {
              assertSame(owner, TrailExecution.current())
              assertFalse(owner.isFinished)
              events += "runtime and app cleanup"
            }
          },
        ) { session ->
          owner = requireNotNull(TrailExecution.current())
          assertNotSame(previous, owner)
          assertEquals(trailId, owner.trailId)
          assertEquals(recording, session.sessionId)
          owner.invokeOnCompletion { events += "release" }
          session.sessionId
        }
        assertTrue(owner.isFinished)
        assertEquals(listOf("runtime and app cleanup", "release"), events)
        previous = owner
      }
    } finally { logsDir.deleteRecursively() }
  }

  @Test
  fun `normal iOS device cleanup preserves the result and releases after child cleanup`() = runBlocking {
    for (driver in listOf(TrailblazeDriverType.IOS_HOST, TrailblazeDriverType.IOS_AXE)) {
      for (bodyFailure in listOf(null, IllegalStateException("trail failed"))) {
        checkOwnedDeviceCleanup(driver, bodyFailure, cancelDuringExecution = false)
      }
    }
  }

  @Test
  fun `real cancellation still propagates and releases after runtime and child cleanup`() = runBlocking {
    checkOwnedDeviceCleanup(TrailblazeDriverType.IOS_HOST, null, cancelDuringExecution = true)
  }

  private suspend fun checkOwnedDeviceCleanup(
    driver: TrailblazeDriverType,
    bodyFailure: Throwable?,
    cancelDuringExecution: Boolean,
  ) {
    val logsDir = Files.createTempDirectory("host-device-cleanup-").toFile()
    val deviceScope = CoroutineScope(Job() + Dispatchers.Default)
    val expected = SessionId("normal_cleanup_probe")
    val execution = TrailExecution("authored-trail")
    val childStarted = CompletableDeferred<Unit>()
    val cleanupCompleted = CompletableDeferred<Unit>()
    val events = ConcurrentLinkedQueue<String>()
    var returned: SessionId? = null
    var thrown: Throwable? = null
    var observedOwner: TrailExecution? = null
    val loggingRule = HostTrailblazeLoggingRule(
      trailblazeDeviceInfoProvider = {
        TrailblazeDeviceInfo(
          trailblazeDeviceId = TrailblazeDeviceId("simulated-ios", TrailblazeDevicePlatform.IOS),
          trailblazeDriverType = driver, widthPixels = 390, heightPixels = 844,
        )
      }, logsDir = logsDir,
    )
    try {
      // The desktop runner installs custody on its existing job, not on an extra child
      // around driver cleanup. Completion must also cover that job's owned children.
      val run = deviceScope.launch(TrailExecution.element(execution)) {
        try {
          returned = TrailblazeHostYamlRunner.executeTrailSession(
            loggingRule = loggingRule, overrideSessionId = expected,
            testName = "device cleanup", deviceLabel = "simulated-ios",
            sendSessionEndLog = false, onProgressMessage = {},
            screenshotProvider = { error("No screenshot available") },
            metadata = SessionMetadata(trailId = "authored-trail"),
            cleanup = {
              // Match the driver's outer NonCancellable cleanup, including work on another thread.
              withContext(NonCancellable) {
                withContext(Dispatchers.IO) {
                  assertSame(observedOwner, TrailExecution.current())
                  assertFalse(execution.isFinished)
                  // Normal non-MCP iOS teardown deliberately cancels this device scope.
                  deviceScope.cancel()
                  events += "runtime and app cleanup"
                  cleanupCompleted.complete(Unit)
                }
              }
            },
          ) { session ->
            observedOwner = requireNotNull(TrailExecution.current())
            observedOwner.invokeOnCompletion { events += "release" }
            CoroutineScope(currentCoroutineContext()).launch {
              try {
                childStarted.complete(Unit)
                awaitCancellation()
              } finally {
                withContext(NonCancellable) {
                  cleanupCompleted.await()
                  assertFalse(execution.isFinished)
                  events += "child cleanup"
                }
              }
            }
            childStarted.await()
            if (cancelDuringExecution) awaitCancellation()
            if (bodyFailure != null) throw bodyFailure
            session.sessionId
          }
        } catch (failure: Throwable) {
          thrown = failure
        }
      }
      run.invokeOnCompletion { execution.finish() }
      if (cancelDuringExecution) {
        childStarted.await()
        run.cancel()
      }
      run.join()
      if (cancelDuringExecution) {
        assertIs<CancellationException>(thrown)
        assertNull(returned)
      } else if (bodyFailure != null) {
        assertSame(bodyFailure, thrown)
        assertNull(returned)
      } else {
        assertNull(thrown)
        assertEquals(expected, returned)
      }
      assertSame(execution, observedOwner)
      assertEquals(listOf("runtime and app cleanup", "child cleanup", "release"), events.toList())
      assertTrue(execution.isFinished)
    } finally {
      cleanupCompleted.complete(Unit)
      deviceScope.cancel()
      logsDir.deleteRecursively()
    }
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
