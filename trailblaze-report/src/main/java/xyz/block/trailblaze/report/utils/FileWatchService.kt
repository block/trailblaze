package xyz.block.trailblaze.report.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchEvent
import java.nio.file.WatchService
import kotlin.concurrent.thread
import kotlin.io.path.name

class FileWatchService(
  private val dirToWatch: File,
  private val onFileChange: (ChangeType, File) -> Unit,
  private val debounceDelayMs: Long = 500L, // Debounce changes within 500ms
  private val maxEventsPerSecond: Int = 10, // Circuit breaker: max 10 events per second
) {
  private val eventTypes: List<ChangeType> = listOf(
    ChangeType.CREATE,
    ChangeType.DELETE,
    ChangeType.MODIFY,
  )

  enum class ChangeType(val watchEventKind: WatchEvent.Kind<Path>) {
    CREATE(StandardWatchEventKinds.ENTRY_CREATE),
    DELETE(StandardWatchEventKinds.ENTRY_DELETE),
    MODIFY(StandardWatchEventKinds.ENTRY_MODIFY),
    ;

    companion object {
      fun fromWatchEventKind(watchEventKind: WatchEvent.Kind<out Any>): ChangeType = ChangeType.entries.first { it.watchEventKind == watchEventKind }
    }
  }

  // Path to the directory to watch
  val path: Path = Paths.get(dirToWatch.canonicalPath)

  // Create a WatchService
  val watchService: WatchService = FileSystems.getDefault().newWatchService()

  // Thread for running the watch loop
  private var watchThread: Thread? = null

  // Coroutine scope for debouncing
  private val debounceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

  // Track pending events for debouncing
  private val pendingEvents = mutableMapOf<String, Pair<ChangeType, File>>()

  // Circuit breaker for rate limiting
  private var eventCount = 0
  private var lastResetTime = System.currentTimeMillis()

  private fun shouldIgnoreFile(file: File): Boolean {
    val fileName = file.name.lowercase()

    // Ignore directories that start with special characters
    if (file.isDirectory && fileName.isNotEmpty() && !fileName[0].isLetterOrDigit()) {
      return true
    }

    return file.isHidden
  }

  private fun isWithinRateLimit(): Boolean {
    val currentTime = System.currentTimeMillis()
    if (currentTime - lastResetTime > 1000) {
      // Reset counter every second
      eventCount = 0
      lastResetTime = currentTime
    }
    return eventCount < maxEventsPerSecond
  }

  fun stopWatching() {
    // Close the WatchService to stop watching - this will cause the thread to exit
    watchService.close()
    watchThread?.interrupt()
    watchThread = null
    debounceScope.cancel()
    println("Stopped watching directory: $path")
  }

  fun startWatching() {
    // Register the directory with the WatchService for create, delete, and modify events
    path.register(
      watchService,
      eventTypes.map { it.watchEventKind }.toTypedArray(),
    )

    println("Watching directory: $path")

    // Start the watch loop in a background thread
    watchThread = thread(name = "FileWatcher-${dirToWatch.name}") {
      try {
        // Infinite loop to watch for events
        while (!Thread.currentThread().isInterrupted) {
          // Retrieve and remove the next watch key, waiting if none are present
          val key = watchService.take()

          // Process the events for the watch key
          for (event in key.pollEvents()) {
            val kind = event.kind()
            // The filename is the context of the event
            val filename = event.context() as Path

            val realChangedFile = File(path.toFile(), filename.name)

            // Apply filtering and rate limiting
            if (shouldIgnoreFile(realChangedFile)) {
              continue // Skip ignored files
            }

            if (!isWithinRateLimit()) {
              println("Rate limit exceeded for file watcher in $dirToWatch, skipping event for ${realChangedFile.name}")
              continue
            }

            eventCount++
            val changeType = ChangeType.fromWatchEventKind(kind)

            // Debounce events for the same file
            debounceFileEvent(realChangedFile, changeType)
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
        println("Error in file watcher for $dirToWatch: ${e.message}")
        e.printStackTrace()
      }
    }
  }

  private fun debounceFileEvent(file: File, changeType: ChangeType) {
    val fileKey = file.absolutePath

    synchronized(pendingEvents) {
      pendingEvents[fileKey] = Pair(changeType, file)
    }

    // Cancel any existing debounce job for this file and create a new one
    debounceScope.launch {
      delay(debounceDelayMs)

      val eventToProcess = synchronized(pendingEvents) {
        pendingEvents.remove(fileKey)
      }

      eventToProcess?.let { (debouncedChangeType, debouncedFile) ->
        try {
          println("File Changed in $dirToWatch! $debouncedChangeType ${debouncedFile.canonicalPath}")
          onFileChange(debouncedChangeType, debouncedFile)
        } catch (e: Exception) {
          println("Error processing file change event for ${debouncedFile.absolutePath}: ${e.message}")
          e.printStackTrace()
        }
      }
    }
  }
}
