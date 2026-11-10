package xyz.block.trailblaze.cli

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import xyz.block.trailblaze.yaml.unified.TrailLabel

/** [SurveyTrailUpdate] over a hand-written report: which labels reach which trails, and what it says. */
class SurveyTrailUpdateTest {

  private val root: File = createTempDirectory("survey-trail-update-test").toFile()

  @AfterTest fun cleanup() {
    root.deleteRecursively()
  }

  private val report = SurveyTrailUpdate.parse(
    """
    {
      "surveys": [{ "id": "journeys", "neededBy": ["jobs"] }, { "id": "jobs" }, { "id": "areas", "neededBy": [] }],
      "sessions": [
        { "session": { "id": "s1", "trailId": "checkout", "path": "/logs/s1" } },
        { "session": { "id": "s2", "trailId": "checkout" } },
        { "session": { "id": "s3", "trailId": "refund" } },
        { "session": { "id": "s4" } }
      ],
      "labels": [
        { "survey": "journeys", "type": "journey", "name": "take-payment", "runs": ["s1", "s3"], "reasoning": "In 2 of 4 runs." },
        { "survey": "jobs", "type": "job", "name": "take-payment", "runs": ["s1", "s2", "s3", "s4"] },
        { "survey": "areas", "type": "area", "name": "payments", "runs": ["s3"] }
      ],
      "excluded": [{ "id": "s5", "path": "/logs/s5" }],
      "unreadable": [{ "path": "/logs/s6", "message": "no" }]
    }
    """.trimIndent(),
  )

  @Test
  fun `only selected surveys label, each trail once per label, without reasoning`() {
    val job = TrailLabel(type = "job", name = "take-payment", survey = "jobs")
    assertEquals(
      mapOf(
        "checkout" to listOf(job),
        "refund" to listOf(job, TrailLabel(type = "area", name = "payments", survey = "areas")),
      ),
      SurveyTrailUpdate.labelsByTrail(report),
    )
  }

  @Test
  fun `stored results files cover every session given, excluded and unreadable ones too`() {
    assertEquals(
      listOf("/logs/s1/surveys.json", "/logs/s5/surveys.json", "/logs/s6/surveys.json"),
      report.storedResultFiles().map { it.path },
    )
  }

  @Test
  fun `apply writes found trails, and reports missing, shared and unwritable ones`() {
    val trail = { id: String, config: String -> "config:\n  id: $id\n$config" + "trail:\n  - step: Open the app\n    recordable: false\n" }
    val checkout = File(root, "checkout.trail.yaml").apply { writeText(trail("checkout", "")) }
    File(root, "refund.trail.yaml").writeText(trail("refund", ""))
    File(root, "refund-copy.trail.yaml").writeText(trail("refund", ""))
    val outcome = SurveyTrailUpdate.apply(report, listOf(root))
    assertEquals(listOf(checkout), outcome.updated.keys.toList())
    assertEquals(setOf("refund"), outcome.ambiguous.keys)
    assertContains(checkout.readText(), "  generatedLabels:\n    - { type: job, name: take-payment, survey: jobs }\n")

    checkout.writeText("config: { id: checkout }\ntrail: []\n")
    File(root, "refund-copy.trail.yaml").delete()
    val again = SurveyTrailUpdate.apply(report, listOf(root))
    assertEquals(listOf(checkout), again.failed.keys.toList())
    // A trail that can't be written is one more not-written trail, not the end of the run.
    val lockedDir = File(root, "locked").apply { mkdirs() }
    val locked = File(lockedDir, "checkout.trail.yaml").apply { writeText(trail("checkout", "")) }
    checkout.delete()
    lockedDir.setWritable(false)
    try {
      val readOnly = SurveyTrailUpdate.apply(report, listOf(root))
      // Root (as on CI) writes through the lock, so the trail is updated there instead.
      assertEquals(listOf(locked), (if (lockedDir.canWrite()) readOnly.updated else readOnly.failed).keys.toList())
      assertEquals(1, readOnly.unchanged, "refund already had its labels and is still counted")
    } finally {
      lockedDir.setWritable(true)
    }
    val text = SurveyTrailUpdate.render(again, root)
    assertContains(text, "Labels: added 2 to 1 trail(s); 0 already had theirs.")
    assertContains(text, "    + area:payments (areas)")
    assertContains(text, "not written: checkout.trail.yaml: its `config:` is not in block style")
  }
}
