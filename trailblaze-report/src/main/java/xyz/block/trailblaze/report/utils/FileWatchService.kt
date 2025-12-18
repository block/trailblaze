package xyz.block.trailblaze.report.utils

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
import kotlin.concurrent.thread
import kotlin.io.path.name

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
  val path: Path = Paths.get(dirToWatch.canonicalPath)

  // Create a WatchService
  val watchService: WatchService = FileSystems.getDefault().newWatchService()

  // Thread for running the watch loop
  private var watchThread: Thread? = null

  // Coroutine scope for debouncing - use Default for CPU-bound work, not IO for file system events
  private val debounceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

  // Track pending events for debouncing
  private val pendingEvents = mutableMapOf<String, Pair<FileChangeEvent.ChangeType, File>>()

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

    return false
  }

  fun stopWatching() {
    // Close the WatchService to stop watching - this will cause the thread to exit
    watchService.close()
    watchThread?.interrupt()
    watchThread = null
    debounceScope.cancel()
    fileChangeChannel.close()
    println("[FileWatchService] Stopped watching: $path")
  }

  fun startWatching() {
    // Register the directory with the WatchService for create, delete, and modify events
    path.register(
      watchService,
      eventTypes.map { it.watchEventKind }.toTypedArray(),
    )

    println("[FileWatchService] Started watching: $path (debounce: ${debounceDelayMs}ms)")

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
            val fileName = filename.name

            // Apply filtering based on filename (no I/O)
            if (shouldIgnoreFile(fileName)) {
              continue // Skip ignored files
            }

            val changeType = FileChangeEvent.ChangeType.fromWatchEventKind(kind)

            // Construct the full file path only after filtering
            val realChangedFile = File(path.toFile(), fileName)

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
        println("[FileWatchService] Error in watch loop for $dirToWatch: ${e.message}")
        e.printStackTrace()
      }
    }
  }

  private fun debounceFileEvent(file: File, changeType: FileChangeEvent.ChangeType) {
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
          // Use absolutePath instead of canonicalPath to avoid expensive filesystem I/O
          println("[FileWatchService] Emitting event: $debouncedChangeType ${debouncedFile.name} (${debouncedFile.absolutePath})")
          fileChangeChannel.send(FileChangeEvent(debouncedChangeType, debouncedFile))
        } catch (e: Exception) {
          println("[FileWatchService] Error emitting event for ${debouncedFile.absolutePath}: ${e.message}")
          e.printStackTrace()
        }
      }
    }
  }
}
