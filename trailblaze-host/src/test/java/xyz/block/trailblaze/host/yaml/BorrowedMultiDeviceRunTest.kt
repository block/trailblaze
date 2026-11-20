package xyz.block.trailblaze.host.yaml

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.model.PromptExecutor
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.FakeHostAppTarget
import xyz.block.trailblaze.host.driver.HostDriverDescriptorRegistry
import xyz.block.trailblaze.host.lease.DeviceLeaseClient
import xyz.block.trailblaze.host.lease.DeviceLeaseRegistry
import xyz.block.trailblaze.host.lease.LeasableDevice
import xyz.block.trailblaze.host.lease.Lease
import xyz.block.trailblaze.host.lease.LeaseRequest
import xyz.block.trailblaze.host.lease.LeaseResult
import xyz.block.trailblaze.host.lease.RemoteDeviceCall
import xyz.block.trailblaze.host.lease.RemoteDevicePools
import xyz.block.trailblaze.host.lease.RemoteDeviceReply
import xyz.block.trailblaze.host.lease.RemoteScreenState
import xyz.block.trailblaze.host.lease.RemoteToolResult
import xyz.block.trailblaze.http.DynamicLlmClient
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import xyz.block.trailblaze.llm.TrailblazeReferrer
import xyz.block.trailblaze.model.DesktopAppRunYamlParams
import xyz.block.trailblaze.model.TrailExecutionResult
import xyz.block.trailblaze.model.TrailblazeConfig
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.toolcalls.commands.InputTextTrailblazeTool
import xyz.block.trailblaze.ui.TrailblazeAnalytics
import xyz.block.trailblaze.ui.TrailblazeDeviceManager
import xyz.block.trailblaze.ui.TrailblazeSettingsRepo
import xyz.block.trailblaze.ui.models.TrailblazeServerState.SavedTrailblazeAppConfig
import xyz.block.trailblaze.yaml.createTrailblazeYaml

/**
 * A multi-device trail whose devices are borrowed from other machines, run through the real
 * desktop runner: an Android phone lent by one machine and an iOS simulator lent by another.
 */
class BorrowedMultiDeviceRunTest {

  private val tempDir: File = Files.createTempDirectory("borrowed-multi-device").toFile()

  /** A lending machine with one device, answering as its endpoint does. */
  private class FakeLender(url: String, val device: LeasableDevice, private val platform: TrailblazeDevicePlatform) :
    DeviceLeaseClient(url) {
    val registry = DeviceLeaseRegistry()
    val calls = CopyOnWriteArrayList<RemoteDeviceCall>()

    override fun devices() = registry.status(listOf(device))

    override fun lease(request: LeaseRequest): LeaseOutcome =
      when (val result = registry.lease(listOf(device), request)) {
        is LeaseResult.Granted -> LeaseOutcome.Granted(result.lease)
        is LeaseResult.Busy -> LeaseOutcome.Busy(result.reason, result.holder)
      }

    override fun renew(lease: Lease, busy: Boolean) = RenewOutcome.RENEWED

    override fun call(lease: Lease, request: RemoteDeviceCall): RemoteDeviceReply {
      calls += request
      return when (request.op) {
        RemoteDeviceCall.Op.RUN_TOOL -> RemoteDeviceReply(toolResult = RemoteToolResult(success = true))
        RemoteDeviceCall.Op.SCREEN_STATE -> RemoteDeviceReply(
          screenState = RemoteScreenState(
            platform = platform,
            deviceWidth = 1080,
            deviceHeight = 2400,
            viewHierarchy = ViewHierarchyTreeNode(),
            classifiers = listOf(platform.name.lowercase(), "phone"),
          ),
        )
        RemoteDeviceCall.Op.INSTALLED_APPS -> RemoteDeviceReply(installedAppIds = emptySet())
        RemoteDeviceCall.Op.END -> RemoteDeviceReply()
      }
    }

    override fun release(lease: Lease) {
      registry.release(lease.leaseId, lease.secret)?.forEach { registry.finishReturn(it.id) }
    }

    /** The text of each tool this lender ran, in order. */
    fun typed(): List<String> = calls.filter { it.op == RemoteDeviceCall.Op.RUN_TOOL }.map { call ->
      (createTrailblazeYaml().decodeTools(call.toolYaml!!).single().trailblazeTool as InputTextTrailblazeTool).text
    }
  }

  private val linux = FakeLender(
    LINUX_URL,
    LeasableDevice("android/emulator-5554", TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY),
    TrailblazeDevicePlatform.ANDROID,
  )
  private val mac = FakeLender(
    MAC_URL,
    LeasableDevice("ios/SIM-1", TrailblazeDriverType.IOS_HOST),
    TrailblazeDevicePlatform.IOS,
  )

