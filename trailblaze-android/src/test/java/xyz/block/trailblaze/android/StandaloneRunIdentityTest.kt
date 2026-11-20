package xyz.block.trailblaze.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.TrailblazeAndroidLoggingRule
import xyz.block.trailblaze.android.runner.rpc.OnDeviceRpcServer
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.http.DynamicLlmClient
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.llm.RunYamlResponse
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import xyz.block.trailblaze.llm.TrailblazeReferrer
import xyz.block.trailblaze.logs.client.LogEmitter
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.model.TrailblazeConfig
import xyz.block.trailblaze.rules.TrailblazeLoggingRule
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.yaml.createTrailblazeYaml

class StandaloneRunIdentityTest {
  private val original = listOf("android", "phone").map(::TrailblazeDeviceClassifier)

  private fun request(yaml: String) =
    RunYamlRequest(
      useRecordedSteps = true,
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
      yaml = yaml,
      testName = "identity",
      trailFilePath = null,
      targetAppName = null,
      trailblazeDeviceId = TrailblazeDeviceId("identity", TrailblazeDevicePlatform.ANDROID),
      config = TrailblazeConfig(),
      referrer = TrailblazeReferrer(id = "test", display = "test"),
    )

  @Test
  fun `successive whole trails prepare current identity without reselecting declarations`() {
    val yaml =
      """
      config:
        skip: {android-phone-es-mx: regional skip}
        devices:
          android-phone: {driver: ANDROID_ONDEVICE_ACCESSIBILITY, locale: es-MX}
          android-phone-es-mx: {driver: ANDROID_TEST, locale: es-US}
      trail:
        - step: Open app
      """
        .trimIndent()
    var runtime: List<TrailblazeDeviceClassifier>? = null
    repeat(2) {
      runtime = standaloneRecordingClassifiers(request(yaml), false, original, runtime)
      assertEquals(listOf("android", "phone", "es", "mx"), runtime?.map { it.classifier })
      val selected = createTrailblazeYaml().extractTrailConfig(yaml, original)!!
      assertEquals("ANDROID_ONDEVICE_ACCESSIBILITY", selected.driver)
      assertEquals("es-MX", selected.locale)
      assertEquals(null, selected.skip)
    }
  }

  @Test
  fun `same session bare tools retain region and new session tools reset it`() {
    val regional = original + listOf("es", "mx").map(::TrailblazeDeviceClassifier)
    val tool = request("- openApp: {appId: com.example.app}")
    assertEquals(regional, standaloneRecordingClassifiers(tool, true, original, regional))
    assertEquals(original, standaloneRecordingClassifiers(tool, false, original, regional))
    assertEquals(
      regional,
      standaloneRecordingClassifiers(
        tool.copy(deviceClassifierOverride = regional.map { it.classifier }),
        false,
        regional,
        null,
      ),
    )
  }

