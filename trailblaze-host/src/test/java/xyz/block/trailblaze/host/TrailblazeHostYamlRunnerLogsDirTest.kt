package xyz.block.trailblaze.host

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.model.PromptExecutor
import assertk.assertThat
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.http.DynamicLlmClient
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import xyz.block.trailblaze.llm.TrailblazeReferrer
import xyz.block.trailblaze.mcp.android.ondevice.rpc.OnDeviceRpcClient
import xyz.block.trailblaze.model.TrailblazeConfig

/**
 * Pins that the host runner builds its per-run [xyz.block.trailblaze.report.utils.LogsRepo] at the
 * logs directory it is given, rather than at [xyz.block.trailblaze.util.GitUtils]' `<git root>/logs`.
 *
 * Why this matters: every host path constructs its own
 * [xyz.block.trailblaze.host.rules.HostTrailblazeLoggingRule], and that rule's own disk writes plus
 * the reads in `generateAndSaveRecording` / `compareSnapshotsAgainstGoldens` all go through the
 * resulting repo. When the runner ignored the configured `logsDirectory`, a run whose setting
 * pointed elsewhere wrote its rule-side files under the git root while the daemon persisted the
 * session where the setting said — so the recording generator found no logs for the session it had
 * just run and skipped saving.
 *
 * Observable without a device because the repo is constructed (and `mkdirs()` its directory) before
 * the first LLM client or RPC dispatch. Both runs below therefore fail — the stub LLM client throws
 * on `createLlmClient()`, and the RPC device does not exist — but the directory decision has
 * already been made by then, which is exactly what these tests read.
 */
class TrailblazeHostYamlRunnerLogsDirTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private val testDeviceId = TrailblazeDeviceId(
    instanceId = "logs-dir-test-device",
    trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
  )

  /** Valid enough to decode and to yield one prompt step, so the run reaches the logging rule. */
  private val validYaml = """
    trail:
      - step: "Tap the thing"
  """.trimIndent()

  /** Throws on first use, which is the step immediately after the logging rule is constructed. */
  private val throwingLlmClient = object : DynamicLlmClient {
    override fun createPromptExecutor(): PromptExecutor = error("no LLM in this test")
    override fun createLlmClient(): LLMClient = error("no LLM in this test")
  }

  private lateinit var rpcClient: OnDeviceRpcClient

  @Before
  fun setUp() {
    rpcClient = OnDeviceRpcClient(testDeviceId)
  }

  @After
  fun tearDown() {
    rpcClient.close()
  }

  private fun request() = RunYamlRequest(
    testName = "logs-dir-test",
    yaml = validYaml,
    trailFilePath = null,
    targetAppName = null,
    useRecordedSteps = false,
    trailblazeDeviceId = testDeviceId,
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

  /** A path that does not exist yet, so only the runner's own repo can create it. */
  private fun unclaimedLogsDir(name: String) = File(tempFolder.newFolder(name), "configured-logs")

  @Test
  fun `runHostV3WithAccessibilityYaml puts its logs repo at the given directory`() {
    val logsDir = unclaimedLogsDir("v3")
    assertThat(logsDir.exists()).isFalse()

    runCatching {
      runBlocking {
        TrailblazeHostYamlRunner.runHostV3WithAccessibilityYaml(
          dynamicLlmClient = throwingLlmClient,
          onDeviceRpc = rpcClient,
          runYamlRequest = request(),
          trailblazeDeviceId = testDeviceId,
          onProgressMessage = {},
          targetTestApp = null,
          logsDir = logsDir,
        )
      }
    }

    assertThat(logsDir.isDirectory).isTrue()
  }

  @Test
  fun `runHostTrailblazeRunnerWithOnDeviceRpc puts its logs repo at the given directory`() {
    val logsDir = unclaimedLogsDir("on-device-rpc")
    assertThat(logsDir.exists()).isFalse()

    runCatching {
      runBlocking {
        TrailblazeHostYamlRunner.runHostTrailblazeRunnerWithOnDeviceRpc(
          dynamicLlmClient = throwingLlmClient,
          onDeviceRpc = rpcClient,
          runYamlRequest = request(),
          trailblazeDeviceId = testDeviceId,
          onProgressMessage = {},
          targetTestApp = null,
          logsDir = logsDir,
        )
      }
    }

    assertThat(logsDir.isDirectory).isTrue()
  }

  @Test
  fun `a run given no logs directory does not create one of its own`() {
    val logsDir = unclaimedLogsDir("omitted")

    runCatching {
      runBlocking {
        TrailblazeHostYamlRunner.runHostV3WithAccessibilityYaml(
          dynamicLlmClient = throwingLlmClient,
          onDeviceRpc = rpcClient,
          runYamlRequest = request(),
          trailblazeDeviceId = testDeviceId,
          onProgressMessage = {},
          targetTestApp = null,
        )
      }
    }

    // Guards the two tests above against passing vacuously: they would still be green if the
    // directory were created by the temp-folder helper or by anything other than the threaded
    // value. Omitting `logsDir` leaves the same path untouched, so its creation above is
    // attributable to the parameter.
    assertThat(logsDir.exists()).isFalse()
  }
}
