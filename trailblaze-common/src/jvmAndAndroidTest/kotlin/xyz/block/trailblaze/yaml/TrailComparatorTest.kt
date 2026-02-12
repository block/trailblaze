package xyz.block.trailblaze.yaml

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isTrue
import org.junit.Test

class TrailComparatorTest {
  private val trailblazeYaml = TrailblazeYaml()
  private val comparator = TrailComparator()

  @Test
  fun identicalTrails_returnsMatch() {
    val yaml = """
      - config:
          title: Test Case
          priority: P1
          context: User is logged in
      - prompts:
        - step: Click the button
        - verify: The dialog appears
    """.trimIndent()

    val result = comparator.compare(yaml, yaml, trailblazeYaml)

    assertThat(result.isMatch).isTrue()
    assertThat(result.differences).isEmpty()
  }

  @Test
  fun differentTitles_returnsTitleMismatch() {
    val expected = """
      - config:
          title: Original Title
      - prompts:
        - step: Do something
    """.trimIndent()

    val actual = """
      - config:
          title: Changed Title
      - prompts:
        - step: Do something
    """.trimIndent()

    val result = comparator.compare(expected, actual, trailblazeYaml)

    assertThat(result.isMatch).isFalse()
    assertThat(result.differences).hasSize(1)
    val diff = result.differences[0]
    assertThat(diff).isInstanceOf(TrailDifference.TitleMismatch::class)
    assertThat((diff as TrailDifference.TitleMismatch).expected).isEqualTo("Original Title")
    assertThat(diff.actual).isEqualTo("Changed Title")
  }

  @Test
  fun differentPriorities_returnsPriorityMismatch() {
    val expected = """
      - config:
          priority: P0
      - prompts:
        - step: Do something
    """.trimIndent()

    val actual = """
      - config:
          priority: P2
      - prompts:
        - step: Do something
    """.trimIndent()

    val result = comparator.compare(expected, actual, trailblazeYaml)

    assertThat(result.isMatch).isFalse()
    val priorityDiffs = result.differences.filterIsInstance<TrailDifference.PriorityMismatch>()
    assertThat(priorityDiffs).hasSize(1)
    assertThat(priorityDiffs[0].expected).isEqualTo("P0")
    assertThat(priorityDiffs[0].actual).isEqualTo("P2")
  }

  @Test
  fun differentStepCount_returnsStepCountMismatch() {
    val expected = """
      - prompts:
        - step: Step 1
        - step: Step 2
        - step: Step 3
    """.trimIndent()

    val actual = """
      - prompts:
        - step: Step 1
        - step: Step 2
    """.trimIndent()

    val result = comparator.compare(expected, actual, trailblazeYaml)

    assertThat(result.isMatch).isFalse()
    val countDiffs = result.differences.filterIsInstance<TrailDifference.StepCountMismatch>()
    assertThat(countDiffs).hasSize(1)
    assertThat(countDiffs[0].expectedCount).isEqualTo(3)
    assertThat(countDiffs[0].actualCount).isEqualTo(2)
  }

  @Test
  fun differentStepContent_returnsStepContentMismatch() {
    val expected = """
      - prompts:
        - step: Click the login button
        - verify: User is logged in
    """.trimIndent()

    val actual = """
      - prompts:
        - step: Tap the login button
        - verify: User is logged in
    """.trimIndent()

    val result = comparator.compare(expected, actual, trailblazeYaml)

    assertThat(result.isMatch).isFalse()
    val contentDiffs = result.differences.filterIsInstance<TrailDifference.StepContentMismatch>()
    assertThat(contentDiffs).hasSize(1)
    assertThat(contentDiffs[0].stepIndex).isEqualTo(0)
    assertThat(contentDiffs[0].expected).isEqualTo("Click the login button")
    assertThat(contentDiffs[0].actual).isEqualTo("Tap the login button")
  }

  @Test
  fun missingActualConfig_returnsMissingActualConfig() {
    val expected = """
      - config:
          title: Test Case
      - prompts:
        - step: Do something
    """.trimIndent()

    val actual = """
      - prompts:
        - step: Do something
    """.trimIndent()

    val result = comparator.compare(expected, actual, trailblazeYaml)

    assertThat(result.isMatch).isFalse()
    val missingDiffs = result.differences.filterIsInstance<TrailDifference.MissingActualConfig>()
    assertThat(missingDiffs).hasSize(1)
  }

  @Test
  fun compareStepsOnly_ignoresConfig() {
    val steps1 = listOf("Step A", "Step B")
    val steps2 = listOf("Step A", "Step B")

    val result = comparator.compareStepsOnly(steps1, steps2)

    assertThat(result.isMatch).isTrue()
  }

  @Test
  fun compareStepsOnly_detectsDifferences() {
    val steps1 = listOf("Step A", "Step B")
    val steps2 = listOf("Step A", "Step C")

    val result = comparator.compareStepsOnly(steps1, steps2)

    assertThat(result.isMatch).isFalse()
    val contentDiffs = result.differences.filterIsInstance<TrailDifference.StepContentMismatch>()
    assertThat(contentDiffs).hasSize(1)
    assertThat(contentDiffs[0].stepIndex).isEqualTo(1)
  }

  @Test
  fun extractNaturalLanguageSteps_extractsFromPromptsItems() {
    val yaml = """
      - config:
          title: Test
      - prompts:
        - step: First step
        - verify: Check something
        - step: Second step
    """.trimIndent()

    val steps = comparator.extractNaturalLanguageSteps(yaml, trailblazeYaml)

    assertThat(steps).containsExactly("First step", "Check something", "Second step")
  }

