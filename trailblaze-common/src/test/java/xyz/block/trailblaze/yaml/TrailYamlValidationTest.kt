package xyz.block.trailblaze.yaml

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Test
import xyz.block.trailblaze.util.GitUtils
import java.io.File
import kotlin.test.assertTrue

/**
 * Validates that all *trail.yaml files in the git repository can be successfully parsed
 * using the TrailblazeYaml parser.
 */
class TrailYamlValidationTest {

  @Test
  fun `validate all trail yaml files can be parsed`() = runBlocking {
    // Get the git repository root directory using the existing GitUtils utility
    val gitRootPath = GitUtils.getGitRootViaCommand()
      ?: error("Failed to determine git repository root")
    val gitRoot = File(gitRootPath)
    val trailFiles = TrailYamlValidator.findAllTrailYamlFiles(gitRoot)

    println("Found ${trailFiles.size} trail.yaml files to validate in git repository")

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
    println("\n=== Trail YAML Validation Results ===")
    println("Total files: ${trailFiles.size}")
    println("Successful: $successCount")
    println("Failed: ${failures.size}")

    // If there are failures, print details
    if (failures.isNotEmpty()) {
      println("\n=== Failed Files ===")
      failures.forEachIndexed { index, failure ->
        println("\n${index + 1}. ${failure.filePath}")
        println("   Error: ${failure.errorMessage}")
        println("   Exception: ${failure.exception::class.simpleName}: ${failure.exception.message}")
      }

      // Fail the test with a summary
      val failureMessage = buildString {
        appendLine("Failed to parse ${failures.size} trail.yaml file(s):")
        failures.forEach { failure ->
          appendLine("  - ${failure.filePath}: ${failure.errorMessage}")
        }
      }
      assertTrue(false, failureMessage)
    } else {
      println("\n✓ All trail.yaml files parsed successfully!")
    }
  }
}
