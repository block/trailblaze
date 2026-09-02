package xyz.block.trailblaze.android.test

import android.app.Activity
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
import java.util.concurrent.TimeUnit
import org.hamcrest.Matcher
import xyz.block.trailblaze.android.test.hierarchy.AndroidComposeHierarchyCollector
import xyz.block.trailblaze.api.DriverDispatch

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

  /** Executes an Espresso action against a classic Android View. */
  fun performViewAction(matcher: Matcher<View>, action: ViewAction)

  /** Executes an Espresso assertion against a classic Android View. */
  fun checkView(matcher: Matcher<View>, assertion: ViewAssertion)

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

  /** Views are synchronized by Espresso and Compose by the existing Compose test rule. */
  override suspend fun <R> dispatchAndAwaitSettle(action: suspend () -> R): R =
    try {
      action()
    } finally {
      waitForIdle()
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

  override fun performViewAction(matcher: Matcher<View>, action: ViewAction) {
    Espresso.onView(matcher).perform(action)
  }

  override fun checkView(matcher: Matcher<View>, assertion: ViewAssertion) {
    Espresso.onView(matcher).check(assertion)
  }

  /**
   * The Compose rule's own roots, falling back to an unsynchronized read of the same roots when the
   * app never goes idle.
   *
   * The fallback is not belt-and-braces. `fetchSemanticsNodes` synchronizes before it returns
   * anything, so on a screen with an endless animation it does not return a stale tree — it throws,
   * and the caller gets no tree at all. That is a screen state, not a defect: the signed-out Square
   * landing runs a looping photo carousel that keeps the recomposer perpetually busy, and builds
   * 9907 and 9911 each lost trails there mid-sign-in, on a screen that was healthy and had the
   * Sign in button on it the whole time. Swallowing the timeout in [waitForIdle] was not enough for
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
   * The same view-tree read [composeRootsIn] takes, scoped to the current Activity's window.
   *
   * A Compose `Dialog` or `Popup` — which lives in a window of its own — is therefore not in the
   * result. Tracking those means installing `ViewRootForTest.onViewCreatedCallback`, which the
   * Compose rule owns and which taking over would break. Not worth it for a path that only runs
   * when an app is never idle: what is never idle here is a full-screen animation, and the dialogs
   * this driver cares about sit on settled screens the rule's own read serves.
   */
  private fun unsynchronizedComposeRoots(): List<SemanticsNode> {
    // Resolved off the main thread on purpose, as in `AndroidScrollActions.scrollableViews`: a
    // host's activityProvider is free to reach for the resumed Activity through `runOnMainSync`,
    // which throws if it is already on the main thread. Square's does, and it also POLLS — so
    // hoisting this is not only about the throw. Build 9923 lost case 5380821 here, 110s into a
    // sign-in spent on the signed-out landing: that screen's looping carousel is never idle, which
    // is the one condition that reaches this fallback at all.
    val decorView = currentActivity().window.decorView
    // Through [onMainThread] rather than `runOnMainSync` directly, because this is reachable FROM
    // the UI thread — a tool dispatched inside an Espresso action reads the tree from there — and
    // `runOnMainSync` refuses that outright too. The UNBOUNDED hop on purpose: this fallback
    // serves the action path, which legitimately waits out whatever the main thread is doing.
    return onMainThread { AndroidComposeHierarchyCollector.rootsUnder(decorView) }
  }

  override fun captureScreenshot(): Bitmap? = screenshotProvider()

  override fun waitForIdle(ceilingMs: Long?) {
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
    // Espresso synchronizes every onView action/check with registered app idling resources. A root
    // check is the public API for asking it to perform that synchronization without mutating UI.
    //
    // Never idling is a legitimate state of a real app, not a test failure. A screen with a looping
    // animation — the signed-out Square landing runs an endless photo carousel — keeps Compose's
    // recomposer perpetually busy, so this synchronization can only ever expire there. Letting that
    // expiry propagate turns "the app is animating" into a failed trail on whichever step happened
    // to be running: build 9907 lost its sign-in that way, after 69s of waiting on a screen that
    // was healthy and had the Sign in button on it the whole time.
    //
    // So expiry means "stop waiting", not "give up". Every caller polls — the resolve loop re-reads
    // the tree until its own deadline, and the sign-in tool waits on screens with budgets of its
    // own — and a tree read off a still-animating screen is answerable in exactly the way a
    // never-arriving idle signal is not. The wait is an optimization; the polling is the contract.
    val espressoTimedOut = runCatching {
      Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isRoot())
        .check { _: View?, _: androidx.test.espresso.NoMatchingViewException? -> }
    }.fold(onSuccess = { false }) { if (it.isIdleTimeout()) true else throw it }
    // The Compose wait is SKIPPED once Espresso has already reported never-idle. Compose registers
    // its recomposer as an Espresso idling resource, so the check above waits on it too and
    // `ComposeTestRule.waitForIdle()` delegates back to Espresso on Android — running it after a
    // timeout re-pays the whole framework timeout to be told the same thing. Every caller polls, so
    // on a never-idle screen that doubling lands on each poll: build 9923 spent 110s of case
    // 5380821's sign-in on the signed-out landing's endless carousel.
    if (espressoTimedOut) return
    runCatching { composeTestRule?.waitForIdle() }
      .onFailure { if (!it.isIdleTimeout()) throw it }
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
  private fun Throwable.isIdleTimeout(): Boolean {
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

  /**
   * Whether [this] is Compose test reporting that the screen has no Compose content at all, as
   * opposed to a real failure of the thing being read.
   *
   * `fetchSemanticsNodes` throws this the moment the root registry is empty, on the theory that a
   * Compose test without Compose is a wiring mistake. In a real mixed app it is a screen state: a
   * Views-only screen has no roots, and so does the blank moment mid-transition after one screen's
   * Compose content detaches and before the next screen's attaches. Square's sign-in does exactly
   * that — the broadcast tears down the signed-out landing and the shell renders only after a
   * server-bound sync — and letting the throw propagate turned every poll that should have waited
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
