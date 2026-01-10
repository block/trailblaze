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
 * Trails can be organized into subdirectory categories under the trails directory.
 * The lookup order and save destination are configured separately:
 *
 * - **lookupSubdirectories**: Directories to search when looking for existing trails, in priority order.
 *   The first match wins. Use empty string "" to include the root directory in the search.
 *
 * - **defaultSaveSubdirectory**: Where new recordings are saved. Use "generated" to clearly
 *   indicate auto-generated trails, or null to save at the root.
 *
 * Example for Block internal (handwritten takes priority, but saves go to generated):
 * ```
 * lookupSubdirectories = listOf("handwritten", "generated")
 * defaultSaveSubdirectory = "generated"
 * ```
 * Search order: handwritten → generated
 * New recordings saved to: trails/generated/...
 *
 * Example for open source (simple, everything at root):
 * ```
 * lookupSubdirectories = emptyList()  // or listOf("")
 * defaultSaveSubdirectory = null
 * ```
 * Search and save directly at: trails/testrail/suite_123/...
 *
 * @param trailsDirectory The root directory for all trails. Defaults to ~/.trailblaze/trails
 * @param lookupSubdirectories Subdirectories to search when looking for trails, in priority order.
 *                             Empty list (default) means search only at root.
 *                             Use "" to include root in the search order.
 * @param defaultSaveSubdirectory Subdirectory where new recordings are saved.
 *                                null (default) means save at root.
 *                                "generated" is recommended for auto-generated trails.
 */
class RecordedTrailsRepoJvm(
  private val trailsDirectory: File = File(System.getProperty("user.home"), ".trailblaze/trails"),
  private val lookupSubdirectories: List<String> = emptyList(),
  private val defaultSaveSubdirectory: String? = null,
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
        directoryPath = if (defaultSaveSubdirectory != null) "$defaultSaveSubdirectory/$trailPath" else trailPath
        // Filename is platform-classifiers (e.g., "ios-iphone.trail.yaml", "android.trail.yaml")
        // If no suffix, use a timestamp to avoid overwriting
        fileName = if (suffix.isNotEmpty()) {
          "${suffix.removePrefix("-")}.trail.yaml"
        } else {
          "trail.yaml"
        }
      } else {
        // Fallback to session-based filename if no trailPath is provided
        directoryPath = defaultSaveSubdirectory ?: ""
        fileName = "${sessionInfo.sessionId}/$suffix.trail.yaml"
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
        if (defaultSaveSubdirectory != null) "$defaultSaveSubdirectory/$trailPath" else trailPath
      } else {
        // Fallback to just the subdirectory if no trailPath is provided
        defaultSaveSubdirectory ?: ""
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
      // Determine which directories to search based on lookup order
      val searchPaths = if (lookupSubdirectories.isEmpty()) {
        // No lookup subdirectories configured - search at root only
        listOf("" to trailPath)
      } else {
        // Search in each subdirectory in priority order
        // Empty string "" means search at root
        lookupSubdirectories.map { subdir ->
          val path = if (subdir.isEmpty()) trailPath else "$subdir/$trailPath"
          subdir to path
        }
      }

      // Search directories in priority order, results grouped by order then sorted within each
      val allTrails = searchPaths.flatMap { (subdirectory, relativePath) ->
        // subdirectory is the lookup subdirectory (null-ish if empty string for root)
        val subdirectoryForDisplay = subdirectory.takeIf { it.isNotEmpty() }

        val targetDir = File(trailsDirectory, relativePath)
        if (!targetDir.exists() || !targetDir.isDirectory) {
          emptyList()
        } else {
          // Find all *trail.yaml files in the directory:
          // - trail.yaml (default file)
          // - {platform}-{classifier}.trail.yaml (platform-specific recordings, e.g., ios-iphone.trail.yaml)
          targetDir.listFiles()
            ?.filter { file ->
              file.isFile && file.name.endsWith("trail.yaml")
            }
            ?.map { file ->
              val fileRelativePath = "$relativePath/${file.name}"
              ExistingTrail(
                absolutePath = file.absolutePath,
                relativePath = fileRelativePath,
                fileName = file.name,
                subdirectory = subdirectoryForDisplay,
              )
            }
            // Sort with trail.yaml first, then alphabetically
            ?.sortedWith(compareBy({ it.fileName != TrailRecordings.TRAIL_DOT_YAML }, { it.fileName }))
            ?: emptyList()
        }
      }

      // Detect shadowed trails: mark trails that have the same filename in a higher-priority directory
      // Build a map of filename -> first subdirectory that has it (the one that wins)
      val fileNameToWinningSubdir = mutableMapOf<String, String?>()
      allTrails.forEach { trail ->
        if (!fileNameToWinningSubdir.containsKey(trail.fileName)) {
          fileNameToWinningSubdir[trail.fileName] = trail.subdirectory
        }
      }

      // Mark trails as shadowed if they're not the winning one
      allTrails.map { trail ->
        val winningSubdir = fileNameToWinningSubdir[trail.fileName]
        if (winningSubdir != trail.subdirectory) {
          trail.copy(isShadowed = true, shadowedBy = winningSubdir)
        } else {
          trail
        }
      }
    } catch (e: Exception) {
      println("Failed to search for existing recordings: ${e.message}")
      emptyList()
    }
  }

  override fun getWatchDirectoriesForSession(sessionInfo: SessionInfo): List<String> {
    // trailConfig.id is the trail path (e.g., "testrail/suite_123/section_456/case_789")
    val trailPath = sessionInfo.trailConfig?.id ?: return emptyList()

    // Determine which directories to check based on lookup order
    val searchPaths = if (lookupSubdirectories.isEmpty()) {
      // No lookup subdirectories configured - check at root only
      listOf(trailPath)
    } else {
      // Check in each subdirectory in priority order
      // Empty string "" means check at root
      lookupSubdirectories.map { subdir ->
        if (subdir.isEmpty()) trailPath else "$subdir/$trailPath"
      }
    }

    // Return all directories that exist
    // We don't create directories here to avoid empty directories;
    // directories are created only when saveRecording() writes a file
    return searchPaths.mapNotNull { relativePath ->
      val dir = File(trailsDirectory, relativePath)
      if (dir.exists() && dir.isDirectory) {
        dir.absolutePath
      } else {
        null
      }
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
            if (event.file.name.endsWith("trail.yaml")) {
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
