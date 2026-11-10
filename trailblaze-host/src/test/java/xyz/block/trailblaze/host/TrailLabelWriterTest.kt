package xyz.block.trailblaze.host

import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.yaml.unified.TrailLabel

/**
 * [TrailLabelWriter] edits only `generatedLabels`, places a new block where the emitter would, and
 * writes the block [TrailLabelLint] accepts. Every result is linted through the real parser.
 */
class TrailLabelWriterTest {

  @get:Rule
  val tmp = TemporaryFolder()

  private val checkout = TrailLabel(type = "journey", name = "checkout", survey = "journeys")
  private val refund = TrailLabel(type = "journey", name = "refund", survey = "journeys")

  private fun trail(config: String) = "config:\n  id: example\n$config" + "trail:\n  - step: Open the app\n    recordable: false\n"

  private fun add(text: String, vararg labels: TrailLabel) = TrailLabelWriter.addGeneratedLabels(text, labels.toList())

  private fun written(text: String, vararg labels: TrailLabel): String {
    val edit = add(text, *labels).getOrThrow()
    assertNull(TrailLabelLint.lintUndecodedTrail("t.trail.yaml", edit.text), edit.text)
    return edit.text
  }

  @Test
  fun `a trail without labels gets the block before context`() {
    assertEquals(
      trail("  generatedLabels:\n    - { type: journey, name: checkout, survey: journeys }\n  context: Example\n"),
      written(trail("  context: Example\n"), checkout),
    )
  }

  @Test
  fun `the block goes right after curated labels`() {
    val text = trail("  labels:\n    - { type: job, name: x }\n  target: example\n")
    assertEquals(
      trail("  labels:\n    - { type: job, name: x }\n  generatedLabels:\n    - { type: journey, name: checkout, survey: journeys }\n  target: example\n"),
      written(text, checkout),
    )
  }

  @Test
  fun `with no key the emitter writes after the labels, the block ends the config`() {
    val text = "config:\n  id: example\n  target: example\n\n# trail follows\ntrail:\n  - step: Open the app\n    recordable: false\n"
    assertEquals(
      "config:\n  id: example\n  target: example\n  generatedLabels:\n    - { type: journey, name: checkout, survey: journeys }\n\n# trail follows\n" +
        "trail:\n  - step: Open the app\n    recordable: false\n",
      written(text, checkout),
    )
  }

  @Test
  fun `existing entries stay, new ones merge in sorted, and the rest of the file is untouched`() {
    val text = trail(
      "  tags:\n  - smoke\n  generatedLabels:\n    - { type: journey, name: refund, survey: journeys, reasoning: Kept as written. }\n" +
        "  metadata:\n    owner: payments  # a comment\n",
    )
    assertEquals(
      trail(
        "  tags:\n  - smoke\n  generatedLabels:\n    - { type: journey, name: checkout, survey: journeys }\n" +
          "    - { type: journey, name: refund, survey: journeys, reasoning: Kept as written. }\n" +
          "  metadata:\n    owner: payments  # a comment\n",
      ),
      written(text, checkout),
    )
  }

  @Test
  fun `a label already there, from the same survey, is not added again`() {
    val text = trail("  generatedLabels:\n    - { type: journey, name: checkout, survey: journeys }\n")
    val edit = add(text, checkout, checkout).getOrThrow()
    assertEquals(text, edit.text)
    assertTrue(edit.added.isEmpty())
    val other = checkout.copy(survey = "other")
    assertEquals(listOf(other), add(text, other).getOrThrow().added, "another survey's label is its own entry")
  }

  @Test
  fun `a four-space config and a quoted key are read as written`() {
    val text = "config:\n    \"id\": example\n    'context': Example\ntrail:\n  - step: Open the app\n    recordable: false\n"
    assertEquals(
      "config:\n    \"id\": example\n    generatedLabels:\n      - { type: journey, name: checkout, survey: journeys }\n    'context': Example\n" +
        "trail:\n  - step: Open the app\n    recordable: false\n",
      add(text, checkout).getOrThrow().text,
    )
  }

  @Test
  fun `templates elsewhere in the file survive unexpanded`() {
    val text = trail("  context: {{CWD}}/fixtures\n").replace("Open the app", "Open {{BASE_URL}}")
    val out = written(text, checkout)
    assertContains(out, "  context: {{CWD}}/fixtures\n")
    assertContains(out, "Open {{BASE_URL}}")
  }

  @Test
  fun `a flow-style config, an unreadable block, or a bad label is refused`() {
    val flow = "config: { id: example }\ntrail:\n  - step: Open the app\n    recordable: false\n"
    assertContains(add(flow, checkout).exceptionOrNull()!!.message!!, "not in block style")
    val unreadable = trail("  generatedLabels:\n    - { name: checkout }\n")
    assertContains(add(unreadable, checkout).exceptionOrNull()!!.message!!, "can't be read")
    assertContains(add(trail(""), checkout.copy(survey = null)).exceptionOrNull()!!.message!!, "`survey` is required")
    assertContains(add(trail(""), checkout.copy(type = "Journey")).exceptionOrNull()!!.message!!, "`type` must be lowercase")
  }

  @Test
  fun `a CRLF file, or a broken entry already there, is refused rather than carried into the edit`() {
    val crlf = trail("  context: Example\n").replace("\n", "\r\n")
    assertContains(add(crlf, checkout).exceptionOrNull()!!.message!!, "CRLF")
    val broken = trail("  generatedLabels:\n    - { type: journey, name: refund }\n")
    assertContains(add(broken, checkout).exceptionOrNull()!!.message!!, "`survey` is required")
  }

  @Test
  fun `several labels in one call land as one sorted block`() {
    assertEquals(
      trail("  generatedLabels:\n    - { type: journey, name: checkout, survey: journeys }\n    - { type: journey, name: refund, survey: journeys }\n"),
      written(trail(""), refund, checkout),
    )
  }

  @Test
  fun `trails are found by config id, and an id two files share maps to both`() {
    val root = tmp.newFolder("trails")
    root.resolve("a").apply { mkdirs() }.resolve("trail.yaml").writeText(trail("  context: {{CWD}}\n"))
    root.resolve("b.trail.yaml").writeText(trail(""))
    root.resolve("c.trail.yaml").writeText("config:\n  id: other\ntrail: []\n")
    root.resolve("notes.yaml").writeText("config:\n  id: example\n")
    root.resolve("build").apply { mkdirs() }.resolve("x.trail.yaml").writeText("config:\n  id: built\n")
    val locked = root.resolve("locked.trail.yaml").apply { writeText("config:\n  id: locked\n") }
    locked.setReadable(false)
    val byId = TrailLabelWriter.trailsById(listOf(root))
    // Root (as on CI) reads the file anyway, so it is found there and skipped elsewhere.
    assertEquals(setOf("example", "other") + listOfNotNull("locked".takeIf { locked.canRead() }), byId.keys)
    assertEquals(2, byId.getValue("example").size)
  }
}
