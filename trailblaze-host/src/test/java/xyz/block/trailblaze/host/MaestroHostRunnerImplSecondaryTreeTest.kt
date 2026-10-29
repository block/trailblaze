package xyz.block.trailblaze.host

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import assertk.assertions.isTrue
import kotlinx.datetime.Clock
import org.junit.Before
import org.junit.Test
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.MigrationScreenState
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.host.recording.EffectiveStreamScreenshotConfig
import xyz.block.trailblaze.host.screenstate.SecondaryTreeCarrier
import xyz.block.trailblaze.host.screenstate.SecondaryTreeResult
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.model.SessionId
import java.util.Collections

/**
 * Pins the iOS producer side of dual-tree capture through the runner's real entry point
 * ([MaestroHostRunnerImpl.screenStateProvider]): with the toggle on, an iOS capture carries the
 * `axe describe-ui` tree as its secondary tree; with it off, the driver's own screen state is
 * handed back untouched and `axe` is never shelled.
 *
 * Every device-touching member of the runner is lazy, and the fake driver screen state factory
 * keeps this test from forcing any of them — no simulator is involved.
 */
class MaestroHostRunnerImplSecondaryTreeTest {

  private val fakeUdid = "AAAAAAAA-1111-2222-3333-BBBBBBBBBBBB"

  /**
   * Stands in for [xyz.block.trailblaze.host.screenstate.HostMaestroDriverScreenState]: it runs
   * the capture it is handed at construction, the way the real build does, so this test exercises
   * the runner's half of that contract without touching a simulator.
   */
  private class FakeScreenState(
    secondaryTreeCapture: (() -> SecondaryTreeResult)? = null,
  ) : ScreenState, SecondaryTreeCarrier {
    private val result: SecondaryTreeResult = secondaryTreeCapture?.invoke() ?: SecondaryTreeResult(null)
    override val secondaryTreeCaptureRan: Boolean = secondaryTreeCapture != null
    override val driverMigrationTreeNode: TrailblazeNode? = result.tree
    override val secondaryTreeFailure: String? = result.failureReason
    override val treeCapturedAtHostMs: Long = System.currentTimeMillis()
    override val screenshotBytes: ByteArray = byteArrayOf(9, 8, 7)
    override val deviceWidth: Int = 393
    override val deviceHeight: Int = 852
    override val viewHierarchy: ViewHierarchyTreeNode get() = error("not used by this test")
    override val trailblazeDevicePlatform = TrailblazeDevicePlatform.IOS
    override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList()
    override val trailblazeNodeTree: TrailblazeNode = TrailblazeNode(
      nodeId = 42,
      driverDetail = DriverNodeDetail.IosMaestro(text = "driver tree"),
    )
  }

  private fun axeTree(label: String) = TrailblazeNode(
    nodeId = 100,
    driverDetail = DriverNodeDetail.IosAxe(role = "AXButton", label = label),
  )

  private val noopLogger = TrailblazeLogger(
    logEmitter = { },
    screenStateLogger = { it.fileName },
  )

  private val sessionProvider = {
    TrailblazeSession(sessionId = SessionId("secondary-tree-test"), startTime = Clock.System.now())
  }

  /** Records every udid the secondary-tree seam was asked for; the capture runs off-thread. */
  private val capturedUdids: MutableList<String> = Collections.synchronizedList(mutableListOf())

  /** The state the fake factory last built, so a test can compare identity against it. */
  private var lastDriverScreenState: FakeScreenState? = null

  /** Every dual-tree diagnostic the runner emitted. */
  private val migrationCaptureLines: MutableList<String> = Collections.synchronizedList(mutableListOf())

  private fun runner(
    captureSecondaryTree: Boolean,
    platform: TrailblazeDevicePlatform = TrailblazeDevicePlatform.IOS,
    secondaryTree: TrailblazeNode? = axeTree("Continue"),
    captureResult: () -> SecondaryTreeResult = { SecondaryTreeResult.of(secondaryTree) },
  ) = MaestroHostRunnerImpl(
    trailblazeDeviceId = TrailblazeDeviceId(instanceId = fakeUdid, trailblazeDevicePlatform = platform),
    trailblazeLogger = noopLogger,
    sessionProvider = sessionProvider,
    captureSecondaryTree = captureSecondaryTree,
    secondaryTreeCapture = { udid ->
      capturedUdids.add(udid)
      captureResult()
    },
    driverScreenStateFactory = { _, secondaryTreeCapture ->
      FakeScreenState(secondaryTreeCapture).also { lastDriverScreenState = it }
    },
    migrationCaptureLog = { migrationCaptureLines.add(it) },
  )

  /** Returns each result in turn, then repeats the last one for every later capture. */
  private fun sequence(vararg results: SecondaryTreeResult): () -> SecondaryTreeResult {
    val remaining = results.toMutableList()
    return { if (remaining.size > 1) remaining.removeAt(0) else remaining.first() }
  }

  private fun transientFailure() = SecondaryTreeResult.failed("axe describe-ui exited 1: timed out")