  /** A phone whose lender runs it in-process, where a local run instruments only the run's target. */
  private val inProcess = FakeLender(
    IN_PROCESS_URL,
    LeasableDevice("android/emulator-5556", TrailblazeDriverType.ANDROID_TEST),
    TrailblazeDevicePlatform.ANDROID,
  )

  private val pools = RemoteDevicePools(
    poolUrls = { listOf(LINUX_URL, MAC_URL, IN_PROCESS_URL) },
    holder = "me@laptop",
    clientFor = { url -> mapOf(LINUX_URL to linux, MAC_URL to mac, IN_PROCESS_URL to inProcess).getValue(url) },
    repeater = { _, _, _ -> },
  )

  private val logsRepo = LogsRepo(logsDir = File(tempDir, "logs").also { it.mkdirs() }, watchFileSystem = false)

  private val deviceManager = TrailblazeDeviceManager(
    logsRepo = logsRepo,
    settingsRepo = TrailblazeSettingsRepo(
      settingsFile = File(tempDir, "settings.json"),
      initialConfig = SavedTrailblazeAppConfig(selectedTrailblazeDriverTypes = emptyMap()),
      defaultHostAppTarget = TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget,
      allTargetApps = { emptySet() },
      supportedDriverTypes = emptySet(),
    ),
    defaultHostAppTarget = TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget,
    currentTrailblazeLlmModelProvider = { testModel },
    initialAppTargets = emptySet(),
    runYamlLambda = { error("not used: the runner is called directly") },
    installedAppIdsProviderBlocking = { error("a borrowed device's apps are listed by its lender") },
    onDeviceInstrumentationArgsProvider = { emptyMap() },
    trailblazeAnalytics = TrailblazeAnalytics.NoOp,
    hostDriverDescriptors = HostDriverDescriptorRegistry.EMPTY,
    remoteDevicePools = pools,
  )

  private val runner = DesktopYamlRunner(
    trailblazeDeviceManager = deviceManager,
    trailblazeAnalytics = TrailblazeAnalytics.NoOp,
    trailblazeHostAppTargetProvider = { TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget },
    dynamicLlmClientProvider = { noLlm },
    logsDirProvider = { File(tempDir, "logs") },
  )

  private val phone: TrailblazeDeviceId = deviceOf(linux)
  private val sim: TrailblazeDeviceId = deviceOf(mac)
  private val inProcessPhone: TrailblazeDeviceId = deviceOf(inProcess)

  private fun deviceOf(lender: FakeLender) =
    pools.remoteDevices().single { it.instanceId.startsWith(lender.device.id.substringAfter('/')) }.trailblazeDeviceId

  @AfterTest
  fun tearDown() {
    pools.returnAll()
    tempDir.deleteRecursively()
  }

  private fun trail(simDriver: String? = null, phoneDriver: String? = null, phoneTarget: String? = null) = """
    config:
      devices:
        pair:
          devices:
            phone:
              classifier: android
${phoneDriver?.let { "              driver: $it" } ?: ""}
${phoneTarget?.let { "              target: $it" } ?: ""}
            sim:
              classifier: ios
${simDriver?.let { "              driver: $it" } ?: ""}
    trail:
      - step: Type on the phone, then on the simulator
        recording:
          pair:
            - inputText:
                text: on the phone
            - switchDevice:
                name: sim
            - inputText:
                text: on the simulator
  """.trimIndent()

  private fun run(
    yaml: String,
    bindings: Map<String, String> = mapOf("sim" to sim.instanceId),
    start: TrailblazeDeviceId = phone,
  ): TrailExecutionResult = runBlocking {
    val completion = CompletableDeferred<TrailExecutionResult>()
    val job = runner.runYaml(
      DesktopAppRunYamlParams(
        forceStopTargetApp = false,
        runYamlRequest = RunYamlRequest(
          testName = "borrowed cast",
          yaml = yaml,
          trailFilePath = null,
          targetAppName = null,
          useRecordedSteps = true,
          trailblazeDeviceId = start,
          trailblazeLlmModel = testModel,
          config = TrailblazeConfig(selfHeal = false),
          referrer = TrailblazeReferrer.YAML_TAB,
          deviceBindings = bindings,
        ),
        targetTestApp = null,
        findTargetById = { id -> FakeHostAppTarget(id) },
        onProgressMessage = {},
        onConnectionStatus = {},
        additionalInstrumentationArgs = { error("no device here is local, so none needs instrumentation args") },
        onComplete = { completion.complete(it) },
        captureVideo = false,
        captureLogcat = false,
        captureIosLogs = false,
      ),
    )
    completion.await().also { job.join() }
  }

