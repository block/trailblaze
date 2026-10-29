package xyz.block.trailblaze.cli

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.scripting.WorkspaceSurveyBundleSource

/**
 * `trailblaze check`'s survey phase: a survey that runs mid-trail must be able to. Each failure
 * names the fix.
 */
class CheckCommandSurveyPhaseTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private val command = CheckCommand()

  private fun capturingStderr(block: () -> Unit): String {
    val captured = ByteArrayOutputStream()
    val original = System.err
    System.setErr(PrintStream(captured, true))
    try {
      block()
    } finally {
      System.setErr(original)
    }
    return captured.toString()
  }

  private fun workspace(surveys: String, name: String = "ws"): File {
    val root = tempFolder.newFolder(name)
    File(root, "trails/config/trailmaps/shop/surveys").mkdirs()
    File(root, "trails/config/trailmaps/shop/trailmap.yaml").writeText("id: shop\ntarget:\n  display_name: Shop\n")
    File(root, "trails/config/trailmaps/shop/surveys/shop.survey.ts").writeText(surveys.trimIndent())
    return root
  }

  private fun run(workspace: File, bundle: ((File) -> Unit)? = {}): Pair<Int, String> {
    var exit = -1
    val stderr = capturingStderr { exit = command.runSurveyLintPhase(workspace, bundle) }
    return exit to stderr
  }

  private val defaultSurvey = """
    import { trailblaze } from "@trailblaze/scripting/surveys";
    export const checkoutDone = trailblaze.survey({ feature: "shop.checkout", finding: { analytics: { name: "checkout_done" } } });
    export const crashed = trailblaze.survey({ feature: "shop.crash", requiresFinishedSession: true }, (s) =>
      s.summary.outcome === "FAILED" ? { summary: "failed", evidence: [] } : undefined,
    );
  """

  @Test
  fun `surveys that keep to their side of the line pass`() {
    val (exit, stderr) = run(workspace(defaultSurvey))
    assertEquals(CheckCommand.EXIT_OK, exit, stderr)
    assertFalse(stderr.contains("survey gate"), stderr)
  }

  @Test
  fun `a mid-trail survey that reads the outcome fails, naming the flag`() {
    val (exit, stderr) = run(
      workspace(
        """
        import { trailblaze } from "@trailblaze/scripting/surveys";
        export const passedRun = trailblaze.survey({ feature: "shop.run" }, (s) =>
          s.summary.outcome === "SUCCEEDED" ? { summary: "ok", evidence: [] } : undefined,
        );
        """,
      ),
    )
    assertEquals(CheckCommand.EXIT_TYPE_ERROR, exit)
    assertTrue(stderr.contains("Survey passedRun reads session.summary.outcome"), stderr)
    assertTrue(stderr.contains("Add requiresFinishedSession: true"), stderr)
  }

  @Test
  fun `a typed survey whose data type holds parentheses is still read`() {
    val (exit, stderr) = run(
      workspace(
        """
        import { trailblaze } from "@trailblaze/scripting/surveys";
        export const outcomes = trailblaze.survey<{ outcomes: ("ok" | "lost")[] }>({}, (session) =>
          session.tools.length > 0 ? { summary: "ran", evidence: [], data: { outcomes: ["ok"] } } : undefined,
        );
        """,
      ),
    )
    assertEquals(CheckCommand.EXIT_TYPE_ERROR, exit)
    assertTrue(stderr.contains("Survey outcomes reads session.tools"), stderr)
  }

  @Test
  fun `a mid-trail survey that reads the Trailblaze logs fails, naming them`() {
    val (exit, stderr) = run(
      workspace(
        """
        import { trailblaze } from "@trailblaze/scripting/surveys";
        export const healed = trailblaze.survey({}, (session) => {
          const heals = session.trailblazeLogs.filter({ type: "SelfHealInvokedLog" });
          return heals.length > 0 ? { summary: "healed", evidence: heals } : undefined;
        });
        """,
      ),
    )
    assertEquals(CheckCommand.EXIT_TYPE_ERROR, exit)
    assertTrue(stderr.contains("Survey healed reads session.trailblazeLogs"), stderr)
  }

  @Test
  fun `a mid-trail finding over tool calls fails, naming the flag`() {
    val (exit, stderr) = run(
      workspace(
        """
        import { trailblaze } from "@trailblaze/scripting/surveys";
        export const tapped = trailblaze.survey({ feature: "shop.tap", finding: { tools: { name: "tapOn" } } });
        """,
      ),
    )
    assertEquals(CheckCommand.EXIT_TYPE_ERROR, exit)
    assertTrue(stderr.contains("Survey tapped reads session.tools (a finding query)"), stderr)
  }

  @Test
  fun `a mid-trail survey reading tool calls through find fails, however find is written`() {
    val forms = mapOf(
      "arrow" to "find: (s) => s.tools.filter({ name: \"tapOn\" }).map((t) => ({ summary: \"tap\", evidence: [t] }))",
      "method" to "find(session) { return session.tools.filter({ name: \"tapOn\" }).map((t) => ({ summary: \"tap\", evidence: [t] })); }",
      "declarative" to "find: { tools: { name: \"tapOn\" } }",
    )
    for ((form, find) in forms) {
      val (exit, stderr) = run(
        workspace(
          """
          import { trailblaze } from "@trailblaze/scripting/surveys";
          export const tapped = trailblaze.survey({ feature: "shop.tap", $find });
          """,
          name = form,
        ),
      )
      assertEquals(CheckCommand.EXIT_TYPE_ERROR, exit, form)
      assertTrue(stderr.contains("Survey tapped reads session.tools"), "$form: $stderr")
    }
  }

  @Test
  fun `a survey still written with the old footprint key fails and says to use finding`() {
    val (exit, stderr) = run(
      workspace(
        """
        import { trailblaze } from "@trailblaze/scripting/surveys";
        export const paid = trailblaze.survey({ feature: "shop.pay", footprint: { network: { path: "/pay" } } });
        export const tapped = trailblaze.survey({ feature: "shop.tap", requiresFinishedSession: true, footprint: { tools: { name: "tapOn" } } });
        export const quoted = trailblaze.survey({ requiresFinishedSession: true, "footprint": { network: { path: "/q" } } });
        export const singleQuoted = trailblaze.survey({ requiresFinishedSession: true, 'footprint': { network: { path: "/s" } } });
        export const described = trailblaze.survey({ description: "footprint: in a string", finding: { network: { path: "/d" } } });
        export const payload = trailblaze.survey({ description: 'Payload, "footprint": metadata', finding: { network: { path: "/p" } } });
        const footprint = { network: { path: "/h" } };
        export const shorthand = trailblaze.survey({ requiresFinishedSession: true, footprint });
        """,
      ),
    )
    assertEquals(CheckCommand.EXIT_TYPE_ERROR, exit)
    assertTrue(stderr.contains("Survey quoted sets footprint, the old name of finding."), stderr)
    assertTrue(stderr.contains("Survey singleQuoted sets footprint, the old name of finding."), stderr)
    assertTrue(stderr.contains("Survey shorthand sets footprint, the old name of finding."), stderr)
    assertFalse(stderr.contains("Survey described"), stderr)
    assertFalse(stderr.contains("Survey payload"), stderr)
    assertTrue(stderr.contains("Survey paid sets footprint, the old name of finding. Rename the key to finding."), stderr)
    assertTrue(stderr.contains("Survey tapped sets footprint, the old name of finding. Rename the key to finding."), stderr)
  }

  @Test
  fun `a finding matching an analytics property named tools passes`() {
    val (exit, stderr) = run(
      workspace(
        """
        import { trailblaze } from "@trailblaze/scripting/surveys";
        export const edited = trailblaze.survey({
          feature: "shop.edit",
          finding: { analytics: { name: "opened", properties: { tools: "editor" } } },
        });
        """,
      ),
    )
    assertEquals(CheckCommand.EXIT_OK, exit, stderr)
  }

  @Test
  fun `a key named finding inside a finding's data is data, not another finding`() {
    val (exit, stderr) = run(
      workspace(
        """
        import { trailblaze } from "@trailblaze/scripting/surveys";
        export const reported = trailblaze.survey({
          description: "Reported a tap",
          finding: { network: { path: "/x" }, data: { finding: { tools: ["tapOn"] } } },
        });
        """,
      ),
    )
    assertEquals(CheckCommand.EXIT_OK, exit, stderr)
  }

  @Test
  fun `a predicate reading a field named tools on event data passes`() {
    val (exit, stderr) = run(
      workspace(
        """
        import { trailblaze } from "@trailblaze/scripting/surveys";
        export const edited = trailblaze.survey({
          feature: "shop.edit",
          finding: { analytics: { name: "opened", where: (e) => e.properties.tools.includes("editor") } },
        });
        export const opened = trailblaze.survey({ feature: "shop.open" }, (s) =>
          s.analytics.find({ where: (e) => e.properties.tools.length > 0 }) ? { summary: "opened", evidence: [] } : undefined,
        );
        """,
      ),
    )
    assertEquals(CheckCommand.EXIT_OK, exit, stderr)
  }

  @Test
  fun `an absent over tool calls fails`() {
    val (exit, stderr) = run(
      workspace(
        """
        import { trailblaze } from "@trailblaze/scripting/surveys";
        export const vetoed = trailblaze.survey({
          feature: "shop.vetoed",
          finding: { network: { path: "/a" }, absent: { deviceLog: { contains: "crash" } } },
        });
        """,
      ),
    )
    assertEquals(CheckCommand.EXIT_TYPE_ERROR, exit)
    assertTrue(stderr.contains("Survey vetoed reads session.deviceLog (a finding query)"), stderr)
  }

  @Test
  fun `a survey that reads end-only data behind session final passes`() {
    val (exit, stderr) = run(
      workspace(
        """
        import { trailblaze } from "@trailblaze/scripting/surveys";
        export const passedRun = trailblaze.survey({ feature: "shop.run" }, (s) =>
          s.final && s.summary.outcome === "SUCCEEDED" ? { summary: "ok", evidence: [] } : undefined,
        );
        """,
      ),
    )
    assertEquals(CheckCommand.EXIT_OK, exit, stderr)
  }

  @Test
  fun `two files exporting the same survey name fail, naming both`() {
    val ws = workspace(defaultSurvey)
    File(ws, "trails/config/surveys").mkdirs()
    File(ws, "trails/config/surveys/other.survey.ts").writeText(
      "export const checkoutDone = survey({ feature: \"other.checkout\", finding: { network: { path: \"/x\" } } });",
    )
    val (exit, stderr) = run(ws)
    assertEquals(CheckCommand.EXIT_TYPE_ERROR, exit)
    assertTrue(
      stderr.contains("Survey checkoutDone is exported by surveys/other.survey.ts and trailmaps/shop/surveys/shop.survey.ts"),
      stderr,
    )
  }

  @Test
  fun `a final inside a string is not a guard`() {
    val (exit, stderr) = run(
      workspace(
        """
        import { trailblaze } from "@trailblaze/scripting/surveys";
        export const tapped = trailblaze.survey({ feature: "checkout.final" }, (s) =>
          s.tools.find({ name: "tapOn" }) ? { summary: "tapped", evidence: [] } : undefined,
        );
        """,
      ),
    )
    assertEquals(CheckCommand.EXIT_TYPE_ERROR, exit)
    assertTrue(stderr.contains("Survey tapped reads session.tools"), stderr)
  }

  @Test
  fun `a read inside a template interpolation counts`() {
    val (exit, stderr) = run(
      workspace(
        """
        import { trailblaze } from "@trailblaze/scripting/surveys";
        export const described = trailblaze.survey({ feature: "shop.run" }, (s) =>
          ({ summary: `ran: ${'$'}{s.summary.outcome}`, evidence: [] }),
        );
        """,
      ),
    )
    assertEquals(CheckCommand.EXIT_TYPE_ERROR, exit)
    assertTrue(stderr.contains("Survey described reads session.summary.outcome"), stderr)
  }

  @Test
  fun `a helper declared after a mid-trail survey is not read as its own`() {
    val (exit, stderr) = run(
      workspace(
        """
        import { trailblaze } from "@trailblaze/scripting/surveys";
        export const checkoutDone = trailblaze.survey({ feature: "shop.checkout", finding: { analytics: { name: "checkout_done" } } });
        const outcomeOf = (s) => s.summary.outcome;
        export const crashed = trailblaze.survey({ feature: "shop.crash", requiresFinishedSession: true }, (s) =>
          outcomeOf(s) === "FAILED" ? { summary: "failed", evidence: [] } : undefined,
        );
        """,
      ),
    )
    assertEquals(CheckCommand.EXIT_OK, exit, stderr)
  }

  @Test
  fun `a commented-out export is not a duplicate`() {
    val ws = workspace(defaultSurvey)
    File(ws, "trails/config/surveys").mkdirs()
    File(ws, "trails/config/surveys/old.survey.ts").writeText(
      "/* export const checkoutDone = survey({ feature: \"old\", finding: { network: { path: \"/x\" } } }); */\n",
    )
    val (exit, stderr) = run(ws)
    assertEquals(CheckCommand.EXIT_OK, exit, stderr)
  }

  @Test
  fun `a file that does not build for the sandbox fails, naming both fixes and the split`() {
    val (exit, stderr) = run(workspace(defaultSurvey), bundle = { error("Could not resolve \"node:fs\"") })
    assertEquals(CheckCommand.EXIT_TYPE_ERROR, exit)
    assertTrue(stderr.contains("checkoutDone runs mid-trail"), stderr)
    assertTrue(stderr.contains("requiresFinishedSession: true"), stderr)
    assertTrue(stderr.contains("requiresHost tool"), stderr)
    assertTrue(stderr.contains("move those to their own file"), stderr)
    assertTrue(stderr.contains("Could not resolve \"node:fs\""), stderr)
  }

  @Test
  fun `the real sandbox build refuses a mid-trail survey that imports node fs`() {
    val bundler = WorkspaceSurveyBundleSource.resolvedToolchainBundler(cacheDir = tempFolder.newFolder("cache"))
    assumeTrue("needs esbuild and the TypeScript SDK (bun install in sdks/typescript)", bundler != null)
    val ws = workspace(
      """
      import { readFileSync } from "node:fs";
      import { trailblaze } from "@trailblaze/scripting/surveys";
      export const fromDisk = trailblaze.survey({ feature: "shop.disk" }, () => (readFileSync("/x") ? undefined : undefined));
      """,
    )
    val (exit, stderr) = run(ws, bundle = { kotlinx.coroutines.runBlocking { bundler!!.bundleSurvey(it) } })
    assertEquals(CheckCommand.EXIT_TYPE_ERROR, exit, stderr)
    assertTrue(stderr.contains("fromDisk runs mid-trail"), stderr)

    // The same file passes once the survey says it needs the finished session.
    File(ws, "trails/config/trailmaps/shop/surveys/shop.survey.ts").writeText(
      File(ws, "trails/config/trailmaps/shop/surveys/shop.survey.ts").readText()
        .replace("feature: \"shop.disk\"", "feature: \"shop.disk\", requiresFinishedSession: true"),
    )
    assertEquals(CheckCommand.EXIT_OK, run(ws, bundle = { kotlinx.coroutines.runBlocking { bundler!!.bundleSurvey(it) } }).first)
  }

  @Test
  fun `the real sandbox build refuses a mid-trail survey that imports node process`() {
    val bundler = WorkspaceSurveyBundleSource.resolvedToolchainBundler(cacheDir = tempFolder.newFolder("cache"))
    assumeTrue("needs esbuild and the TypeScript SDK (bun install in sdks/typescript)", bundler != null)
    val ws = workspace(
      """
      import process from "node:process";
      import { trailblaze } from "@trailblaze/scripting/surveys";
      export const fromEnv = trailblaze.survey({ feature: "shop.env" }, () => (process.env.X ? undefined : undefined));
      """,
    )
    val (exit, stderr) = run(ws, bundle = { kotlinx.coroutines.runBlocking { bundler!!.bundleSurvey(it) } })
    assertEquals(CheckCommand.EXIT_TYPE_ERROR, exit, stderr)
    assertTrue(stderr.contains("fromEnv runs mid-trail"), stderr)
  }
}
