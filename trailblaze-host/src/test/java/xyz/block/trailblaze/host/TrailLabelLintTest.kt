package xyz.block.trailblaze.host

import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.cli.CheckCommand
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import xyz.block.trailblaze.yaml.unified.TrailDocument

/**
 * Behavioral contract for [TrailLabelLint]. Trails are written as YAML and decoded through the real
 * parser, because the format check reads the raw text and the rules read the decoded labels.
 */
class TrailLabelLintTest {

  @get:Rule
  val tmp = TemporaryFolder()

  private fun lint(yaml: String): TrailLabelLint.Finding? {
    val trail = when (val doc = createTrailblazeYaml().decodeTrailDocument(yaml)) {
      is TrailDocument.Unified -> doc.trail
    }
    return TrailLabelLint.lint("flows/example.trail.yaml", yaml, trail)
  }

  private fun trail(labelLines: String): String =
    "config:\n  id: example\n$labelLines  context: Example\ntrail:\n  - step: Open the app\n    recordable: false\n"

  @Test
  fun `canonical labels are clean`() {
    assertNull(
      lint(
        trail(
          """
          |  labels:
          |    - { type: job, name: payments.take-payment, reasoning: Hand-picked. }
          |    - { type: feature, name: manual-card-entry }
          |  generatedLabels:
          |    - { type: card-response, name: "Declined: insufficient funds", survey: payments/card-response }
          |    - { type: journey, name: checkout, survey: journeys/checkout }
          |    - { type: journey, name: checkout, survey: other/checkout }
          |
          """.trimMargin(),
        ),
      ),
    )
  }

  @Test
  fun `a trail without labels is clean`() {
    assertNull(lint(trail("")))
  }

  @Test
  fun `a curated label may be in any order and any layout`() {
    assertNull(
      lint(
        trail(
          """
          |  labels:
          |  - type: job
          |    name: payments.take-payment
          |  - { type: area, name: payments }
          |
          """.trimMargin(),
        ),
      ),
    )
  }

  @Test
  fun `a curated label naming a survey fails`() {
    val finding = assertNotNull(lint(trail("  labels:\n    - { type: job, name: x, survey: s/r }\n")))
    assertContains(finding.problems.single(), "`survey` is only for generated labels")
  }

  @Test
  fun `a generated label without a survey fails`() {
    val finding = assertNotNull(lint(trail("  generatedLabels:\n    - { type: journey, name: checkout }\n")))
    assertContains(finding.problems.single(), "`survey` is required")
  }

  @Test
  fun `bad types, names and duplicates fail`() {
    val finding = assertNotNull(
      lint(
        trail(
          """
          |  labels:
          |    - { type: Journey, name: checkout }
          |    - { type: job, name: " padded" }
          |    - { type: job, name: "" }
          |    - { type: area, name: "two\nlines" }
          |    - { type: area, name: payments }
          |    - { type: area, name: payments }
          |
          """.trimMargin(),
        ),
      ),
    )
    val problems = finding.problems.joinToString("\n")
    assertContains(problems, "labels `Journey:checkout`: `type` must be lowercase")
    assertContains(problems, "`name` has leading or trailing whitespace")
    assertContains(problems, "`name` is blank")
    assertContains(problems, "`name` must be one line")
    assertContains(problems, "`area:payments` is listed more than once")
  }

  @Test
  fun `unsorted generated labels fail and the finding carries the sorted block`() {
    val finding = assertNotNull(
      lint(
        trail(
          """
          |  generatedLabels:
          |    - { type: journey, name: login, survey: journeys/login }
          |    - { type: area, name: payments, survey: areas/payments }
          |
          """.trimMargin(),
        ),
      ),
    )
    assertEquals(
      "  generatedLabels:\n" +
        "    - { type: area, name: payments, survey: areas/payments }\n" +
        "    - { type: journey, name: login, survey: journeys/login }\n",
      finding.canonicalGeneratedBlock,
    )
  }

  @Test
  fun `generated labels in block style or field order other than canonical fail`() {
    val blockStyle = "  generatedLabels:\n  - type: journey\n    name: login\n    survey: journeys/login\n"
    assertNotNull(assertNotNull(lint(trail(blockStyle))).canonicalGeneratedBlock)
    val reordered = "  generatedLabels:\n    - { name: login, type: journey, survey: journeys/login }\n"
    assertNotNull(assertNotNull(lint(trail(reordered))).canonicalGeneratedBlock)
  }

  @Test
  fun `the block the finding offers is itself clean`() {
    val messy = "  generatedLabels:\n  - { survey: b, type: t, name: n }\n  - { type: a, name: n, survey: s }\n"
    val fixed = assertNotNull(assertNotNull(lint(trail(messy))).canonicalGeneratedBlock)
    assertNull(lint(trail(fixed)))
  }

