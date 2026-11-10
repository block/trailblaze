package xyz.block.trailblaze.scripting.subprocess

import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import xyz.block.trailblaze.config.McpServerConfig
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.model.TrailblazeConfig
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet.DynamicTrailblazeToolSet
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Unit coverage for [McpSubprocessRuntimeLauncher]. The happy-path spawn + handshake + register
 * pipeline is exercised end-to-end in [SubprocessRuntimeEndToEndTest]; here we focus on the
 * wiring-layer decisions that don't need a real subprocess:
 *
 *  - empty `mcp_servers:` is a cheap no-op (no file handles, no stderr dir created),
 *  - `command:`-only entries are skipped (schema-reserved, not runtime-implemented).
 */
class McpSubprocessRuntimeLauncherTest {

  private val deviceInfo = TrailblazeDeviceInfo(
    trailblazeDeviceId = TrailblazeDeviceId("unit", TrailblazeDevicePlatform.ANDROID),
    trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
    widthPixels = 1080,
    heightPixels = 2400,
  )

  private fun emptyRepo(): TrailblazeToolRepo =
    TrailblazeToolRepo(DynamicTrailblazeToolSet(name = "launcher-test-empty", toolClasses = emptySet()))

  @Test fun `empty mcpServers returns an empty runtime with no side effects`() {
    val repo = emptyRepo()
    val logDir = Files.createTempDirectory("launcher-empty-test").toFile()

    val runtime = runBlocking {
      McpSubprocessRuntimeLauncher.launchAll(
        mcpServers = emptyList(),
        deviceInfo = deviceInfo,
        config = TrailblazeConfig.DEFAULT,
        sessionId = SessionId("launcher_test_empty"),
        sessionLogDir = logDir,
        toolRepo = repo,
      )
    }

    assertThat(runtime.sessions).isEmpty()
    // No spawn = no stderr log files created. Establishes the "zero overhead when unused"
    // contract for targets that don't declare mcp_servers.
    assertThat(subprocessStderrFiles(logDir)).isEmpty()

    runBlocking { runtime.shutdownAll() }
    logDir.deleteRecursively()
  }

  /**
   * More script entries than there are `Dispatchers.IO` permits to hold them must fail the launch,
   * and must fail it *before* the first spawn — each subprocess pins one permit for the whole
   * session, so crossing the cap does not degrade, it hangs every daemon route while `/ping` keeps
   * answering. A refusal that arrived after spawning would already have done the damage.
   *
   * The cap is pinned through the property rather than read from the host so the boundary is the
   * same on a laptop and in CI.
   */
  @Test fun `more script entries than IO permits fails before spawning anything`() {
    val repo = emptyRepo()
    val logDir = Files.createTempDirectory("launcher-io-capacity-test").toFile()
    val property = SubprocessIoCapacity.IO_PARALLELISM_PROPERTY
    val original = System.getProperty(property)
    val cap = SubprocessIoCapacity.HOST_RESERVED_IO_PERMITS + 4

    try {
      System.setProperty(property, cap.toString())
      // One more than the 4 permits left once the host keeps its reserve. The scripts deliberately
      // do not exist: reaching a spawn attempt at all would be the failure this case guards.
      val tooMany = (1..5).map { McpServerConfig(script = "/nonexistent/tool_$it.mjs") }

      val failure = assertFailsWith<SubprocessIoCapacityException> {
        runBlocking {
          McpSubprocessRuntimeLauncher.launchAll(
            mcpServers = tooMany,
            deviceInfo = deviceInfo,
            config = TrailblazeConfig.DEFAULT,
            sessionId = SessionId("launcher_test_io_capacity"),
            sessionLogDir = logDir,
            toolRepo = repo,
          )
        }
      }

      assertThat(failure.subprocessCount).isEqualTo(5)
      assertThat(failure.ioParallelism).isEqualTo(cap)
      // Nothing was spawned, so no per-subprocess stderr log exists.
      assertThat(subprocessStderrFiles(logDir)).isEmpty()
      assertThat(repo.getCurrentToolDescriptors().size).isEqualTo(0)
    } finally {
      if (original == null) System.clearProperty(property) else System.setProperty(property, original)
      logDir.deleteRecursively()
    }
  }

  /**
   * A launch that dies partway through must give its IO permits back. Nothing else can: no runtime
   * is returned, so there is no `shutdownAll` to reach them. Leaking them would permanently shrink
   * the budget of a daemon that stays up for days — and every later refusal would name a number of
   * "live sessions" that had already exited.
   */
  @Test fun `a launch that fails mid-spawn gives its IO permits back`() {
    val repo = emptyRepo()
    val logDir = Files.createTempDirectory("launcher-io-release-test").toFile()
    assertThat(SubprocessIoCapacity.outstandingPermits()).isEqualTo(0)

    try {
      // Well inside the cap, so the reservation is granted and the failure comes from the spawn.
      val doomed = listOf(McpServerConfig(script = "/nonexistent/tool.mjs"))
      runCatching {
        runBlocking {
          McpSubprocessRuntimeLauncher.launchAll(
            mcpServers = doomed,
            deviceInfo = deviceInfo,
            config = TrailblazeConfig.DEFAULT,
            sessionId = SessionId("launcher_test_io_release"),
            sessionLogDir = logDir,
            toolRepo = repo,
          )
        }
      }.also { assertThat(it.isFailure).isEqualTo(true) }

      assertThat(SubprocessIoCapacity.outstandingPermits()).isEqualTo(0)
    } finally {
      logDir.deleteRecursively()
    }
  }

