package xyz.block.trailblaze.scripting

import java.io.File

/**
 * Shared test fixture for end-to-end tests that need a workspace trailmap authored in the
 * meta-only scripted-tool shape — a `<tool>.yaml` carrying only `script:` + `_meta:`
 * (no top-level `name:` / `inputSchema:`) plus a sibling `<tool>.ts` declaring the
 * tool via `trailblaze.tool<I, O>(handler)`. Consumed by:
 *
 *  - `CompileCommandTest` — exercises the CLI path through
 *    `AnalyzerScriptedToolEnrichment.resolveFromEnvironment()` →
 *    `TrailblazeCompiler.compile` with the analyzer wired.
 *  - `WorkspaceCompileBootstrapTest` — exercises the daemon-init recompile path
 *    against the same authoring shape.
 *
 * Both tests need byte-identical trailmap content (same `trailmap.yaml`, same `sampleTool.yaml`,
 * same `sampleTool.ts`) so the integration contract they validate is comparable —
 * any divergence between the two fixtures would muddy "did the failure come from the
 * CLI or the bootstrap layer?" diagnosis. Keeping the fixture in one place also means
 * a future schema tweak (new `_meta:` key, new authoring convention) touches one
 * helper rather than every consumer.
 */
internal object MetaOnlyDescriptorTestFixture {

  /**
   * Skip-message string shared by both consumer tests' `assumeTrue` gates. Calling out
   * the exact env var here means a developer reading test output learns the precise
   * fix ("set `TRAILBLAZE_SDK_DIR=<repo>/sdks/typescript`") rather than having to
   * grep the analyzer's resolution logic for the unobvious walk-up rule. The path is
   * OSS-canonical; readers in nested-checkout layouts add their own prefix per the
   * usual convention (same caveat the wikipedia README's intro carries).
   */
  const val ANALYZER_UNAVAILABLE_SKIP_MESSAGE: String =
    "Skipping: AnalyzerScriptedToolEnrichment.resolveFromEnvironment() returned null — " +
      "Node binary, SDK dir, shim, or ts-json-schema-generator missing. Set " +
      "TRAILBLAZE_SDK_DIR=<repo>/sdks/typescript locally to run this test."

  /**
   * Writes a meta-only trailmap named `metaonly` under [trailmapsDir] with one tool (`sampleTool`)
   * authored against the typed bare-function form. Returns the created trailmap directory so
   * callers can do further per-test setup (e.g., inspect emitted output, modify the source
   * for an invalidation test).
   *
   * The `.ts` source intentionally exercises a typed input interface (`SampleArgs.who:
   * string`) so the analyzer's name + inputSchema + description extraction has non-trivial
   * shape to work against — a no-input tool would short-circuit too much of the path.
   */
  fun writeMetaOnlyTrailmap(trailmapsDir: File): File {
    val trailmapDir = File(trailmapsDir, "metaonly").apply { mkdirs() }
    File(trailmapDir, "trailmap.yaml").writeText(
      """
      id: metaonly
      target:
        display_name: Meta Only
        tools:
          - sampleTool
      """.trimIndent(),
    )
    val toolsDir = File(trailmapDir, "tools").apply { mkdirs() }
    File(toolsDir, "sampleTool.yaml").writeText(
      """
      script: ./sampleTool.ts
      _meta:
        trailblaze/requiresContext: true
      """.trimIndent(),
    )
    File(toolsDir, "sampleTool.ts").writeText(
      """
      import { trailblaze } from "@trailblaze/scripting";

      export interface SampleArgs {
        /** Greeting target. */
        who: string;
      }

      /**
       * Sample tool used by the meta-only descriptor integration tests. Returns a
       * formatted greeting.
       */
      export const sampleTool = trailblaze.tool<SampleArgs>(async (input) => {
        return `hello, ${'$'}{input.who}`;
      });
      """.trimIndent(),
    )
    return trailmapDir
  }
}