  @Test
  fun `check phase fails on a broken label and the kill switch skips it`() {
    val workspaceRoot = tmp.newFolder("labels")
    workspaceRoot.resolve("trails").apply { mkdirs() }.resolve("labeled.trail.yaml")
      .writeText(trail("  generatedLabels:\n    - { type: journey, name: checkout }\n"))
    assertEquals(CheckCommand.EXIT_TYPE_ERROR, CheckCommand().runTrailLintPhase(workspaceRoot))
    assertEquals(
      CheckCommand.EXIT_OK,
      CheckCommand().runTrailLintPhase(workspaceRoot, trailLabelGateEnabled = false),
    )
  }

  @Test
  fun `generated labels the scan cannot locate fail rather than pass`() {
    val flowStyle =
      "config: { id: example, generatedLabels: [{ type: journey, name: checkout, survey: s }] }\n" +
        "trail:\n  - step: Open the app\n    recordable: false\n"
    assertEquals(
      "  generatedLabels:\n    - { type: journey, name: checkout, survey: s }\n",
      assertNotNull(lint(flowStyle)).canonicalGeneratedBlock,
    )
  }

  @Test
  fun `a commented config line and a leading comment still find the block`() {
    val canonical = "  generatedLabels:\n    - { type: journey, name: checkout, survey: s }\n"
    val body = "  # leading comment\n  id: example\n$canonical  context: Example\n" +
      "trail:\n  - step: Open the app\n    recordable: false\n"
    assertNull(lint("config: # the config\n$body"))
  }

  @Test
  fun `check phase fails on a label missing a required field`() {
    val workspaceRoot = tmp.newFolder("undecodable")
    workspaceRoot.resolve("trails").apply { mkdirs() }.resolve("untyped.trail.yaml")
      .writeText(trail("  labels:\n    - { name: checkout }\n"))
    assertEquals(CheckCommand.EXIT_TYPE_ERROR, CheckCommand().runTrailLintPhase(workspaceRoot))
    val finding = assertNotNull(
      TrailLabelLint.lintUndecodedTrail("t.trail.yaml", trail("  labels:\n    - { name: checkout }\n")),
    )
    assertContains(finding.problems.single(), "labels: cannot be read")
    assertNull(TrailLabelLint.lintUndecodedTrail("t.trail.yaml", trail("  labels:\n    - { type: job, name: x }\n")))
  }

  @Test
  fun `label rules still apply when something else stops the trail decoding`() {
    val withStrayKey = trail("  labels:\n    - { type: Journey, name: checkout }\n") + "notes: stray top-level key\n"
    val finding = assertNotNull(TrailLabelLint.lintUndecodedTrail("t.trail.yaml", withStrayKey))
    assertContains(finding.problems.single(), "`type` must be lowercase")
    val workspaceRoot = tmp.newFolder("stray")
    workspaceRoot.resolve("trails").apply { mkdirs() }.resolve("stray.trail.yaml").writeText(withStrayKey)
    assertEquals(CheckCommand.EXIT_TYPE_ERROR, CheckCommand().runTrailLintPhase(workspaceRoot))
  }

  @Test
  fun `a trail using a template is still linted`() {
    val templated = "config:\n  id: example\n  labels:\n    - { type: Journey, name: checkout }\n" +
      "  context: {{CWD}}/fixtures\ntrail:\n  - step: Open {{BASE_URL}}\n    recordable: false\n"
    val finding = assertNotNull(TrailLabelLint.lintUndecodedTrail("t.trail.yaml", templated))
    assertContains(finding.problems.single(), "`type` must be lowercase")
    val workspaceRoot = tmp.newFolder("templated")
    workspaceRoot.resolve("trails").apply { mkdirs() }.resolve("templated.trail.yaml").writeText(templated)
    assertEquals(CheckCommand.EXIT_TYPE_ERROR, CheckCommand().runTrailLintPhase(workspaceRoot))
  }

  @Test
  fun `templates inside labels are read as written`() {
    val templated = "config:\n  id: example\n  labels:\n    - { type: job, name: \"{{A}}\" }\n    - { type: job, name: \"{{B}}\" }\n" +
      "  generatedLabels:\n    - { type: journey, name: checkout, survey: s, reasoning: \"Visits {{BASE_URL}} checkout.\" }\n" +
      "  context: {{CWD}}/fixtures\ntrail:\n  - step: Open the app\n    recordable: false\n"
    assertNull(TrailLabelLint.lintUndecodedTrail("t.trail.yaml", templated))
  }

  @Test
  fun `null or empty label lists are clean when something else stops the trail decoding`() {
    listOf("  labels: null\n  generatedLabels:\n", "  labels: []\n  generatedLabels: ~\n").forEach { lists ->
      assertNull(TrailLabelLint.lintUndecodedTrail("t.trail.yaml", trail(lists) + "notes: stray top-level key\n"), lists)
    }
  }

  @Test
  fun `the failure output names the trail and the fix`() {
    val finding = assertNotNull(
      lint(trail("  generatedLabels:\n    - { type: b, name: x, survey: s }\n    - { type: a, name: x, survey: s }\n")),
    )
    val out = TrailLabelLint.renderFailures(listOf(finding))
    assertContains(out, "FAIL flows/example.trail.yaml: generatedLabels: not in the form")
    assertTrue(out.indexOf("- { type: a, name: x, survey: s }") < out.indexOf("- { type: b, name: x, survey: s }"), out)
  }

