package xyz.block.trailblaze.playwright.network

import com.microsoft.playwright.Page
import com.microsoft.playwright.Request
import com.microsoft.playwright.Response
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.net.URI
import java.util.LinkedHashMap
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

/** Metadata-only, post-arm response observations for one Playwright page and Trailblaze session. */
internal class WebResponseObservationManager(
  private val nanoTime: () -> Long = System::nanoTime,
  private val scheduleExpiry: (Long, () -> Unit) -> Unit = { delayMs, action ->
    EXPIRY_EXECUTOR.schedule(action, delayMs, TimeUnit.MILLISECONDS)
  },
) {
  data class Criteria(
    val origin: String,
    val path: String,
    val method: String,
    val statusMin: Int,
    val statusMax: Int,
  )

  data class Match(
    val sequence: Long,
    val elapsedMs: Long,
    val method: String,
    val origin: String,
    val path: String,
    val status: Int,
  )

  sealed interface BeginResult {
    data object Success : BeginResult
    data class Error(val message: String) : BeginResult
  }

  sealed interface PollResult {
    data object Waiting : PollResult
    data class Matched(val match: Match) : PollResult
    data class TimedOut(val elapsedMs: Long, val nearMisses: NearMisses) : PollResult
    data object Missing : PollResult
  }

  data class NearMisses(
    var wrongOrigin: Int = 0,
    var wrongPath: Int = 0,
    var wrongMethod: Int = 0,
    var wrongStatus: Int = 0,
  )

  private data class Observation(
    val criteria: Criteria,
    val armedAtNanos: Long,
    val deadlineNanos: Long,
    val watermark: Long,
    val generation: Long,
    val nearMisses: NearMisses = NearMisses(),
    var match: Match? = null,
  )

  private val observations = linkedMapOf<String, Observation>()
  private val terminalMatches = linkedMapOf<String, Match>()
  private val terminalTimeouts = linkedMapOf<String, PollResult.TimedOut>()
  private var sequence = 0L
  private var generation = 0L

  @Synchronized
  fun begin(id: String, criteria: Criteria, timeoutMs: Long): BeginResult {
    expireObservations(nanoTime())
    if (observations.containsKey(id) || terminalMatches.containsKey(id)) {
      return BeginResult.Error("An active or unconsumed response observation already uses that ID.")
    }
    if (observations.size >= MAX_ACTIVE_OBSERVATIONS) {
      return BeginResult.Error("A page can have at most $MAX_ACTIVE_OBSERVATIONS active response observations.")
    }
    terminalTimeouts.remove(id)
    val armedAt = nanoTime()
    val observationGeneration = ++generation
    observations[id] = Observation(
      criteria = criteria,
      armedAtNanos = armedAt,
      deadlineNanos = armedAt + timeoutMs * NANOS_PER_MILLISECOND,
      watermark = sequence,
      generation = observationGeneration,
    )
    scheduleExpiry(timeoutMs) { expire(id, observationGeneration) }
    return BeginResult.Success
  }

  @Synchronized
  fun requestStarted(): Long = ++sequence

  @Synchronized
  fun observe(requestSequence: Long?, method: String, url: String, status: Int) {
    if (requestSequence == null) return
    val uri = runCatching { URI(url) }.getOrNull() ?: return
    val eventOrigin = canonicalOrigin(uri) ?: return
    val eventPath = uri.rawPath?.ifEmpty { "/" } ?: return
    val now = nanoTime()
    expireObservations(now)

    val matches = mutableListOf<Pair<String, Match>>()
    observations.forEach { (id, observation) ->
      if (
        observation.match != null ||
        requestSequence <= observation.watermark ||
        now > observation.deadlineNanos
      ) {
        return@forEach
      }
      val criteria = observation.criteria
      when {
        eventOrigin != criteria.origin -> observation.nearMisses.wrongOrigin++
        eventPath != criteria.path -> observation.nearMisses.wrongPath++
        method != criteria.method -> observation.nearMisses.wrongMethod++
        status !in criteria.statusMin..criteria.statusMax -> observation.nearMisses.wrongStatus++
        else -> {
          val match = Match(
            sequence = requestSequence,
            elapsedMs = elapsedMs(observation.armedAtNanos, now),
            method = criteria.method,
            origin = criteria.origin,
            path = criteria.path,
            status = status,
          )
          observation.match = match
          matches += id to match
        }
      }
    }
    matches.forEach { (id, match) ->
      observations.remove(id)
      retainTerminalMatch(id, match)
    }
  }

  @Synchronized
  fun poll(id: String): PollResult {
    terminalMatches.remove(id)?.let { return PollResult.Matched(it) }
    val observation = observations[id]
      ?: return terminalTimeouts.remove(id) ?: PollResult.Missing
    val now = nanoTime()
    if (now >= observation.deadlineNanos) {
      return terminalTimeout(id, observation, now, retain = false)
    }
    observation.match?.let {
      observations.remove(id)
      return PollResult.Matched(it)
    }
    return PollResult.Waiting
  }

  @Synchronized
  fun cancel(id: String) {
    observations.remove(id)
    terminalMatches.remove(id)
    terminalTimeouts.remove(id)
  }

  @Synchronized
  fun clear() {
    observations.clear()
    terminalMatches.clear()
    terminalTimeouts.clear()
  }

  @Synchronized internal fun activeCount(): Int = observations.size

  @Synchronized
  private fun expire(id: String, expectedGeneration: Long) {
    val observation = observations[id] ?: return
    if (observation.generation == expectedGeneration && nanoTime() >= observation.deadlineNanos) {
      observation.match?.let {
        observations.remove(id)
        retainTerminalMatch(id, it)
      } ?: terminalTimeout(id, observation, nanoTime(), retain = true)
    }
  }

  private fun elapsedMs(startNanos: Long, endNanos: Long): Long =
    ((endNanos - startNanos).coerceAtLeast(0L)) / NANOS_PER_MILLISECOND

  private fun expireObservations(now: Long) {
    val expired = observations.filterValues { now >= it.deadlineNanos }
    expired.forEach { (id, observation) ->
      observation.match?.let {
        observations.remove(id)
        retainTerminalMatch(id, it)
      } ?: terminalTimeout(id, observation, now, retain = true)
    }
  }

  private fun retainTerminalMatch(id: String, match: Match) {
    terminalMatches[id] = match
    while (terminalMatches.size > MAX_TERMINAL_MATCHES) {
      terminalMatches.remove(terminalMatches.keys.first())
    }
  }

  private fun terminalTimeout(
    id: String,
    observation: Observation,
    now: Long,
    retain: Boolean,
  ): PollResult.TimedOut {
    observations.remove(id)
    val result = PollResult.TimedOut(
      elapsedMs(observation.armedAtNanos, now),
      observation.nearMisses,
    )
    if (retain) {
      terminalTimeouts[id] = result
      while (terminalTimeouts.size > MAX_TERMINAL_TIMEOUTS) {
        terminalTimeouts.remove(terminalTimeouts.keys.first())
      }
    }
    return result
  }

  companion object {
    const val MAX_ACTIVE_OBSERVATIONS = 8
    const val MAX_TERMINAL_MATCHES = 8
    private const val MAX_TERMINAL_TIMEOUTS = MAX_ACTIVE_OBSERVATIONS
    private const val NANOS_PER_MILLISECOND = 1_000_000L
    private val EXPIRY_EXECUTOR = Executors.newSingleThreadScheduledExecutor { runnable ->
      Thread(runnable, "trailblaze-web-response-observation-expiry").apply { isDaemon = true }
    }

    fun canonicalOrigin(uri: URI): String? {
      val scheme = uri.scheme?.lowercase() ?: return null
      if (scheme != "http" && scheme != "https") return null
      val host = uri.host?.lowercase() ?: return null
      val defaultPort = if (scheme == "https") 443 else 80
      val port = uri.port.takeIf { it != -1 && it != defaultPort }
      val authorityHost = if (host.contains(':')) "[$host]" else host
      return buildString {
        append(scheme).append("://").append(authorityHost)
        port?.let { append(':').append(it) }
      }
    }
  }
}

