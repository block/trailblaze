package xyz.block.trailblaze.yaml.unified

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.block.trailblaze.yaml.TrailFormatYaml

/**
 * A trail's `labels` and `generatedLabels` survive a decode and re-encode unchanged, which is what
 * a replay does when it writes recordings back. Each label is written on one line, next to `tags`.
 */
class TrailLabelYamlTest {

  private val yaml = TrailFormatYaml()

  private val labeled =
    """
    config:
      id: payments/card-entry
      target: myapp
      tags:
      - sign-off-test
      labels:
        - { type: feature, name: manual-card-entry, reasoning: Steps 2-5 validate each keyed-card field. }
        - { type: job, name: payments.take-payment }
      generatedLabels:
        - { type: card-response, name: "Declined: insufficient funds", survey: payments/card-response }
        - { type: journey, name: checkout, survey: journeys/checkout, reasoning: Reached the checkout-complete checkpoint. }
      context: Card entry

    trail:
      - step: "Open the app"
        recordable: false
    """
      .trimIndent() + "\n"

  @Test
  fun `both label lists decode into typed entries`() {
    val config = yaml.decodeUnifiedTrail(labeled).config
    assertEquals(
      listOf(
        TrailLabel(
          type = "feature",
          name = "manual-card-entry",
          reasoning = "Steps 2-5 validate each keyed-card field.",
        ),
        TrailLabel(type = "job", name = "payments.take-payment"),
      ),
      config.labels,
    )
    assertEquals(
      listOf(
        TrailLabel(
          type = "card-response",
          name = "Declined: insufficient funds",
          survey = "payments/card-response",
        ),
        TrailLabel(
          type = "journey",
          name = "checkout",
          survey = "journeys/checkout",
          reasoning = "Reached the checkout-complete checkpoint.",
        ),
      ),
      config.generatedLabels,
    )
  }

  @Test
  fun `a labeled trail re-encodes byte-identical, so a replay write-back keeps its labels`() {
    val emitted = yaml.encodeUnifiedTrailToString(yaml.decodeUnifiedTrail(labeled))
    assertEquals(labeled, emitted)
  }

  @Test
  fun `labels are written one per line, right after tags and before later keys`() {
    val trail =
      UnifiedTrail(
        config =
          UnifiedTrailConfig(
            id = "x",
            tags = listOf("smoke"),
            context = "Some context",
            generatedLabels =
              listOf(TrailLabel(type = "journey", name = "login", survey = "journeys/login")),
          ),
        trail =
          listOf(
            UnifiedTrailStep(step = "Open the app", recordings = emptyMap(), recordable = false)
          ),
      )
    val emitted = yaml.encodeUnifiedTrailToString(trail)
    val lines = emitted.lines()
    val labelLine = "    - { type: journey, name: login, survey: journeys/login }"
    assertTrue(labelLine in lines, "expected the label on one line, got:\n$emitted")
    assertTrue(lines.indexOf("  generatedLabels:") > lines.indexOf("  tags:"), emitted)
    assertTrue(
      lines.indexOf("  generatedLabels:") < lines.indexOfFirst { it.startsWith("  context:") },
      emitted,
    )
  }

  @Test
  fun `a config holding only labels emits and round-trips`() {
    val trail =
      UnifiedTrail(
        config =
          UnifiedTrailConfig(labels = listOf(TrailLabel(type = "job", name = "payments.refund"))),
        trail =
          listOf(
            UnifiedTrailStep(step = "Open the app", recordings = emptyMap(), recordable = false)
          ),
      )
    val emitted = yaml.encodeUnifiedTrailToString(trail)
    assertEquals(trail, yaml.decodeUnifiedTrail(emitted), emitted)
  }

