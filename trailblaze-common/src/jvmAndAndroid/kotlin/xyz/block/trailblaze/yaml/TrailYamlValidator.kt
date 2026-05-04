package xyz.block.trailblaze.yaml

import xyz.block.trailblaze.config.project.TrailDiscovery
import xyz.block.trailblaze.recordings.TrailRecordings
import java.io.File

/**
 * Utility object for validating trail YAML files discovered in a workspace.
 */
object TrailYamlValidator {

  /**
   * Represents a validation issue for a trail YAML file.
   */
  data class ValidationIssue(
    val filePath: String,
    val errorMessage: String,
    val exception: Throwable
  )

  /**
   * Validates all trail YAML files in the given directory and returns a list of validation issues.
   * If the list is empty, all files are valid.
   *
   * @param directory The directory to search for trail YAML files
   * @param parser Optional TrailblazeYaml instance to use for parsing. Defaults to createTrailblazeYaml()
   * @return List of validation issues. Empty list means all files are valid.
   */
  fun validateTrailYamlFiles(
    directory: File,
    parser: TrailblazeYaml = createTrailblazeYaml()
  ): List<ValidationIssue> {
    val trailFiles = findAllTrailYamlFiles(directory)
    return trailFiles.mapNotNull { file -> validateTrailFile(file, parser) }
  }

  /**
   * Recursively finds all trail YAML files in the given directory.
   *
   * @param directory The directory to search
   * @return List of all trail YAML files found
   */
  fun findAllTrailYamlFiles(directory: File): List<File> =
    if (!directory.exists() || !directory.isDirectory) emptyList()
    else {
      TrailDiscovery
        .discoverTrailFiles(directory.toPath())
        .filterNot(::isProjectConfigFile)
    }

  /**
   * Returns true if this `trailblaze.yaml` is acting as project/workspace config rather than a
   * trail definition. Covers both populated project configs (`llm:`, `targets:`, etc.) and
   * comment-only / otherwise empty workspace anchors.
   */
  private fun isProjectConfigFile(file: File): Boolean {
    if (file.name != TrailRecordings.TRAILBLAZE_DOT_YAML) return false
    return try {
      val meaningfulLines = file.readLines()
        .map { it.substringBefore("#").trim() }
        .filter { it.isNotEmpty() }
      if (meaningfulLines.isEmpty()) return true
      meaningfulLines.any { line ->
        line.startsWith("defaults:") ||
          line.startsWith("packs:") ||
          line.startsWith("targets:") ||
          line.startsWith("toolsets:") ||
          line.startsWith("tools:") ||
          line.startsWith("providers:") ||
          line.startsWith("llm:")
      }
    } catch (_: Exception) {
      false
    }
  }

  /**
   * Attempts to parse a trail YAML file and returns a ValidationIssue if parsing fails,
   * or null if successful.
   *
   * @param file The trail YAML file to validate
   * @param parser Optional TrailblazeYaml instance to use for parsing. Defaults to createTrailblazeYaml()
   * @return ValidationIssue if parsing failed, null if successful
   */
  fun validateTrailFile(
    file: File,
    parser: TrailblazeYaml = createTrailblazeYaml()
  ): ValidationIssue? {
    return try {
      val yamlContent = file.readText()
      parser.decodeTrail(yamlContent)
      null // Success
    } catch (e: Exception) {
      ValidationIssue(
        filePath = file.path,
        errorMessage = e.message ?: "Unknown error",
        exception = e
      )
    }
  }
}
