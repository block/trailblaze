package xyz.block.trailblaze.android.accessibility

import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolSet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * Two `run_yaml` dispatches of one session can overlap, so what this pins is that a dispatch never
 * sees a half-published cache entry: the repo a caller gets back is always a real repo for its own
 * key, never null and never the previous session's.
 */
class SessionToolRepoCacheTest {

  private fun newRepo() = TrailblazeToolRepo(
    trailblazeToolSet = TrailblazeToolSet.DynamicTrailblazeToolSet(
      name = "empty",
      toolClasses = emptySet(),
      yamlToolNames = emptySet(),
    ),
  )

  private fun key(sessionId: String?, targetId: String? = "target-a") = SessionToolRepoCache.Key(
    sessionId = sessionId,
    targetId = targetId,
    driverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
  )

  @Test
  fun `a session's dispatches share one repo`() {
    val cache = SessionToolRepoCache()
    val first = cache.repoFor(key("session-a")) { newRepo() }
    val second = cache.repoFor(key("session-a")) { newRepo() }
    assertSame(first, second)
  }

  @Test
  fun `a different key builds a new repo`() {
    val cache = SessionToolRepoCache()
    val first = cache.repoFor(key("session-a")) { newRepo() }
    val second = cache.repoFor(key("session-a", targetId = "target-b")) { newRepo() }
    assertNotSame(first, second)
  }

  @Test
  fun `a dispatch with no session id is never cached`() {
    val cache = SessionToolRepoCache()
    val first = cache.repoFor(key(sessionId = null)) { newRepo() }
    val second = cache.repoFor(key(sessionId = null)) { newRepo() }
    assertNotSame(first, second)
  }

  /**
   * The overlap this cache has to survive. The first dispatch is inside its build when the second
   * one looks the same key up. Publishing the key before the repo would let the second dispatch
   * match on a key whose repo is still null — it would either crash on the missing repo or, once a
   * previous session had populated it, resolve every tool against that session's repo instead.
   */
  @Test
  fun `a dispatch that arrives mid-build still gets its own session's repo`() {
    val cache = SessionToolRepoCache()
    // A previous session, so a premature key publish has a stale repo to hand out rather than a
    // null one — the quieter and more damaging of the two failures.
    val staleRepo = cache.repoFor(key("session-old")) { newRepo() }

    val buildStarted = CountDownLatch(1)
    val secondDispatchReturned = CountDownLatch(1)
    val pool = Executors.newFixedThreadPool(2)
    try {
      val first = pool.submit<TrailblazeToolRepo> {
        cache.repoFor(key("session-new")) {
          buildStarted.countDown()
          // Hold the build open until the second dispatch has returned — which, when the pair is
          // published atomically, it cannot do until this build finishes, so this wait is expected
          // to time out. It only completes early if the second dispatch got an answer mid-build,
          // which is the defect.
          secondDispatchReturned.await(1, TimeUnit.SECONDS)
          newRepo()
        }
      }
      check(buildStarted.await(5, TimeUnit.SECONDS)) { "the first dispatch never started building" }
      val second = pool.submit<TrailblazeToolRepo> {
        cache.repoFor(key("session-new")) { newRepo() }
          .also { secondDispatchReturned.countDown() }
      }

      val firstRepo = first.get(10, TimeUnit.SECONDS)
      val secondRepo = second.get(10, TimeUnit.SECONDS)
      assertNotSame(staleRepo, secondRepo, "the overlapping dispatch got the previous session's repo")
      assertSame(firstRepo, secondRepo, "the overlapping dispatch did not reuse the session's repo")
    } finally {
      pool.shutdownNow()
    }
  }

  @Test
  fun `a repo is built once no matter how many dispatches race for it`() {
    val cache = SessionToolRepoCache()
    val builds = java.util.concurrent.atomic.AtomicInteger(0)
    val start = CountDownLatch(1)
    val pool = Executors.newFixedThreadPool(8)
    try {
      val repos = (1..8).map {
        pool.submit<TrailblazeToolRepo> {
          start.await(5, TimeUnit.SECONDS)
          cache.repoFor(key("session-a")) {
            builds.incrementAndGet()
            newRepo()
          }
        }
      }
      start.countDown()
      val resolved = repos.map { it.get(10, TimeUnit.SECONDS) }
      assertEquals(1, builds.get(), "the repo was built more than once")
      check(resolved.all { it === resolved.first() }) { "the racing dispatches got different repos" }
    } finally {
      pool.shutdownNow()
    }
  }
}
