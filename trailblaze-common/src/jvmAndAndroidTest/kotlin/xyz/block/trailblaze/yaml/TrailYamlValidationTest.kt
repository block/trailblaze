package xyz.block.trailblaze.yaml

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Test
import xyz.block.trailblaze.util.GitUtils
import java.io.File
import kotlin.test.assertTrue
import xyz.block.trailblaze.util.Console

/**
 * Validates that all trail YAML files (trailblaze.yaml and *.trail.yaml) in the git repository
 * can be successfully parsed using the TrailblazeYaml parser.
 */
class TrailYamlValidationTest {

  @Test
  fun `validate all trail yaml files can be parsed`() = runBlocking {
    // Get the git repository root directory using the existing GitUtils utility
    val gitRootPath = GitUtils.getGitRootViaCommand()
      ?: error("Failed to determine git repository root")
    val gitRoot = File(gitRootPath)
    val trailFiles = TrailYamlValidator.findAllTrailYamlFiles(gitRoot)

    Console.log("Found ${trailFiles.size} trail YAML files to validate in git repository")

    // Parse all files in parallel using coroutines
    val results = trailFiles.map { file ->
      async(Dispatchers.Default) {
        TrailYamlValidator.validateTrailFile(file)
      }
    }.awaitAll()

    // Collect all failures
    val failures = results.filterNotNull()

    // Report results
    val successCount = results.size - failures.size
    Console.log("\n=== Trail YAML Validation Results ===")
    Console.log("Total files: ${trailFiles.size}")
    Console.log("Successful: $successCount")
    Console.log("Failed: ${failures.size}")

    // If there are failures, print details
    if (failures.isNotEmpty()) {
      Console.log("\n=== Failed Files ===")
      failures.forEachIndexed { index, failure ->
        Console.log("\n${index + 1}. ${failure.filePath}")
        Console.log("   Error: ${failure.errorMessage}")
        Console.log("   Exception: ${failure.exception::class.simpleName}: ${failure.exception.message}")
      }

      // Fail the test with a summary
      val failureMessage = buildString {
        appendLine("Failed to parse ${failures.size} trail YAML file(s):")
        failures.forEach { failure ->
          appendLine("  - ${failure.filePath}: ${failure.errorMessage}")
        }
      }
      assertTrue(false, failureMessage)
    } else {
      Console.log("\n✓ All trail YAML files parsed successfully!")
    }
  }
}
