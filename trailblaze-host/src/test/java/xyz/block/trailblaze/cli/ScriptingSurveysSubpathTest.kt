package xyz.block.trailblaze.cli

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Assume.assumeTrue
import picocli.CommandLine
import xyz.block.trailblaze.logs.client.TrailblazeSerializationInitializer

/**
 * End-to-end guard for the `@trailblaze/scripting/surveys` specifier from a **consumer repo
 * layout**, the surveys twin of [ScriptingMatcherSubpathTest]. A repo that owns a trailmap
 * typechecks and tests that trailmap's surveys against the `dist/surveys.{d.ts,js}` pair
 * `trailblaze check` materializes, so this proves the pair compiles and loads there rather than
 * only that the files exist (which `WorkspaceTypeScriptSetupTest` covers without bun).
 *
 * A tool reads the declaration bundle through `tsc`; a bun test imports the runtime module and runs
 * a survey over a session directory. The tool's import is type-only on purpose: the survey runtime
 * reads sessions through `node:fs`, which a tool must not carry into its bundle, while a type
 * import is still resolved and checked against `surveys.d.ts`.
 *
 * Skips when `bun` isn't on PATH, for the same reason as [ScriptingMatcherSubpathTest].
 */
class ScriptingSurveysSubpathTest {

  private val workDir: File = createTempDirectory("trailblaze-surveys-subpath-test").toFile()

  @AfterTest fun cleanup() {
    workDir.deleteRecursively()
    TrailblazeSerializationInitializer.registerWorkspaceYamlTools(emptyMap())
  }

  @Test
  fun `a trailmap in a consumer repo layout typechecks and runs a survey from the surveys subpath`() {
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

    File(toolsDir, "demo_surveyOutcome.ts").writeText(
      """
      import { trailblaze } from "@trailblaze/scripting";
      import type { FindingInput, SurveySession } from "@trailblaze/scripting/surveys";

      export interface DemoSurveyOutcomeArgs {
        /** Outcome to describe. */
        outcome: string;
      }

      /**
       * Returns the summary a survey would report for 'outcome'.
       * Exists to type-check the `@trailblaze/scripting/surveys` declaration bundle.
       */
      export const demo_surveyOutcome = trailblaze.tool<DemoSurveyOutcomeArgs>(
        { supportedPlatforms: ["android"] },
        async (args) => {
          const describe = (summary: Pick<SurveySession["summary"], "outcome">): Pick<FindingInput, "summary"> => ({
            summary: `Ended ${'$'}{summary.outcome}`,
          });
          return describe({ outcome: String(args.outcome) }).summary;
        },
      );
      """.trimIndent() + "\n",
    )

    File(toolsDir, "surveys-subpath.test.ts").writeText(
      """
      import { afterAll, expect, test } from "bun:test";
      import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
      import { tmpdir } from "node:os";
      import { join } from "node:path";
      import { SurveySession, runSurvey, trailblaze } from "@trailblaze/scripting/surveys";

      const LOG = "xyz.block.trailblaze.logs.client.TrailblazeLog";
      const root = mkdtempSync(join(tmpdir(), "surveys-subpath-"));
      afterAll(() => rmSync(root, { recursive: true, force: true }));

      function sessionWithTool(name: string): SurveySession {
        const dir = join(root, name);
        mkdirSync(dir, { recursive: true });
        writeFileSync(
          join(dir, "001_TrailblazeToolLog.json"),
          JSON.stringify({
            class: `${'$'}{LOG}.TrailblazeToolLog`,
            session: name,
            timestamp: new Date(1_700_000_000_000).toISOString(),
            toolName: name,
            trailblazeTool: { toolName: name, raw: {} },
            successful: true,
          }),
        );
        return SurveySession.load(dir);
      }

      const tapped = trailblaze.survey({ feature: "demo.tap", description: "The trail tapped" }, (session) => {
        const tap = session.tools.find({ name: "tapOn" });
        return tap ? { summary: "Tapped", evidence: [tap] } : undefined;
      });

      test("a survey built from the subpath reports the feature a session exercised", async () => {
        const findings = await runSurvey(tapped, sessionWithTool("tapOn"), "tapped");
        expect(findings.map((f) => f.summary)).toEqual(["Tapped"]);
      });

      test("the same survey stays silent on a session that never exercised it", async () => {
        // Proves the assertion above has power.
        expect(await runSurvey(tapped, sessionWithTool("swipe"), "tapped")).toEqual([]);
      });
      """.trimIndent() + "\n",
    )

    val exit = CliCallerContext.withCallerCwd(workspaceRoot.toPath()) {
      CommandLine(CheckCommand()).execute()
    }

    assertEquals(
      0,
      exit,
      "`trailblaze check` should type-check the trailmap's tool against `surveys.d.ts` and run its " +
        "bun test against `surveys.js`, from a consumer-repo layout",
    )
  }
}
