package xyz.block.trailblaze.yaml

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Test
import xyz.block.trailblaze.util.GitUtils
import java.io.File
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import xyz.block.trailblaze.util.Console

/**
 * Validates that every trail YAML file (trailblaze.yaml and *.trail.yaml) in the git repository
 * parses under the STRICT parser — unknown keys (typos, stale/removed fields, mis-nested
 * selectors) fail the build instead of being silently dropped at decode. This is the repo gate
 * that keeps malformed trails from ever landing.
 *
 * Coverage boundary: strictness only bites on CLOSED shapes the parser has a serializer for —
 * trail structure (config/step/recording keys), selectors, and class-backed tools registered on
 * this test's classpath. A tool call whose name isn't on the classpath (e.g. workspace-local
 * tools declared under `trails/config/trailmaps`, which `:trailblaze-common:jvmTest` doesn't load)
 * decodes to [xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool], which stores its args as
 * a raw open map — so an unknown/misspelled ARG on such a tool is NOT caught here. Those tool-arg
 * types are checked separately by `trailblaze check`'s recording type-validation against each
 * trailmap's generated `tools/trailblaze-client.d.ts`.
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

    // Strict parser: kaml throws UnknownPropertyException on any key outside a closed shape.
    // Immutable/stateless for decoding, so one instance is safe to share across coroutines.
    val strictParser = createTrailblazeYaml(strict = true)

    // Parse all files in parallel using coroutines
    val results = trailFiles.map { file ->
      async(Dispatchers.Default) {
        TrailYamlValidator.validateTrailFile(file, strictParser)
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

  /**
   * Guards the gate itself: proves the strict parser actually REJECTS an unknown key, and that the
   * lenient default silently accepts the same input. Without this, the corpus test above would
   * still pass if strictness ever regressed to lenient (a clean corpus fails 0 files either way),
   * leaving the gate silently dead. `bogusUnknownArg` on a registered tool is the known-bad input;
   * the assertion keys off that name, not the full wording, so it survives message rewording.
   */
  @Test
  fun `strict parser rejects unknown keys but lenient accepts them`() {
    val knownBad = """
      - config:
          id: probe/strict-gate
          title: probe
          target: square
          platform: ios
          driver: IOS_HOST
      - prompts:
        - step: s
          recording:
            tools:
            - assertVisibleBySelector:
                reason: r
                nodeSelector:
                  iosMaestro:
                    textRegex: More
                bogusUnknownArg: 1
    """.trimIndent()

    val strictFailure = assertFailsWith<Exception> {
      createTrailblazeYaml(strict = true).decodeTrailDocument(knownBad)
    }
    assertTrue(
      strictFailure.message?.contains("bogusUnknownArg") == true,
      "Strict parse should fail because of the unknown key, but was: ${strictFailure.message}",
    )

    // The lenient default drops the unknown key instead of throwing — this is what strictness fixes.
    createTrailblazeYaml().decodeTrailDocument(knownBad)
  }
}