  private fun permanentFailure() = SecondaryTreeResult.unavailable("axe is not available")

  @Before
  fun resetStreamScreenshotConfig() {
    // The runner resolves its screenshot mode at construction; the OFF path is the one under
    // test, and a leftover JVM-wide opt-in would route captures at a real baguette stream.
    EffectiveStreamScreenshotConfig.clearForTests()
  }

  @Test
  fun `enabled on iOS wraps the driver state with the axe tree`() {
    val axe = axeTree("Continue")

    val state = runner(captureSecondaryTree = true, secondaryTree = axe).screenStateProvider()

    val driverState = lastDriverScreenState!!
    assertThat(state).isInstanceOf(MigrationScreenState::class)
    val migration = state as MigrationScreenState
    assertThat(migration.driverMigrationTreeNode).isEqualTo(axe)
    // The primary capture must survive the wrap unchanged — every runtime tool reads it.
    assertThat(migration.trailblazeNodeTree).isEqualTo(driverState.trailblazeNodeTree)
    assertThat(migration.deviceWidth).isEqualTo(driverState.deviceWidth)
    assertThat(migration.deviceHeight).isEqualTo(driverState.deviceHeight)
    assertThat(migration.screenshotBytes).isSameInstanceAs(driverState.screenshotBytes)
    assertThat(capturedUdids).containsExactly(fakeUdid)
  }

  @Test
  fun `disabled returns the driver state and never shells axe`() {
    val state = runner(captureSecondaryTree = false).screenStateProvider()

    assertThat(state is MigrationScreenState).isFalse()
    assertThat(state).isSameInstanceAs(lastDriverScreenState)
    assertThat(capturedUdids).isEmpty()
  }

  @Test
  fun `a null axe tree still wraps, so the snapshot records it as unusable for migration`() {
    val state = runner(captureSecondaryTree = true, secondaryTree = null).screenStateProvider()

    assertThat(state).isInstanceOf(MigrationScreenState::class)
    assertThat((state as MigrationScreenState).driverMigrationTreeNode).isNull()
    assertThat(capturedUdids).containsExactly(fakeUdid)
  }

  @Test
  fun `a non-iOS device is never wrapped and never shells axe`() {
    val state = runner(
      captureSecondaryTree = true,
      platform = TrailblazeDevicePlatform.WEB,
    ).screenStateProvider()

    assertThat(state is MigrationScreenState).isFalse()
    assertThat(state).isSameInstanceAs(lastDriverScreenState)
    assertThat(capturedUdids).isEmpty()
  }

  @Test
  fun `a missing axe is reported once per run, not once per capture`() {
    val runner = runner(captureSecondaryTree = true, captureResult = { permanentFailure() })

    val states = (1..3).map { runner.screenStateProvider() }

    // Three captures, one line: the cause cannot change mid-run, and this path also runs on
    // every Maestro command log, so per-capture reporting buried the line in its own repetition.
    assertThat(migrationCaptureLines).hasSize(1)
    assertThat(migrationCaptureLines.single()).contains(fakeUdid)
    // Every capture still wraps, and still says "ran with no usable tree" — that is what
    // migrate-trail reads to skip the snapshot instead of guessing from the primary tree.
    states.forEach {
      assertThat(it).isInstanceOf(MigrationScreenState::class)
      assertThat((it as MigrationScreenState).driverMigrationTreeNode).isNull()
    }
  }

  @Test
  fun `a missing axe is shelled once per run, not once per capture`() {
    val runner = runner(captureSecondaryTree = true, captureResult = { permanentFailure() })

    repeat(3) { runner.screenStateProvider() }

    // An absent binary stays absent, so the run stops asking after the first answer.
    assertThat(capturedUdids).containsExactly(fakeUdid)
  }

  @Test
  fun `a transient failure does not stop the next capture from trying`() {
    val axe = axeTree("Continue")
    val runner = runner(
      captureSecondaryTree = true,
      captureResult = sequence(transientFailure(), SecondaryTreeResult.of(axe)),
    )

    val first = runner.screenStateProvider()
    val second = runner.screenStateProvider()

    // The usual cause is a screen mid-animation, and the next settled screen reads fine. Latching
    // on the first one would cost the whole session's migration data for one bad frame.
    assertThat(capturedUdids).hasSize(2)
    assertThat((first as MigrationScreenState).driverMigrationTreeNode).isNull()
    assertThat((second as MigrationScreenState).driverMigrationTreeNode).isEqualTo(axe)
  }

  @Test
  fun `back-to-back transient failures stop the run at the cap`() {
    val runner = runner(captureSecondaryTree = true, captureResult = { transientFailure() })

    repeat(MaestroHostRunnerImpl.MAX_CONSECUTIVE_SECONDARY_TREE_FAILURES + 4) {
      runner.screenStateProvider()
    }

    // A genuinely broken axe costs a bounded number of describe-ui timeouts, not one per capture.
    assertThat(capturedUdids).hasSize(MaestroHostRunnerImpl.MAX_CONSECUTIVE_SECONDARY_TREE_FAILURES)
  }

