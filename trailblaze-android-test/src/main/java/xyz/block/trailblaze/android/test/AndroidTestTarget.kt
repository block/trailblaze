package xyz.block.trailblaze.android.test

import android.app.Activity
import android.os.SystemClock
import android.graphics.Bitmap
import android.view.View
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.espresso.AppNotIdleException
import androidx.test.espresso.Espresso
import androidx.test.espresso.IdlingPolicies
import androidx.test.espresso.IdlingResourceTimeoutException
import androidx.test.espresso.ViewAction
import androidx.test.espresso.ViewAssertion
import androidx.test.espresso.ViewInteraction
import androidx.test.espresso.matcher.RootMatchers
import java.util.concurrent.TimeUnit
import org.hamcrest.Matcher
import org.hamcrest.Matchers.sameInstance
import xyz.block.trailblaze.android.test.hierarchy.AndroidComposeHierarchyCollector
import xyz.block.trailblaze.android.test.hierarchy.AndroidHybridHierarchyCollector
import xyz.block.trailblaze.api.DriverDispatch
import xyz.block.trailblaze.api.dispatchThenSettle

/**
 * Native Android test surface used by the Android test Trailblaze driver.
 *
 * The host app supplies the active [composeTestRule]. This is important for mixed View/Compose
 * applications: a second Compose rule would install a second clock/idling bridge and can make the
 * application's existing test harness deadlock. Espresso remains the owner of View synchronization;
 * the supplied Compose rule remains the owner of semantics synchronization.
 */
interface AndroidTestTarget : DriverDispatch {
  /** The Compose rule already installed by the application's test harness, when Compose is used. */
  val composeTestRule: AndroidComposeTestRule<*, *>?

  /** The currently resumed Activity. Resolve on every capture so recreation cannot stale it. */
  fun currentActivity(): Activity

  /**
   * Executes an Espresso action against a classic Android View, in the window whose root is [root]
   * when given. Espresso's default root is the focused window, which excludes a popup that does
   * not take focus (a search field's suggestion menu) even though the capture shows it.
   */
  fun performViewAction(matcher: Matcher<View>, action: ViewAction, root: View? = null)

  /** Executes an Espresso assertion against a classic Android View; [root] as for [performViewAction]. */
  fun checkView(matcher: Matcher<View>, assertion: ViewAssertion, root: View? = null)

  /**
   * Returns every active Compose semantics root, including dialog and popup roots when available.
   *
   * Reads the **unmerged** tree. The merged tree folds a composable's children into it — a
   * Button's Text child disappears into the Button — which erases exactly the structure a
   * selector needs to talk about. See [AndroidComposeActions] for the ascend-to-action-ancestor
   * rule that makes acting on an unmerged node safe.
   *
   * Synchronizes with the app first, so this is for the ACTION path only — the one caller that has
   * the instrumentation thread to itself. Espresso's idle wait takes exclusive hold of the main
   * looper, and a second thread asking for it while a trail runs does not get a stale answer, it
   * gets a thrown `TestLooperManager already held for this looper` — possibly on the trail's side.
   * Anything that can run concurrently with a trail reads through [composeRootsIn] instead.
   */
  fun composeRoots(): List<SemanticsNode>

