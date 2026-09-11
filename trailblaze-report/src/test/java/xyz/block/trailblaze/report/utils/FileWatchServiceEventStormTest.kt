package xyz.block.trailblaze.report.utils

import java.io.File
import java.nio.file.Files
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * A watcher pointed at a live session directory must not be driven by the daemon's own writes.
 *
 * The Android network-capture bridge appends to `<session-dir>/network.ndjson` continuously for the
 * whole duration of a run, so the directory the daemon watches is also a directory the daemon is
 * writing to many times a second. That combination wedged a real run: the daemon spun logging
 * `[FileWatchService] Emitting event: MODIFY network.ndjson`, the trail made no forward progress for
 * the full inactivity window, and the session ended with no terminating status.
 *
 * Two independent things had to be true for that to happen, and both are pinned here:
 * - the session's own append-only event streams are not a change any watcher should react to, and
 * - even for a file that IS watched, a sustained stream of MODIFY events must schedule an amount of
 *   work bounded by elapsed time rather than by how fast the file is being written.
 *
 * Driven through [FileWatchService.onWatchEvent] — the same call the watch loop makes — rather than
 * through a real directory, because NIO's `WatchService` is inotify-backed on Linux but a
 * 2s-granularity poller on macOS: a real-filesystem version of this test would be slow and flaky on
 * a developer Mac while proving nothing extra about the ingestion contract under test.
 */
class FileWatchServiceEventStormTest {

  private val watchDir: File = Files.createTempDirectory("file-watch-storm").toFile()
  private val collectorScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
  private var service: FileWatchService? = null

  @AfterTest
  fun tearDown() {
    service?.stopWatching()
    collectorScope.cancel()
    watchDir.deleteRecursively()
  }

  @Test
  fun `a storm of MODIFY events on the session's own ndjson stream produces no work at all`() {
    val service = newService(debounceDelayMs = DEBOUNCE_MS)
    val emissions = collectEmissions(service)

    val fed = feedFor(service, fileName = "network.ndjson", durationMs = STREAM_MS)

    assertTrue(fed > MIN_EVENTS_FOR_A_REAL_STORM, "Only fed $fed events — not a storm, so this proves nothing")
    // Let any window that a regression would have opened run to completion before counting.
    Thread.sleep(DEBOUNCE_MS * 4)
    assertEquals(
      0L,
      service.debounceWindowsOpened.get(),
      "The session's own append-only stream must be filtered before the debouncer, so a storm of " +
        "$fed MODIFY events on network.ndjson must not schedule a single debounce window",
    )
    assertEquals(
      emptyList(),
      emissions.toList(),
      "network.ndjson is written by the daemon itself and its readers poll it — no watcher should " +
        "be woken per appended line",
    )
  }

  @Test
  fun `a plugin-namespaced events stream is filtered too, while ordinary files still emit`() {
    val service = newService(debounceDelayMs = DEBOUNCE_MS)
    val emissions = collectEmissions(service)

    // Stream names are producer-chosen (a sanitized plugin id), so there is no fixed filename to
    // enumerate — the extension is the contract.
    service.onWatchEvent("com.x.plugin.network.ndjson", FileChangeEvent.ChangeType.MODIFY)
    service.onWatchEvent("analytics.ndjson", FileChangeEvent.ChangeType.MODIFY)
    // The filter must not over-reach: a session's ordinary files are still what the watcher is for.
    service.onWatchEvent("session.json", FileChangeEvent.ChangeType.MODIFY)

    Thread.sleep(DEBOUNCE_MS * 4)
    assertEquals(
      listOf("session.json"),
      emissions.map { it.file.name },
      "Only the non-stream file should have been emitted",
    )
  }