  @Test
  fun `a success between transient failures resets the streak`() {
    val cap = MaestroHostRunnerImpl.MAX_CONSECUTIVE_SECONDARY_TREE_FAILURES
    val results = buildList {
      // One short of the cap, then a success, then the cap again: only the second unbroken run
      // of failures may latch.
      repeat(cap - 1) { add(transientFailure()) }
      add(SecondaryTreeResult.of(axeTree("Continue")))
      repeat(cap) { add(transientFailure()) }
    }
    val runner = runner(captureSecondaryTree = true, captureResult = sequence(*results.toTypedArray()))

    repeat(results.size + 3) { runner.screenStateProvider() }

    assertThat(capturedUdids).hasSize(results.size)
  }

  @Test
  fun `a throwing capture counts toward the streak like a returned failure`() {
    val runner = runner(
      captureSecondaryTree = true,
      captureResult = { throw RuntimeException("axe subprocess died") },
    )

    repeat(MaestroHostRunnerImpl.MAX_CONSECUTIVE_SECONDARY_TREE_FAILURES + 2) {
      runner.screenStateProvider()
    }

    // A capture that dies is no more permanent than one that returns a failure, and no less
    // expensive — so it retries, and it latches at the same cap.
    assertThat(capturedUdids).hasSize(MaestroHostRunnerImpl.MAX_CONSECUTIVE_SECONDARY_TREE_FAILURES)
    assertThat(migrationCaptureLines.first()).contains("axe subprocess died")
  }

  @Test
  fun `the warning says whether capture is off for the run or retrying`() {
    val transient = runner(captureSecondaryTree = true, captureResult = { transientFailure() })
    transient.screenStateProvider()
    assertThat(migrationCaptureLines.single()).contains("Retrying on the next capture")

    migrationCaptureLines.clear()

    val permanent = runner(captureSecondaryTree = true, captureResult = { permanentFailure() })
    permanent.screenStateProvider()
    assertThat(migrationCaptureLines.single()).contains("off for the rest of this run")
  }

  @Test
  fun `running out of retries withdraws the promise to retry, out loud`() {
    val runner = runner(captureSecondaryTree = true, captureResult = { transientFailure() })

    repeat(MaestroHostRunnerImpl.MAX_CONSECUTIVE_SECONDARY_TREE_FAILURES + 2) {
      runner.screenStateProvider()
    }

    // Exactly two lines, in order: the first promised a retry, so the run must say when the
    // retries ran out. A single latched warning left the session silently off after promising
    // the opposite.
    assertThat(migrationCaptureLines).hasSize(2)
    assertThat(migrationCaptureLines[0]).contains("Retrying on the next capture")
    assertThat(migrationCaptureLines[1]).contains("off for the rest of this run")
  }

  @Test
  fun `a run that recovers never claims capture is off`() {
    val runner = runner(
      captureSecondaryTree = true,
      captureResult = sequence(transientFailure(), SecondaryTreeResult.of(axeTree("Continue"))),
    )

    repeat(4) { runner.screenStateProvider() }

    assertThat(migrationCaptureLines).hasSize(1)
    assertThat(migrationCaptureLines.single()).contains("Retrying on the next capture")
  }

  @Test
  fun `a working axe is shelled on every capture`() {
    val runner = runner(captureSecondaryTree = true, secondaryTree = axeTree("Continue"))

    repeat(3) { runner.screenStateProvider() }

    assertThat(capturedUdids).hasSize(3)
    assertThat(migrationCaptureLines).isEmpty()
  }

  @Test
  fun `the failure reason the capture reported reaches the warning`() {
    val runner = MaestroHostRunnerImpl(
      trailblazeDeviceId = TrailblazeDeviceId(fakeUdid, TrailblazeDevicePlatform.IOS),
      trailblazeLogger = noopLogger,
      sessionProvider = sessionProvider,
      captureSecondaryTree = true,
      secondaryTreeCapture = { SecondaryTreeResult.failed("axe describe-ui exited 127: not found") },
      driverScreenStateFactory = { _, capture -> FakeScreenState(capture) },
      migrationCaptureLog = { migrationCaptureLines.add(it) },
    )

    runner.screenStateProvider()

    assertThat(migrationCaptureLines.single()).contains("axe describe-ui exited 127: not found")
  }

  @Test
  fun `the toggle is on only for true`() {
    assertThat(HostMigrationCapture.enabled { "true" }).isTrue()
    assertThat(HostMigrationCapture.enabled { "TRUE" }).isTrue()
    assertThat(HostMigrationCapture.enabled { "false" }).isFalse()
    assertThat(HostMigrationCapture.enabled { "1" }).isFalse()
    assertThat(HostMigrationCapture.enabled { null }).isFalse()
  }

  @Test
  fun `the toggle reads the env var the on-device side is bridged from`() {
    val asked = mutableListOf<String>()
    HostMigrationCapture.enabled { name -> asked.add(name); null }
    assertThat(asked).containsExactly("TRAILBLAZE_CAPTURE_SECONDARY_TREE")
  }
}
