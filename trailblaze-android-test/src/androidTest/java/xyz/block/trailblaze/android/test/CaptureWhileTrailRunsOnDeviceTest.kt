package xyz.block.trailblaze.android.test

import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.Clock
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import xyz.block.trailblaze.android.test.hierarchy.AndroidHybridHierarchyCollector
import xyz.block.trailblaze.android.test.tools.AndroidTestAssertVisibleTool
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.utils.NoOpElementComparator

/**
 * Capturing the screen from one thread while a trail runs on another.
 *
 * This is the in-process ANDROID_TEST server's shape, and only that server's: it answers RPC on
 * Ktor workers, but Espresso interactions must be issued from the instrumentation thread, so a
 * trail is dispatched over there while capture deliberately stays behind. Capture has to stay
 * behind — it is what the host's readiness probe reads, and a probe that queued behind a running
 * trail would time out on a server that is perfectly healthy.
 *
 * Which leaves the two overlapping over a tree neither of them owns. Compose semantics and the
 * View tree are the UI thread's, mutated by the composition and layout a trail's interaction
 * drives, and a read taken from anywhere else can interleave with one. Nothing about the resulting
 * half-updated tree throws — it is simply wrong about the screen — so the fixture reports which
 * thread read it rather than the test guessing from a symptom.
 */
class CaptureWhileTrailRunsOnDeviceTest {

  @get:Rule val composeRule = createEmptyComposeRule() as AndroidComposeTestRule<*, *>

  private lateinit var scenario: ActivityScenario<MixedUiFixtureActivity>
  private lateinit var fixture: MixedUiFixtureActivity
  private lateinit var target: RuleBackedAndroidTestTarget
  private lateinit var agent: AndroidTestTrailblazeAgent

  @Before
  fun launchFixture() {
    scenario = ActivityScenario.launch(MixedUiFixtureActivity::class.java)
    var activity: MixedUiFixtureActivity? = null
    scenario.onActivity { activity = it }
    fixture = checkNotNull(activity)
    composeRule.waitForIdle()
    target = RuleBackedAndroidTestTarget(
      activityProvider = { fixture },
      composeTestRule = composeRule,
    )
    agent = AndroidTestTrailblazeAgent(
      target = target,
      trailblazeLogger = TrailblazeLogger.createNoOp(),
      trailblazeDeviceInfoProvider = {
        TrailblazeDeviceInfo(
          trailblazeDeviceId = TrailblazeDeviceId(
            instanceId = "instrumentation",
            trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
          ),
          trailblazeDriverType = TrailblazeDriverType.ANDROID_TEST,
          widthPixels = fixture.resources.displayMetrics.widthPixels,
          heightPixels = fixture.resources.displayMetrics.heightPixels,
        )
      },
      sessionProvider = {
        TrailblazeSession(
          sessionId = SessionId("capture_while_trail_runs"),
          startTime = Clock.System.now(),
        )
      },
    )
    SemanticsReadProbe.reset()
  }

  @After
  fun closeFixture() {
    scenario.close()
  }

  /**
   * A capture taken off the instrumentation thread still reads the semantics tree on the thread
   * that owns it.
   *
   * The collector's own walk is the read that matters: it visits every node, and for each one it
   * asks for children, bounds and config — all of which come back off the live layout tree.
   *
   * The same capture also proves the root walk keeps descending through a Compose host: the
   * fixture parks a further ComposeView beneath its AndroidView, and that nested root's node is
   * reachable by no other means — a walk that stops at the first host loses exactly this tag.
   */
  @Test
  fun theComposeTreeIsReadOnTheThreadThatOwnsIt() {
    val collected = onAnotherThread { AndroidHybridHierarchyCollector.collect(fixture, target) }
    SemanticsReadProbe.assertReadOnlyOnUiThread()
    assertTrue(
      collected.containsComposeTag(MixedUiFixtureActivity.NESTED_COMPOSE_TAG),
      "The nested Compose host's semantics are missing — the root walk stopped at the outer host",
    )
  }