  /** The normal path: a runtime holds its permits until teardown, then returns them. */
  @Test fun `shutting a runtime down returns its IO permits`() {
    val repo = emptyRepo()
    val logDir = Files.createTempDirectory("launcher-io-shutdown-test").toFile()

    try {
      val runtime = runBlocking {
        McpSubprocessRuntimeLauncher.launchAll(
          mcpServers = emptyList(),
          deviceInfo = deviceInfo,
          config = TrailblazeConfig.DEFAULT,
          sessionId = SessionId("launcher_test_io_shutdown"),
          sessionLogDir = logDir,
          toolRepo = repo,
        )
      }
      runBlocking { runtime.shutdownAll() }

      assertThat(SubprocessIoCapacity.outstandingPermits()).isEqualTo(0)
    } finally {
      logDir.deleteRecursively()
    }
  }

  /**
   * A subprocess that outlives SIGKILL's wait still has a transport parking its `Dispatchers.IO`
   * permit. The runtime's lifecycle is over, but the permit is not free: refunding it would let the
   * next launch be admitted against IO capacity that does not exist — the daemon-wide hang the
   * tally exists to prevent, reached through the accounting rather than the cap.
   *
   * Needs a scripted [FakeProcess]: SIGKILL always lands on a real one, so the survivor case cannot
   * be arranged with a real subprocess.
   */
  @Test fun `a subprocess that outlives SIGKILL keeps its IO permit reserved until it is gone`() {
    assertThat(SubprocessIoCapacity.outstandingPermits()).isEqualTo(0)
    val stubborn = FakeProcess(survivesSigkill = true, pid = 4242L)
    val obedient = FakeProcess(survivesSigkill = false, pid = 4243L)
    val reservation = SubprocessIoCapacity.reserve(subprocessCount = 2, ioParallelism = 64)
    val runtime = LaunchedSubprocessRuntime(
      sessions = listOf(unconnectedSession(stubborn, "stuck.ts"), unconnectedSession(obedient, "fine.ts")),
      repo = emptyRepo(),
      registeredNames = emptyList(),
      ioReservation = reservation,
    )

    // The tally is process-wide and every case in this JVM shares it, so a failed assertion below
    // must not leave permits behind — it would fail every later case instead of this one.
    try {
      val allExited = runBlocking { runtime.shutdownAll() }

      assertThat(allExited).isEqualTo(false)
      assertThat(stubborn.destroyForciblyCalls).isEqualTo(1)
      // The obedient child's permit came back; the survivor's is still counted.
      assertThat(SubprocessIoCapacity.outstandingPermits()).isEqualTo(1)

      // Once the OS finally reaps it, a later teardown returns the rest — and not before.
      stubborn.exit()
      assertThat(runBlocking { runtime.shutdownAll() }).isEqualTo(true)
      assertThat(SubprocessIoCapacity.outstandingPermits()).isEqualTo(0)
    } finally {
      stubborn.exit()
      reservation.release()
    }
  }

  /**
   * Nobody calls `shutdownAll` twice in production — the runtime is discarded after the first one.
   * So a survivor's permit has to come back on its own when the OS finally reaps the child, or a
   * daemon that stays up for days shrinks its own budget one stuck subprocess at a time until it
   * refuses every launch.
   */
  @Test fun `a survivor's permit is reclaimed when it finally exits, with no second teardown`() {
    assertThat(SubprocessIoCapacity.outstandingPermits()).isEqualTo(0)
    val stubborn = FakeProcess(survivesSigkill = true, pid = 4244L)
    val reservation = SubprocessIoCapacity.reserve(subprocessCount = 1, ioParallelism = 64)
    val runtime = LaunchedSubprocessRuntime(
      sessions = listOf(unconnectedSession(stubborn, "stuck.ts")),
      repo = emptyRepo(),
      registeredNames = emptyList(),
      ioReservation = reservation,
    )

    try {
      assertThat(runBlocking { runtime.shutdownAll() }).isEqualTo(false)
      assertThat(SubprocessIoCapacity.outstandingPermits()).isEqualTo(1)

      // The only event. No teardown call, no polling by the runtime.
      stubborn.exit()

      // Generous, because the reclaim hops through a pooled thread; it is hang containment, not a
      // latency budget.
      assertThat(awaitOutstandingPermits(0)).isEqualTo(0)
    } finally {
      stubborn.exit()
      reservation.release()
    }
  }

