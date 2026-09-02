package xyz.block.trailblaze.cli

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import picocli.CommandLine
import xyz.block.trailblaze.logs.client.TrailblazeSerializationInitializer

/**
 * End-to-end guard for the `@trailblaze/scripting/matcher` specifier, run from a **consumer
 * repo layout** (`<root>/trailblaze-config/trailmaps/<id>/tools/`) rather than this repo's
 * `trails/config/` one.
 *
 * **Why the layout is the point.** Trailmaps are vendored byte-for-byte between repos. Before
 * the matcher subpath existed, the only way to reach the selector resolver from a trailmap
 * tool was a relative path into the SDK's source tree — and such a path is depth-sensitive.
 * One written from `trails/config/trailmaps/<id>/tools/` walks five levels back to reach the
 * framework repo root; from `trailblaze-config/trailmaps/<id>/tools/` those same five levels
 * land ABOVE the consumer's repo root. The identical file that passes in one layout fails on
 * arrival in the other with `Cannot find module`. A package specifier has no such coupling:
 * `PerTrailmapTsconfigEmitter` re-derives the `paths` mapping from the trailmap's actual
 * depth on every `trailblaze check`.
 *
 * So this test asserts the chain that a trailmap actually resolves through, at consumer depth,
 * in one shot — the two `dist/matcher.*` bundles, their staging into JAR resources, the
 * extraction into `<workspace>/.trailblaze/sdk/dist/`, the emitted `paths` glob, and both
 * resolutions of it — by running `trailblaze check` over a trailmap that imports the specifier
 * from a tool AND from a test. Both halves are needed to cover the shipped pair: `tsc` reads
 * `matcher.d.ts` and skips `*.test.ts`, so only the tool exercises the declaration bundle;
 * `bun test` loads `matcher.js`, so only the test exercises the runtime module.
 *
 * Note that the package's `exports` map is NOT in that chain: `trailblaze check`
 * resolves through the tsconfig glob into `dist/`, so this test still passes with the
 * `./matcher` entry missing or misspelled. `src/package-exports.test.ts` is what covers the map.
 *
 * Skips when `bun` isn't on PATH, matching `DaemonScriptedToolBundlerTest`'s treatment of
 * esbuild — the JVM suite is expected to run on a fresh checkout. The skip costs little: the
 * artifacts' presence in the JAR and their extraction are asserted without bun by
 * `WorkspaceTypeScriptSetupTest`, so what a skip gives up is only the part that genuinely
 * needs a JS runtime.
 */
class ScriptingMatcherSubpathTest {

  private val workDir: File = createTempDirectory("trailblaze-matcher-subpath-test").toFile()

  @AfterTest fun cleanup() {
    workDir.deleteRecursively()
    // `check` delegates its compile half to `CompileCommand`, which registers the workspace's
    // `*.tool.yaml` files on the process-global YAML-tool registry. Clear it so a later test
    // in the same JVM doesn't resolve names against this test's temp workspace.
    TrailblazeSerializationInitializer.registerWorkspaceYamlTools(emptyMap())
  }