  @Test
  fun `each device's steps run on the machine that lends it`() {
    val result = run(trail())

    assertIs<TrailExecutionResult.Success>(result, "$result")
    assertEquals(listOf("on the phone"), linux.typed())
    assertEquals(listOf("on the simulator"), mac.typed())
    assertTrue(mac.calls.all { it.device == "ios/SIM-1" }, "the simulator's calls name it as its lender does: ${mac.calls}")
  }

  @Test
  fun `a companion someone else is using fails the run, naming them, before any step runs`() {
    // Leased by another machine that is using it right now, so it can't be taken.
    val theirs = assertIs<LeaseResult.Granted>(
      mac.registry.lease(listOf(mac.device), LeaseRequest(devices = listOf(mac.device.id), holder = "alex@desk")),
    ).lease
    assertTrue(mac.registry.renew(theirs.leaseId, theirs.secret, busy = true))

    val result = run(trail())

    val failed = assertIs<TrailExecutionResult.Failed>(result, "$result")
    assertTrue(failed.errorMessage.orEmpty().contains("alex@desk"), failed.errorMessage)
    assertEquals(emptyList(), linux.typed())
  }

  @Test
  fun `a companion pinned to a driver its lender doesn't run is refused`() {
    val result = run(trail(simDriver = "IOS_AXE"))

    val failed = assertIs<TrailExecutionResult.Failed>(result, "$result")
    assertTrue(
      failed.errorMessage.orEmpty().contains("IOS_AXE") && failed.errorMessage.orEmpty().contains("IOS_HOST"),
      failed.errorMessage,
    )
    assertEquals(emptyList(), mac.typed())
  }

  @Test
  fun `a start device pinned to a driver its lender doesn't run is refused`() {
    val result = run(trail(phoneDriver = "ANDROID_TEST"))

    val failed = assertIs<TrailExecutionResult.Failed>(result, "$result")
    assertTrue(
      failed.errorMessage.orEmpty().contains("ANDROID_TEST") &&
        failed.errorMessage.orEmpty().contains("ANDROID_ONDEVICE_ACCESSIBILITY"),
      failed.errorMessage,
    )
    assertEquals(emptyList(), linux.typed())
  }

  @Test
  fun `a borrowed in-process start device runs its own target on its lender`() {
    val result = run(trail(phoneTarget = "buyer"), start = inProcessPhone)

    assertIs<TrailExecutionResult.Success>(result, "$result")
    assertEquals(listOf("on the phone"), inProcess.typed())
    assertTrue(
      inProcess.calls.filter { it.op == RemoteDeviceCall.Op.RUN_TOOL }.all { it.target == "buyer" },
      "the lender is told to run the start device's own target: ${inProcess.calls}",
    )
  }

  @Test
  fun `a trail whose companion isn't bound is refused before any step, not run on the start device alone`() {
    val result = run(trail(), bindings = emptyMap())

    val failed = assertIs<TrailExecutionResult.Failed>(result, "$result")
    assertTrue(failed.errorMessage.orEmpty().contains("'sim'"), failed.errorMessage)
    assertEquals(emptyList(), linux.typed())
  }

  @Test
  fun `a run started on a borrowed device refuses this machine's own devices as companions`() {
    val result = run(trail(), bindings = mapOf("sim" to "LOCAL-SIM"))

    val failed = assertIs<TrailExecutionResult.Failed>(result, "$result")
    assertTrue(
      failed.errorMessage.orEmpty().contains("LOCAL-SIM") && failed.errorMessage.orEmpty().contains("borrowed"),
      failed.errorMessage,
    )
    assertEquals(emptyList(), linux.typed())
  }

  private val noLlm = object : DynamicLlmClient {
    override fun createPromptExecutor(): PromptExecutor = error("no LLM in this test")
    // Built for the run but never called: every step replays its recording.
    override fun createLlmClient(): LLMClient = OpenAILLMClient(apiKey = "not-a-real-key")
  }

  private companion object {
    const val LINUX_URL = "http://localhost:51000"
    const val MAC_URL = "http://localhost:51001"
    const val IN_PROCESS_URL = "http://localhost:51002"

    val testModel = TrailblazeLlmModel(
      trailblazeLlmProvider = TrailblazeLlmProvider(id = "test", display = "Test"),
      modelId = "test-model",
      inputCostPerOneMillionTokens = 0.0,
      outputCostPerOneMillionTokens = 0.0,
      contextLength = 1000,
      maxOutputTokens = 1000,
      capabilityIds = emptyList(),
    )
  }
}
