package xyz.block.trailblaze.host

import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.bundle.WorkspaceClientDtsGenerator

/** `ctx.surveys.<name>` in a trailmap's generated client types: its mid-trail surveys, and only those. */
class MidTrailSurveyBindingsTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  @Test
  fun `a trailmap's generated types list the surveys that can run mid-trail and leave out finished-session ones`() {
    val trailmapDir = tempFolder.newFolder("shop")
    File(trailmapDir, "surveys/payments").mkdirs()
    File(trailmapDir, "surveys/payments/checkout.survey.ts").writeText(
      """
      |import { trailblaze, survey } from "@trailblaze/scripting/surveys";
      |export const checkoutDone = trailblaze.survey({ feature: "shop.checkout", finding: { analytics: { name: "checkout_done" } } });
      |export const crashed = trailblaze.survey(
      |  { feature: "shop.crash", requiresFinishedSession: true },
      |  (s) => (s.summary.outcome === "FAILED" ? { summary: "failed", evidence: [] } : undefined),
      |);
      |export const refunded = survey({ feature: 'shop.refund', finding: { network: { path: "/refunds" } } });
      """.trimMargin(),
    )

    val surveys = PerTrailmapClientDtsEmitter.midTrailSurveys(trailmapDir)
    assertEquals(listOf("checkoutDone", "refunded"), surveys.map { it.name })

    val rendered = Files.readString(
      WorkspaceClientDtsGenerator().generateForTrailmap(trailmapDir.toPath(), emptyList(), emptyList(), surveys = surveys),
    )
    assertTrue(rendered.contains("interface TrailblazeSurveyMap {"), rendered)
    assertTrue(rendered.contains("/** Survey `shop.checkout`, from surveys/payments/checkout.survey.ts. */\n    checkoutDone: true;"), rendered)
    assertTrue(rendered.contains("refunded: true;"), rendered)
    assertFalse(rendered.contains("crashed"), rendered)
  }

  @Test
  fun `only a survey's own spec sets its flag, not its handler or a later spec`() {
    val trailmapDir = tempFolder.newFolder("shop")
    File(trailmapDir, "surveys").mkdirs()
    File(trailmapDir, "surveys/checkout.survey.ts").writeText(
      """
      |import { trailblaze, survey } from "@trailblaze/scripting/surveys";
      |export const checkoutDone = trailblaze.survey(
      |  { feature: "shop.checkout", description: "a } in a string, and // not a comment" },
      |  (s) => {
      |    const replay = { requiresFinishedSession: true };
      |    return s.analytics.find({ name: "checkout_done" }) ? { summary: "done", evidence: [] } : undefined;
      |  },
      |);
      |const later = survey({ feature: "shop.later", requiresFinishedSession: true }, () => undefined);
      """.trimMargin(),
    )
    val surveys = PerTrailmapClientDtsEmitter.midTrailSurveys(trailmapDir)
    assertEquals(listOf("checkoutDone"), surveys.map { it.name })
    assertEquals(listOf("shop.checkout"), surveys.map { it.feature })
  }

  @Test
  fun `comments and regex literals do not change what a survey file exports or flags`() {
    val trailmapDir = tempFolder.newFolder("shop")
    File(trailmapDir, "surveys").mkdirs()
    File(trailmapDir, "surveys/checkout.survey.ts").writeText(
      """
      |import { trailblaze, survey } from "@trailblaze/scripting/surveys";
      |// export const ghost = survey({ feature: "shop.ghost", finding: { network: { path: "/x" } } });
      |export const crashed = survey(/* finished only */ { feature: "shop.crash", requiresFinishedSession: true }, (s) => undefined);
      |export const bracedLog = survey({ feature: "shop.log", requiresFinishedSession: true, finding: { deviceLog: { text: /^\{/ } } });
      |export const checkoutDone = survey({ feature: "shop.checkout", finding: { analytics: { name: "checkout_done" } } });
      """.trimMargin(),
    )
    assertEquals(listOf("checkoutDone"), PerTrailmapClientDtsEmitter.midTrailSurveys(trailmapDir).map { it.name })
  }

  @Test
  fun `a trailmap with no surveys augments no survey map`() {
    val trailmapDir = tempFolder.newFolder("plain")
    val rendered = Files.readString(
      WorkspaceClientDtsGenerator().generateForTrailmap(
        trailmapDir.toPath(),
        emptyList(),
        emptyList(),
        surveys = PerTrailmapClientDtsEmitter.midTrailSurveys(trailmapDir),
      ),
    )
    assertFalse(rendered.contains("TrailblazeSurveyMap"), rendered)
  }
}