  @Test
  fun `a trailmap in a consumer repo layout resolves the matcher subpath under bun`() {
    assumeTrue(
      "bun is not on PATH — `trailblaze check` cannot run the trailmap's tests. " +
        "Source bin/activate-hermit for the pinned bun.",
      CliPathUtils.isCommandOnPath("bun"),
    )
    val workspaceRoot = File(workDir, "consumer-repo").apply { mkdirs() }
    val toolsDir = File(workspaceRoot, "trailblaze-config/trailmaps/demo/tools").apply { mkdirs() }
    File(toolsDir.parentFile, "trailmap.yaml").writeText(
      """
      id: demo
      target:
        display_name: Demo
        platforms:
          android:
            app_ids: [com.example.demo]
      """.trimIndent() + "\n",
    )

    // A TOOL, not just a test, because the two halves of the shipped pair are checked by
    // different phases: `tsc` reads `matcher.d.ts` and only covers tool sources (the emitted
    // per-trailmap tsconfig excludes `*.test.ts`, which has no `bun:test` types under tsc),
    // while `bun test` loads `matcher.js`. Without a tool importing the subpath, a broken or
    // mis-generated declaration bundle ships green.
    File(toolsDir, "demo_matcherLabel.ts").writeText(
      """
      import { trailblaze } from "@trailblaze/scripting";
      import {
        resolve,
        resolveText,
        type TrailblazeNode,
        type TrailblazeNodeSelector,
      } from "@trailblaze/scripting/matcher";

      export interface DemoMatcherLabelArgs {
        /** Text the selector matches against, full-string. */
        text: string;
      }

      /**
       * Returns the text of the node a selector built from 'text' matches, or "noMatch".
       * Exists to type-check the `@trailblaze/scripting/matcher` declaration bundle.
       */
      export const demo_matcherLabel = trailblaze.tool<DemoMatcherLabelArgs>(
        { supportedPlatforms: ["android"] },
        async (args) => {
          const root: TrailblazeNode = {
            nodeId: 0,
            bounds: { left: 0, top: 0, right: 100, bottom: 100 },
            driverDetail: { class: "androidAccessibility", text: String(args.text) },
          };
          const selector: TrailblazeNodeSelector = {
            androidAccessibility: { textRegex: String(args.text) },
          };
          const result = resolve(root, selector);
          return result.kind === "singleMatch"
            ? String(resolveText(result.node.driverDetail))
            : "noMatch";
        },
      );
      """.trimIndent() + "\n",
    )

    // Exercises the four symbols a selector-matching trailmap test needs — the resolver, the
    // driver-detail text accessor, and both the node and selector types — so a barrel that
    // drops one of them fails here rather than in a consumer's CI.
    File(toolsDir, "matcher-subpath.test.ts").writeText(
      """
      import { expect, test } from "bun:test";
      import {
        resolve,
        resolveText,
        type TrailblazeNode,
        type TrailblazeNodeSelector,
      } from "@trailblaze/scripting/matcher";

      const root: TrailblazeNode = {
        nodeId: 0,
        bounds: { left: 0, top: 0, right: 100, bottom: 100 },
        driverDetail: { class: "androidAccessibility", text: "Root" },
        children: [
          {
            nodeId: 1,
            bounds: { left: 0, top: 10, right: 100, bottom: 30 },
            driverDetail: { class: "androidAccessibility", text: "Add item to cart" },
          },
        ],
      };

      test("the resolver matches a node the selector names", () => {
        const selector: TrailblazeNodeSelector = {
          androidAccessibility: { textRegex: "Add item to cart" },
        };
        const result = resolve(root, selector);
        expect(result.kind).toBe("singleMatch");
        if (result.kind !== "singleMatch") throw new Error("unreachable");
        expect(resolveText(result.node.driverDetail)).toBe("Add item to cart");
      });

      test("the resolver reports no match for a selector nothing satisfies", () => {
        // Proves the assertion above has power: the same call on a selector no node
        // satisfies must NOT come back as a match.
        const result = resolve(root, { androidAccessibility: { textRegex: "Remove item" } });
        expect(result.kind).toBe("noMatch");
      });
      """.trimIndent() + "\n",
    )

    val exit = CliCallerContext.withCallerCwd(workspaceRoot.toPath()) {
      CommandLine(CheckCommand()).execute()
    }

    assertEquals(
      0,
      exit,
      "`trailblaze check` should materialize the SDK, type-check the trailmap's tool against " +
        "`matcher.d.ts`, and run its bun test against `matcher.js`, from a consumer-repo layout",
    )

    // Both halves have to exist at the stem the EMITTED tsconfig points at — read the glob
    // target out of the generated file rather than hard-coding a depth, so this stays true if
    // the workspace-root anchoring for the `trailblaze-config/` layout ever moves.
    val tsconfig = File(toolsDir, "tsconfig.json")
    val globTarget = Regex(""""@trailblaze/scripting/\*"\s*:\s*\["([^"]+)/\*"]""")
      .find(tsconfig.readText())
      ?.groupValues
      ?.get(1)
    assertNotNull(globTarget, "expected a `@trailblaze/scripting/*` paths glob in ${tsconfig.absolutePath}")
    val sdkDist = File(toolsDir, globTarget).canonicalFile
    listOf("matcher.d.ts", "matcher.js").forEach { artifact ->
      assertTrue(
        File(sdkDist, artifact).isFile,
        "expected $artifact under the SDK dist the tsconfig glob names ($sdkDist); found: " +
          (sdkDist.list()?.sorted()?.joinToString() ?: "<no dir>"),
      )
    }
  }
}