  @Test
  fun `values that would misread as plain YAML are quoted and read back exactly`() {
    val tricky =
      listOf(
        "Declined: insufficient funds",
        "a, b",
        "null",
        "true",
        "- leading dash",
        "@handle",
        "with \"quotes\" and \\ slash",
        "trailing space ",
        "hash # mid",
        "[bracketed]",
        "",
        "bell\u0001and\bbackspace",
        "next\u0085line",
        "line\u2028separator",
        "\uFEFFbom",
        "001",
        "2026-11-03",
        "1e3",
        "0x1F",
        ".5",
        "+1",
        ".inf",
        ".NaN",
      )
    val trail =
      UnifiedTrail(
        config =
          UnifiedTrailConfig(
            labels = tricky.map { TrailLabel(type = "free", name = it, reasoning = it) }
          ),
        trail =
          listOf(
            UnifiedTrailStep(step = "Open the app", recordings = emptyMap(), recordable = false)
          ),
      )
    val emitted = yaml.encodeUnifiedTrailToString(trail)
    assertEquals(trail.config.labels, yaml.decodeUnifiedTrail(emitted).config.labels, emitted)
    assertEquals(
      tricky.size,
      emitted.lines().count { it.startsWith("    - { type: free") },
      emitted,
    )
  }

  @Test
  fun `values a YAML reader would type as numbers or dates are quoted, other values stay plain`() {
    val quoted =
      listOf("001", "2026-11-03", "1e3", "0x1F", ".5", "+1", ".inf", ".NaN", "3ds", "Null", "yes")
    quoted.forEach {
      assertEquals(
        "{ type: t, name: \"$it\" }",
        TrailLabels.renderEntry(TrailLabel(type = "t", name = it)),
      )
    }
    val plain =
      listOf("checkout", "v1.2", "payments.take-payment", "a/b", "card 2", "_private", ".hidden")
    plain.forEach {
      assertEquals(
        "{ type: t, name: $it }",
        TrailLabels.renderEntry(TrailLabel(type = "t", name = it)),
      )
    }
  }

  @Test
  fun `an empty label list round-trips as empty, not null`() {
    val trail =
      UnifiedTrail(
        config = UnifiedTrailConfig(id = "x", labels = emptyList(), generatedLabels = emptyList()),
        trail =
          listOf(
            UnifiedTrailStep(step = "Open the app", recordings = emptyMap(), recordable = false)
          ),
      )
    val emitted = yaml.encodeUnifiedTrailToString(trail)
    assertTrue("  labels: []" in emitted.lines(), emitted)
    assertEquals(trail.config, yaml.decodeUnifiedTrail(emitted).config, emitted)
  }

  @Test
  fun `a template mask parses without expanding and restores each template as written`() {
    val text = "context: {{CWD}}/x\nname: \"{{A}} and {{ B }}\"\nnote: trailblaze-template-0t\n"
    val mask = TrailLabels.TemplateMask.of(text)
    assertTrue("{{" !in mask.masked, mask.masked)
    val name = mask.masked.lines()[1].removePrefix("name: ").trim('"')
    assertEquals("{{A}} and {{ B }}", mask.restore(name))
    // Text that already looks like a placeholder is left alone.
    assertEquals("trailblaze-template-0t", mask.restore("trailblaze-template-0t"))
  }

  @Test
  fun `canonical order sorts by type, then name, then survey`() {
    val unsorted =
      listOf(
        TrailLabel(type = "journey", name = "login", survey = "b/login"),
        TrailLabel(type = "area", name = "payments"),
        TrailLabel(type = "journey", name = "login", survey = "a/login"),
        TrailLabel(type = "journey", name = "checkout", survey = "a/checkout"),
      )
    assertEquals(
      listOf("area:payments", "journey:checkout", "journey:login", "journey:login"),
      unsorted.sortedWith(TrailLabels.CANONICAL_ORDER).map { it.key },
    )
    assertEquals(
      listOf("a/login", "b/login"),
      unsorted
        .sortedWith(TrailLabels.CANONICAL_ORDER)
        .filter { it.name == "login" }
        .map { it.survey },
    )
  }
}
