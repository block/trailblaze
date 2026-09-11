package xyz.block.trailblaze.android.accessibility

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A reused scripted-tool launch is only reusable by a dispatch that resolves tools against the
 * SAME repo, because the launch registers its tools into one. Reuse keyed on the session alone
 * would hand a later dispatch a repo with none of the session's scripted tools in it, and every
 * one of them would fail "Unknown tool" at dispatch — a silent capability loss, not a slowdown.
 */
class ScriptedToolBundleReuseTest {

  private fun newRepo() = TrailblazeToolRepo(
    trailblazeToolSet = TrailblazeToolSet.DynamicTrailblazeToolSet(
      name = "empty",
      toolClasses = emptySet(),
      yamlToolNames = emptySet(),
    ),
  )

  private val sessionA = SessionId("session-a")
  private val sessionB = SessionId("session-b")

  @BeforeTest
  fun reset() = clearRetained()

  @AfterTest
  fun tearDown() = clearRetained()

  private fun clearRetained() {
    ScriptedToolBundleReuse.release(sessionA)
    ScriptedToolBundleReuse.release(sessionB)
  }

  @Test
  fun `nothing is claimable before a launch is retained`() {
    assertFalse(ScriptedToolBundleReuse.claim(sessionA, newRepo()))
  }

  @Test
  fun `the same session dispatching against the same repo reuses the launch`() {
    val repo = newRepo()
    ScriptedToolBundleReuse.retain(sessionA, repo, runtime = null)
    assertTrue(ScriptedToolBundleReuse.claim(sessionA, repo))
  }

  @Test
  fun `a different repo instance cannot reuse the launch`() {
    ScriptedToolBundleReuse.retain(sessionA, newRepo(), runtime = null)
    assertFalse(ScriptedToolBundleReuse.claim(sessionA, newRepo()))
  }

  @Test
  fun `a different session cannot reuse the launch`() {
    val repo = newRepo()
    ScriptedToolBundleReuse.retain(sessionA, repo, runtime = null)
    assertFalse(ScriptedToolBundleReuse.claim(sessionB, repo))
  }

  @Test
  fun `retaining a new session drops the previous session's claim`() {
    val repoA = newRepo()
    ScriptedToolBundleReuse.retain(sessionA, repoA, runtime = null)
    ScriptedToolBundleReuse.retain(sessionB, newRepo(), runtime = null)
    assertFalse(ScriptedToolBundleReuse.claim(sessionA, repoA))
  }

  @Test
  fun `a launch that is replaced is shut down, not dropped`() {
    // A caller that builds a fresh tool repo per dispatch misses every claim and retains every
    // time. Without this, each of those retains would strand a QuickJS engine.
    val first = Any()
    val second = Any()
    assertTrue(ScriptedToolBundleReuse.replacesPreviousLaunch(previous = first, incoming = second))
    assertFalse(ScriptedToolBundleReuse.replacesPreviousLaunch(previous = first, incoming = first))
    assertFalse(ScriptedToolBundleReuse.replacesPreviousLaunch(previous = null, incoming = second))
  }

  @Test
  fun `releasing makes the launch unclaimable`() {
    val repo = newRepo()
    ScriptedToolBundleReuse.retain(sessionA, repo, runtime = null)
    ScriptedToolBundleReuse.release(sessionA)
    assertFalse(ScriptedToolBundleReuse.claim(sessionA, repo))
  }

  @Test
  fun `an interrupted session's teardown leaves the session that replaced it running`() {
    // Starting a session while one is running only LAUNCHES the previous job's cancellation, and
    // that job's teardown is NonCancellable — so the interrupted session's `finally` can arrive
    // here after the replacement has retained a launch of its own. Releasing whatever is retained
    // would shut down the live runtime the new session is mid-dispatch on.
    val repoB = newRepo()
    ScriptedToolBundleReuse.retain(sessionB, repoB, runtime = null)

    ScriptedToolBundleReuse.release(sessionA)

    assertTrue(ScriptedToolBundleReuse.claim(sessionB, repoB))
  }

  /**
   * The launch is the slow part, so deciding to launch and launching cannot be separate steps: two
   * overlapping dispatches of one session share a tool repo, and a second launch into that repo
   * dies on the tool names the first one registered — `addDynamicTools` refuses to shadow a name.
   * A second launch is not a wasted launch, it is a failed dispatch.
   */
  @Test
  fun `overlapping dispatches of one session launch its bundles once`() = runBlocking {
    val repo = newRepo()
    val launches = AtomicInteger(0)
    val firstLaunchStarted = CompletableDeferred<Unit>()
    val secondDispatchReturned = CompletableDeferred<Unit>()

    val first = async(Dispatchers.Default) {
      ScriptedToolBundleReuse.claimOrLaunch(sessionA, repo) {
        launches.incrementAndGet()
        firstLaunchStarted.complete(Unit)
        // Held open until the second dispatch returns — which, with claim-or-launch serialized, it
        // cannot do until this launch finishes, so this wait is expected to time out. It completes
        // early only if the second dispatch got past the claim mid-launch, which is the defect.
        withTimeoutOrNull(1_000) { secondDispatchReturned.await() }
        null
      }
    }
    firstLaunchStarted.await()
    val second = async(Dispatchers.Default) {
      ScriptedToolBundleReuse.claimOrLaunch(sessionA, repo) {
        launches.incrementAndGet()
        null
      }.also { secondDispatchReturned.complete(Unit) }
    }

    assertFalse(first.await(), "the first dispatch should have launched, not reused")
    assertTrue(second.await(), "the second dispatch should have reused the first launch")
    assertEquals(1, launches.get(), "the session's bundles were launched more than once")
  }
}
