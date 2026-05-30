package xyz.block.trailblaze.scripting

import xyz.block.trailblaze.config.InlineScriptToolConfig
import xyz.block.trailblaze.util.Console
import java.io.File

/**
 * Outcome of partitioning a list of QuickJS-bound scripted tools into "still go through
 * the bundler" vs "skipped by the import-closure analyzer."
 *
 *  - [toBundle] — the tools that should proceed to the real bundler. Order-preserving with
 *    respect to the input list. Includes tools whose author explicitly set
 *    `requiresHost: true` (their flag is honored as the author signal and they continue to
 *    bundle host-side for `client.callTool` composition).
 *  - [skippedNames] — names of tools the analyzer flagged for host-only. The runner logs
 *    a directed breadcrumb per skip (see [partitionByImportClosure]) and uses this list
 *    for the rollup line at the end of the partition step.
 */
data class HostOnlyPartition(
  val toBundle: List<InlineScriptToolConfig>,
  val skippedNames: List<String>,
)

/**
 * Splits [tools] into "still bundle for QuickJS" vs "auto-flipped to host-only by import
 * analysis" — see the `[#3190]` log breadcrumbs below for the upstream tracking pointer.
 *
 * **Author flag short-circuits the analyzer.** Tools with `requiresHost == true` skip the
 * analyzer entirely — the flag is the explicit author signal that the tool is host-only by
 * design (typically because it composes host-only Kotlin tools via `client.callTool`), and
 * the import-closure heuristic must not be allowed to second-guess that declaration. Those
 * tools go into [HostOnlyPartition.toBundle] so the existing
 * `requiresHost`-as-LLM-visibility-flag semantics continue to apply: bundle for the host's
 * QuickJS, register host-side, on-device launcher filters them at registration via the
 * same flag.
 *
 * **Analyzer-flagged tools are dropped from the bundling loop entirely.** Bundling them
 * would fail with a "Could not resolve" esbuild error AND tank every sibling tool in the
 * partition (the bundling loop has no per-tool error isolation). The directed breadcrumb
 * logged here tells the author what to change.
 *
 * @param log Logging seam — defaults to [Console.log]. Tests substitute a capturing
 *   lambda so assertions can pin the breadcrumb text without piping through a real
 *   Console sink.
 */
suspend fun partitionByImportClosure(
  tools: List<InlineScriptToolConfig>,
  analyzer: ScriptedToolImportAnalyzer,
  log: (String) -> Unit = { Console.log(it) },
): HostOnlyPartition {
  val skipped = mutableListOf<String>()
  val toBundle = mutableListOf<InlineScriptToolConfig>()
  for (tool in tools) {
    if (tool.requiresHost) {
      toBundle += tool
      continue
    }
    val verdict = analyzer.analyze(File(tool.script))
    if (verdict.requiresHost) {
      skipped += tool.name
      log(
        "[#3190] Tool '${tool.name}' was marked host-only because:\n" +
          "  ${verdict.reason}\n" +
          "This tool will skip registration in the QuickJS bundle for this session. " +
          "To run on-device, replace the host-only import with an on-device-compatible " +
          "alternative (QuickJS exposes `fetch` natively). To keep the tool available " +
          "host-side, add `runtime: subprocess` to the tool's YAML descriptor so it " +
          "loads in the bun subprocess instead.",
      )
      continue
    }
    toBundle += tool
  }
  if (skipped.isNotEmpty()) {
    log(
      "[#3190] ${skipped.size} tool(s) auto-flipped to host-only by import-closure " +
        "analysis: $skipped. ${toBundle.size} sibling tool(s) will still register.",
    )
  }
  return HostOnlyPartition(toBundle = toBundle, skippedNames = skipped)
}
