package xyz.block.trailblaze.ui.recordings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import xyz.block.trailblaze.logs.model.SessionInfo
import xyz.block.trailblaze.report.utils.FileChangeEvent
import xyz.block.trailblaze.report.utils.FileWatchService
import xyz.block.trailblaze.yaml.TrailConfig
import java.io.File
import java.util.UUID

/**
 * JVM implementation of RecordingsRepo that saves recordings to the file system.
 *
 * @param trailsDirectory The directory where recordings will be saved.
 *                           Defaults to ~/.trailblaze/recordings
 */
class RecordedTrailsRepoJvm(
  private val trailsDirectory: File = File(System.getProperty("user.home"), ".trailblaze/trails")
) : RecordedTrailsRepo {

  // Cache shared flows per directory path to avoid creating multiple watchers for the same directory
  private val flowCache = mutableMapOf<String, Flow<TrailFileChangeEvent>>()

  init {
    // Ensure the recordings directory exists
    if (!trailsDirectory.exists()) {
      trailsDirectory.mkdirs()
    }
  }

  override fun saveRecording(
    yaml: String,
    sessionInfo: SessionInfo,
    includePlatform: Boolean,
    numClassifiers: Int
  ): Result<String> {
    val trailConfig = sessionInfo.trailConfig ?: TrailConfig()
    val platform = sessionInfo.trailblazeDeviceInfo?.trailblazeDriverType?.platform?.name?.lowercase()
    val classifiers = sessionInfo.trailblazeDeviceInfo?.classifiers ?: listOf()

    // Build suffix based on parameters
    val suffixParts = mutableListOf<String>()

    if (includePlatform && platform != null) {
      suffixParts.add(platform)
    }

    // Add classifiers based on numClassifiers parameter
    // -1 means all classifiers, 0 means none, positive number means that many
    val classifiersToInclude = when {
      numClassifiers < 0 -> classifiers
      numClassifiers == 0 -> emptyList()
      else -> classifiers.take(numClassifiers)
    }
    suffixParts.addAll(classifiersToInclude)

    val suffix = if (suffixParts.isNotEmpty()) {
      "-${suffixParts.joinToString("-")}"
    } else {
      ""
    }

    return try {
      val fileName = if (trailConfig.id != null) {
        // Replace path separators with underscores for safe filenames
        "${trailConfig.id}$suffix.trail.yaml"
      } else {
        // Fallback to timestamp-based filename if no trailPath is provided
        "recording_${System.currentTimeMillis()}$suffix.trail.yaml"
      }

      val recordingFile = File(trailsDirectory, fileName)

      // Create parent directories if they don't exist
      recordingFile.parentFile?.mkdirs()

      // Write the YAML content
      recordingFile.writeText(yaml)

      println("Recording saved to: ${recordingFile.absolutePath}")
      Result.success(recordingFile.absolutePath)
    } catch (e: Exception) {
      println("Failed to save recording: ${e.message}")
      Result.failure(e)
    }
  }

  override fun getTrailsDirectory(): String {
    return trailsDirectory.absolutePath
  }

  override fun getExistingTrails(sessionInfo: SessionInfo): List<String> {
    val trailConfig = sessionInfo.trailConfig ?: TrailConfig()

    // Determine the search pattern based on id
    val searchPrefix = when {
      trailConfig.id != null -> trailConfig.id
      else -> return emptyList() // No way to identify recordings without id
    }

    return try {
      if (!trailsDirectory.exists() || !trailsDirectory.isDirectory) {
        return emptyList()
      }

      // Extract the filename part (after the last slash) for matching
      val searchFileName = searchPrefix!!.substringAfterLast('/')

      // Find all files recursively that match the pattern
      trailsDirectory.walkTopDown()
        .filter { file ->
          file.isFile &&
              file.name.startsWith(searchFileName) &&
              file.name.endsWith(".trail.yaml")
        }
        .map { it.absolutePath }
        .sorted()
        .toList()
    } catch (e: Exception) {
      println("Failed to search for existing recordings: ${e.message}")
      emptyList()
    }
  }

  override fun getWatchDirectoryForSession(sessionInfo: SessionInfo): String? {
    val trailPath = sessionInfo.trailConfig?.id ?: return null

    // Extract the directory path from trailPath
    // e.g., "suite_123/section_456/case_789/789" -> "suite_123/section_456/case_789"
    val directoryPath = if (trailPath.contains('/')) {
      trailPath.substringBeforeLast('/')
    } else {
      // If no slash, the ID itself is the directory
      trailPath
    }

    val watchDir = File(trailsDirectory, directoryPath)

    // Create the directory if it doesn't exist so the file watcher can be set up
    // This ensures we catch new recordings even when starting with an empty directory
    if (!watchDir.exists()) {
      try {
        watchDir.mkdirs()
        println("Created watch directory: ${watchDir.absolutePath}")
      } catch (e: Exception) {
        println("Failed to create watch directory ${watchDir.absolutePath}: ${e.message}")
        return null
      }
    }

    return if (watchDir.isDirectory) {
      watchDir.absolutePath
    } else {
      println("Watch path exists but is not a directory: ${watchDir.absolutePath}")
      null
    }
  }

  override fun watchDirectory(directoryPath: String): Flow<TrailFileChangeEvent>? {
    val directory = File(directoryPath)
    if (!directory.exists() || !directory.isDirectory) {
      println("Cannot watch non-existent or non-directory path: $directoryPath")
      return null
    }

    // Return cached flow if it exists for this directory
    return synchronized(flowCache) {
      flowCache.getOrPut(directoryPath) {
        createWatchFlow(directory, directoryPath)
      }
    }
  }

  private fun createWatchFlow(directory: File, directoryPath: String): Flow<TrailFileChangeEvent> {
    // Create a scope for the shared flow - it will live as long as there are collectors
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    return callbackFlow {
      val fileWatcher = FileWatchService(
        dirToWatch = directory,
        debounceDelayMs = 300L,
        maxEventsPerSecond = 5
      )

      try {
        fileWatcher.startWatching()
        println("Started watching directory: $directoryPath")

        // Launch a coroutine to collect file changes from the FileWatchService flow
        launch {
          fileWatcher.fileChanges.collect { event ->
            // Only emit events for .trail.yaml files
            if (event.file.name.endsWith(".trail.yaml")) {
              val mappedChangeType = when (event.changeType) {
                FileChangeEvent.ChangeType.CREATE -> TrailFileChangeType.CREATE
                FileChangeEvent.ChangeType.DELETE -> TrailFileChangeType.DELETE
                FileChangeEvent.ChangeType.MODIFY -> TrailFileChangeType.MODIFY
              }
              println("[RecordedTrailsRepo] Emitting file change: ${mappedChangeType} ${event.file.name}")
              send(TrailFileChangeEvent(mappedChangeType, event.file.absolutePath))
            }
          }
        }

        // Wait for the flow to be cancelled
        awaitClose {
          println("Stopping file watcher for: $directoryPath")
          fileWatcher.stopWatching()
          scope.cancel()
          // Remove from cache when stopped
          synchronized(flowCache) {
            flowCache.remove(directoryPath)
          }
        }
      } catch (e: Exception) {
        println("Error in file watcher for $directoryPath: ${e.message}")
        e.printStackTrace()
        fileWatcher.stopWatching()
        scope.cancel()
        // Remove from cache on error
        synchronized(flowCache) {
          flowCache.remove(directoryPath)
        }
        close(e)
      }
    }.shareIn(
      scope = scope,
      started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5000),
      replay = 0
    )
  }
}