  /**
   * Every Compose semantics root inside [windowRoot]'s window, read on the UI thread and WITHOUT
   * synchronizing with the app.
   *
   * The two differences from [composeRoots] are the same difference. Semantics belong to the UI
   * thread, so reading them there is what keeps a tree self-consistent — a stronger guarantee than
   * waiting for idle and then reading from a thread the app is free to recompose underneath — and
   * it costs nothing that has to be bought from Espresso, which is what makes this read safe to
   * take while a trail is mid-interaction.
   *
   * Gated on a Compose rule being supplied, because that is this driver's declaration that its
   * Compose actions have a rule to run through. Capturing nodes no tool could then act on would
   * only teach selectors to resolve to them.
   *
   * Capture goes through THIS method, never [composeRoots] — so a target that sources its Compose
   * roots some other way (a custom [composeRoots] with no rule) must override this one too, or its
   * captures silently come back View-only.
   *
   * The UI-thread contract is enforced here rather than documented at the caller:
   * [onMainThreadForCapture] is re-entrant, so the one hot caller — already inside a posted
   * block — pays nothing, and a future off-thread caller becomes correct instead of silently
   * wrong. The hop is the BOUNDED capture one for the same reason it is in
   * `AndroidHybridHierarchyCollector.collect`: this method exists for the capture path, and a
   * wedged main thread must surface as not-ready rather than park the probe forever.
   */
  fun composeRootsIn(windowRoot: View): List<SemanticsNode> =
    if (composeTestRule == null) {
      emptyList()
    } else {
      onMainThreadForCapture { AndroidComposeHierarchyCollector.rootsUnder(windowRoot) }
    }

  /** Captures the complete device window for Trailblaze logging. */
  fun captureScreenshot(): Bitmap?

  /**
   * Waits for both halves of a mixed Android UI to become idle.
   *
   * [ceilingMs] bounds the wait when the CALLER's contract carries its own ceiling (the canonical
   * `wait` tool, Maestro's `waitForAnimationToEnd` timeout). Null means the framework's own idle
   * policy applies — the right bound for the implicit settles every native tool performs.
   */
  fun waitForIdle(ceilingMs: Long? = null)

  /**
   * Lets the app's own timers run for the real time that passed since the last settle or call.
   *
   * Called before each capture a tool dispatches on, so a tool that polls the screen — a recorded
   * "wait until Content Loaded" — sees an app whose `delay()`s finish. A no-op for targets whose
   * app is not on a virtual clock.
   */
  fun advanceAppClock() {}

  /** Treats the app's clock as caught up with real time now, without advancing it. */
  fun markAppClockSynced() {}

  /**
   * Views are synchronized by Espresso and Compose by the existing Compose test rule.
   *
   * An idle timeout from [waitForIdle] after the action landed is logged rather than failing the
   * dispatch, so an implementation that lets one escape never reports a tap that already changed
   * the app as failed. Any other exception — an app exception Espresso rethrows — still fails it.
   * See [DriverDispatch].
   */
  override suspend fun <R> dispatchAndAwaitSettle(action: suspend () -> R): R {
    // Catch the app's clock up before the action, while the screen is still: the same catch-up
    // in the settle after it replays the action's own ripple and transition frame by frame, which
    // cost the first tap of a trail 1.8s.
    advanceAppClock()
    // The settle after it only waits for idle, as a Compose test's does: the clock was just caught
    // up, and advancing it again replays the action's own frames.
    return dispatchThenSettle(
      action,
      settle = { markAppClockSynced(); waitForIdle() },
      isSettleTimeout = { it.isIdleTimeout() },
    )
  }
}

/**
 * Default target for applications whose test harness can expose its existing Compose rule.
 *
 * [screenshotProvider] is injected because apps differ in how they capture multi-window and
 * multi-display state. Trailblaze does not silently fall back to a root-only screenshot here.
 */