  /**
   * Overlapping teardowns must not refund one child twice. A partial-launch unwind and a later
   * `shutdownAll` both see the same still-alive children, so both ask for their permits back on
   * exit; if each ask registered its own callback, the first child to be reaped would return two
   * permits — and the second one belongs to a sibling that is still parking it. The tally's floor
   * cannot catch that, because it is not at zero while the sibling is counted.
   */
  @Test fun `a survivor is refunded once even when two teardowns both ask`() {
    assertThat(SubprocessIoCapacity.outstandingPermits()).isEqualTo(0)
    val first = FakeProcess(survivesSigkill = true, pid = 4245L)
    val second = FakeProcess(survivesSigkill = true, pid = 4246L)
    val reservation = SubprocessIoCapacity.reserve(subprocessCount = 2, ioParallelism = 64)
    val runtime = LaunchedSubprocessRuntime(
      sessions = listOf(unconnectedSession(first, "stuck-a.ts"), unconnectedSession(second, "stuck-b.ts")),
      repo = emptyRepo(),
      registeredNames = emptyList(),
      ioReservation = reservation,
    )

    try {
      // Both teardowns run while both children are alive, so both sign the same two up for a refund.
      assertThat(runBlocking { runtime.shutdownAll() }).isEqualTo(false)
      assertThat(runBlocking { runtime.shutdownAll() }).isEqualTo(false)
      assertThat(SubprocessIoCapacity.outstandingPermits()).isEqualTo(2)

      first.exit()

      // One child was reaped, so exactly one permit comes back. The other is still parked.
      assertThat(awaitOutstandingPermits(1)).isEqualTo(1)
      // And it stays parked. A duplicate refund fires from the same exit, microseconds behind the
      // first, so without this the poll above could sample between them and call the bug a pass.
      Thread.sleep(250)
      assertThat(SubprocessIoCapacity.outstandingPermits()).isEqualTo(1)
      assertThat(second.isAlive).isEqualTo(true)

      second.exit()
      assertThat(awaitOutstandingPermits(0)).isEqualTo(0)
    } finally {
      first.exit()
      second.exit()
      reservation.release()
    }
  }

  /** Polls the process-wide tally until it reaches [expected] or the containment bound elapses. */
  private fun awaitOutstandingPermits(expected: Int): Int {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
    while (System.nanoTime() < deadline) {
      val outstanding = SubprocessIoCapacity.outstandingPermits()
      if (outstanding == expected) return outstanding
      Thread.sleep(10)
    }
    return SubprocessIoCapacity.outstandingPermits()
  }

  /**
   * A session over a [FakeProcess] with a never-connected MCP client: `shutdown` closes the client
   * (tolerated by its `runCatching`) and then runs the real escalation ladder against the fake, so
   * the teardown path under test is the production one.
   */
  private fun unconnectedSession(process: FakeProcess, scriptName: String): McpSubprocessSession =
    McpSubprocessSession(
      spawnedProcess = SpawnedProcess(process = process, scriptFile = File(scriptName), argv = listOf("bun", scriptName)),
      transport = StdioClientTransport(
        input = ByteArrayInputStream(ByteArray(0)).asSource().buffered(),
        output = ByteArrayOutputStream().asSink().buffered(),
        error = null,
      ),
      client = Client(McpSubprocessSession.DEFAULT_CLIENT_INFO, ClientOptions()),
      stderrCapture = StderrCapture(),
      stderrPump = Thread { },
    )

  @Test fun `command-only entries skip spawn and return empty runtime`() {
    val repo = emptyRepo()
    val logDir = Files.createTempDirectory("launcher-command-skip-test").toFile()

    // `command:` is schema-reserved but not implemented in this landing — authors might
    // legitimately declare it expecting the follow-up landing, and the launcher should skip it
    // without failing the whole session.
    val commandOnly = listOf(
      McpServerConfig(command = listOf("python", "-m", "mcp_example")),
    )

    val runtime = runBlocking {
      McpSubprocessRuntimeLauncher.launchAll(
        mcpServers = commandOnly,
        deviceInfo = deviceInfo,
        config = TrailblazeConfig.DEFAULT,
        sessionId = SessionId("launcher_test_cmd"),
        sessionLogDir = logDir,
        toolRepo = repo,
      )
    }

    assertThat(runtime.sessions).isEmpty()
    assertThat(subprocessStderrFiles(logDir)).isEmpty()
    // Repo stays untouched — the skipped entry contributes nothing to the registry.
    assertThat(repo.getCurrentToolDescriptors().size).isEqualTo(0)

    runBlocking { runtime.shutdownAll() }
    logDir.deleteRecursively()
  }

  private fun subprocessStderrFiles(logDir: File): List<File> =
    logDir.listFiles()?.filter { it.name.startsWith("subprocess_stderr_") && it.name.endsWith(".log") }.orEmpty()
}