  /**
   * The overlap itself: a capture issued from another thread answers WHILE a run is still going,
   * and still reads the tree on the UI thread.
   *
   * The ordering is structural, not timed. The run is a real assert-visible step waiting for the
   * fixture's late-placement node to gain bounds — and the only thing that reveals that node is
   * the captor, AFTER its own capture completes. So the run cannot finish before the capture has:
   * a green run IS the proof of overlap. The forbidden fix — dispatching capture onto the
   * instrumentation thread behind the trail — deadlocks this sequence (the reveal waits on a
   * capture that waits on the run) and fails deterministically on the resolver's own timeout,
   * because the host's readiness probe is a capture and must never queue behind a run.
   *
   * The captor starts only after the run's first poll trips the read probe, so the capture is
   * taken strictly inside the run, not before it.
   */
  @Test
  fun aCaptureAnswersWhileTheTestThreadIsStillInsideARun() {
    val capturedTree = AtomicReference<AndroidHybridHierarchyCollector.Collected?>(null)
    val captureFailure = AtomicReference<Throwable?>(null)
    val captor = thread(name = "trailblaze-test-captor") {
      runCatching {
        awaitFirstProbeRead()
        AndroidHybridHierarchyCollector.collect(fixture, target)
      }
        .onSuccess { capturedTree.set(it) }
        .onFailure { captureFailure.set(it) }
      // Revealed on failure too: a capture that broke must fail ITS assertions below, not park the
      // run until the resolver expires and blame the step.
      fixture.revealLateContentNow()
    }

    run(AndroidTestAssertVisibleTool(composeTag(MixedUiFixtureActivity.COMPOSE_LATE_TAG)))

    // Generous hang containment only — the ordering above already proved the capture finished
    // while the run was in flight, so this bound carries no timing argument.
    captor.join(TimeUnit.SECONDS.toMillis(60))
    assertTrue(!captor.isAlive, "The captor is still running after the run completed")
    captureFailure.get()?.let { throw AssertionError("The concurrent capture failed", it) }
    val collected = assertNotNull(capturedTree.get(), "The concurrent capture never completed")
    // The trail's own polling also reads the probe, so "the probe was read" alone says nothing
    // about the captor. Its OWN tree containing the probe node does: mapping that node is what
    // invokes the probe's semantics lambda.
    assertTrue(
      collected.containsComposeTag(MixedUiFixtureActivity.COMPOSE_READ_PROBE_TAG),
      "The captor's capture does not contain the probe node, so it never read the probe itself",
    )
    SemanticsReadProbe.assertReadOnlyOnUiThread()
  }

  /**
   * A target with no Compose rule captures the View half and nothing else.
   *
   * The gate is [AndroidTestTarget.composeRootsIn]'s: without a rule this driver has no way to ACT
   * on a Compose node, and capturing nodes no tool could reach would only teach selectors to
   * resolve to them.
   */
  @Test
  fun aRuleLessTargetCapturesViewsAndNoComposeNodes() {
    val ruleLess = RuleBackedAndroidTestTarget(activityProvider = { fixture })
    val collected = AndroidHybridHierarchyCollector.collect(fixture, ruleLess)
    val nodes = collected.trailblazeTree.aggregate()
    assertTrue(
      nodes.any { it.driverDetail is DriverNodeDetail.AndroidView },
      "The View half must still be captured without a Compose rule",
    )
    assertTrue(
      nodes.none { it.driverDetail is DriverNodeDetail.Compose } &&
        collected.semanticsIdByNodeId.isEmpty(),
      "A rule-less target must not capture Compose nodes it could never act on",
    )
  }

  /**
   * A root that is attached but not RESUMED is not captured — the Compose rule's own registry
   * tracks a `resumedRoots` set, and this walk must not be wider than the read it replaced.
   *
   * Attached-but-paused Compose content is a real screen state (ViewPager2 keeps adjacent pages
   * attached at STARTED), and its host has no on-screen bounds, so an over-wide walk would dangle
   * that tree at the snapshot root and teach selectors to resolve to controls nobody can see. The
   * RESUMED half of the assertion keeps the filter honest in the other direction: a filter that
   * drops too much fails here too.
   */
  @Test
  fun anAttachedButNotResumedRootIsNotCaptured() {
    scenario.moveToState(Lifecycle.State.STARTED)
    val paused = AndroidHybridHierarchyCollector.collect(fixture, target)
    assertTrue(
      paused.trailblazeTree.aggregate().none { it.driverDetail is DriverNodeDetail.Compose },
      "A non-RESUMED root's semantics were captured — the rule's registry would have excluded them",
    )
    scenario.moveToState(Lifecycle.State.RESUMED)
    composeRule.waitForIdle()
    val resumed = AndroidHybridHierarchyCollector.collect(fixture, target)
    assertTrue(
      resumed.containsComposeTag(MixedUiFixtureActivity.COMPOSE_BUTTON_TAG),
      "The RESUMED root's semantics are missing — the lifecycle filter is dropping live content",
    )
  }

  /**
   * A tree-less snapshot never walks the hierarchy — honored by NOT building, not by deferring.
   *
   * The host's device mirror polls captures 5x/sec with `includeTree=false` for pixels alone, and
   * the walk it skips runs on the app's main thread. The probe staying silent is what proves the
   * walk never ran.
   *
   * The [ScreenState] surface then reads as it does on the other two Android drivers — empty
   * hierarchy, null node tree — so the flag means one thing everywhere and a generic consumer
   * (session logging reads the non-null `viewHierarchy`) cannot crash on this driver alone. A
   * lazy tree would fail this test twice over: the deferred walk trips the probe, and the reads
   * come back populated.
   */
  @Test
  fun aTreeLessSnapshotSkipsTheWalkAndReadsEmptyLikeTheOtherDrivers() {
    val state = AndroidTestScreenState(target, includeScreenshot = false, includeTree = false)

    assertTrue(
      SemanticsReadProbe.readingThreads().isEmpty(),
      "A tree-less snapshot read the probe's semantics — the hierarchy walk ran anyway",
    )
    assertNull(state.trailblazeNodeTree, "Tree-less must report null, as the sibling drivers do")
    assertEquals(
      ViewHierarchyTreeNode(),
      state.viewHierarchy,
      "Tree-less must report an empty hierarchy, as the sibling drivers do",
    )
    assertNull(state.viewHierarchyTextRepresentation)
    assertNull(state.annotationElements)
    assertNull(state.pageContextSummary)
  }

