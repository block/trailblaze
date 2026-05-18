package xyz.block.trailblaze.yaml

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import xyz.block.trailblaze.yaml.serializers.PromptStepSerializer

/**
 * Roundtrip tests for the optional `postcondition` field on [DirectionStep] and
 * [VerificationStep]. These tests pin three things the executor downstream relies on:
 *
 *  1. A YAML step without `postcondition` parses with `postcondition = null` so existing
 *     trails behave unchanged.
 *  2. A YAML step with `postcondition: { waypoint: <id> }` parses into a populated
 *     [StepPostcondition] with the documented defaults applied.
 *  3. Explicit `timeoutMs` and `pollIntervalMs` overrides survive the roundtrip without
 *     being silently dropped — the executor's poll bound is the field's wire value.
 *
 * Why direct YAML rather than fixture files: this is a contract test for the field
 * shape itself, not for the encompassing trail-yaml document. Keeping it isolated makes
 * the failure message specific when the serializer drifts.
 */
class StepPostconditionYamlTest {

  private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false))
  private val promptStepSerializer = PromptStepSerializer()

  @Test
  fun `step without postcondition parses to null - back-compat default`() {
    val parsed = yaml.decodeFromString(
      promptStepSerializer,
      """
      step: Tap the More tab
      """.trimIndent(),
    )

    val direction = parsed as DirectionStep
    assertEquals("Tap the More tab", direction.step)
    assertNull(direction.postcondition, "postcondition must be null when not declared")
  }

  @Test
  fun `direction step with waypoint-only postcondition uses defaults`() {
    val parsed = yaml.decodeFromString(
      promptStepSerializer,
      """
      step: Swipe to dismiss the sheet
      postcondition:
        waypoint: "square/ios/more-tab-no-sheet"
      """.trimIndent(),
    )

    val direction = parsed as DirectionStep
    assertEquals("square/ios/more-tab-no-sheet", direction.postcondition?.waypoint)
    assertEquals(StepPostcondition.DEFAULT_TIMEOUT_MS, direction.postcondition?.timeoutMs)
    assertEquals(StepPostcondition.DEFAULT_POLL_INTERVAL_MS, direction.postcondition?.pollIntervalMs)
  }

  @Test
  fun `direction step with explicit timeout and poll-interval overrides survives roundtrip`() {
    val parsed = yaml.decodeFromString(
      promptStepSerializer,
      """
      step: Long-settling step
      postcondition:
        waypoint: "test/some-screen"
        timeoutMs: 10000
        pollIntervalMs: 500
      """.trimIndent(),
    )

    val direction = parsed as DirectionStep
    assertEquals(10_000L, direction.postcondition?.timeoutMs)
    assertEquals(500L, direction.postcondition?.pollIntervalMs)
  }

  @Test
  fun `verification step also accepts postcondition`() {
    val parsed = yaml.decodeFromString(
      promptStepSerializer,
      """
      verify: The item shows up in the search results
      postcondition:
        waypoint: "test/search-results"
      """.trimIndent(),
    )

    val verification = parsed as VerificationStep
    assertEquals("The item shows up in the search results", verification.verify)
    assertEquals("test/search-results", verification.postcondition?.waypoint)
  }

  @Test
  fun `blank waypoint id fails construction loudly - not silently accepted`() {
    assertFailsWith<IllegalArgumentException> {
      StepPostcondition(waypoint = "")
    }
  }

  @Test
  fun `non-positive timeout fails construction loudly`() {
    assertFailsWith<IllegalArgumentException> {
      StepPostcondition(waypoint = "test/x", timeoutMs = 0L)
    }
    assertFailsWith<IllegalArgumentException> {
      StepPostcondition(waypoint = "test/x", timeoutMs = -100L)
    }
  }
}
