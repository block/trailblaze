package xyz.block.trailblaze.ui

import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.TemplatingUtil
import xyz.block.trailblaze.util.TrailYamlTemplateResolver
import java.io.File

/**
 * Load-time `{{VAR}}` template resolution for trail YAML submitted to the daemon (the `/cli/run`
 * handler, the desktop app's "Run trail" path, and MCP `runYaml` — all funnel through this).
 *
 * Applied unconditionally because a token that survives to runtime is not preserved:
 * [xyz.block.trailblaze.AgentMemory.interpolateVariables] blanks unknown tokens to `""` whenever
 * session memory is non-empty. General resolution mechanics (no-token short-circuit,
 * unresolvable tokens surviving as literals, `TRAILBLAZE_DEFERRED_VARIABLES`) are documented on
 * [TrailYamlTemplateResolver.resolve].
 *
 * Whose environment the tokens resolve against:
 * - **CLI-delegated submissions** ([trailFilePath] non-null): the CLI already resolved the YAML
 *   against ITS environment before delegating, so for every token the CLI could resolve, the
 *   CLI's env vars and CWD deliberately win — those tokens arrive already substituted and this
 *   second resolve never sees them. A token the CLI could NOT resolve (unset in the CLI's env,
 *   or deferred there) arrives as a `{{var}}` literal and IS re-attempted against the daemon's
 *   environment here — pre-existing behavior for file-backed submissions, unchanged by this seam.
 * - **Bare submissions** ([trailFilePath] null — desktop editor content, MCP `runYaml`, HTTP
 *   clients sending YAML directly): this is the only load-time resolution, so env vars and the
 *   `{{CWD}}` built-in resolve against the DAEMON process's environment. A token named like a
 *   daemon env var (`{{USER}}`, `{{HOME}}`, …) resolves here and never reaches runtime memory
 *   interpolation, shadowing any same-named memory seed; defer the name via
 *   `TRAILBLAZE_DEFERRED_VARIABLES` in the daemon's environment to keep it a runtime token.
 */
internal fun resolveSubmittedTrailYaml(yamlContent: String, trailFilePath: String?): String {
  val tokensBefore = TemplatingUtil.getRequiredTemplateVariables(yamlContent)
  if (tokensBefore.isEmpty()) return yamlContent

  val resolved = TrailYamlTemplateResolver.resolve(yamlContent, trailFilePath?.let(::File))

  // Token NAMES only — values may be secrets and must never reach the daemon log.
  val leftAsLiteral = TemplatingUtil.getRequiredTemplateVariables(resolved) intersect tokensBefore
  val substituted = tokensBefore - leftAsLiteral
  Console.log(
    "[CliRunTemplateResolution] load-time template resolution " +
      "(context=${if (trailFilePath != null) "cli-delegated" else "bare submission, daemon env"}): " +
      "substituted=$substituted leftAsLiteral=$leftAsLiteral",
  )
  return resolved
}