  @Test
  fun `a sustained MODIFY stream on a watched file schedules work bounded by time, not by write rate`() {
    val service = newService(debounceDelayMs = DEBOUNCE_MS)
    val emissions = collectEmissions(service)

    val fed = feedFor(service, fileName = "session.json", durationMs = STREAM_MS)

    assertTrue(fed > MIN_EVENTS_FOR_A_REAL_STORM, "Only fed $fed events — not a storm, so this proves nothing")
    Thread.sleep(DEBOUNCE_MS * 4)

    // One window per debounce interval is the ideal; the ceiling is deliberately several times that
    // so ordinary scheduling jitter can't flake the test. What it does exclude is the behavior this
    // guards against — a window (and a coroutine) per *event*, which for $fed events is orders of
    // magnitude above this bound.
    val windowsIfBoundedByTime = STREAM_MS / DEBOUNCE_MS
    val ceiling = windowsIfBoundedByTime * 4
    val windows = service.debounceWindowsOpened.get()
    assertTrue(
      windows <= ceiling,
      "Feeding $fed events over ${STREAM_MS}ms with a ${DEBOUNCE_MS}ms debounce opened $windows " +
        "windows; bounded-by-time would be about $windowsIfBoundedByTime (ceiling $ceiling). " +
        "A count near $fed means every event is scheduling its own coroutine again.",
    )
    assertTrue(
      emissions.size <= ceiling,
      "Emissions must be rate-limited the same way: got ${emissions.size} for $fed events (ceiling $ceiling)",
    )
    // Bounded must not mean silent — a file that keeps changing still has to reach its readers.
    assertTrue(emissions.isNotEmpty(), "A continuously-modified watched file must still emit")
  }

  @Test
  fun `stopping mid-window leaves nothing pending, including for events that arrive after the stop`() {
    // The pending entry for a file IS the "a window is open for this file" marker, and a cancelled
    // scope drops the coroutine body outright — so the `finally` that normally clears it never
    // runs. Left behind, that key silences the file for the rest of the process and retains its
    // `File`. The watch thread also does not stop the instant it is asked to: `interrupt()` does
    // nothing to a thread already inside `onWatchEvent`, so an event can still arrive afterwards
    // and must not put a new one there.
    val service = newService(debounceDelayMs = DEBOUNCE_MS * 20)

    service.onWatchEvent("session.json", FileChangeEvent.ChangeType.MODIFY)
    assertEquals(1, service.pendingWindowCount, "the window under test has to actually be open")

    service.stopWatching()
    assertEquals(0, service.pendingWindowCount, "stopping must not leave a window marker behind")

    service.onWatchEvent("session.json", FileChangeEvent.ChangeType.MODIFY)
    assertEquals(0, service.pendingWindowCount, "an event racing the stop must not open a window")
  }

  @Test
  fun `restarting a stopped watcher allocates nothing rather than leaking a deaf watch thread`() {
    // `stopWatching` cancels the debounce scope and closes the channel, neither of which can be
    // revived, so a restart can never emit again whatever it does. Asserted on the OS resources
    // instead: without the guard, `startWatching` still allocates a `WatchService` and a live
    // thread parked in `take()` for the rest of the process, and the instance then LOOKS alive
    // while being permanently deaf. Silence alone is not the discriminator — the pre-debouncer
    // stopped-check makes both versions silent.
    val service = newService(debounceDelayMs = DEBOUNCE_MS)
    service.startWatching()
    service.stopWatching()
    val threadsAfterStop = watcherThreadCount()

    service.startWatching()
    Thread.sleep(DEBOUNCE_MS)

    assertEquals(
      threadsAfterStop,
      watcherThreadCount(),
      "restarting a stopped watcher must not leave another watch thread parked in take()",
    )
  }

  /** Live threads this watcher would have named, by the convention in `startWatching`. */
  private fun watcherThreadCount(): Int =
    Thread.getAllStackTraces().keys.count { it.isAlive && it.name == "FileWatcher-${watchDir.name}" }

  private fun newService(debounceDelayMs: Long): FileWatchService =
    FileWatchService(watchDir, debounceDelayMs = debounceDelayMs).also { service = it }

  private fun collectEmissions(service: FileWatchService): MutableList<FileChangeEvent> {
    val emissions = Collections.synchronizedList(mutableListOf<FileChangeEvent>())
    collectorScope.launch { service.fileChanges.collect { emissions.add(it) } }
    return emissions
  }

  /** Feeds MODIFY events for [fileName] as fast as this thread can for [durationMs]; returns the count. */
  private fun feedFor(service: FileWatchService, fileName: String, durationMs: Long): Long {
    val deadline = System.currentTimeMillis() + durationMs
    var fed = 0L
    while (System.currentTimeMillis() < deadline) {
      service.onWatchEvent(fileName, FileChangeEvent.ChangeType.MODIFY)
      fed++
    }
    return fed
  }

  companion object {
    private const val DEBOUNCE_MS = 100L
    private const val STREAM_MS = 1_000L

    /**
     * Floor on how many events the feed loop must actually deliver for the assertions above to mean
     * anything. Any machine clears this by orders of magnitude; it exists so a pathologically slow
     * or descheduled runner reports "the storm never happened" instead of passing vacuously.
     */
    private const val MIN_EVENTS_FOR_A_REAL_STORM = 1_000L
  }
}
