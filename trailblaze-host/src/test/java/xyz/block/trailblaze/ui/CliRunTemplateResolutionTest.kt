package xyz.block.trailblaze.ui

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins load-time template resolution for YAML submitted to the daemon's `/cli/run` handler
 * ([resolveSubmittedTrailYaml], called from `handleCliRunRequest` before parsing).
 *
 * Regression guard: bare submissions (no `trailFilePath` — MCP/HTTP clients sending YAML content
 * directly) must get the same load-time resolution a CLI file run gets, against the daemon's own
 * environment. Before this seam existed, only file-backed submissions were resolved, so `{{VAR}}`
 * tokens in bare submissions survived to runtime where memory interpolation blanks unknown tokens
 * instead of resolving them.
 */
class CliRunTemplateResolutionTest {

  @Test
  fun `bare submission resolves env vars against the daemon environment`() {
    // PATH is set in every environment this suite runs in (dev machines and CI agents).
    val daemonEnvValue = System.getenv("PATH")
    assertTrue(!daemonEnvValue.isNullOrEmpty(), "PATH expected to be set in the test environment")

    val resolved = resolveSubmittedTrailYaml(
      yamlContent = "- step: Use {{PATH}} from the daemon env",
      trailFilePath = null,
    )

    assertEquals("- step: Use $daemonEnvValue from the daemon env", resolved)
  }

  @Test
  fun `bare submission resolves the CWD built-in`() {
    val resolved = resolveSubmittedTrailYaml(
      yamlContent = "- step: Load fixtures from {{CWD}}/fixtures",
      trailFilePath = null,
    )

    assertEquals("- step: Load fixtures from ${System.getProperty("user.dir")}/fixtures", resolved)
  }

  @Test
  fun `token absent from the daemon env survives as a literal`() {
    // Unique per run so no real environment can ever have it set.
    val unsetVariable = "TRAILBLAZE_UNSET_" + UUID.randomUUID().toString().replace("-", "_").uppercase()
    assertNull(
      System.getenv(unsetVariable),
      "test precondition: this variable must not be set in the environment",
    )
    val yaml = "- step: Sign in as {{$unsetVariable}}"

    val resolved = resolveSubmittedTrailYaml(yamlContent = yaml, trailFilePath = null)

    assertEquals(yaml, resolved)
  }

  @Test
  fun `file-backed submission re-attempts tokens the CLI could not resolve`() {
    // A CLI-delegated submission arrives pre-resolved, but a token the CLI could NOT resolve
    // survives as a literal — the daemon's resolve gives it a second chance against ITS env.
    val daemonEnvValue = System.getenv("PATH")
    assertTrue(!daemonEnvValue.isNullOrEmpty(), "PATH expected to be set in the test environment")

    val resolved = resolveSubmittedTrailYaml(
      yamlContent = "- step: Use {{PATH}} the CLI left unresolved",
      trailFilePath = "/some/workspace/example.trail.yaml",
    )

    assertEquals("- step: Use $daemonEnvValue the CLI left unresolved", resolved)
  }

  @Test
  fun `pre-resolved CLI-delegated submission passes through byte-identical`() {
    // The CLI resolves against its own environment before delegating, so a file-backed
    // submission arrives with its resolvable tokens already substituted; the daemon's
    // second resolve must not change a byte.
    val preResolved = "- step: Navigate to https://staging.example.com/dashboard"

    val resolved = resolveSubmittedTrailYaml(
      yamlContent = preResolved,
      trailFilePath = "/some/workspace/example.trail.yaml",
    )

    assertEquals(preResolved, resolved)
  }
}
