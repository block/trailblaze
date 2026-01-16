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
import xyz.block.trailblaze.recordings.TrailRecordings
import xyz.block.trailblaze.report.utils.FileChangeEvent
import xyz.block.trailblaze.report.utils.FileWatchService
import xyz.block.trailblaze.yaml.TrailConfig
import java.io.File

/**
 * JVM implementation of RecordingsRepo that saves recordings to the file system.
 *
 * All trails are stored directly under the trails directory (e.g., trails/testrail/suite_123/...).
 *
 * @param trailsDirectory The root directory for all trails. Defaults to ~/.trailblaze/trails
 */
class RecordedTrailsRepoJvm(
  private val trailsDirectory: File = File(System.getProperty("user.home"), ".trailblaze/trails"),
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
  ): Result<String> {
    val trailConfig = sessionInfo.trailConfig ?: TrailConfig()
    val classifiers = sessionInfo.trailblazeDeviceInfo?.classifiers ?: listOf()

    // Build suffix based on parameters
    val suffixParts = mutableListOf<String>()

    suffixParts.addAll(classifiers.map { it.classifier })

    val suffix = suffixParts.joinToString("-")

    return try {
      val directoryPath: String
      val fileName: String
      if (trailConfig.id != null) {
        // trailConfig.id is the trail path (e.g., "testrail/suite_123/section_456/case_789")
        // The directory IS the trail identity, so filename is just platform-classifiers
        val trailPath = trailConfig.id!!
        // Prepend save subdirectory if configured, otherwise save at root
        directoryPath = trailPath
        // Filename is platform-classifiers (e.g., "ios-iphone.trail.yaml", "android.trail.yaml")
        // If no suffix, use a timestamp to avoid overwriting
        fileName = if (suffix.isNotEmpty()) {
          "${suffix.removePrefix("-")}.${TrailRecordings.TRAIL_DOT_YAML}"
        } else {
          TrailRecordings.TRAIL_DOT_YAML
        }
      } else {
        // Fallback to session-based filename if no trailPath is provided
        directoryPath = ""
        fileName = "${sessionInfo.sessionId}/$suffix.${TrailRecordings.TRAIL_DOT_YAML}"
      }

      val recordingFile = File(File(trailsDirectory, directoryPath), fileName)

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

  override fun savePrompts(
    yaml: String,
    sessionInfo: SessionInfo,
  ): Result<String> {
    val trailConfig = sessionInfo.trailConfig ?: TrailConfig()

    return try {
      val directoryPath: String = if (trailConfig.id != null) {
        // trailConfig.id is the trail path (e.g., "testrail/suite_123/section_456/case_789")
        val trailPath = trailConfig.id!!
        // Prepend save subdirectory if configured, otherwise save at root
        trailPath
      } else {
        // Fallback to just the subdirectory if no trailPath is provided
        ""
      }

      val promptsFile = File(File(trailsDirectory, directoryPath), TrailRecordings.TRAIL_DOT_YAML)

      // Create parent directories if they don't exist
      promptsFile.parentFile?.mkdirs()

      // Write the YAML content
      promptsFile.writeText(yaml)

      println("Prompts saved to: ${promptsFile.absolutePath}")
      Result.success(promptsFile.absolutePath)
    } catch (e: Exception) {
      println("Failed to save prompts: ${e.message}")
      Result.failure(e)
    }
  }

  override fun getTrailsDirectory(): String {
    return trailsDirectory.absolutePath
  }

  override fun getExistingTrails(sessionInfo: SessionInfo): List<ExistingTrail> {
    val trailConfig = sessionInfo.trailConfig ?: TrailConfig()

    // trailConfig.id is the trail path (e.g., "testrail/suite_123/section_456/case_789")
    // The directory IS the trail identity
    val trailPath = trailConfig.id ?: return emptyList()

    return try {
      val targetDir = File(trailsDirectory, trailPath)
      if (!targetDir.exists() || !targetDir.isDirectory) {
        return emptyList()
      }

      // Find all *trail.yaml files in the directory:
      // - trail.yaml (default file)
      // - {platform}-{classifier}.trail.yaml (platform-specific recordings, e.g., ios-iphone.trail.yaml)
      targetDir.listFiles()
        ?.filter { file ->
          file.isFile && file.name.endsWith(TrailRecordings.TRAIL_DOT_YAML)
        }?.map { file ->
          val fileRelativePath = "$trailPath/${file.name}"
          ExistingTrail(
            absolutePath = file.absolutePath,
            relativePath = fileRelativePath,
            fileName = file.name,
          )
        }
        // Sort with trail.yaml first, then alphabetically
        ?.sortedWith(compareBy({ it.fileName != TrailRecordings.TRAIL_DOT_YAML }, { it.fileName }))
        ?: emptyList()
    } catch (e: Exception) {
      println("Failed to search for existing recordings: ${e.message}")
      emptyList()
    }
  }

  override fun getWatchDirectoryForSession(sessionInfo: SessionInfo): String? {
    // trailConfig.id is the trail path (e.g., "testrail/suite_123/section_456/case_789")
    val trailPath = sessionInfo.trailConfig?.id ?: return null

    // Return the directory if it exists
    // We don't create directories here to avoid empty directories;
    // directories are created only when saveRecording() writes a file
    val dir = File(trailsDirectory, trailPath)
    return if (dir.exists() && dir.isDirectory) {
      dir.absolutePath
    } else {
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
        debounceDelayMs = 300L
      )

      try {
        fileWatcher.startWatching()
        println("Started watching directory: $directoryPath")

        // Launch a coroutine to collect file changes from the FileWatchService flow
        launch {
          fileWatcher.fileChanges.collect { event ->
            // Only emit events for .trail.yaml files
            if (event.file.name.endsWith(TrailRecordings.TRAIL_DOT_YAML)) {
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
