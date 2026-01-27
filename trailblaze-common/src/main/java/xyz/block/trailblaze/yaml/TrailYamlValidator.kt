package xyz.block.trailblaze.yaml

import java.io.File

/**
 * Utility object for validating trail.yaml files.
 * Provides static methods for finding and validating trail YAML files in a directory.
 */
object TrailYamlValidator {

  /**
   * Represents a validation issue for a trail.yaml file
   */
  data class ValidationIssue(
    val filePath: String,
    val errorMessage: String,
    val exception: Throwable
  )

  /**
   * Validates all *trail.yaml files in the given directory and returns a list of validation issues.
   * If the list is empty, all files are valid.
   *
   * @param directory The directory to search for trail.yaml files
   * @param parser Optional TrailblazeYaml instance to use for parsing. Defaults to TrailblazeYaml.Default
   * @return List of validation issues. Empty list means all files are valid.
   */
  fun validateTrailYamlFiles(
    directory: File,
    parser: TrailblazeYaml = TrailblazeYaml.Default
  ): List<ValidationIssue> {
    val trailFiles = findAllTrailYamlFiles(directory)
    return trailFiles.mapNotNull { file -> validateTrailFile(file, parser) }
  }

  /**
   * Recursively finds all *trail.yaml files in the given directory
   *
   * @param directory The directory to search
   * @return List of all trail.yaml files found
   */
  fun findAllTrailYamlFiles(directory: File): List<File> {
    val trailFiles = mutableListOf<File>()

    if (!directory.exists() || !directory.isDirectory) {
      return trailFiles
    }

    directory.walkTopDown()
      .onEnter { dir -> dir.name != "build" }
      .filter { it.isFile && it.name.endsWith("trail.yaml") }
      .forEach { trailFiles.add(it) }

    return trailFiles
  }

  /**
   * Attempts to parse a trail.yaml file and returns a ValidationIssue if parsing fails,
   * or null if successful.
   *
   * @param file The trail.yaml file to validate
   * @param parser Optional TrailblazeYaml instance to use for parsing. Defaults to TrailblazeYaml.Default
   * @return ValidationIssue if parsing failed, null if successful
   */
  fun validateTrailFile(
    file: File,
    parser: TrailblazeYaml = TrailblazeYaml.Default
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