  // ── when / except ──

  private val devices =
    "  devices:\n" +
      "    android-phone: {}\n" +
      "    ios-iphone: {}\n" +
      "    ios-ipad: {}\n" +
      "    lab-a: {}\n" +
      "    lab-a-rack: {}\n" +
      "    lab-pair:\n" +
      "      devices:\n" +
      "        seller:\n" +
      "          classifier: lab-b\n" +
      "        buyer:\n" +
      "          classifier: lab-c\n"

  private fun scoped(label: String): TrailLabelLint.Finding? = lint(trail(devices + "  labels:\n    - $label\n"))

  @Test
  fun `when and except that pick devices are clean`() {
    listOf(
      "{ type: feature, name: x, when: [ios], except: [ios-ipad] }",
      "{ type: feature, name: x, when: [lab-a], except: [rack] }",
      "{ type: feature, name: x, when: [lab-pair] }",
      "{ type: feature, name: x, when: [lab-pair.buyer] }",
      "{ type: feature, name: x, when: [lab-b] }",
      "{ type: feature, name: x, except: [android] }",
      "{ type: feature, name: x, when: [phone, ipad] }",
    ).forEach { assertNull(scoped(it), it) }
  }

  @Test
  fun `a value that picks none of the trail's devices fails and lists them`() {
    val finding = assertNotNull(scoped("{ type: feature, name: x, when: [tablet] }"))
    assertContains(finding.problems.single(), "labels `feature:x`: `when` value `tablet` picks none of this trail's devices")
    assertContains(finding.problems.single(), "lab-pair.seller")
  }

  @Test
  fun `a value matches whole words only`() {
    assertNotNull(scoped("{ type: feature, name: x, when: [lab-ai] }"))
    assertNotNull(scoped("{ type: feature, name: x, when: [phon] }"))
  }

  @Test
  fun `a member is named by its configuration, not on its own`() {
    assertNotNull(scoped("{ type: feature, name: x, when: [seller] }"))
    assertNotNull(scoped("{ type: feature, name: x, when: [other-pair.seller] }"))
  }

  @Test
  fun `when and except that leave no device fail`() {
    val finding = assertNotNull(scoped("{ type: feature, name: x, when: [ios-ipad], except: [ipad] }"))
    assertContains(finding.problems.single(), "leave none of this trail's devices")
  }

  @Test
  fun `an empty or malformed list fails`() {
    assertContains(assertNotNull(scoped("{ type: feature, name: x, when: [] }")).problems.single(), "`when` is empty")
    assertContains(
      assertNotNull(scoped("{ type: feature, name: x, except: [\"ios ipad\"] }")).problems.single(),
      "`except` value `ios ipad` is not a device",
    )
    assertContains(
      assertNotNull(scoped("{ type: feature, name: x, when: [ios, ios] }")).problems.single(),
      "`when` lists `ios` more than once",
    )
  }

  @Test
  fun `recorded classifiers are devices too, so a trail with no devices map can scope a label`() {
    val recorded = "config:\n  id: example\n  labels:\n    - { type: feature, name: x, when: [ios] }\n" +
      "trail:\n  - step: Tap pay\n    recording:\n      android:\n        - pressBack: {}\n" +
      "      ios-iphone:\n        - pressBack: {}\n"
    assertNull(lint(recorded))
  }

  @Test
  fun `when on a trail that declares no devices fails`() {
    val finding = assertNotNull(lint(trail("  labels:\n    - { type: feature, name: x, when: [ios] }\n")))
    assertContains(finding.problems.single(), "this trail declares none")
  }

  @Test
  fun `a template elsewhere in the trail does not stop when being checked against its devices`() {
    fun templated(scope: String) =
      "config:\n  id: example\n  devices:\n    android-phone: {}\n" +
        "  labels:\n    - { type: feature, name: x, when: [$scope] }\n" +
        "  context: {{CWD}}/fixtures\ntrail:\n  - step: Open the app\n    recordable: false\n"
    assertContains(
      assertNotNull(TrailLabelLint.lintUndecodedTrail("t.trail.yaml", templated("tablet"))).problems.single(),
      "`when` value `tablet` picks none of this trail's devices (android-phone)",
    )
    assertNull(TrailLabelLint.lintUndecodedTrail("t.trail.yaml", templated("phone")))
  }

  @Test
  fun `a scalar when is reported as needing a list`() {
    val text = trail("  labels:\n    - { type: feature, name: x, when: ios }\n")
    assertContains(
      assertNotNull(TrailLabelLint.lintUndecodedTrail("t.trail.yaml", text)).problems.single(),
      "`when`/`except` are lists (`when: [ios]`)",
    )
  }

  @Test
  fun `a trail that does not decode still has its when and except shape checked`() {
    val text = trail("  labels:\n    - { type: feature, name: x, when: [] }\n") + "notes: stray top-level key\n"
    assertContains(assertNotNull(TrailLabelLint.lintUndecodedTrail("t.trail.yaml", text)).problems.single(), "`when` is empty")
  }
}
