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
import xyz.block.trailblaze.recordings.UnifiedRecordingWriter
import xyz.block.trailblaze.report.utils.FileChangeEvent
import xyz.block.trailblaze.report.utils.FileWatchService
import xyz.block.trailblaze.yaml.TrailConfig
import xyz.block.trailblaze.yaml.TrailYamlItem
import java.io.File
import xyz.block.trailblaze.util.Console

/**
 * JVM implementation of RecordingsRepo that saves recordings to the file system.
 *
 * All trails are stored directly under the trails directory (e.g., trails/regression/suite_123/...).
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
    items: List<TrailYamlItem>,
    sessionInfo: SessionInfo,
  ): Result<String> {
    val trailConfig = sessionInfo.trailConfig ?: TrailConfig()
    val classifiers = sessionInfo.trailblazeDeviceInfo?.classifiers ?: listOf()

    // Classifier key (e.g. "android", "ios-iphone") — the unified slot key AND the sibling filename base.
    val suffix = classifiers.joinToString("-") { it.classifier }

    // Everything below is inside the try so a render failure (e.g. a recording shape the unified
    // format can't hold) becomes a Result.failure the desktop save surfaces as "Save Failed",
    // not an exception escaping the save coroutine.
    return try {
      val trailPath = trailConfig.id
      if (trailPath == null) {
        // Fallback: no trail identity → a session-scoped directory that never holds a unified
        // trail.yaml. No routing applies.
        val recordingFile = File(
          trailsDirectory,
          "${sessionInfo.sessionId}/$suffix${TrailRecordings.DOT_TRAIL_DOT_YAML_FILE_SUFFIX}",
        )
        val rendered = UnifiedRecordingWriter.renderStandalone(items, suffix)
          .getOrElse { return Result.failure(it) }
        recordingFile.parentFile?.mkdirs()
        recordingFile.writeText(rendered)
        Console.log("Recording saved to: ${recordingFile.absolutePath}")
        return Result.success(recordingFile.absolutePath)
      }

      // trailConfig.id is the trail path (e.g. "regression/suite_123/section_456/case_789") — the
      // directory IS the trail identity. Create it before routing so the writer resolves the unified
      // target inside it (a not-yet-existing path would otherwise resolve to its parent).
      val trailDir = File(trailsDirectory, trailPath).apply { mkdirs() }
      // Per-classifier sibling filename: "<classifiers>.trail.yaml", or the NL definition filename
      // when there's no classifier.
      val siblingFileName = if (suffix.isNotEmpty()) {
        "${suffix.removePrefix("-")}${TrailRecordings.DOT_TRAIL_DOT_YAML_FILE_SUFFIX}"
      } else {
        TrailRecordings.DEFAULT_NL_DEFINITION_FILENAME
      }

      // Route through the shared writer (same routing the CLI uses).
      if (UnifiedRecordingWriter.shouldMergeIntoSharedTrail(trailDir, suffix)) {
        return saveRecordingAsUnified(trailDir, items, suffix)
      }

      // Per-classifier sibling. Refuse to drop one into a directory that already has a shared
      // unified trail — the sibling can't update it, so it would only shadow it at run time.
      if (UnifiedRecordingWriter.unifiedTrailPresent(trailDir)) {
        return Result.failure(
          IllegalStateException(UnifiedRecordingWriter.siblingShadowRefusalMessage(siblingFileName, trailDir)),
        )
      }

      val rendered = UnifiedRecordingWriter.renderStandalone(items, suffix)
        .getOrElse { return Result.failure(it) }
      val recordingFile = File(trailDir, siblingFileName)
      recordingFile.parentFile?.mkdirs()
      recordingFile.writeText(rendered)
      Console.log("Recording saved to: ${recordingFile.absolutePath}")
      Result.success(recordingFile.absolutePath)
    } catch (e: Exception) {
      Console.log("Failed to save recording: ${e.message}")
      Result.failure(e)
    }
  }

  /**
   * Merge the recorded [items] into the directory's unified `trail.yaml` under [classifier]'s slot,
   * preserving every other classifier already on disk. Refuses rather than clobbering a corrupt
   * existing trail, or writing a recording shape the unified format can't hold.
   */
  private fun saveRecordingAsUnified(
    trailDir: File,
    items: List<TrailYamlItem>,
    classifier: String,
  ): Result<String> {
    return when (val outcome = UnifiedRecordingWriter.mergeIntoUnified(trailDir, items, classifier)) {
      is UnifiedRecordingWriter.MergeOutcome.Merged -> {
        Console.log("Recording merged into ${outcome.target.absolutePath} (classifier `$classifier`)")
        Result.success(outcome.target.absolutePath)
      }

      is UnifiedRecordingWriter.MergeOutcome.MultiToolTrailheadUnsupported -> Result.failure(
        IllegalStateException(UnifiedRecordingWriter.multiToolTrailheadMessage(outcome.toolCount)),
      )

      is UnifiedRecordingWriter.MergeOutcome.NoTarget -> Result.failure(
        IllegalStateException(
          "Can't save this recording: no unified trail.yaml target resolved in ${trailDir.absolutePath}.",
        ),
      )

      is UnifiedRecordingWriter.MergeOutcome.RefusedCorrupt -> Result.failure(
        IllegalStateException(UnifiedRecordingWriter.corruptRefusalMessage(outcome.target, outcome.reason)),
      )

      is UnifiedRecordingWriter.MergeOutcome.SkippedEmpty -> Result.failure(
        IllegalStateException(UnifiedRecordingWriter.EMPTY_MERGE_MESSAGE),
      )

      is UnifiedRecordingWriter.MergeOutcome.SteplessIntoExistingTrail -> Result.failure(
        IllegalStateException(UnifiedRecordingWriter.STEPLESS_INTO_EXISTING_MESSAGE),
      )
    }
  }


  override fun getTrailsDirectory(): String {
    return trailsDirectory.absolutePath
  }

  override fun getExistingTrails(sessionInfo: SessionInfo): List<ExistingTrail> {
    val trailConfig = sessionInfo.trailConfig ?: TrailConfig()

    // trailConfig.id is the trail path (e.g., "regression/suite_123/section_456/case_789")
    // The directory IS the trail identity
    val trailPath = trailConfig.id ?: return emptyList()

    return try {
      val targetDir = File(trailsDirectory, trailPath)
      if (!targetDir.exists() || !targetDir.isDirectory) {
        return emptyList()
      }

      // Find all trail files in the directory:
      // - trailblaze.yaml (NL definition file)
      // - {platform}-{classifier}.trail.yaml (platform-specific recordings, e.g., ios-iphone.trail.yaml)
      targetDir.listFiles()
        ?.filter { file ->
          file.isFile && TrailRecordings.isTrailFile(file.name)
        }?.map { file ->
          val fileRelativePath = "$trailPath/${file.name}"
          // Content-detect the unified format (a `config:`/`trail:` mapping) so a unified trail
          // stored under a NAMED `*.trail.yaml` — not just the bare `trail.yaml` — is recognized and
          // doesn't render bogus filename-derived platform/classifier chips. Cheap line scan; a read
          // failure degrades to the filename check.
          val isUnifiedContent = try {
            TrailRecordings.isUnifiedTrailContent(file.readText())
          } catch (e: Exception) {
            false
          }
          ExistingTrail(
            absolutePath = file.absolutePath,
            relativePath = fileRelativePath,
            fileName = file.name,
            isUnifiedContent = isUnifiedContent,
          )
        }
        // Sort with trail.yaml first, then alphabetically
        ?.sortedWith(compareBy({ !TrailRecordings.isNlDefinitionFile(it.fileName) }, { it.fileName }))
        ?: emptyList()
    } catch (e: Exception) {
      Console.log("Failed to search for existing recordings: ${e.message}")
      emptyList()
    }
  }

  override fun getWatchDirectoryForSession(sessionInfo: SessionInfo): String? {
    // trailConfig.id is the trail path (e.g., "regression/suite_123/section_456/case_789")
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
      Console.log("Cannot watch non-existent or non-directory path: $directoryPath")
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
        Console.log("Started watching directory: $directoryPath")

        // Launch a coroutine to collect file changes from the FileWatchService flow
        launch {
          fileWatcher.fileChanges.collect { event ->
            // Only emit events for .trail.yaml files
            if (TrailRecordings.isTrailFile(event.file.name)) {
              val mappedChangeType = when (event.changeType) {
                FileChangeEvent.ChangeType.CREATE -> TrailFileChangeType.CREATE
                FileChangeEvent.ChangeType.DELETE -> TrailFileChangeType.DELETE
                FileChangeEvent.ChangeType.MODIFY -> TrailFileChangeType.MODIFY
              }
              Console.log("[RecordedTrailsRepo] Emitting file change: ${mappedChangeType} ${event.file.name}")
              send(TrailFileChangeEvent(mappedChangeType, event.file.absolutePath))
            }
          }
        }

        // Wait for the flow to be cancelled
        awaitClose {
          Console.log("Stopping file watcher for: $directoryPath")
          fileWatcher.stopWatching()
          scope.cancel()
          // Remove from cache when stopped
          synchronized(flowCache) {
            flowCache.remove(directoryPath)
          }
        }
      } catch (e: Exception) {
        Console.log("Error in file watcher for $directoryPath: ${e.message}")
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
