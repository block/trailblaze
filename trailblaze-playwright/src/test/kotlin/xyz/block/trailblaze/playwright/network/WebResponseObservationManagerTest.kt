package xyz.block.trailblaze.playwright.network

import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Test

class WebResponseObservationManagerTest {
  private var nowNanos = 0L
  private val manager = WebResponseObservationManager(nanoTime = { nowNanos })
  private val criteria = WebResponseObservationManager.Criteria(
    origin = "https://example.com",
    path = "/v1/engagement",
    method = "POST",
    statusMin = 200,
    statusMax = 299,
  )

  private fun observe(method: String, url: String, status: Int) {
    manager.observe(manager.requestStarted(), method, url, status)
  }

  @Test
  fun `pre-arm and mismatched responses cannot satisfy an observation`() {
    observe("POST", "https://example.com/v1/engagement?secret=before", 204)
    assertIs<WebResponseObservationManager.BeginResult.Success>(manager.begin("proof", criteria, 1_000))

    observe("POST", "https://other.example/v1/engagement?secret=origin", 204)
    observe("POST", "https://example.com/v1/other?secret=path", 204)
    observe("GET", "https://example.com/v1/engagement?secret=method", 204)
    observe("POST", "https://example.com/v1/engagement?secret=status", 500)
    assertIs<WebResponseObservationManager.PollResult.Waiting>(manager.poll("proof"))

    observe("POST", "https://example.com/v1/engagement?secret=accepted", 204)
    val matched = assertIs<WebResponseObservationManager.PollResult.Matched>(manager.poll("proof"))
    assertEquals("https://example.com", matched.match.origin)
    assertEquals("/v1/engagement", matched.match.path)
    assertEquals(6, matched.match.sequence)
    assertIs<WebResponseObservationManager.PollResult.Missing>(manager.poll("proof"))
  }

  @Test
  fun `request started before arm cannot match when its response arrives after arm`() {
    val preArmRequestSequence = manager.requestStarted()
    assertIs<WebResponseObservationManager.BeginResult.Success>(manager.begin("proof", criteria, 1_000))

    manager.observe(
      preArmRequestSequence,
      "POST",
      "https://example.com/v1/engagement",
      204,
    )
    assertIs<WebResponseObservationManager.PollResult.Waiting>(manager.poll("proof"))

    observe("POST", "https://example.com/v1/engagement", 204)
    assertIs<WebResponseObservationManager.PollResult.Matched>(manager.poll("proof"))
  }

  @Test
  fun `response without a request start identity fails closed`() {
    assertIs<WebResponseObservationManager.BeginResult.Success>(manager.begin("proof", criteria, 1_000))
    manager.observe(null, "POST", "https://example.com/v1/engagement", 204)
    assertIs<WebResponseObservationManager.PollResult.Waiting>(manager.poll("proof"))
  }

  @Test
  fun `timeout consumes observation and reports only bounded near-miss counters`() {
    assertIs<WebResponseObservationManager.BeginResult.Success>(manager.begin("proof", criteria, 10))
    observe("POST", "https://example.com/v1/engagement?token=never-retained", 503)
    nowNanos = 10_000_000L

    val timeout = assertIs<WebResponseObservationManager.PollResult.TimedOut>(manager.poll("proof"))
    assertEquals(1, timeout.nearMisses.wrongStatus)
    assertEquals(10, timeout.elapsedMs)
    assertIs<WebResponseObservationManager.PollResult.Missing>(manager.poll("proof"))
  }

  @Test
  fun `duplicate ids and ninth concurrent observation fail closed`() {
    repeat(WebResponseObservationManager.MAX_ACTIVE_OBSERVATIONS) { index ->
      assertIs<WebResponseObservationManager.BeginResult.Success>(
        manager.begin("proof-$index", criteria, 1_000)
      )
    }
    assertIs<WebResponseObservationManager.BeginResult.Error>(manager.begin("proof-0", criteria, 1_000))
    assertIs<WebResponseObservationManager.BeginResult.Error>(manager.begin("proof-9", criteria, 1_000))
    assertEquals(WebResponseObservationManager.MAX_ACTIVE_OBSERVATIONS, manager.activeCount())
  }

