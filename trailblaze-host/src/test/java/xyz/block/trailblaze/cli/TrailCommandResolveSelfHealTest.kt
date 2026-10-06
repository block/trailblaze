package xyz.block.trailblaze.cli

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import picocli.CommandLine

/**
 * `trailblaze run`'s self-heal precedence — `--self-heal` > `TRAILBLAZE_SELF_HEAL_ENABLED` >
 * persisted `trailblaze config self-heal` > off — on both run paths.
 *
 * A delegated run resolves in two processes: the client puts the flag-or-env value on the
 * request it builds ([TrailCommand.delegatedRunRequest]) and the daemon applies [resolveSelfHeal]
 * to it. The daemon never sees the client's env, so the env tier only holds if the request
 * carries it.
 */
class TrailCommandResolveSelfHealTest {

  private fun parse(vararg argv: String): TrailCommand =
    TrailCommand().also { CommandLine(it).parseArgs(*argv, "any.trail.yaml") }

  private fun env(value: String?): (String) -> String? =
    { name -> if (name == "TRAILBLAZE_SELF_HEAL_ENABLED") value else null }

  /** The `selfHeal` on the request [command] sends the daemon for a delegated run. */
  private fun sentSelfHeal(command: TrailCommand, envValue: String?): Boolean? =
    command.delegatedRunRequest(
      file = File("any.trail.yaml"),
      yamlContent = "- prompts:\n  - step: do the thing\n",
      testName = "any",
      deviceSpec = null,
      useRecordedSteps = true,
      initialArgs = emptyMap(),
      envReader = env(envValue),
    ).selfHeal

  /** What a daemon whose persisted config is [persisted] applies to [command]'s delegated run. */
  private fun delegatedRunSelfHeal(command: TrailCommand, envValue: String?, persisted: Boolean?): Boolean =
    resolveSelfHeal(sentSelfHeal(command, envValue)) { persisted }

  /** The daemon checks the caller's workspace trailmaps, and can only find that workspace if told. */
  @Test
  fun `a delegated run carries the caller's TRAILBLAZE_CONFIG_DIR`() {
    val request = parse().delegatedRunRequest(
      file = File("any.trail.yaml"),
      yamlContent = "- prompts:\n  - step: do the thing\n",
      testName = "any",
      deviceSpec = null,
      useRecordedSteps = true,
      initialArgs = emptyMap(),
      envReader = { name -> if (name == "TRAILBLAZE_CONFIG_DIR") "/work/trailblaze-config" else null },
    )

    assertEquals("/work/trailblaze-config", request.callerConfigDir)
  }

  @Test
  fun `env false beats a persisted true on a delegated run`() {
    assertEquals(false, sentSelfHeal(parse(), envValue = "false"), "the request must carry the env value")
    assertFalse(delegatedRunSelfHeal(parse(), envValue = "false", persisted = true))
  }

  @Test
  fun `env true beats a persisted false on a delegated run`() {
    assertTrue(delegatedRunSelfHeal(parse(), envValue = "TRUE", persisted = false))
  }

  @Test
  fun `the flag beats the env on a delegated run`() {
    assertTrue(delegatedRunSelfHeal(parse("--self-heal=true"), envValue = "false", persisted = false))
    assertFalse(delegatedRunSelfHeal(parse("--self-heal=false"), envValue = "true", persisted = true))
  }

  @Test
  fun `with no flag and no env the daemon's persisted config decides`() {
    assertNull(sentSelfHeal(parse(), envValue = null), "the request must leave it to the daemon")
    assertTrue(delegatedRunSelfHeal(parse(), envValue = null, persisted = true))
    assertFalse(delegatedRunSelfHeal(parse(), envValue = null, persisted = null))
  }

  @Test
  fun `a malformed env value falls through to the persisted config`() {
    assertTrue(delegatedRunSelfHeal(parse(), envValue = "yes", persisted = true))
  }

  @Test
  fun `the in-process run applies the same precedence`() {
    val resolve = { argv: Array<String>, envValue: String?, persisted: Boolean? ->
      parse(*argv).resolveEffectiveSelfHeal(env(envValue)) { persisted }
    }
    assertFalse(resolve(emptyArray(), "false", true))
    assertTrue(resolve(emptyArray(), "true", false))
    assertTrue(resolve(arrayOf("--self-heal=true"), "false", false))
    assertEquals(true, resolve(emptyArray(), null, true))
    assertFalse(resolve(emptyArray(), null, null))
  }
}