  @Test
  fun extractNaturalLanguageSteps_ignoresRecordings() {
    val yaml = """
      - prompts:
        - step: Do something
          recording:
            tools:
              - tap:
                  x: 100
                  y: 200
        - verify: Check result
    """.trimIndent()

    val steps = comparator.extractNaturalLanguageSteps(yaml, trailblazeYaml)

    assertThat(steps).containsExactly("Do something", "Check result")
  }

  @Test
  fun toSummary_formatsMatchCorrectly() {
    val result = TrailComparisonResult(emptyList())

    assertThat(result.toSummary()).isEqualTo("Trails match")
  }

  @Test
  fun toSummary_formatsDifferencesCorrectly() {
    val result = TrailComparisonResult(
      listOf(
        TrailDifference.TitleMismatch("Expected", "Actual"),
        TrailDifference.StepCountMismatch(3, 2)
      )
    )

    val summary = result.toSummary()

    assertThat(summary).isEqualTo(
      """
        Trail comparison found 2 difference(s):
        - Title mismatch: 'Expected' vs 'Actual'
        - Step count mismatch: 3 expected vs 2 actual
      """.trimIndent()
    )
  }

  @Test
  fun compareWithConfigDisabled_ignoresConfigDifferences() {
    val expected = """
      - config:
          title: Original Title
          priority: P0
      - prompts:
        - step: Do something
    """.trimIndent()

    val actual = """
      - config:
          title: Changed Title
          priority: P2
      - prompts:
        - step: Do something
    """.trimIndent()

    val result = comparator.compare(expected, actual, trailblazeYaml, compareConfig = false)

    assertThat(result.isMatch).isTrue()
    assertThat(result.differences).isEmpty()
  }

  @Test
  fun multipleDifferences_returnsAllDifferences() {
    val expected = """
      - config:
          title: Original
          context: Precondition A
      - prompts:
        - step: Step 1
        - step: Step 2
    """.trimIndent()

    val actual = """
      - config:
          title: Changed
          context: Precondition B
      - prompts:
        - step: Step 1
        - step: Modified Step 2
    """.trimIndent()

    val result = comparator.compare(expected, actual, trailblazeYaml)

    assertThat(result.isMatch).isFalse()
    // Should have: TitleMismatch, ContextMismatch, StepContentMismatch
    assertThat(result.differences).hasSize(3)
    assertThat(result.differences.filterIsInstance<TrailDifference.TitleMismatch>()).hasSize(1)
    assertThat(result.differences.filterIsInstance<TrailDifference.ContextMismatch>()).hasSize(1)
    assertThat(result.differences.filterIsInstance<TrailDifference.StepContentMismatch>()).hasSize(1)
  }

  // Multi-trail conflict detection tests

  @Test
  fun hasConflictingSteps_withIdenticalSteps_returnsFalse() {
    val steps1 = listOf("Step A", "Step B")
    val steps2 = listOf("Step A", "Step B")
    val steps3 = listOf("Step A", "Step B")

    val result = comparator.hasConflictingSteps(listOf(steps1, steps2, steps3))

    assertThat(result).isFalse()
  }

  @Test
  fun hasConflictingSteps_withDifferentSteps_returnsTrue() {
    val steps1 = listOf("Step A", "Step B")
    val steps2 = listOf("Step A", "Step C") // Different step

    val result = comparator.hasConflictingSteps(listOf(steps1, steps2))

    assertThat(result).isTrue()
  }

  @Test
  fun hasConflictingSteps_withDifferentStepCount_returnsTrue() {
    val steps1 = listOf("Step A", "Step B")
    val steps2 = listOf("Step A", "Step B", "Step C") // Extra step

    val result = comparator.hasConflictingSteps(listOf(steps1, steps2))

    assertThat(result).isTrue()
  }

  @Test
  fun hasConflictingSteps_withSingleTrail_returnsFalse() {
    val steps = listOf("Step A", "Step B")

    val result = comparator.hasConflictingSteps(listOf(steps))

    assertThat(result).isFalse()
  }

  @Test
  fun hasConflictingSteps_withEmptyList_returnsFalse() {
    val result = comparator.hasConflictingSteps(emptyList())

    assertThat(result).isFalse()
  }

  @Test
  fun hasConflictingSteps_withThreeTrailsOneConflicting_returnsTrue() {
    val steps1 = listOf("Step A", "Step B")
    val steps2 = listOf("Step A", "Step B")
    val steps3 = listOf("Step A", "Different Step") // Conflict

    val result = comparator.hasConflictingSteps(listOf(steps1, steps2, steps3))

    assertThat(result).isTrue()
  }

  @Test
  fun findConflicts_identifiesAllConflictingPairs() {
    val namedTrails = mapOf(
      "android-phone" to listOf("Step A", "Step B"),
      "android-tablet" to listOf("Step A", "Step B"),
      "ios-iphone" to listOf("Step A", "Different Step") // Conflicts with android
    )

    val conflicts = comparator.findConflicts(namedTrails)

    // ios-iphone conflicts with both android-phone and android-tablet
    assertThat(conflicts).hasSize(2)
    val conflictNames = conflicts.flatMap { listOf(it.trailName1, it.trailName2) }.toSet().toList().sorted()
    assertThat(conflictNames).containsExactly("android-phone", "android-tablet", "ios-iphone")
  }

  @Test
  fun findConflicts_withNoConflicts_returnsEmptyList() {
    val namedTrails = mapOf(
      "android-phone" to listOf("Step A", "Step B"),
      "android-tablet" to listOf("Step A", "Step B"),
      "ios-iphone" to listOf("Step A", "Step B")
    )

    val conflicts = comparator.findConflicts(namedTrails)

    assertThat(conflicts).isEmpty()
  }
}
