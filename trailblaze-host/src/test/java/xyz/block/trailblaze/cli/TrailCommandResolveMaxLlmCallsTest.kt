package xyz.block.trailblaze.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import picocli.CommandLine

/**
 * Precedence behavior of [TrailCommand.resolveEffectiveMaxLlmCalls]:
 *
 *   CLI flag  >  TRAILBLAZE_MAX_LLM_CALLS env var  >  workspace trailblaze.yaml
 *      defaults.maxLlmCalls  >  persisted CliConfigHelper config  >  null (runner default)
 *
 * Each test pins exactly one tier and asserts which value the resolver returns. The
 * testable overload (see [TrailCommand.resolveEffectiveMaxLlmCalls] with explicit `cwd`,
 * `envReader`, `persistedConfigReader`) lets us drive each tier without touching real
 * process state.
 */
class TrailCommandResolveMaxLlmCallsTest {

  @get:Rule val tempFolder = TemporaryFolder()

  /**
   * Lays out a `trails/config/trailblaze.yaml` under [tempFolder.root] with the given
   * `defaults.maxLlmCalls` value (or leaves the field out when null). Returns the
   * workspace root path the resolver should be pointed at via the `cwd` override.
   */
  private fun writeWorkspaceConfig(maxLlmCalls: Int?): java.nio.file.Path {
    val configDir = File(tempFolder.root, "trails/config").apply { mkdirs() }
    val yaml = if (maxLlmCalls == null) {
      "defaults:\n  target: sampleapp\n"
    } else {
      "defaults:\n  max-llm-calls: $maxLlmCalls\n"
    }
    File(configDir, "trailblaze.yaml").writeText(yaml)
    return tempFolder.root.toPath()
  }

  private fun parseCmd(vararg argv: String): TrailCommand {
    val cmd = TrailCommand()
    CommandLine(cmd).parseArgs(*argv, "any.trail.yaml")
    return cmd
  }

  @Test
  fun `CLI flag wins over everything else`() {
    val cmd = parseCmd("--max-llm-calls", "10")
    val cwd = writeWorkspaceConfig(maxLlmCalls = 20)
    val result = cmd.resolveEffectiveMaxLlmCalls(
      cwd = cwd,
      envReader = { "30" },
      persistedConfigReader = { 40 },
    )
    assertEquals(10, result)
  }

  @Test
  fun `env var wins when CLI flag is absent`() {
    val cmd = parseCmd()
    val cwd = writeWorkspaceConfig(maxLlmCalls = 20)
    val result = cmd.resolveEffectiveMaxLlmCalls(
      cwd = cwd,
      envReader = { name -> if (name == "TRAILBLAZE_MAX_LLM_CALLS") "30" else null },
      persistedConfigReader = { 40 },
    )
    assertEquals(30, result)
  }

  @Test
  fun `workspace yaml wins when CLI flag and env var are absent`() {
    val cmd = parseCmd()
    val cwd = writeWorkspaceConfig(maxLlmCalls = 20)
    val result = cmd.resolveEffectiveMaxLlmCalls(
      cwd = cwd,
      envReader = { null },
      persistedConfigReader = { 40 },
    )
    assertEquals(20, result)
  }

  @Test
  fun `persisted config wins when CLI, env, and workspace are silent`() {
    val cmd = parseCmd()
    val cwd = writeWorkspaceConfig(maxLlmCalls = null)
    val result = cmd.resolveEffectiveMaxLlmCalls(
      cwd = cwd,
      envReader = { null },
      persistedConfigReader = { 40 },
    )
    assertEquals(40, result)
  }

  @Test
  fun `returns null when every tier is silent`() {
    val cmd = parseCmd()
    // No workspace file at all — point cwd at an empty temp dir.
    val emptyDir = tempFolder.newFolder("empty").toPath()
    val result = cmd.resolveEffectiveMaxLlmCalls(
      cwd = emptyDir,
      envReader = { null },
      persistedConfigReader = { null },
    )
    assertNull(result)
  }

  @Test
  fun `malformed env var is skipped with the next tier honored`() {
    val cmd = parseCmd()
    val cwd = writeWorkspaceConfig(maxLlmCalls = 20)
    val result = cmd.resolveEffectiveMaxLlmCalls(
      cwd = cwd,
      envReader = { "not-a-number" },
      persistedConfigReader = { 40 },
    )
    // env value rejected → workspace tier wins
    assertEquals(20, result)
  }

  @Test
  fun `zero env var is skipped - rejected as non-positive`() {
    val cmd = parseCmd()
    val cwd = writeWorkspaceConfig(maxLlmCalls = 20)
    val result = cmd.resolveEffectiveMaxLlmCalls(
      cwd = cwd,
      envReader = { "0" },
      persistedConfigReader = { 40 },
    )
    assertEquals(20, result)
  }

  @Test
  fun `non-positive persisted value is skipped - resolver falls through to null`() {
    // Regression: the persisted-config tier was previously returned raw, so a manually-
    // corrupted settings.json (e.g. -3 or 0) would propagate to RunYamlRequest and trip
    // its init-time `require` with an IllegalArgumentException. The validator now skips
    // bad persisted values with a one-line warning and falls through to null.
    val cmd = parseCmd()
    val emptyDir = tempFolder.newFolder("empty").toPath()
    assertNull(
      cmd.resolveEffectiveMaxLlmCalls(
        cwd = emptyDir,
        envReader = { null },
        persistedConfigReader = { -3 },
      ),
    )
    assertNull(
      cmd.resolveEffectiveMaxLlmCalls(
        cwd = emptyDir,
        envReader = { null },
        persistedConfigReader = { 0 },
      ),
    )
  }

  @Test
  fun `malformed workspace value is skipped with persisted config honored`() {
    val cmd = parseCmd()
    // Pin maxLlmCalls = -5 directly in the YAML — bypasses the kotlinx-serialization layer
    // since Int? doesn't constrain sign. The resolver's `parsePositiveIntOrWarn` skips it
    // and falls through to persisted config.
    val configDir = File(tempFolder.root, "trails/config").apply { mkdirs() }
    File(configDir, "trailblaze.yaml").writeText("defaults:\n  max-llm-calls: -5\n")
    val result = cmd.resolveEffectiveMaxLlmCalls(
      cwd = tempFolder.root.toPath(),
      envReader = { null },
      persistedConfigReader = { 40 },
    )
    assertEquals(40, result)
  }
}
