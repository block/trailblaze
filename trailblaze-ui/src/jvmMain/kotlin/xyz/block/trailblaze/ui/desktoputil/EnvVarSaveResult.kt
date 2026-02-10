package xyz.block.trailblaze.ui.desktoputil

import java.io.File

/**
 * Result of attempting to save an environment variable to the shell profile.
 */
sealed class EnvVarSaveResult {
  data class Success(val filePath: String) : EnvVarSaveResult()

  data class Error(val message: String) : EnvVarSaveResult()
}

/**
 * Saves an environment variable to the user's shell profile by appending an export statement.
 *
 * @param variableName The name of the environment variable (e.g., "OPENAI_API_KEY")
 * @param value The value to set
 * @param shellProfile The shell profile file to write to (e.g., ~/.zshrc)
 * @param comment Optional comment to add above the export statement
 * @return [EnvVarSaveResult.Success] if saved successfully, [EnvVarSaveResult.Error] otherwise
 */
fun saveEnvVarToShellProfile(
  variableName: String,
  value: String,
  shellProfile: File?,
  comment: String = "Added by Trailblaze Desktop",
): EnvVarSaveResult {
  if (value.isBlank()) {
    return EnvVarSaveResult.Error("Value cannot be empty")
  }

  if (shellProfile == null) {
    return EnvVarSaveResult.Error("Could not determine shell profile location")
  }

  return try {
    // Create the file if it doesn't exist
    if (!shellProfile.exists()) {
      shellProfile.createNewFile()
    }

    // Check if the variable is already set in the file
    val existingContent = shellProfile.readText()
    if (existingContent.contains("export $variableName=")) {
      return EnvVarSaveResult.Error(
        "$variableName is already defined in ${shellProfile.name}. " +
          "Please edit the file manually to update it."
      )
    }

    // Append the export statement
    val exportLine = "\n# $comment\nexport $variableName=\"$value\"\n"
    shellProfile.appendText(exportLine)

    EnvVarSaveResult.Success(shellProfile.absolutePath)
  } catch (e: Exception) {
    EnvVarSaveResult.Error("${e.javaClass.simpleName}: ${e.message}")
  }
}
