package xyz.block.trailblaze.scripting

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.surveys.SurveyBundles

class RunWarningSurveysTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private fun workspace(vararg surveys: Pair<String, String>): File {
    val config = tempFolder.newFolder("config")
    surveys.forEach { (path, text) -> File(config, path).apply { parentFile.mkdirs() }.writeText(text) }
    return config
  }

  private val session: List<File> get() = listOf(tempFolder.newFolder())

  @Test
  fun `a workspace's warning surveys run over the report's sessions, and store what they find`() {
    val config = workspace(
      "surveys/crashes.survey.ts" to
        "export const crashes = trailblaze.survey({ warning: true, requiresFinishedSession: true }, () => undefined);",
      "trailmaps/shop/surveys/journeys.survey.ts" to
        "export const journeys = trailblaze.survey({ requiresFinishedSession: true }, () => undefined);",
    )
    val sessionDirs = listOf(tempFolder.newFolder(), tempFolder.newFolder())
    val calls = mutableListOf<List<String>>()

    val ran = RunWarningSurveys.run(sessionDirs, config) { args -> calls += args; 0 }

    assertEquals(listOf("crashes"), ran)
    val args = calls.single()
    assertTrue("--warnings" in args && "--write" in args, "only warning surveys run, and their findings are stored: $args")
    // No warning survey is nothing to fix; a failed stream no pass has reported yet is this one's to say.
    assertTrue("--allow-none" in args && "--allow-stream-errors" !in args, "$args")
    val afterStreams = mutableListOf<List<String>>()
    RunWarningSurveys.run(sessionDirs, config, streamsReported = true) { a -> afterStreams += a; 0 }
    assertTrue("--allow-stream-errors" in afterStreams.single(), "a streams pass already reported them: ${afterStreams.single()}")
    assertEquals(sessionDirs.map { it.absolutePath }, args.withIndex().filter { (i, _) -> i > 0 && args[i - 1] == "--sessions" }.map { it.value })
  }

  @Test
  fun `a workspace with no warning survey starts nothing`() {
    val config = workspace(
      "surveys/journeys.survey.ts" to
        "export const journeys = trailblaze.survey({ requiresFinishedSession: true, description: \"warning: true\" }, () => undefined);",
    )
    var started = false

    assertEquals(emptyList(), RunWarningSurveys.run(session, config) { _ -> started = true; 0 })
    assertEquals(false, started)
    assertEquals(emptyList(), RunWarningSurveys.run(session, configDir = null) { _ -> started = true; 0 })
    assertEquals(false, started)
  }

  @Test
  fun `a survey whose spec is built elsewhere may be a warning survey, so the surveys CLI decides`() {
    val config = workspace(
      "surveys/shared.survey.ts" to
        "import { warningSpec } from \"./specs\";\nexport const crashes = survey(warningSpec, () => undefined);",
    )
    val calls = mutableListOf<List<String>>()

    assertEquals(listOf("crashes"), RunWarningSurveys.run(session, config) { args -> calls += args; 2 })
    assertTrue("--warnings" in calls.single(), "the CLI, which loads the spec, picks the warning surveys")
  }

  @Test
  fun `a spec whose warning the source cannot read is left to the surveys CLI`() {
    val config = workspace(
      "surveys/warnings.survey.ts" to
        """
        |const WARN = true;
        |const base = { warning: true };
        |const warning = true;
        |export const computed = survey({ warning: WARN, requiresFinishedSession: true }, () => undefined);
        |export const spread = survey({ ...base, requiresFinishedSession: true }, () => undefined);
        |export const shorthand = survey({ warning, requiresFinishedSession: true }, () => undefined);
        |export const keyed = survey({ ["warning"]: true, requiresFinishedSession: true }, () => undefined);
        |export const off = survey({ warning: false, requiresFinishedSession: true, description: "warning, maybe" }, () => undefined);
        """.trimMargin(),
    )

    assertEquals(
      listOf("computed", "spread", "shorthand", "keyed"),
      RunWarningSurveys.run(session, config) { _ -> 0 },
      "only a survey the source shows does not warn is skipped",
    )
  }

  @Test
  fun `a survey file whose exports the source cannot read is left to the surveys CLI`() {
    val config = workspace(
      "surveys/crashes.survey.ts" to "export { crashes } from \"./crashes\";",
      "surveys/all.survey.ts" to "export * from \"./more\";",
      "surveys/built.survey.ts" to "import { crashSurvey } from \"./lib\";\nexport const crashes2 = crashSurvey();",
      // Only the first declarator is read, so the warning survey after it is the CLI's to find.
      "surveys/pair.survey.ts" to
        "export const quiet = survey({ requiresFinishedSession: true }, () => undefined), " +
          "loud = survey({ warning: true, requiresFinishedSession: true }, () => undefined);",
      "surveys/plain.survey.ts" to
        "export type Row = { id: string };\nexport function helper() {}\n" +
          "export const journeys = survey({ requiresFinishedSession: true }, () => undefined);",
    )

    assertEquals(
      listOf("all.survey.ts", "built.survey.ts", "crashes.survey.ts", "pair.survey.ts"),
      RunWarningSurveys.run(session, config) { _ -> 0 }.sorted(),
      "types, functions and a survey the source shows does not warn start nothing",
    )
  }

  @Test
  fun `a survey file's plain values beside its surveys start nothing`() {
    val config = workspace(
      "surveys/journeys.survey.ts" to
        """
        |export const Kind = { Checkout: "checkout", Refund: "refund" } as const;
        |export const KINDS: readonly string[] = ["checkout", "refund"];
        |export const LIMIT = 5;
        |export const NAME = "journeys";
        |export const SEEN = new Set<string>();
        |export const label = (kind: string): string => kind.toUpperCase();
        |export const one = kind => kind;
        |export const later = async (kind: string) => kind;
        |export const screens = trailblaze.eventStream({ name: "screens", from: "logs" });
        |export const nested = { options: { ...defaults } };
        |export const journeys = survey({ requiresFinishedSession: true }, () => undefined);
        """.trimMargin(),
    )
    var started = false

    assertEquals(emptyList(), RunWarningSurveys.run(session, config) { _ -> started = true; 0 })
    assertEquals(false, started, "constants, functions and streams are not surveys")
  }

  @Test
  fun `a plain value followed by another declarator, a call, or a spread is left to the surveys CLI`() {
    val config = workspace(
      "surveys/listed.survey.ts" to
        "export const LIMIT = 5, crashes = survey({ warning: true, requiresFinishedSession: true }, () => undefined);",
      "surveys/called.survey.ts" to "export const crashes2 = Object.freeze(makeSurvey());",
      // A spread copies a survey, brand and all.
      "surveys/copied.survey.ts" to "import { sharedCrashes } from \"./shared\";\nexport const crashes3 = { ...sharedCrashes };",
    )

    assertEquals(
      listOf("called.survey.ts", "copied.survey.ts", "listed.survey.ts"),
      RunWarningSurveys.run(session, config) { _ -> 0 }.sorted(),
    )
  }

  @Test
  fun `warning surveys that fail, or cannot start, never throw into the report`() {
    val config = workspace(
      "surveys/crashes.survey.ts" to
        "export const crashes = survey({ warning: true, requiresFinishedSession: true }, () => undefined);",
    )

    assertEquals(listOf("crashes"), RunWarningSurveys.run(session, config) { _ -> 1 })
    assertEquals(listOf("crashes"), RunWarningSurveys.run(session, config) { _ -> error("no bun") })
  }

  @Test
  fun `only a spec's own warning key marks a warning survey`() {
    val surveys = SurveyBundles.exportedSurveys(
      """
      |export const warned = survey({ "warning": true, requiresFinishedSession: true }, () => undefined);
      |export const off = survey({ warning: false, requiresFinishedSession: true }, () => undefined);
      |export const nested = survey({ finding: { events: { data: { warning: true } } } });
      """.trimMargin(),
    )
    assertEquals(listOf("warned" to true, "off" to false, "nested" to false), surveys.map { it.name to it.warning })
  }
}