  @Test
  fun `real standalone handler preserves original lane and region across bare requests`() =
    runBlocking {
      val deviceId = TrailblazeDeviceId("standalone-order", TrailblazeDevicePlatform.ANDROID)
      val physical =
        TrailblazeDeviceInfo(
          deviceId,
          TrailblazeDriverType.DEFAULT_ANDROID,
          100,
          200,
          classifiers = original,
        )
      var failDeviceInfo = false
      val logging =
        TrailblazeAndroidLoggingRule(
          physicalDeviceInfoProvider = {
            check(!failDeviceInfo) { "Device info unavailable" }
            physical
          },
          trailblazeDeviceClassifiersProvider = { original },
          logsBaseUrl = "http://127.0.0.1:1",
          initialDriverType = TrailblazeDriverType.DEFAULT_ANDROID,
          requestedDeviceClassifiersProvider = { emptyList() },
          noLogging = true,
        )
      val contexts = mutableListOf<TrailblazeDeviceInfo>()
      val runner =
        object : BaseAndroidStandaloneServerTest(logging, 12345) {
          override fun getDeviceClassifiers() = original

          override fun getDynamicLlmClient(
            trailblazeLlmModel: TrailblazeLlmModel
          ): DynamicLlmClient = error("No LLM")

          override fun handleRunRequest(
            runYamlRequest: RunYamlRequest,
            agentMemory: AgentMemory,
          ): TrailblazeToolResult.Success? {
            createTrailblazeYaml().decodeTrailOrToolEnvelope(
              runYamlRequest.yaml,
              deviceClassifiers = trailblazeLoggingRule.selectionClassifiers(),
            )
            contexts += trailblazeLoggingRule.trailblazeDeviceInfoProvider()
            return null
          }

          fun prepare(request: RunYamlRequest) = prepareRunYamlRequest(request)

          fun provider(id: TrailblazeDeviceId): TrailblazeDeviceInfo = preparedRpcDeviceInfo()
        }
      val logs = mutableListOf<TrailblazeLog>()
      val sink =
        object :
          TrailblazeLoggingRule(
            logsBaseUrl = "http://127.0.0.1:1",
            additionalLogEmitter = LogEmitter { logs += it },
          ) {
          override val trailblazeDeviceInfoProvider = { physical }
        }
      val server =
        OnDeviceRpcServer(
          loggingRule = sink,
          runTrailblazeYaml = runner.createRunTrailblazeYamlCallback(),
          trailblazeDeviceInfoProvider = runner::provider,
          prepareRunYamlRequest = runner::prepare,
          screenStateCaptor = { error("No device capture") },
          waitForSettled = {},
        )
      val full =
        request(
            """
            config:
              devices:
                android-phone-es: {driver: ANDROID_ONDEVICE_ACCESSIBILITY, locale: es-MX}
                android-phone-es-mx: {driver: ANDROID_TEST, locale: es-US}
            trail:
              - step: Open app
            """
              .trimIndent()
          )
          .copy(
            trailblazeDeviceId = deviceId,
            awaitCompletion = true,
            deviceClassifierOverride = listOf("android", "phone", "es"),
            config =
              TrailblazeConfig(
                overrideSessionId = SessionId("standalone_a"),
                sendSessionStartLog = true,
                sendSessionEndLog = false,
              ),
          )
      assertIs<RpcResult.Success<RunYamlResponse>>(server.handle(full))
      val beforeRejectedRequest = logging.trailblazeDeviceInfoProvider()
      kotlin.test.assertFailsWith<IllegalArgumentException> {
        server.handle(full.copy(deviceClassifierOverride = listOf("android", "phone", "es", "us")))
      }
      assertEquals(beforeRejectedRequest, logging.trailblazeDeviceInfoProvider())
      failDeviceInfo = true
      try {
        kotlin.test.assertFailsWith<IllegalStateException> {
          runner.prepare(full.copy(yaml = "- openApp: {appId: com.example.app}",
            config = full.config.copy(overrideSessionId = SessionId("failed_provider")),
            deviceClassifierOverride = listOf("android", "phone", "es", "us")))
        }
      } finally { failDeviceInfo = false }
      assertEquals(beforeRejectedRequest, logging.trailblazeDeviceInfoProvider())
      val bare =
        full.copy(
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
      // A lowered host call changes runtime identity while keeping this session's original lane.
      assertIs<RpcResult.Success<RunYamlResponse>>(
        server.handle(
          bare.copy(
            config = bare.config.copy(sendSessionStartLog = false),
            deviceClassifierOverride = listOf("android", "phone", "es", "us"),
          )
        )
      )
      assertEquals(
        listOf("android", "phone", "es", "us"),
        contexts.last().classifiers.map { it.classifier },
      )
      assertEquals("android-phone-es", contexts.last().metadata["executionDeviceClass"])
      // Another full request selects the original definition despite the previous US projection.
      assertIs<RpcResult.Success<RunYamlResponse>>(server.handle(full))
      assertEquals(
        listOf("android", "phone", "es", "mx"),
        contexts.last().classifiers.map { it.classifier },
      )
      assertEquals("android-phone-es", contexts.last().metadata["executionDeviceClass"])
      val repeatedStart =
        logs
          .filterIsInstance<TrailblazeLog.TrailblazeSessionStatusChangeLog>()
          .map { it.sessionStatus }
          .filterIsInstance<SessionStatus.Started>()
          .last()
      assertEquals("es-MX", repeatedStart.trailConfig?.locale)
      assertEquals(
        listOf("android", "phone", "es", "mx"),
        repeatedStart.trailblazeDeviceInfo.classifiers.map { it.classifier },
      )
      assertEquals(
        "android-phone-es",
        repeatedStart.trailblazeDeviceInfo.metadata["executionDeviceClass"],
      )
      assertIs<RpcResult.Success<RunYamlResponse>>(
        server.handle(
          bare.copy(
            config =
              bare.config.copy(
                overrideSessionId = SessionId("standalone_b"),
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
      val malformed =
        assertIs<RpcResult.Success<RunYamlResponse>>(
          server.handle(full.copy(yaml = "config: ["))
        )
      assertEquals(false, malformed.data.success)
    }
  @Test
  fun `replacement cancels actual standalone execution before preparing the next identity`() = runBlocking {
    val id = TrailblazeDeviceId("replacement", TrailblazeDevicePlatform.ANDROID)
    val physical = TrailblazeDeviceInfo(id, TrailblazeDriverType.DEFAULT_ANDROID, 100, 200, classifiers = original)
    val logging = TrailblazeAndroidLoggingRule(
      physicalDeviceInfoProvider = { physical }, trailblazeDeviceClassifiersProvider = { original },
      logsBaseUrl = "http://127.0.0.1:1", initialDriverType = TrailblazeDriverType.DEFAULT_ANDROID,
      requestedDeviceClassifiersProvider = { emptyList() }, noLogging = true,
    )
    val started = kotlinx.coroutines.CompletableDeferred<Unit>()
    val cancelledByReplacement = kotlinx.coroutines.CompletableDeferred<Boolean>()
    val replacementEntered = kotlinx.coroutines.CompletableDeferred<Unit>()
    val cleanupStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
    val releaseCleanup = kotlinx.coroutines.CompletableDeferred<Unit>()
    val cleanupSession = kotlinx.coroutines.CompletableDeferred<SessionId?>()
    val cleanupOverlapped = kotlinx.coroutines.CompletableDeferred<Boolean>()
    val contexts = java.util.concurrent.CopyOnWriteArrayList<TrailblazeDeviceInfo>()
    val runner = object : BaseAndroidStandaloneServerTest(logging, 12345) {
      override fun getDeviceClassifiers() = original
      override fun getDynamicLlmClient(trailblazeLlmModel: TrailblazeLlmModel): DynamicLlmClient = error("No LLM")
      override fun handleRunRequest(runYamlRequest: RunYamlRequest, agentMemory: AgentMemory): TrailblazeToolResult.Success? {
        startInTestCoroutineScope {
          if (runYamlRequest.testName == "blocked") {
            started.complete(Unit)
            try {
              // Test-owned containment: old wiring reaches this expiry instead of cancelling work.
              kotlinx.coroutines.withTimeout(60_000) { kotlinx.coroutines.awaitCancellation() }
            } catch (e: kotlinx.coroutines.CancellationException) {
              cancelledByReplacement.complete(e !is kotlinx.coroutines.TimeoutCancellationException)
              kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                cleanupStarted.complete(Unit)
                releaseCleanup.await()
                cleanupSession.complete(logging.session?.sessionId)
                cleanupOverlapped.complete(replacementEntered.isCompleted)
                contexts += logging.trailblazeDeviceInfoProvider()
              }
              throw e
            }
          } else {
            replacementEntered.complete(Unit)
            contexts += logging.trailblazeDeviceInfoProvider()
          }
        }
        return null
      }
      fun prepare(request: RunYamlRequest) = prepareRunYamlRequest(request)
      fun stop() = cancelAnyActiveRuns()
    }
    val server = OnDeviceRpcServer(loggingRule = logging,
      runTrailblazeYaml = runner.createRunTrailblazeYamlCallback(),
      trailblazeDeviceInfoProvider = { logging.trailblazeDeviceInfoProvider() },
      prepareRunYamlRequest = runner::prepare, screenStateCaptor = { error("No capture") }, waitForSettled = {})
    val first = request("config: {devices: {android-phone-es: {locale: es-MX}}}\ntrail: []").copy(
      testName = "blocked", awaitCompletion = false, deviceClassifierOverride = listOf("android", "phone", "es"),
      config = TrailblazeConfig(overrideSessionId = SessionId("first"), sendSessionStartLog = false, sendSessionEndLog = false))
    try {
      assertIs<RpcResult.Success<RunYamlResponse>>(server.handle(first))
      started.await()
      val replacing = async {
        server.handle(first.copy(testName = "replacement", awaitCompletion = true,
          yaml = "- openApp: {appId: com.example.app}", deviceClassifierOverride = listOf("android", "phone", "es", "us"),
          config = first.config.copy(overrideSessionId = SessionId("replacement"))))
      }
      cleanupStarted.await()
      releaseCleanup.complete(Unit)
      val result = replacing.await()
      assertIs<RpcResult.Success<RunYamlResponse>>(result)
      assertEquals(true, result.data.success)
      assertEquals(true, cancelledByReplacement.await(), "Actual standalone work must be cancelled rather than waiting for its own deadline")
      assertEquals(SessionId("first"), cleanupSession.await())
      assertEquals(false, cleanupOverlapped.await(), "Replacement must wait until actual standalone cleanup finishes")
      assertEquals(listOf("android", "phone", "es", "mx"), contexts.first().classifiers.map { it.classifier })
      assertEquals(listOf("android", "phone", "es", "us"), contexts.last().classifiers.map { it.classifier })
    } finally { releaseCleanup.complete(Unit); runner.stop() }
  }

}