  /**
   * The driver's own tree-requiring members still fail loudly on a tree-less snapshot.
   *
   * None of these are part of [ScreenState], so no generic consumer can reach them — only this
   * driver's tools, each of which exists to act on a live node. Returning empty there would
   * silently degrade a capture mistake into "element not found", which is the misdiagnosis the
   * whole selector-failure report exists to prevent.
   */
  @Test
  fun aTreeLessSnapshotStillFailsLoudlyOnTheDriversOwnTreeMembers() {
    val state = AndroidTestScreenState(target, includeScreenshot = false, includeTree = false)

    assertFailsWith<IllegalStateException> { state.requiredNodeTree }
    assertFailsWith<IllegalStateException> { state.viewByNodeId }
    assertFailsWith<IllegalStateException> { state.semanticsIdByNodeId }
  }

  /**
   * The mirror's actual request shape: pixels WITH no tree.
   *
   * The two tests above capture screenshot-free, which is a combination the mirror never sends —
   * so on their own they leave the pixels-and-no-tree path, the only one a real
   * `includeTree=false` request produces, unexercised.
   */
  @Test
  fun aTreeLessSnapshotStillCapturesPixels() {
    // Screenshot-capable the way AndroidTestTrailblazeRule builds it for report-bound captures:
    // RuleBackedAndroidTestTarget's own provider defaults to null, so a bare target would report
    // no pixels for reasons that have nothing to do with includeTree.
    val screenshotCapable = object : AndroidTestTarget by target {
      override fun captureScreenshot() = AndroidTestInstrumentation.deviceScreenshot()
    }

    val state =
      AndroidTestScreenState(screenshotCapable, includeScreenshot = true, includeTree = false)

    assertNotNull(state.screenshotBytes, "Pixels are the whole point of a tree-less mirror frame")
    assertTrue(
      SemanticsReadProbe.readingThreads().isEmpty(),
      "A tree-less snapshot read the probe's semantics — the hierarchy walk ran anyway",
    )
    assertNull(state.trailblazeNodeTree)
  }

  /**
   * The control for the probe assertion above: a default snapshot DOES trip the probe, so the
   * tree-less test's silence is the flag's doing rather than a probe nothing reads anymore.
   */
  @Test
  fun aDefaultSnapshotWalksTheHierarchy() {
    val state = AndroidTestScreenState(target, includeScreenshot = false)

    assertTrue(
      SemanticsReadProbe.readingThreads().isNotEmpty(),
      "A default snapshot never read the probe — is the probe still in the fixture's content?",
    )
    assertTrue(
      state.requiredNodeTree.aggregate().any { node ->
        (node.driverDetail as? DriverNodeDetail.Compose)?.testTag == MixedUiFixtureActivity.COMPOSE_READ_PROBE_TAG
      },
      "The default snapshot's tree is missing the probe node it just read",
    )
    assertNotNull(state.trailblazeNodeTree, "A default snapshot must expose its tree on ScreenState")
    // The derived fields hang off the same tree, so a snapshot that walked must populate them.
    // Asserted here rather than only as nulls on the tree-less path, so blanking them
    // unconditionally cannot pass both tests.
    assertNotNull(state.viewHierarchyTextRepresentation)
    assertNotNull(state.annotationElements)
  }

  /**
   * Blocks until something has read the fixture's probe. After the probe reset in [launchFixture],
   * the first read can only be the run's own screen-state poll — nothing else captures — so this
   * is "the run is in flight", observed rather than assumed.
   */
  private fun awaitFirstProbeRead() {
    val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(60)
    while (SemanticsReadProbe.readingThreads().isEmpty()) {
      check(System.currentTimeMillis() < deadline) { "No run ever read the screen" }
      Thread.sleep(10)
    }
  }

  private fun AndroidHybridHierarchyCollector.Collected.containsComposeTag(tag: String): Boolean =
    trailblazeTree.aggregate().any { node ->
      (node.driverDetail as? DriverNodeDetail.Compose)?.testTag == tag
    }

  /** Runs [block] on a thread that is neither the UI thread nor the instrumentation thread. */
  private fun <T> onAnotherThread(block: () -> T): T {
    val result = AtomicReference<Result<T>>()
    thread(name = "trailblaze-test-captor") { result.set(runCatching(block)) }.join()
    return result.get().getOrThrow()
  }

  private fun composeTag(tag: String) =
    TrailblazeNodeSelector(compose = DriverNodeMatch.Compose(testTag = tag))

  private fun run(tool: TrailblazeTool) {
    val result = agent.runTrailblazeTools(
      tools = listOf(tool),
      traceId = null,
      screenState = null,
      elementComparator = NoOpElementComparator,
      screenStateProvider = agent.screenStateProvider,
    ).result
    assertIs<TrailblazeToolResult.Success>(result, "ANDROID_TEST tool failed: $result")
  }
}