/** Bounded weak-identity correlation that never prolongs the lifetime of raw Playwright requests. */
private class WeakRequestSequenceMap(
  private val maxEntries: Int,
) {
  private class IdentityKey(
    request: Request,
    queue: ReferenceQueue<Request>? = null,
  ) : WeakReference<Request>(request, queue) {
    private val identityHash = System.identityHashCode(request)

    override fun hashCode(): Int = identityHash

    override fun equals(other: Any?): Boolean =
      this === other || (other is IdentityKey && get() != null && get() === other.get())
  }

  private val queue = ReferenceQueue<Request>()
  private val sequences = LinkedHashMap<IdentityKey, Long>()

  @Synchronized
  fun put(request: Request, sequence: Long) {
    discardCollectedKeys()
    sequences[IdentityKey(request, queue)] = sequence
    while (sequences.size > maxEntries) {
      sequences.remove(sequences.keys.first())
    }
  }

  @Synchronized
  fun remove(request: Request): Long? {
    discardCollectedKeys()
    return sequences.remove(IdentityKey(request))
  }

  @Synchronized
  fun clear() {
    sequences.clear()
    discardCollectedKeys()
  }

  private fun discardCollectedKeys() {
    while (true) {
      val collected = queue.poll() as? IdentityKey ?: return
      sequences.remove(collected)
    }
  }
}

/** Owns one listener per live Page and clears cross-call state on Trailblaze session rollover. */
internal object WebResponseObservationRegistry {
  private data class Entry(
    var sessionId: String,
    val manager: WebResponseObservationManager,
  )

  private val entries = WeakHashMap<Page, Entry>()

  @Synchronized
  fun manager(page: Page, sessionId: String): WebResponseObservationManager {
    val existing = entries[page]
    if (existing != null) {
      if (existing.sessionId != sessionId) {
        existing.manager.clear()
        existing.sessionId = sessionId
      }
      return existing.manager
    }

    val manager = WebResponseObservationManager()
    val pageRef = WeakReference(page)
    val requestSequences = WeakRequestSequenceMap(MAX_TRACKED_REQUESTS)
    page.onRequest(Consumer<Request> { request ->
      requestSequences.put(request, manager.requestStarted())
    })
    page.onResponse(Consumer<Response> { response ->
      val boundPage = pageRef.get() ?: return@Consumer
      val request = response.request()
      val requestSequence = requestSequences.remove(request)
      val belongsToMainFrame = runCatching {
        request.frame() == boundPage.mainFrame()
      }.getOrDefault(false)
      if (!belongsToMainFrame) return@Consumer
      manager.observe(
        requestSequence = requestSequence,
        method = request.method(),
        url = response.url(),
        status = response.status(),
      )
    })
    page.onRequestFinished(Consumer<Request> { request ->
      requestSequences.remove(request)
    })
    page.onRequestFailed(Consumer<Request> { request ->
      requestSequences.remove(request)
    })
    page.onClose(Consumer<Page> {
      manager.clear()
      requestSequences.clear()
      synchronized(this) { entries.remove(it) }
    })
    entries[page] = Entry(sessionId, manager)
    return manager
  }

  private const val MAX_TRACKED_REQUESTS = 512
}
