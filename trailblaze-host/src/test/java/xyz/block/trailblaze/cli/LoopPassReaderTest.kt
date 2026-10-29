package xyz.block.trailblaze.cli

import kotlinx.datetime.Instant
import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.agent.model.AgentTaskStatusData
import xyz.block.trailblaze.agent.model.PromptRecordingResult
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.logs.model.TaskId
import xyz.block.trailblaze.logs.model.TrailblazeClockDomain
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.yaml.DirectionStep
import xyz.block.trailblaze.yaml.PromptStep
import xyz.block.trailblaze.yaml.TrailblazeToolYamlWrapper
import xyz.block.trailblaze.yaml.VerificationStep
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LoopPassReaderTest {

  private val session = SessionId("loop-pass-reader")
  private val trailhead = DirectionStep("Launch signed in", isTrailhead = true)
  private val open = DirectionStep("Open the menu")
  private val check = VerificationStep("The menu is open")

  @Test
  fun `a session's finished passes are the first step of a pass to the last step of it`() {
    val logs = listOf(
      start(trailhead, 0), done(trailhead, 100),
      start(open, 200), done(open, 300),
      start(check, 310), done(check, 400),
      start(open, 500), done(open, 600),
      start(check, 610), done(check, 700),
      start(open, 800), done(open, 900),
    )

    val windows = LoopPassReader.passWindows(logs, stepsPerPass = 2)

    assertEquals(listOf(PassWindow(200, 400), PassWindow(500, 700)), windows.completed)
    assertEquals(PassWindow(800, 800), windows.unfinished)
  }

  @Test
  fun `a step that self-healed starts at its failed attempt`() {
    val logs = listOf(
      start(trailhead, 0), done(trailhead, 100),
      start(open, 200), done(open, 300, failure = "Recording failed at tapOnElementBySelector"),
      start(open, 310), done(open, 400),
      start(check, 410), done(check, 500),
    )

    val windows = LoopPassReader.passWindows(logs, stepsPerPass = 2)

    assertEquals(listOf(PassWindow(200, 500)), windows.completed)
    assertNull(windows.unfinished)
  }

  @Test
  fun `the trailhead is no part of a pass and pass 0 starts after it`() {
    val logs = listOf(
      start(trailhead, 50), done(trailhead, 1_000),
      start(open, 1_100), done(open, 1_200),
      start(open, 1_300), done(open, 1_400),
    )

    val windows = LoopPassReader.passWindows(logs, stepsPerPass = 1)

    assertEquals(listOf(PassWindow(1_100, 1_200), PassWindow(1_300, 1_400)), windows.completed)
    assertNull(windows.unfinished)
  }

  @Test
  fun `a failure mid-pass leaves an unfinished window from its first step to the failed step's end`() {
    val logs = listOf(
      start(trailhead, 0), done(trailhead, 100),
      start(open, 200), done(open, 300),
      start(check, 310), done(check, 700, failure = "The menu is closed"),
    )

    val windows = LoopPassReader.passWindows(logs, stepsPerPass = 3)

    assertEquals(emptyList(), windows.completed)
    assertEquals(PassWindow(200, 700), windows.unfinished)
  }

  @Test
  fun `a failure in the second pass leaves the first complete and the second unfinished`() {
    val logs = listOf(
      start(trailhead, 0), done(trailhead, 100),
      start(open, 200), done(open, 300),
      start(check, 310), done(check, 400),
      start(open, 500), done(open, 600),
      start(check, 610), done(check, 750, failure = "The menu is closed"),
    )

    val windows = LoopPassReader.passWindows(logs, stepsPerPass = 2)

    assertEquals(listOf(PassWindow(200, 400)), windows.completed)
    assertEquals(PassWindow(500, 750), windows.unfinished)
  }

  @Test
  fun `a session that failed without a step failure ends its pass where the session ended`() {
    val logs = listOf(
      start(trailhead, 0), done(trailhead, 100),
      start(open, 200), done(open, 300),
      start(check, 310),
      ended(900),
    )

    val windows = LoopPassReader.passWindows(logs, stepsPerPass = 2)

    assertEquals(emptyList(), windows.completed)
    assertEquals(PassWindow(200, 900), windows.unfinished)
  }

  @Test
  fun `a session that failed after its last step passed has no unfinished pass`() {
    val logs = listOf(
      start(trailhead, 0), done(trailhead, 100),
      start(open, 200), done(open, 300),
      ended(900),
    )

    val windows = LoopPassReader.passWindows(logs, stepsPerPass = 1)

    assertEquals(listOf(PassWindow(200, 300)), windows.completed)
    assertNull(windows.unfinished)
  }

  @Test
  fun `a failure at the trailhead has no finished pass and an unfinished window from the trailhead`() {
    val logs = listOf(
      start(trailhead, 40), done(trailhead, 100, failure = "App did not launch"),
    )

    val windows = LoopPassReader.passWindows(logs, stepsPerPass = 2)

    assertEquals(emptyList(), windows.completed)
    assertEquals(PassWindow(40, 100), windows.unfinished)
  }

  @Test
  fun `a session that failed before any step started has an unfinished window over its whole span`() {
    val logs = listOf(started(20), ended(90))

    val windows = LoopPassReader.passWindows(logs, stepsPerPass = 2)

    assertEquals(emptyList(), windows.completed)
    assertEquals(PassWindow(20, 90), windows.unfinished)
  }

  @Test
  fun `teardown is no step of a pass, so a failed last step still leaves its pass unfinished`() {
    val teardown = DirectionStep("Teardown", recordable = false, isTeardown = true)
    val logs = listOf(
      start(trailhead, 0), done(trailhead, 100),
      start(open, 200), done(open, 300),
      start(check, 310), done(check, 400, failure = "The menu is closed"),
      start(teardown, 410), done(teardown, 500),
    )

    val windows = LoopPassReader.passWindows(logs, stepsPerPass = 2)

    assertEquals(emptyList(), windows.completed)
    assertEquals(PassWindow(200, 400), windows.unfinished)
  }

  @Test
  fun `a step that starts and ends in the same millisecond doesn't start the next pass too`() {
    val logs = listOf(
      start(trailhead, 0), done(trailhead, 100),
      start(open, 200), done(open, 200),
      ended(300),
    )

    val windows = LoopPassReader.passWindows(logs, stepsPerPass = 1)

    assertEquals(listOf(PassWindow(200, 200)), windows.completed)
    assertNull(windows.unfinished)
  }

  @Test
  fun `a first step that fails after a good trailhead starts its window at the step`() {
    val logs = listOf(
      start(trailhead, 0), done(trailhead, 100),
      start(open, 200), done(open, 300, failure = "No menu"),
    )

    val windows = LoopPassReader.passWindows(logs, stepsPerPass = 2)

    assertEquals(PassWindow(200, 300), windows.unfinished)
  }

  @Test
  fun `a session that died while a failed step self-healed ends its pass where the session ended`() {
    val logs = listOf(
      start(trailhead, 0), done(trailhead, 100),
      start(open, 200), done(open, 400, failure = "Recording failed at tapOnElementBySelector"),
      start(open, 410),
      ended(900),
    )

    val windows = LoopPassReader.passWindows(logs, stepsPerPass = 1)

    assertEquals(PassWindow(200, 900), windows.unfinished)
  }

  @Test
  fun `a self-heal whose recovery's start log is missing still runs the failed pass to the session's end`() {
    val dir = writeSession(
      start(trailhead, 0), done(trailhead, 100),
      start(open, 200), done(open, 400, failure = "Recording failed at tapOnElementBySelector"),
      selfHealInvoked(open, 405),
      ended(900),
    )

    assertEquals(PassWindow(200, 900), LoopPassReader().read(dir, stepsPerPass = 1).unfinished)
  }

  @Test
  fun `a failed step whose start and recovery's start are both missing still leaves its pass unfinished`() {
    val logs = listOf(
      start(trailhead, 0), done(trailhead, 100),
      start(open, 200), done(open, 300),
      done(open, 500, failure = "Recording failed at tapOnElementBySelector"),
      selfHealInvoked(open, 510),
      ended(900),
    )

    assertEquals(PassWindow(300, 900), LoopPassReader.passWindows(logs, stepsPerPass = 1).unfinished)
  }

  @Test
  fun `a setup trail's steps before the trailhead are no pass`() {
    val signIn = DirectionStep("Sign in to the test account")
    val logs = listOf(
      start(signIn, 0), done(signIn, 100),
      start(trailhead, 110), done(trailhead, 200),
      start(open, 300), done(open, 400),
    )

    assertEquals(listOf(PassWindow(300, 400)), LoopPassReader.passWindows(logs, stepsPerPass = 1).completed)
  }

  @Test
  fun `a setup trail's step is no pass while the trailhead after it still runs`() {
    val signIn = DirectionStep("Sign in to the test account")
    val logs = listOf(start(signIn, 0), done(signIn, 100), start(trailhead, 110))

    assertEquals(emptyList(), LoopPassReader.passWindows(logs, stepsPerPass = 1).completed)
  }

  @Test
  fun `a trailhead that failed after a setup trail passed fails from the trailhead's own start`() {
    val signIn = DirectionStep("Sign in to the test account")
    val logs = listOf(start(signIn, 0), done(signIn, 100), start(trailhead, 110), done(trailhead, 200, failure = "App did not launch"))

    assertEquals(PassWindow(110, 200), LoopPassReader.passWindows(logs, stepsPerPass = 1).unfinished)
  }

  @Test
  fun `a first step that failed with its start log missing starts at the trailhead's end`() {
    val logs = listOf(start(trailhead, 0), done(trailhead, 100), done(open, 300, failure = "No menu"))

    assertEquals(PassWindow(100, 300), LoopPassReader.passWindows(logs, stepsPerPass = 1).unfinished)
  }

  @Test
  fun `logs a device run wrote, named without an underscore before their type, are read too`() {
    val dir = writeSession(
      start(trailhead, 0), done(trailhead, 100),
      start(open, 200), done(open, 300),
      fileName = { index, log -> "session_%010d%s.json".format(index + 1, log::class.simpleName) },
    )

    assertEquals(listOf(PassWindow(200, 300)), LoopPassReader().read(dir, stepsPerPass = 1).completed)
  }

  @Test
  fun `logs past number 999 stay in the order they were written when they share a millisecond`() {
    val dir = writeSession(
      start(trailhead, 0), done(trailhead, 100),
      start(open, 200), done(open, 300),
      start(open, 300), done(open, 400, failure = "No menu"),
      fileName = { index, log -> "%03d_%s.json".format(index + 996, log::class.simpleName) },
    )

    val windows = LoopPassReader().read(dir, stepsPerPass = 1)

    assertEquals(listOf(PassWindow(200, 300)), windows.completed)
    assertEquals(PassWindow(300, 400), windows.unfinished)
  }

  @Test
  fun `a completed step whose start log is missing starts where the step before it ended`() {
    val logs = listOf(
      start(trailhead, 0), done(trailhead, 100),
      done(open, 300),
      start(open, 500), done(open, 600),
    )

    val windows = LoopPassReader.passWindows(logs, stepsPerPass = 1)

    assertEquals(listOf(PassWindow(100, 300), PassWindow(500, 600)), windows.completed)
  }

  @Test
  fun `a failed step whose start log is missing starts where the step before it ended`() {
    val logs = listOf(
      start(trailhead, 0), done(trailhead, 100),
      start(open, 200), done(open, 300),
      done(open, 600, failure = "No menu"),
    )

    val windows = LoopPassReader.passWindows(logs, stepsPerPass = 1)

    assertEquals(listOf(PassWindow(200, 300)), windows.completed)
    assertEquals(PassWindow(300, 600), windows.unfinished)
  }

  @Test
  fun `a setup that failed with its start log missing is not started at the teardown after it`() {
    val teardown = DirectionStep("Teardown", recordable = false, isTeardown = true)
    val logs = listOf(
      started(0), done(trailhead, 100, failure = "App did not launch"),
      start(teardown, 110), done(teardown, 120),
      ended(150),
    )

    val windows = LoopPassReader.passWindows(logs, stepsPerPass = 1)

    assertEquals(PassWindow(0, 100), windows.unfinished)
  }

  @Test
  fun `a log written twice, once by the host and once by the runner's fallback, counts once`() {
    val (passed, _) = done(open, 300)
    val dir = writeSession(
      start(trailhead, 0), done(trailhead, 100),
      start(open, 200), passed to 300,
      (passed as TrailblazeLog.ObjectiveCompleteLog).copy(hostReceivedAt = Instant.fromEpochMilliseconds(305)) to 300,
      start(check, 310), done(check, 400, failure = "The menu is closed"),
    )

    val windows = LoopPassReader().read(dir, stepsPerPass = 2)

    assertEquals(emptyList(), windows.completed)
    assertEquals(PassWindow(200, 400), windows.unfinished)
  }

  @Test
  fun `a start a second writer numbered before the step it follows still starts the next pass in that millisecond`() {
    val dir = writeSession(
      start(trailhead, 0), done(trailhead, 100),
      start(open, 200),
      start(open, 300),
      done(open, 300),
      done(open, 400, failure = "No menu"),
      fileName = { index, log -> if (index == 3) "001_${log::class.simpleName}.json" else "%03d_%s.json".format(index + 996, log::class.simpleName) },
    )

    val windows = LoopPassReader().read(dir, stepsPerPass = 1)

    assertEquals(listOf(PassWindow(200, 300)), windows.completed)
    assertEquals(PassWindow(300, 400), windows.unfinished)
  }

  @Test
  fun `a session with no steps per pass has no windows`() {
    val logs = listOf(start(open, 0), done(open, 10))

    assertEquals(SessionPassWindows(emptyList(), null), LoopPassReader.passWindows(logs, stepsPerPass = 0))
  }

  @Test
  fun `logs are ordered by their host time, not the order they are given`() {
    val logs = listOf(
      done(open, 300), start(open, 200), done(trailhead, 100), start(trailhead, 0),
    )

    assertEquals(listOf(PassWindow(200, 300)), LoopPassReader.passWindows(logs, stepsPerPass = 1).completed)
  }

  @Test
  fun `reading a session directory finds its passes`() {
    val dir = writeSession(
      start(trailhead, 0), done(trailhead, 100),
      start(open, 200), done(open, 300),
      start(check, 310), done(check, 400),
      start(open, 500), done(open, 600),
      ended(650),
    )

    val windows = LoopPassReader().read(dir, stepsPerPass = 2)

    assertEquals(listOf(PassWindow(200, 400)), windows.completed)
    assertEquals(PassWindow(500, 650), windows.unfinished)
  }

  @Test
  fun `objective logs stamped by the device are put on the host clock through the tool logs' anchors`() {
    val deviceBehindHostMs = 5_000L
    // The device's clock trails the host's by 5s, so its stamps are 5s early.
    fun deviceStart(step: PromptStep, hostMs: Long) = start(step, hostMs - deviceBehindHostMs, TrailblazeClockDomain.DEVICE)
    fun deviceDone(step: PromptStep, hostMs: Long) = done(step, hostMs - deviceBehindHostMs, clock = TrailblazeClockDomain.DEVICE)
    val anchor = TrailblazeLog.TrailblazeToolLog(
      trailblazeTool = OtherTrailblazeTool(toolName = "tapOn"),
      toolName = "tapOn",
      successful = true,
      traceId = null,
      durationMs = 50,
      session = session,
      timestamp = Instant.fromEpochMilliseconds(250 - deviceBehindHostMs),
      clock = TrailblazeClockDomain.DEVICE,
      hostReceivedAt = Instant.fromEpochMilliseconds(250 + 50),
    )
    val dir = writeSession(
      deviceStart(trailhead, 0), deviceDone(trailhead, 100),
      deviceStart(open, 200), deviceDone(open, 300),
      deviceStart(check, 310), deviceDone(check, 400),
      anchor to 0L,
    )

    val windows = LoopPassReader().read(dir, stepsPerPass = 2)

    assertEquals(listOf(PassWindow(200, 400)), windows.completed)
  }

  @Test
  fun `a log still being written is read again once it is whole`() {
    val dir = writeSession(start(trailhead, 0), done(trailhead, 100), start(open, 200))
    val reader = LoopPassReader()
    val partial = File(dir, "004_ObjectiveCompleteLog.json")
    val whole = encode(done(open, 300).first)
    partial.writeText(whole.take(whole.length / 2))

    assertEquals(emptyList(), reader.read(dir, stepsPerPass = 1).completed)

    partial.writeText(whole)

    assertEquals(listOf(PassWindow(200, 300)), reader.read(dir, stepsPerPass = 1).completed)
  }

  private fun start(step: PromptStep, atMs: Long, clock: TrailblazeClockDomain? = null) =
    TrailblazeLog.ObjectiveStartLog(
      promptStep = step,
      session = session,
      timestamp = Instant.fromEpochMilliseconds(atMs),
      clock = clock,
    ) to atMs

  private fun done(step: PromptStep, atMs: Long, failure: String? = null, clock: TrailblazeClockDomain? = null): Pair<TrailblazeLog, Long> {
    val now = Instant.fromEpochMilliseconds(atMs)
    val data = AgentTaskStatusData(TaskId.generate(), step.prompt, 1, now, 1)
    return TrailblazeLog.ObjectiveCompleteLog(
      promptStep = step,
      objectiveResult = if (failure == null) {
        AgentTaskStatus.Success.ObjectiveComplete(data, llmExplanation = "Done")
      } else {
        AgentTaskStatus.Failure.ObjectiveFailed(data, llmExplanation = failure)
      },
      session = session,
      timestamp = now,
      clock = clock,
    ) to atMs
  }

  private fun selfHealInvoked(step: PromptStep, atMs: Long) = TrailblazeLog.SelfHealInvokedLog(
    promptStep = step,
    session = session,
    timestamp = Instant.fromEpochMilliseconds(atMs),
    recordingResult = PromptRecordingResult.Failure(
      successfulTools = emptyList(),
      failedTool = TrailblazeToolYamlWrapper(name = "tapOn", trailblazeTool = OtherTrailblazeTool(toolName = "tapOn")),
      failureResult = TrailblazeToolResult.Error.ExceptionThrown("element not found"),
    ),
  ) to atMs

  private fun started(atMs: Long): Pair<TrailblazeLog, Long> {
    val deviceId = TrailblazeDeviceId("emulator-5554", TrailblazeDevicePlatform.ANDROID)
    return TrailblazeLog.TrailblazeSessionStatusChangeLog(
      sessionStatus = SessionStatus.Started(
        trailConfig = null,
        trailFilePath = null,
        hasRecordedSteps = false,
        testMethodName = "loop",
        testClassName = "Trail",
        trailblazeDeviceInfo = TrailblazeDeviceInfo(deviceId, TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY, 1080, 1920),
      ),
      session = session,
      timestamp = Instant.fromEpochMilliseconds(atMs),
    ) to atMs
  }

  private fun ended(atMs: Long) = TrailblazeLog.TrailblazeSessionStatusChangeLog(
    sessionStatus = SessionStatus.Ended.Failed(durationMs = atMs, exceptionMessage = "driver died"),
    session = session,
    timestamp = Instant.fromEpochMilliseconds(atMs),
  ) to atMs

  private fun writeSession(
    vararg logs: Pair<TrailblazeLog, Long>,
    fileName: (Int, TrailblazeLog) -> String = { index, log -> "%03d_%s.json".format(index + 1, log::class.simpleName) },
  ): File {
    val dir = Files.createTempDirectory("loop-pass-reader").toFile()
    logs.forEachIndexed { index, (log, _) -> File(dir, fileName(index, log)).writeText(encode(log)) }
    return dir
  }

  private fun encode(log: TrailblazeLog) = TrailblazeJsonInstance.encodeToString(TrailblazeLog.serializer(), log)
}
