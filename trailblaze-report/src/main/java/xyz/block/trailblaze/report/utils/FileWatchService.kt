package xyz.block.trailblaze.report.utils

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.io.File
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.WatchService
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.io.path.name
import xyz.block.trailblaze.capture.model.CaptureFilenames
import xyz.block.trailblaze.events.SessionEvents
import xyz.block.trailblaze.util.Console

class FileWatchService(
  private val dirToWatch: File,
  private val debounceDelayMs: Long = 500L, // Debounce changes within 500ms
) {
  private val eventTypes: List<FileChangeEvent.ChangeType> = listOf(
    FileChangeEvent.ChangeType.CREATE,
    FileChangeEvent.ChangeType.DELETE,
    FileChangeEvent.ChangeType.MODIFY,
  )

  // Path to the directory to watch
  private val path: Path = Paths.get(dirToWatch.canonicalPath)

  // WatchService is created lazily in startWatching() to avoid allocating an OS-level
  // poll thread at construction time. Previously this was an eager `val` which meant
  // every FileWatchService instance — even short-lived ones that were never started —
  // leaked a NIO poll thread until stopWatching() was called.
  private var watchService: WatchService? = null

  // Thread for running the watch loop
  private var watchThread: Thread? = null

  // Coroutine scope for debouncing - use Default for CPU-bound work, not IO for file system events
  private val debounceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

  // Track pending events for debouncing
  private val pendingEvents = mutableMapOf<String, Pair<FileChangeEvent.ChangeType, File>>()

  /**
   * Set by [stopWatching], which is one-way: it cancels [debounceScope] and closes
   * [fileChangeChannel], neither of which can be revived.
   *
   * Read by [startWatching] and by [debounceFileEvent], because the watch thread does not stop the
   * instant it is asked to — `interrupt()` does nothing to a thread already inside [onWatchEvent],
   * so an event can arrive after the stop. Without this flag that event puts a key in
   * [pendingEvents] whose coroutine never runs (a cancelled scope drops the body, so even the
   * `finally` doesn't fire), leaving a permanent "a window is open for this file" marker that
   * silences the file and retains its [File].
   */
  @Volatile
  private var stopped = false

  /**
   * How many debounce windows have been opened since construction — i.e. how many coroutines this
   * watcher has scheduled in response to file events.
   *
   * This, rather than the emission count, is the quantity that ran away when a session's own
   * continuously-appended file was watched, so it is what the regression test asserts on: a burst
   * and a steady stream produce a similar number of *emissions* either way, but the old code
   * scheduled one coroutine per event where this schedules one per [debounceDelayMs].
   */
  internal val debounceWindowsOpened = AtomicLong()

  /**
   * How many files currently have a debounce window open. Zero is the resting state, and it is also
   * what [stopWatching] has to leave behind: a key that outlives the stop is a permanent marker
   * that silences that file and retains its [File].
   */
  internal val pendingWindowCount: Int
    get() = synchronized(pendingEvents) { pendingEvents.size }

  // Channel for file change events
  private val fileChangeChannel = Channel<FileChangeEvent>(Channel.UNLIMITED)

  // Flow of file change events - process on IO dispatcher to avoid blocking
  val fileChanges: Flow<FileChangeEvent> = fileChangeChannel
    .receiveAsFlow()
    .flowOn(Dispatchers.IO)
    .buffer(Channel.UNLIMITED)

  private fun shouldIgnoreFile(fileName: String): Boolean {
    val fileNameLower = fileName.lowercase()

    // Ignore files that start with a dot (hidden files on Unix)
    if (fileNameLower.startsWith(".")) {
      return true
    }

    // Ignore directories that start with special characters
    // Note: We can't check if it's a directory without I/O, but the filename pattern is good enough
    if (fileNameLower.isNotEmpty() && !fileNameLower[0].isLetterOrDigit() && fileNameLower[0] != '_') {
      return true
    }

    // Ignore continuously-written capture files — these generate MODIFY events on every
    // flush during a live session and would overwhelm watchers monitoring session directories.
    // The device logs panel handles its own refresh via polling. Legacy `logcat.txt` is
    // included so older session folders don't generate noise either.
    //
    // `.ndjson` covers the append-forever event streams — in a session directory that is
    // `network.ndjson` from the network-capture bridges. Matching the extension rather than that
    // one filename is deliberate: a stream's name is producer-chosen (a sanitized plugin id, e.g.
    // `com.x.plugin.network.ndjson`), so there is no fixed set to enumerate. These are the
    // daemon's own appends and their readers poll, so no watcher should be woken per line.
    //
    // Note this only ever sees files directly in the watched directory — the registration below is
    // a single non-recursive `path.register`, so streams under `events/` produce no events here at
    // all and are not what this rule is for.
    if (fileNameLower == CaptureFilenames.DEVICE_LOG ||
      fileNameLower == CaptureFilenames.LEGACY_LOGCAT_TXT ||
      fileNameLower.endsWith(CaptureFilenames.VIDEO_EXTENSION) ||
      fileNameLower.endsWith(".${SessionEvents.EXTENSION}")
    ) {
      return true
    }

    return false
  }

  /**
   * Ingest one raw watch event: filter it, then hand it to the debouncer.
   *
   * Extracted from the watch loop so the ingestion contract — what gets filtered, and how much
   * work a burst on one file is allowed to schedule — is unit-testable without an OS
   * file-notification backend. NIO's `WatchService` is inotify-backed on Linux but a
   * 2s-granularity poller on macOS, so driving this through a real directory would be both slow
   * and flaky on a developer Mac. This is the same call the watch loop makes.
   */
  internal fun onWatchEvent(fileName: String, changeType: FileChangeEvent.ChangeType) {
    if (shouldIgnoreFile(fileName)) return
    // Construct the full file path only after filtering
    debounceFileEvent(File(path.toFile(), fileName), changeType)
  }

  fun stopWatching() {
    stopped = true
    // Close the WatchService to stop watching - this will cause the thread to exit
    watchService?.close()
    watchService = null
    watchThread?.interrupt()
    watchThread = null
    debounceScope.cancel()
    // Cancelling the scope kills the coroutines that would have drained these, so drop the
    // undelivered events rather than holding their File references until this instance is
    // collected.
    synchronized(pendingEvents) { pendingEvents.clear() }
    fileChangeChannel.close()
    Console.log("[FileWatchService] Stopped watching: $path")
  }

  fun startWatching() {
    // No-op if already started — prevents leaking a previous WatchService
    // if startWatching() is called multiple times on the same instance.
    if (watchService != null) return

    // A stopped instance cannot be restarted: the debounce scope is cancelled and the channel
    // closed, so a fresh watch thread would deliver events into a coroutine that never runs and a
    // channel nobody can receive from — a watcher that looks alive and emits nothing. Say so
    // instead, and let the caller construct a new instance.
    if (stopped) {
      Console.log("[FileWatchService] Not restarting a stopped watcher for $path — construct a new one")
      return
    }

    // A stopped instance cannot be restarted: the debounce scope is cancelled and the channel
    // closed, so a fresh watch thread would deliver events into a coroutine that never runs and a
    // channel nobody can receive from — a watcher that looks alive and emits nothing. Say so
    // instead, and let the caller construct a new instance.
    // Create the WatchService on demand — this allocates an OS poll thread,
    // so we defer it until watching is actually requested.
    val ws = FileSystems.getDefault().newWatchService()
    watchService = ws

    // Register the directory with the WatchService for create, delete, and modify events.
    // If registration fails (e.g., directory deleted, permission issue), close the
    // WatchService immediately to avoid leaking its OS poll thread.
    try {
      path.register(
        ws,
        eventTypes.map { it.watchEventKind }.toTypedArray(),
      )
    } catch (e: Exception) {
      Console.log("[FileWatchService] Failed to register watcher for $path: ${e.message}")
      ws.close()
      watchService = null
      return
    }

    Console.log("[FileWatchService] Started watching: $path (debounce: ${debounceDelayMs}ms)")

    // Start the watch loop in a background thread.
    //
    // Daemon, because the loop parks in `ws.take()` until someone calls stopWatching(): as a
    // non-daemon thread it kept the whole JVM alive in `DestroyJavaVM` after main returned, so any
    // CLI command that opened the logs repo and exited 0 hung until SIGKILL (`TrailblazeCli.run`
    // only force-exits on non-zero codes). Watching is strictly background work — nothing should
    // outlive the process on its account.
    watchThread = thread(name = "FileWatcher-${dirToWatch.name}", isDaemon = true) {
      try {
        // Infinite loop to watch for events
        while (!Thread.currentThread().isInterrupted) {
          // Retrieve and remove the next watch key, waiting if none are present
          val key = ws.take()

          // Process the events for the watch key
          for (event in key.pollEvents()) {
            val kind = event.kind()
            // The filename is the context of the event
            val filename = event.context() as Path
            val fileName = filename.name

            onWatchEvent(fileName, FileChangeEvent.ChangeType.fromWatchEventKind(kind))
          }

          // Reset the key -- this step is critical to receive further watch events
          val valid = key.reset()
          if (!valid) {
            break
          }
        }
      } catch (e: ClosedWatchServiceException) {
        // WatchService was closed due to stopWatching(); exit the watch loop without error
        // This is expected behavior when the service is stopped intentionally
      } catch (e: InterruptedException) {
        // Thread was interrupted, exit gracefully
        Thread.currentThread().interrupt()
      } catch (e: Exception) {
        Console.log("[FileWatchService] Error in watch loop for $dirToWatch: ${e.message}")
        e.printStackTrace()
      }
    }
  }

  /**
   * Coalesce this event into the window already open for [file], opening one if there isn't.
   *
   * The previous implementation launched a fresh coroutine for EVERY event and relied on the
   * `pendingEvents` map to drop the duplicates at the far end of the delay. That bounds the
   * *emissions* only for a burst delivered faster than the map can be drained — under a file
   * being appended continuously for the length of a run (the session's own `network.ndjson`,
   * which is now filtered out above, but any long-lived append does it) it degenerated to
   * pass-through: each new event refilled the map before the next timer fired, so essentially
   * every event scheduled a coroutine AND emitted, and the daemon spun logging
   * `FileWatchService MODIFY network.ndjson` for the whole run.
   *
   * Now the first event for a file opens exactly one window and every event arriving inside it
   * merely updates the pending entry, so the work a stream schedules is bounded by elapsed time
   * (at most one window and one emission per [debounceDelayMs] per file) rather than by how fast
   * the file is written. The latest change type still wins, as before.
   */
  private fun debounceFileEvent(file: File, changeType: FileChangeEvent.ChangeType) {
    // The watch thread outlives the stop request, and a key inserted after it can never be cleared
    // — see [stopped].
    if (stopped) return

    val fileKey = file.absolutePath

    val windowAlreadyOpen = synchronized(pendingEvents) {
      pendingEvents.put(fileKey, Pair(changeType, file)) != null
    }
    if (windowAlreadyOpen) return

    debounceWindowsOpened.incrementAndGet()
    debounceScope.launch {
      // The pending entry IS the "a window is open for this file" flag read above, so it has to be
      // cleared however this coroutine ends, or the file goes silent for good.
      //
      // But only ever clear the entry THIS window owns. Once the take below succeeds the key is
      // free again, so an event arriving during the emit legitimately opens the next window and
      // puts its own entry there — and an unconditional cleanup would delete that successor, whose
      // coroutine then wakes to find nothing and emits nothing, dropping a real file change.
      var clearedByThisWindow = false
      try {
        delay(debounceDelayMs)

        val eventToProcess = synchronized(pendingEvents) {
          pendingEvents.remove(fileKey)
        }
        clearedByThisWindow = true

        eventToProcess?.let { (debouncedChangeType, debouncedFile) ->
          try {
            // Use absolutePath instead of canonicalPath to avoid expensive filesystem I/O
            Console.log("[FileWatchService] Emitting event: $debouncedChangeType ${debouncedFile.name} (${debouncedFile.absolutePath})")
            fileChangeChannel.send(FileChangeEvent(debouncedChangeType, debouncedFile))
          } catch (e: CancellationException) {
            // Cancellation is this scope being shut down, not a failure of this event. Rethrow so
            // the coroutine actually ends up cancelled rather than merely logged.
            throw e
          } catch (e: Exception) {
            Console.log("[FileWatchService] Error emitting event for ${debouncedFile.absolutePath}: ${e.message}")
            e.printStackTrace()
          }
        }
      } finally {
        if (!clearedByThisWindow) {
          synchronized(pendingEvents) { pendingEvents.remove(fileKey) }
        }
      }
    }
  }
}
