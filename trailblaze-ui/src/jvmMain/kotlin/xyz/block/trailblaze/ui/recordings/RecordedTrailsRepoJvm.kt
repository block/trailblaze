package xyz.block.trailblaze.ui.recordings

import xyz.block.trailblaze.logs.model.SessionInfo
import xyz.block.trailblaze.yaml.TrailConfig
import java.io.File

/**
 * JVM implementation of RecordingsRepo that saves recordings to the file system.
 *
 * @param trailsDirectory The directory where recordings will be saved.
 *                           Defaults to ~/.trailblaze/recordings
 */
class RecordedTrailsRepoJvm(
  private val trailsDirectory: File = File(System.getProperty("user.home"), ".trailblaze/trails")
) : RecordedTrailsRepo {

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
      val fileName = if (trailConfig.trailPath != null) {
        // Replace path separators with underscores for safe filenames
        "${trailConfig.trailPath}$suffix.trail.yaml"
      } else if (trailConfig.id != null) {
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

    // Determine the search pattern based on trailPath or id
    val searchPrefix = when {
      trailConfig.trailPath != null -> trailConfig.trailPath
      trailConfig.id != null -> trailConfig.id
      else -> return emptyList() // No way to identify recordings without trailPath or id
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
}