  @Test
  fun `expired abandoned observations release capacity and cancellation consumes state`() {
    repeat(WebResponseObservationManager.MAX_ACTIVE_OBSERVATIONS) { index ->
      assertIs<WebResponseObservationManager.BeginResult.Success>(
        manager.begin("proof-$index", criteria, 10)
      )
    }
    nowNanos = 11_000_000L
    assertIs<WebResponseObservationManager.BeginResult.Success>(manager.begin("fresh", criteria, 10))
    manager.cancel("fresh")
    assertIs<WebResponseObservationManager.PollResult.Missing>(manager.poll("fresh"))
  }

  @Test
  fun `matched response remains consumable after its deadline and releases active capacity`() {
    assertIs<WebResponseObservationManager.BeginResult.Success>(manager.begin("matched", criteria, 10))
    observe("POST", "https://example.com/v1/engagement", 204)
    assertEquals(0, manager.activeCount())
    nowNanos = 11_000_000L
    repeat(WebResponseObservationManager.MAX_ACTIVE_OBSERVATIONS) { index ->
      assertIs<WebResponseObservationManager.BeginResult.Success>(
        manager.begin("fresh-$index", criteria, 10)
      )
    }
    assertIs<WebResponseObservationManager.BeginResult.Error>(manager.begin("matched", criteria, 10))
    assertIs<WebResponseObservationManager.PollResult.Matched>(manager.poll("matched"))
    assertIs<WebResponseObservationManager.PollResult.Missing>(manager.poll("matched"))
  }

  @Test
  fun `terminal matches are bounded and oldest abandoned success is evicted`() {
    repeat(WebResponseObservationManager.MAX_TERMINAL_MATCHES + 1) { index ->
      assertIs<WebResponseObservationManager.BeginResult.Success>(
        manager.begin("matched-$index", criteria, 10)
      )
      observe("POST", "https://example.com/v1/engagement", 204)
    }

    assertIs<WebResponseObservationManager.PollResult.Missing>(manager.poll("matched-0"))
    assertIs<WebResponseObservationManager.PollResult.Matched>(manager.poll("matched-1"))
  }

  @Test
  fun `scheduled expiry is generation-safe when an id is reused`() {
    val scheduled = mutableListOf<() -> Unit>()
    val scheduledDelays = mutableListOf<Long>()
    val controlled = WebResponseObservationManager(
      nanoTime = { nowNanos },
      scheduleExpiry = { delayMs, action ->
        scheduledDelays += delayMs
        scheduled += action
      },
    )
    assertIs<WebResponseObservationManager.BeginResult.Success>(controlled.begin("proof", criteria, 10))
    controlled.cancel("proof")
    assertIs<WebResponseObservationManager.BeginResult.Success>(controlled.begin("proof", criteria, 100))

    nowNanos = 11_000_000L
    scheduled.first().invoke()
    assertEquals(1, controlled.activeCount())

    nowNanos = 101_000_000L
    scheduled.last().invoke()
    assertEquals(0, controlled.activeCount())
    val timeout = assertIs<WebResponseObservationManager.PollResult.TimedOut>(controlled.poll("proof"))
    assertEquals(101, timeout.elapsedMs)
    assertIs<WebResponseObservationManager.PollResult.Missing>(controlled.poll("proof"))
    assertEquals(listOf(10L, 100L), scheduledDelays)
  }

  @Test
  fun `scheduled expiry releases capacity while preserving one consumable timeout result`() {
    val scheduled = mutableListOf<() -> Unit>()
    val controlled = WebResponseObservationManager(
      nanoTime = { nowNanos },
      scheduleExpiry = { _, action -> scheduled += action },
    )
    assertIs<WebResponseObservationManager.BeginResult.Success>(controlled.begin("proof", criteria, 10))
    controlled.observe(
      controlled.requestStarted(),
      "POST",
      "https://example.com/v1/engagement",
      503,
    )

    nowNanos = 11_000_000L
    scheduled.single().invoke()

    assertEquals(0, controlled.activeCount())
    val timeout = assertIs<WebResponseObservationManager.PollResult.TimedOut>(controlled.poll("proof"))
    assertEquals(1, timeout.nearMisses.wrongStatus)
    assertEquals(11, timeout.elapsedMs)
    assertIs<WebResponseObservationManager.PollResult.Missing>(controlled.poll("proof"))
  }
}