class RuleBackedAndroidTestTarget(
  private val activityProvider: () -> Activity,
  override val composeTestRule: AndroidComposeTestRule<*, *>? = null,
  private val screenshotProvider: () -> Bitmap? = { null },
) : AndroidTestTarget {

  override fun currentActivity(): Activity = activityProvider()

  override fun performViewAction(matcher: Matcher<View>, action: ViewAction, root: View?) {
    Espresso.onView(matcher).inWindowOf(root).perform(action)
  }

  override fun checkView(matcher: Matcher<View>, assertion: ViewAssertion, root: View?) {
    Espresso.onView(matcher).inWindowOf(root).check(assertion)
  }

  private fun ViewInteraction.inWindowOf(root: View?): ViewInteraction =
    if (root == null) this else inRoot(RootMatchers.withDecorView(sameInstance(root)))

  /**
   * The Compose rule's own roots, falling back to an unsynchronized read of the same roots when the
   * app never goes idle.
   *
   * The fallback is not belt-and-braces. `fetchSemanticsNodes` synchronizes before it returns
   * anything, so on a screen with an endless animation it does not return a stale tree — it throws,
   * and the caller gets no tree at all. That is a screen state, not a defect: a landing
   * screen with a looping photo carousel keeps the recomposer perpetually busy, and trails lost
   * their sign-in there on a screen that was healthy and had the Sign in button on it the whole
   * time. Swallowing the timeout in [waitForIdle] was not enough for
   * exactly this reason — that only covers the waits this driver asks for by name.
   *
   * [unsynchronizedComposeRoots] reads the same [SemanticsOwner]s the rule would have read, without
   * asking whether the app has settled first. A tree read off a still-animating screen answers
   * "what is on screen" correctly, which is the only question any caller here asks of it; every
   * caller polls, so a read taken mid-frame costs one more poll at worst.
   *
   * The same fallback serves [isNoComposeRoots], where `fetchSemanticsNodes` refuses to answer for
   * the opposite reason — a screen with no Compose content at all rather than one that never
   * settles. The walk returns whatever roots the view tree actually holds, empty included, and the
   * caller's poll (or the View-hierarchy half of its search) proceeds instead of crashing.
   */
  override fun composeRoots(): List<SemanticsNode> {
    val rule = composeTestRule ?: return emptyList()
    return runCatching {
      rule
        .onAllNodes(
          androidx.compose.ui.test.SemanticsMatcher("is root") { it.isRoot },
          useUnmergedTree = true,
        )
        .fetchSemanticsNodes()
    }.getOrElse {
      if (it.isIdleTimeout() || it.isNoComposeRoots()) unsynchronizedComposeRoots() else throw it
    }
  }

  /**
   * The same view-tree read [composeRootsIn] takes, over the windows a capture reads
   * ([AndroidHybridHierarchyCollector.windowRoots]): the focused one and any shown above it.
   *
   * The Activity's window alone is not enough. A screen that never goes idle can sit in a dialog
   * of its own: an editor can be a modal dialog over a screen that keeps animating, and
   * with only the Activity's window read here, its focused name field was in every capture yet
   * no Compose action could find it, so `clearText` failed with the field focused.
   */
  private fun unsynchronizedComposeRoots(): List<SemanticsNode> {
    // Resolved off the main thread on purpose, as in `AndroidScrollActions.scrollableViews`: a
    // host's activityProvider is free to reach for the resumed Activity through `runOnMainSync`,
    // which throws if it is already on the main thread, and may also POLL — so hoisting this is
    // not only about the throw. A sign-in on a landing screen whose looping carousel is never
    // idle can lose its trail here, which is the one condition that reaches this fallback at all.
    val activity = currentActivity()
    // Through [onMainThread] rather than `runOnMainSync` directly, because this is reachable FROM
    // the UI thread — a tool dispatched inside an Espresso action reads the tree from there — and
    // `runOnMainSync` refuses that outright too. The UNBOUNDED hop on purpose: this fallback
    // serves the action path, which legitimately waits out whatever the main thread is doing.
    return onMainThread {
      AndroidHybridHierarchyCollector.windowRoots(activity)
        .flatMap { AndroidComposeHierarchyCollector.rootsUnder(it) }
    }
  }

  override fun captureScreenshot(): Bitmap? = screenshotProvider()

  /** When the Compose test clock last caught up with real time: a settle, or [advanceAppClock]. */
  @Volatile private var composeClockSyncedAtMs: Long? = null

  /**
   * Moves the Compose test rule's clock forward by the real time since it last caught up.
   *
   * The rule puts every composition's coroutines on a virtual clock that moves only when a test
   * advances it. A real app's `delay()` — a loading screen, a debounce, a snackbar timeout — then
   * never finishes while the driver polls, and a recorded "wait for Content Loaded" waits forever.
   *
   * Capped per call, because advancing replays every frame in between, and on a screen that is
   * still animating each replayed frame is a real measure-and-draw: catching up the seconds a host
   * round trip took cost a tap 8.6s. A poll calls this every interval, so the cap only slows a
   * timer that is being waited on by the gap before the first poll.
   */
  override fun advanceAppClock() {
    val rule = composeTestRule ?: return
    val now = SystemClock.uptimeMillis()
    val last = composeClockSyncedAtMs
    composeClockSyncedAtMs = now
    val elapsed = if (last == null) 0L else (now - last).coerceAtMost(MAX_CLOCK_ADVANCE_MS)
    if (elapsed > 0) {
      // Only a never-idle screen or one with no Compose content is tolerated here, as in
      // awaitIdle; anything else is the app failing during the work this catch-up ran.
      perf("clock.advance $elapsed") { runCatching { rule.mainClock.advanceTimeBy(elapsed, ignoreFrameDuration = true) } }
        .onFailure { if (!it.isIdleTimeout() && !it.isNoComposeRoots()) throw it }
    }
  }

  override fun markAppClockSynced() {
    composeClockSyncedAtMs = SystemClock.uptimeMillis()
  }

  override fun waitForIdle(ceilingMs: Long?) {
    advanceAppClock()
    try {
      awaitIdleWithin(ceilingMs)
    } finally {
      // The settle just drove every pending frame, so the time it took is not owed again.
      composeClockSyncedAtMs = SystemClock.uptimeMillis()
    }
  }

  private fun awaitIdleWithin(ceilingMs: Long?) {
    if (ceilingMs == null) {
      awaitIdle()
      return
    }
    // A caller-supplied ceiling. Espresso has no per-call timeout, so the global idling policies
    // are lowered for the duration of this one wait and restored after. Tool dispatch on this
    // driver is serialized (one trail, one thread), so nothing else can observe the temporary
    // policy. Floored at 1s because IdlingPolicies rejects non-positive timeouts.
    val master = IdlingPolicies.getMasterIdlingPolicy()
    val resource = IdlingPolicies.getDynamicIdlingResourceErrorPolicy()
    val bounded = ceilingMs.coerceAtLeast(1_000L)
    IdlingPolicies.setMasterPolicyTimeout(bounded, TimeUnit.MILLISECONDS)
    IdlingPolicies.setIdlingResourceTimeout(bounded, TimeUnit.MILLISECONDS)
    try {
      awaitIdle()
    } finally {
      IdlingPolicies.setMasterPolicyTimeout(master.idleTimeout, master.idleTimeoutUnit)
      IdlingPolicies.setIdlingResourceTimeout(resource.idleTimeout, resource.idleTimeoutUnit)
    }
  }

  private fun awaitIdle() {
    // `onIdle`, not a root check, because a settle has no target window and must not depend on one
    // holding focus. Every `onView` — `isRoot()` included — first runs `RootViewPicker`, which
    // picks ONE window against `RootMatchers.DEFAULT` and then waits up to a hardcoded 10s for
    // `Root.isReady()`, never re-picking. `DEFAULT` requires `isFocusable()`, which rules out the
    // `FLAG_NOT_FOCUSABLE` branch of `isReady()`, so under it readiness means exactly "this window
    // holds window focus". A settle runs immediately after the action that opened a modal — during
    // a focus handover — so picking the outgoing window in the gap before the incoming one is a
    // candidate leaves a wait that can only expire. A real trail died there on the tap that opens
    // an item-detail sheet: picked root ty=BASE_APPLICATION, has-window-focus=false,
    // is-layout-requested=false, 10s, while the sheet's own window held focus and the tap itself
    // had landed. The same shape reaches any window this process cannot see — an expanded
    // notification shade leaves the app's only root permanently unfocused.
    //
    // `onIdle` performs the same synchronization: it runs `UiController.loopMainThreadUntilIdle()`
    // on the main thread, which is the wait every onView action and check is built on. It just does
    // not pick a root first.
    //
    // Never idling is a legitimate state of a real app, not a test failure. A screen with a looping
    // animation — an endless photo carousel on a landing screen, say — keeps Compose's
    // recomposer perpetually busy, so this synchronization can only ever expire there. Letting that
    // expiry propagate turns "the app is animating" into a failed trail on whichever step happened
    // to be running, such as a sign-in waiting on a screen that was healthy and had the Sign in
    // button on it the whole time.
    //
    // So expiry means "stop waiting", not "give up". Every caller polls — the resolve loop re-reads
    // the tree until its own deadline, and the sign-in tool waits on screens with budgets of its
    // own — and a tree read off a still-animating screen is answerable in exactly the way a
    // never-arriving idle signal is not. The wait is an optimization; the polling is the contract.
    val espressoTimedOut =
      perf("idle.espresso") { runCatching { Espresso.onIdle() } }
        .fold(onSuccess = { false }) { if (it.isIdleTimeout()) true else throw it }
    // The Compose wait is SKIPPED once Espresso has already reported never-idle. Compose registers
    // its recomposer as an Espresso idling resource, so the wait above covers it too and
    // `ComposeTestRule.waitForIdle()` delegates back to Espresso on Android — running it after a
    // timeout re-pays the whole framework timeout to be told the same thing. Every caller polls, so
    // on a never-idle screen that doubling lands on each poll, and a sign-in there spends most of
    // its budget waiting twice.
    if (espressoTimedOut) return
    perf("idle.compose") { runCatching { composeTestRule?.waitForIdle() } }
      .onFailure { if (!it.isIdleTimeout()) throw it }
  }

  /**
   * Whether [this] is Compose test reporting that the screen has no Compose content at all, as
   * opposed to a real failure of the thing being read.
   *
   * `fetchSemanticsNodes` throws this the moment the root registry is empty, on the theory that a
   * Compose test without Compose is a wiring mistake. In a real mixed app it is a screen state: a
   * Views-only screen has no roots, and so does the blank moment mid-transition after one screen's
   * Compose content detaches and before the next screen's attaches. A sign-in can do exactly
   * that — the signed-out screen tears down and the next renders only after a server-bound
   * sync — and letting the throw propagate turned every poll that should have waited
   * out that window into an instant failure claiming the app had no UI.
   *
   * Message-matched for the same reason as [isIdleTimeout]: the type is a bare
   * `IllegalStateException`, so the message is the only signature it has.
   */
  private fun Throwable.isNoComposeRoots(): Boolean {
    var cause: Throwable? = this
    while (cause != null) {
      if (cause.message.orEmpty().contains("No compose hierarchies found in the app")) return true
      cause = cause.cause?.takeIf { it !== cause }
    }
    return false
  }
}

/**
 * Whether [this] is Espresso or Compose reporting that the app never went idle, as opposed to a
 * real failure of the thing being waited on.
 *
 * Matched by type where the type is public API, and by message otherwise: Compose's own timeout
 * is thrown as a bare `ComposeTimeoutException`/`IllegalStateException` naming the busy idling
 * resources, and Espresso wraps its own inside a `RuntimeException` on some paths. A narrow
 * message test is worth more than an exact type list that silently stops matching after a
 * dependency bump — the failure mode of missing one is the crash this exists to prevent.
 */
internal fun Throwable.isIdleTimeout(): Boolean {
  var cause: Throwable? = this
  while (cause != null) {
    if (cause is IdlingResourceTimeoutException || cause is AppNotIdleException) return true
    val message = cause.message.orEmpty()
    if (
      message.contains("Idling resource timed out") ||
      message.contains("idling resource(s) that are not idle") ||
      message.contains("ComposeIdlingResource is busy")
    ) {
      return true
    }
    cause = cause.cause?.takeIf { it !== cause }
  }
  return false
}

/** The most app time one [AndroidTestTarget.advanceAppClock] call replays. */
private const val MAX_CLOCK_ADVANCE_MS = 1_000L
