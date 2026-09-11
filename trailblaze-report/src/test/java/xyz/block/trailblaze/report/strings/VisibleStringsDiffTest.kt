package xyz.block.trailblaze.report.strings

import kotlinx.datetime.Instant
import org.junit.Test
import xyz.block.trailblaze.api.AgentDriverAction
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.ExtractedString
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.VisibleStringSource
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VisibleStringsDiffTest {

  private fun screen(
    stepIndex: Int,
    vararg texts: String,
    partialCapture: Boolean? = null,
    repeatOf: Int? = null,
    volatileTexts: List<String> = emptyList(),
  ) = VisibleStringsScreenLine(
    stepIndex = stepIndex,
    captureId = "shot-$stepIndex.png",
    logType = "AgentDriverLog",
    timestamp = "2026-09-09T17:04:11Z",
    action = "tap",
    deviceWidth = 1080,
    deviceHeight = 1920,
    screenId = "id-$stepIndex",
    partialCapture = partialCapture,
    repeatOfStepIndex = repeatOf,
    strings = texts.map { ExtractedString(text = it, source = VisibleStringSource.TEXT) } +
      volatileTexts.map { ExtractedString(text = it, source = VisibleStringSource.TEXT, volatile = true) },
  )

  private fun sourced(stepIndex: Int, vararg strings: Pair<String, VisibleStringSource>) =
    VisibleStringsScreenLine(
      stepIndex = stepIndex,
      captureId = "shot-$stepIndex.png",
      logType = "AgentDriverLog",
      timestamp = "2026-09-09T17:04:11Z",
      action = "tap",
      deviceWidth = 1080,
      deviceHeight = 1920,
      screenId = "id-$stepIndex",
      strings = strings.map { ExtractedString(text = it.first, source = it.second) },
    )

  private fun file(locale: String?, vararg screens: VisibleStringsScreenLine) =
    VisibleStringsDiff.ParsedFile(
      run = VisibleStringsRunLine(session = "s", locale = locale),
      screens = screens.toList(),
    )

  @Test
  fun `a step whose text changed reports what went and what arrived`() {
    val result = VisibleStringsDiff.diff(
      file("en", screen(0, "Sign in", "Forgot password?")),
      file("en", screen(0, "Log in", "Forgot password?")),
    )

    val step = result.steps.single()
    assertEquals(listOf("Log in"), step.added)
    assertEquals(listOf("Sign in"), step.removed)
    assertEquals(listOf("Forgot password?"), step.unchanged)
    assertTrue(step.changed)
  }

  @Test
  fun `two runs of the same trail at the same locale flag nothing`() {
    val result = VisibleStringsDiff.diff(
      file("en", screen(0, "Sign in"), screen(1, "Welcome")),
      file("en", screen(0, "Sign in"), screen(1, "Welcome")),
    )

    assertTrue(result.steps.none { it.changed })
    assertTrue(result.aligned)
  }

  @Test
  fun `a string identical across two locales is the untranslated signal`() {
    val result = VisibleStringsDiff.diff(
      file("en", screen(0, "Sign in", "Checkout")),
      file("es", screen(0, "Iniciar sesión", "Checkout")),
    )

    assertEquals(listOf("Checkout"), result.steps.single().unchanged)
    assertEquals("en", result.baselineLocale)
    assertEquals("es", result.candidateLocale)
  }

  @Test
  fun `a clock or a balance does not count as a change`() {
    val result = VisibleStringsDiff.diff(
      file("en", screen(0, "Total due", volatileTexts = listOf("$12.00"))),
      file("en", screen(0, "Total due", volatileTexts = listOf("$98.55"))),
    )

    assertFalse(result.steps.single().changed)
  }

  @Test
  fun `a repeated capture is compared against the step it repeats, not against nothing`() {
    val result = VisibleStringsDiff.diff(
      file("en", screen(0, "Checkout"), screen(1, repeatOf = 0)),
      file("en", screen(0, "Checkout"), screen(1, repeatOf = 0)),
    )

    assertEquals(listOf("Checkout"), result.steps[1].unchanged)
    assertFalse(result.steps[1].changed)
  }

  @Test
  fun `a partial candidate makes its removals suspect, and says so about the candidate`() {
    val result = VisibleStringsDiff.diff(
      file("en", screen(0, "Checkout", "Pay now")),
      file("en", screen(0, "Checkout", partialCapture = true)),
    )

    val step = result.steps.single()
    assertEquals(listOf("Pay now"), step.removed)
    assertTrue(step.candidatePartial)
    assertFalse(step.baselinePartial)
    assertTrue(step.inconclusive)
  }

  @Test
  fun `a partial baseline makes its additions suspect, and says so about the baseline`() {
    val result = VisibleStringsDiff.diff(
      file("en", screen(0, "Checkout", partialCapture = true)),
      file("en", screen(0, "Checkout", "Pay now")),
    )

    val step = result.steps.single()
    assertEquals(listOf("Pay now"), step.added)
    assertTrue(step.baselinePartial)
    assertFalse(step.candidatePartial)
  }

  @Test
  fun `a capture that never measured coverage is not treated as partial`() {
    val result = VisibleStringsDiff.diff(
      file("en", screen(0, "Checkout")),
      file("en", screen(0, "Checkout")),
    )

    assertFalse(result.steps.single().inconclusive)
  }

  @Test
  fun `steps only one run reached are reported rather than compared`() {
    val result = VisibleStringsDiff.diff(
      file("en", screen(0, "Checkout"), screen(1, "Receipt")),
      file("en", screen(0, "Checkout"), screen(2, "Error")),
    )

    assertEquals(listOf(0), result.steps.map { it.stepIndex })
    assertEquals(listOf(1), result.baselineOnlySteps)
    assertEquals(listOf(2), result.candidateOnlySteps)
    assertFalse(result.aligned)
  }

  @Test
  fun `a caption demoted to a content description is a change, named by the property it moved to`() {
    val result = VisibleStringsDiff.diff(
      file("en", sourced(0, "Checkout" to VisibleStringSource.TEXT)),
      file("en", sourced(0, "Checkout" to VisibleStringSource.CONTENT_DESCRIPTION)),
    )

    val step = result.steps.single()
    assertTrue(step.changed)
    assertEquals(listOf("Checkout [TEXT]"), step.removed)
    assertEquals(listOf("Checkout [CONTENT_DESCRIPTION]"), step.added)
  }

  /**
   * The move is a real change, but the text is still text nobody translated. Pair-based matching
   * drops it from the untranslated report, which is the one question `--untranslated` asks.
   */
  @Test
  fun `text that only moved property is still untranslated, even though it counts as changed`() {
    val result = VisibleStringsDiff.diff(
      file("en", sourced(0, "Checkout" to VisibleStringSource.TEXT)),
      file("es", sourced(0, "Checkout" to VisibleStringSource.CONTENT_DESCRIPTION)),
    )

    val step = result.steps.single()
    assertTrue(step.changed)
    assertEquals(emptyList(), step.unchanged)
    assertEquals(listOf("Checkout"), step.unchangedText)
  }

  @Test
  fun `genuinely translated text is untranslated under neither measure`() {
    val result = VisibleStringsDiff.diff(
      file("en", sourced(0, "Checkout" to VisibleStringSource.TEXT)),
      file("es", sourced(0, "Pagar" to VisibleStringSource.CONTENT_DESCRIPTION)),
    )

    assertEquals(emptyList(), result.steps.single().unchangedText)
  }

  @Test
  fun `the same text under two properties is only qualified when it is actually ambiguous`() {
    val result = VisibleStringsDiff.diff(
      file("en", sourced(0, "Pay" to VisibleStringSource.TEXT)),
      file("en", sourced(0, "Pay" to VisibleStringSource.TEXT, "Send" to VisibleStringSource.TEXT)),
    )

    assertEquals(listOf("Send"), result.steps.single().added)
    assertEquals(listOf("Pay"), result.steps.single().unchanged)
  }

  @Test
  fun `output is sorted, because set arithmetic has no order and a diff gets snapshotted`() {
    val result = VisibleStringsDiff.diff(
      file("en", screen(0, "Zebra", "Apple", "Mango")),
      file("en", screen(0)),
    )

    assertEquals(listOf("Apple", "Mango", "Zebra"), result.steps.single().removed)
  }

  @Test
  fun `a file that is not this format names the line that failed, instead of a stack trace`() {
    val failure = assertFailsWith<VisibleStringsDiff.MalformedFile> {
      VisibleStringsDiff.parse(
        """
        {"kind":"run","session":"s"}
        {"kind":"screen"}
        """.trimIndent(),
      )
    }

    assertEquals(2, failure.lineNumber)
  }

  /**
   * Skipping an unrecognized kind turns a typo into a wrong answer: the step vanishes and gets
   * reported as the two runs having taken different paths.
   */
  @Test
  fun `a record with an unrecognized kind is rejected, not quietly dropped`() {
    val failure = assertFailsWith<VisibleStringsDiff.MalformedFile> {
      VisibleStringsDiff.parse(
        """
        {"kind":"run","session":"s"}
        {"kind":"scren","stepIndex":0}
        """.trimIndent(),
      )
    }

    assertEquals(2, failure.lineNumber)
  }

  @Test
  fun `a record with no kind at all is rejected too`() {
    val failure = assertFailsWith<VisibleStringsDiff.MalformedFile> {
      VisibleStringsDiff.parse("""{"session":"s"}""")
    }

    assertEquals(1, failure.lineNumber)
  }

  @Test
  fun `a file written by the writer reads back the same way`() {
    val session = SessionId("2026_09_09_test")
    val rendered = VisibleStringsLog.render(
      sessionId = session,
      sessionInfo = null,
      logs = listOf(
        TrailblazeLog.AgentDriverLog(
          viewHierarchy = null,
          trailblazeNodeTree = TrailblazeNode(
            driverDetail = DriverNodeDetail.AndroidAccessibility(text = "Checkout"),
            bounds = TrailblazeNode.Bounds(0, 0, 200, 50),
          ),
          screenshotFile = "shot-a.png",
          action = AgentDriverAction.BackPress,
          durationMs = 10,
          session = session,
          timestamp = Instant.parse("2026-09-09T17:04:11Z"),
          deviceHeight = 1920,
          deviceWidth = 1080,
        ),
      ),
    )!!

    val parsed = VisibleStringsDiff.parse(rendered)

    assertEquals(session.value, parsed.run?.session)
    assertEquals(listOf("Checkout"), parsed.screens.single().strings.map { it.text })
  }
}
