package xyz.block.trailblaze.android.test

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import xyz.block.trailblaze.android.runner.rpc.OnDeviceRpcServer
import xyz.block.trailblaze.devices.*
import xyz.block.trailblaze.llm.*
import xyz.block.trailblaze.logs.client.*
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.model.TrailblazeConfig
import xyz.block.trailblaze.rules.TrailblazeLoggingRule

class AndroidTestRpcProviderOrderTest {
  @Test
  fun `provider and started log preserve same-session lane and projection before callback`() =
    runBlocking {
      val deviceId = TrailblazeDeviceId("provider-order", TrailblazeDevicePlatform.ANDROID)
      val original =
        TrailblazeDeviceInfo(
          deviceId,
          TrailblazeDriverType.ANDROID_TEST,
          100,
          200,
          classifiers = listOf("android", "phone").map(::TrailblazeDeviceClassifier),
        )
      val logging = AndroidTestLoggingRule({ original }, null, true, "http://127.0.0.1:1")
      val rule =
        AndroidTestTrailblazeRule(targetProvider = { error("No device") }, loggingRule = logging)
      val logs = mutableListOf<TrailblazeLog>()
      val sink =
        object :
          TrailblazeLoggingRule(
            logsBaseUrl = "http://127.0.0.1:1",
            additionalLogEmitter = LogEmitter { logs += it },
          ) {
          override val trailblazeDeviceInfoProvider = { original }
        }
      val contexts = mutableListOf<TrailblazeDeviceInfo>()
      val workQueue = TestThreadWorkQueue()
      val callbackStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
      val callbackCancelled = kotlinx.coroutines.CompletableDeferred<Unit>()
      val previousContext = kotlinx.coroutines.CompletableDeferred<TrailblazeDeviceInfo>()
      val releaseCallback = java.util.concurrent.CountDownLatch(1)
      val testThread = kotlin.concurrent.thread(isDaemon = true, name = "identity-test-thread") {
        workQueue.drainUntilStopped()
      }
      try {
        val server =
          OnDeviceRpcServer(
            loggingRule = sink,
            prepareRunYamlRequest = { workQueue.prepareRpcRequest(logging, it) },
            runTrailblazeYaml = { request, session, _ ->
              try {
                workQueue.dispatch {
                  logging.rememberRpcSession(session.sessionId, request.trailblazeDeviceId)
                  rule.decodeTrailForRun(request.yaml)
                  if (request.testName == "blocked") {
                    callbackStarted.complete(Unit)
                    releaseCallback.await()
                    previousContext.complete(logging.trailblazeDeviceInfoProvider())
                  }
                  contexts += logging.trailblazeDeviceInfoProvider()
                  RunYamlCallbackResult(session)
                }
              } finally {
                if (request.testName == "blocked") callbackCancelled.complete(Unit)
              }
            },
            trailblazeDeviceInfoProvider = { logging.trailblazeDeviceInfoProvider() },
            screenStateCaptor = { error("No screen capture") },
            waitForSettled = {},
          )
        val sessionA = SessionId("provider_order_a")
        val request =
          RunYamlRequest(
            testName = "provider-order",
            yaml =
              """
              config:
                devices:
                  android-phone-es: {driver: ANDROID_TEST, locale: es-MX}
                  android-phone-es-mx: {driver: ANDROID_TEST, locale: es-US}
              trail:
                - step: Open app
              """
                .trimIndent(),
            trailFilePath = null,
            targetAppName = null,
            useRecordedSteps = true,
            trailblazeDeviceId = deviceId,
            trailblazeLlmModel =
              TrailblazeLlmModel(
                trailblazeLlmProvider = TrailblazeLlmProvider.OPENAI,
                modelId = "test",
                inputCostPerOneMillionTokens = 0.0,
                outputCostPerOneMillionTokens = 0.0,
                contextLength = 1000,
                maxOutputTokens = 1000,
                capabilityIds = emptyList(),
              ),
            config =
              TrailblazeConfig(
                overrideSessionId = sessionA,
                sendSessionStartLog = true,
                sendSessionEndLog = false,
              ),
            referrer = TrailblazeReferrer("test", "test"),
            awaitCompletion = true,
            deviceClassifierOverride = listOf("android", "phone", "es"),
          )
        assertIs<RpcResult.Success<RunYamlResponse>>(server.handle(request))
        val beforeRejectedRequest = logging.trailblazeDeviceInfoProvider()
        kotlin.test.assertFailsWith<IllegalArgumentException> {
          server.handle(request.copy(deviceClassifierOverride = listOf("android", "phone", "es", "us")))
        }
        assertEquals(beforeRejectedRequest, logging.trailblazeDeviceInfoProvider())
        val bare =
          request.copy(
            yaml = "- openApp: {appId: com.example.app}",
            deviceClassifierOverride = emptyList(),
          )
        assertIs<RpcResult.Success<RunYamlResponse>>(server.handle(bare))
        val starts =
          logs
            .filterIsInstance<TrailblazeLog.TrailblazeSessionStatusChangeLog>()
            .map { it.sessionStatus }
            .filterIsInstance<SessionStatus.Started>()
        assertEquals(2, starts.size)
        for (info in starts.map { it.trailblazeDeviceInfo } + contexts) {
          assertEquals(listOf("android", "phone", "es", "mx"), info.classifiers.map { it.classifier })
          assertEquals("android-phone-es", info.metadata["executionDeviceClass"])
        }
        assertIs<RpcResult.Success<RunYamlResponse>>(
          server.handle(
            bare.copy(
              config =
                bare.config.copy(
                  overrideSessionId = SessionId("provider_order_b"),
                  sendSessionStartLog = false,
                ),
              deviceClassifierOverride = listOf("android", "phone", "es", "us"),
            )
          )
        )
        assertEquals(
          listOf("android", "phone", "es", "us"),
          contexts.last().classifiers.map { it.classifier },
        )
        assertEquals("android-phone", contexts.last().metadata["executionDeviceClass"])

        // A cancelled queue dispatch can finish while its instrumentation block is still running.
        // Drive the actual server and preparation bridge, then inspect that block's current context.
        assertIs<RpcResult.Success<RunYamlResponse>>(server.handle(request.copy(testName = "blocked", awaitCompletion = false)))
        callbackStarted.await()
        val replacement = async(kotlinx.coroutines.Dispatchers.Default) {
          server.handle(bare.copy(config = bare.config.copy(overrideSessionId = SessionId("queue_replacement"),
            sendSessionStartLog = false), deviceClassifierOverride = listOf("android", "phone", "es", "us")))
        }
        callbackCancelled.await()
        assertEquals(listOf("android", "phone", "es", "mx"), logging.trailblazeDeviceInfoProvider().classifiers.map { it.classifier })
        releaseCallback.countDown()
        assertIs<RpcResult.Success<RunYamlResponse>>(replacement.await())
        val previous = previousContext.await()
        assertEquals(listOf("android", "phone", "es", "mx"), previous.classifiers.map { it.classifier })
        assertEquals("android-phone-es", previous.metadata["executionDeviceClass"])
        assertEquals(listOf("android", "phone", "es", "us"), contexts.last().classifiers.map { it.classifier })
        assertEquals("android-phone", contexts.last().metadata["executionDeviceClass"])
      } finally {
        releaseCallback.countDown()
        workQueue.stop()
        testThread.join(120_000)
        kotlin.test.assertFalse(testThread.isAlive, "Instrumentation test queue did not stop")
      }
    }
  @Test
  fun `cancelled queued preparation releases the request without installing or executing it`() = runBlocking {
    val deviceId = TrailblazeDeviceId("cancelled-preparation", TrailblazeDevicePlatform.ANDROID)
    val physical = TrailblazeDeviceInfo(deviceId, TrailblazeDriverType.ANDROID_TEST, 100, 200,
      classifiers = listOf("android", "phone").map(::TrailblazeDeviceClassifier))
    val blockRead = java.util.concurrent.atomic.AtomicBoolean()
    val readStarted = java.util.concurrent.CountDownLatch(1)
    val releaseRead = java.util.concurrent.CountDownLatch(1)
    val logging = AndroidTestLoggingRule({
      if (blockRead.get()) { readStarted.countDown(); releaseRead.await() }
      physical
    }, null, true, "http://127.0.0.1:1")
    val workQueue = TestThreadWorkQueue()
    val occupied = java.util.concurrent.CountDownLatch(1)
    val release = java.util.concurrent.CountDownLatch(1)
    val preparing = kotlinx.coroutines.CompletableDeferred<Unit>()
    val executions = java.util.concurrent.atomic.AtomicInteger()
    val thread = kotlin.concurrent.thread(isDaemon = true) { workQueue.drainUntilStopped() }
    val occupier = async(kotlinx.coroutines.Dispatchers.Default) {
      workQueue.dispatch { occupied.countDown(); release.await() }
    }
    val server = OnDeviceRpcServer(loggingRule = logging,
      prepareRunYamlRequest = { preparing.complete(Unit); workQueue.prepareRpcRequest(logging, it) },
      runTrailblazeYaml = { _, session, _ -> executions.incrementAndGet(); RunYamlCallbackResult(session) },
      trailblazeDeviceInfoProvider = { logging.trailblazeDeviceInfoProvider() },
      screenStateCaptor = { error("No capture") }, waitForSettled = {})
    val request = RunYamlRequest(testName = "abandoned", yaml = "- openApp: {appId: com.example.app}",
      trailFilePath = null, targetAppName = null, useRecordedSteps = true, trailblazeDeviceId = deviceId,
      trailblazeLlmModel = TrailblazeLlmModel(trailblazeLlmProvider = TrailblazeLlmProvider.OPENAI,
        modelId = "test", inputCostPerOneMillionTokens = 0.0, outputCostPerOneMillionTokens = 0.0,
        contextLength = 1000, maxOutputTokens = 1000, capabilityIds = emptyList()),
      config = TrailblazeConfig(overrideSessionId = SessionId("abandoned"), sendSessionStartLog = false, sendSessionEndLog = false),
      referrer = TrailblazeReferrer("test", "test"), awaitCompletion = false,
      deviceClassifierOverride = listOf("android", "phone", "es", "us"))
    var abandoned: kotlinx.coroutines.Deferred<*>? = null
    var abandonedRead: kotlinx.coroutines.Deferred<*>? = null
    try {
      check(occupied.await(120, java.util.concurrent.TimeUnit.SECONDS))
      val dispatch = async(kotlinx.coroutines.Dispatchers.Default) { server.handle(request) }
      abandoned = dispatch
      preparing.await()
      dispatch.cancel()
      val finishedBeforeDrain = kotlinx.coroutines.withTimeoutOrNull(60_000) { dispatch.join(); true } ?: false
      release.countDown()
      occupier.await()
      dispatch.join()
      val current = workQueue.dispatch { logging.trailblazeDeviceInfoProvider() }
      assertEquals(true, finishedBeforeDrain, "Cancelled preparation must release the request before instrumentation unblocks")
      assertEquals(physical.classifiers, current.classifiers)
      assertEquals(0, executions.get())
      // Also cancel after the queued block has begun computing identity, before installation.
      blockRead.set(true)
      val reading = async(kotlinx.coroutines.Dispatchers.Default) { server.handle(request) }
      abandonedRead = reading
      check(readStarted.await(120, java.util.concurrent.TimeUnit.SECONDS))
      reading.cancel()
      kotlinx.coroutines.withTimeout(60_000) { reading.join() }
      blockRead.set(false)
      releaseRead.countDown()
      val afterRead = workQueue.dispatch { logging.trailblazeDeviceInfoProvider() }
      assertEquals(physical.classifiers, afterRead.classifiers)
      assertEquals(0, executions.get())
    } finally {
      releaseRead.countDown()
      abandonedRead?.cancel()
      release.countDown()
      abandoned?.cancel()
      workQueue.stop()
      thread.join(120_000)
    }
  }

}
