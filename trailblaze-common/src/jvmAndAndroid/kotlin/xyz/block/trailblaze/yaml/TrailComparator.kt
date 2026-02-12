package xyz.block.trailblaze.yaml

/**
 * Represents a difference found when comparing two trail items.
 */
sealed class TrailDifference {
  data class TitleMismatch(
    val expected: String?,
    val actual: String?
  ) : TrailDifference()

  data class DescriptionMismatch(
    val expected: String?,
    val actual: String?
  ) : TrailDifference()

  data class ContextMismatch(
    val expected: String?,
    val actual: String?
  ) : TrailDifference()

  data class PriorityMismatch(
    val expected: String?,
    val actual: String?
  ) : TrailDifference()

  data class StepCountMismatch(
    val expectedCount: Int,
    val actualCount: Int
  ) : TrailDifference()

  data class StepContentMismatch(
    val stepIndex: Int,
    val expected: String,
    val actual: String?
  ) : TrailDifference()

  data object MissingExpectedConfig : TrailDifference()

  data object MissingActualConfig : TrailDifference()
}

/**
 * Result of comparing two trails.
 */
data class TrailComparisonResult(
  val differences: List<TrailDifference>,
) {
  /**
   * Returns true if the trails are semantically equivalent (no differences found).
   */
  val isMatch: Boolean get() = differences.isEmpty()

  /**
   * Returns a human-readable summary of all differences.
   */
  fun toSummary(): String {
    if (isMatch) return "Trails match"
    return buildString {
      appendLine("Trail comparison found ${differences.size} difference(s):")
      differences.forEach { diff ->
        appendLine(
          when (diff) {
            is TrailDifference.TitleMismatch ->
              "- Title mismatch: '${diff.expected}' vs '${diff.actual}'"
            is TrailDifference.DescriptionMismatch ->
              "- Description mismatch"
            is TrailDifference.ContextMismatch ->
              "- Context/preconditions mismatch"
            is TrailDifference.PriorityMismatch ->
              "- Priority mismatch: '${diff.expected}' vs '${diff.actual}'"
            is TrailDifference.StepCountMismatch ->
              "- Step count mismatch: ${diff.expectedCount} expected vs ${diff.actualCount} actual"
            is TrailDifference.StepContentMismatch ->
              "- Step ${diff.stepIndex + 1} differs: '${diff.expected}' vs '${diff.actual}'"
            is TrailDifference.MissingExpectedConfig ->
              "- Expected trail is missing config block"
            is TrailDifference.MissingActualConfig ->
              "- Actual trail is missing config block"
          }
        )
      }
    }.trimEnd()
  }
}

/**
 * Utility for comparing trail files and their natural language instructions.
 *
 * This comparator focuses on semantic comparison - it compares the natural language
 * prompts and config metadata while ignoring recorded tool steps (recordings).
 *
 * Usage:
 * ```kotlin
 * val comparator = TrailComparator()
 * val result = comparator.compare(expectedTrailItems, actualTrailItems)
 * if (!result.isMatch) {
 *   println(result.toSummary())
 * }
 * ```
 */
class TrailComparator {

  /**
   * Compare two trails and return a detailed comparison result.
   *
   * @param expected The expected/reference trail items (e.g., from TestRail)
   * @param actual The actual trail items (e.g., from a recorded trail file)
   * @param compareConfig Whether to compare config blocks (default: true)
   * @return A [TrailComparisonResult] containing any differences found
   */
  fun compare(
    expected: List<TrailYamlItem>,
    actual: List<TrailYamlItem>,
    compareConfig: Boolean = true,
  ): TrailComparisonResult {
    val differences = mutableListOf<TrailDifference>()

    // Extract configs
    val expectedConfig = extractConfig(expected)
    val actualConfig = extractConfig(actual)

    // Compare configs if requested
    if (compareConfig) {
      differences.addAll(compareConfigs(expectedConfig, actualConfig))
    }

    // Extract and compare natural language steps
    val expectedSteps = extractNaturalLanguageSteps(expected)
    val actualSteps = extractNaturalLanguageSteps(actual)
    differences.addAll(compareNaturalLanguageSteps(expectedSteps, actualSteps))

    return TrailComparisonResult(differences)
  }

  /**
   * Compare two trails using YAML strings.
   *
   * @param expectedYaml The expected/reference trail YAML
   * @param actualYaml The actual trail YAML
   * @param trailblazeYaml The YAML parser to use (default: TrailblazeYaml.Default)
   * @param compareConfig Whether to compare config blocks (default: true)
   * @return A [TrailComparisonResult] containing any differences found
   */
  fun compare(
    expectedYaml: String,
    actualYaml: String,
    trailblazeYaml: TrailblazeYaml = TrailblazeYaml.Default,
    compareConfig: Boolean = true,
  ): TrailComparisonResult {
    val expectedItems = trailblazeYaml.decodeTrail(expectedYaml)
    val actualItems = trailblazeYaml.decodeTrail(actualYaml)
    return compare(expectedItems, actualItems, compareConfig)
  }

  /**
   * Compare just the natural language steps from two trails.
   * Useful when you only care about step content, not metadata.
   *
   * @param expected The expected natural language steps
   * @param actual The actual natural language steps
   * @return A [TrailComparisonResult] containing any differences found
   */
  fun compareStepsOnly(
    expected: List<String>,
    actual: List<String>,
  ): TrailComparisonResult {
    return TrailComparisonResult(compareNaturalLanguageSteps(expected, actual))
  }

  /**
   * Extracts natural language steps (prompts) from trail items, ignoring recordings.
   */
  fun extractNaturalLanguageSteps(trailItems: List<TrailYamlItem>): List<String> {
    return trailItems
      .filterIsInstance<TrailYamlItem.PromptsTrailItem>()
      .flatMap { it.promptSteps }
      .map { it.prompt }
  }

  /**
   * Extracts natural language steps from a YAML string.
   */
  fun extractNaturalLanguageSteps(
    yaml: String,
    trailblazeYaml: TrailblazeYaml = TrailblazeYaml.Default
  ): List<String> {
    val trailItems = trailblazeYaml.decodeTrail(yaml)
    return extractNaturalLanguageSteps(trailItems)
  }

  /**
   * Extracts the config from trail items.
   */
  fun extractConfig(trailItems: List<TrailYamlItem>): TrailConfig? {
    return trailItems
      .filterIsInstance<TrailYamlItem.ConfigTrailItem>()
      .firstOrNull()
      ?.config
  }

  private fun compareConfigs(
    expected: TrailConfig?,
    actual: TrailConfig?
  ): List<TrailDifference> {
    val differences = mutableListOf<TrailDifference>()

    when {
      expected == null && actual == null -> {
        // Both missing config - that's fine
      }
      expected != null && actual == null -> {
        differences.add(TrailDifference.MissingActualConfig)
      }
      expected == null && actual != null -> {
        differences.add(TrailDifference.MissingExpectedConfig)
      }
      expected != null && actual != null -> {
        if (expected.title != actual.title) {
          differences.add(TrailDifference.TitleMismatch(expected.title, actual.title))
        }
        if (expected.description != actual.description) {
          differences.add(TrailDifference.DescriptionMismatch(expected.description, actual.description))
        }
        if (expected.context != actual.context) {
          differences.add(TrailDifference.ContextMismatch(expected.context, actual.context))
        }
        if (expected.priority != actual.priority) {
          differences.add(TrailDifference.PriorityMismatch(expected.priority, actual.priority))
        }
      }
    }

    return differences
  }

  private fun compareNaturalLanguageSteps(
    expected: List<String>,
    actual: List<String>
  ): List<TrailDifference> {
    val differences = mutableListOf<TrailDifference>()

    if (expected.size != actual.size) {
      differences.add(TrailDifference.StepCountMismatch(expected.size, actual.size))
    }

    // Compare each step that exists in both lists
    expected.forEachIndexed { index, expectedStep ->
      val actualStep = actual.getOrNull(index)
      if (expectedStep != actualStep) {
        differences.add(TrailDifference.StepContentMismatch(index, expectedStep, actualStep))
      }
    }

    return differences
  }

  /**
   * Check if multiple trails have conflicting natural language steps.
   * This is useful to detect when different platform recordings (e.g., android vs iOS)
   * have diverged and need to be synchronized.
   *
   * @param trailStepLists A list of natural language step lists from different trails
   * @return true if any trails have different steps, false if all trails match
   */
  fun hasConflictingSteps(trailStepLists: List<List<String>>): Boolean {
    if (trailStepLists.size < 2) return false

    val reference = trailStepLists.first()
    return trailStepLists.drop(1).any { steps ->
      steps != reference
    }
  }

  /**
   * Check if multiple trails (as TrailYamlItem lists) have conflicting natural language steps.
   *
   * @param trails A list of trail item lists from different trail files
   * @return true if any trails have different steps, false if all trails match
   */
  fun hasConflictingTrails(trails: List<List<TrailYamlItem>>): Boolean {
    val stepLists = trails.map { extractNaturalLanguageSteps(it) }
    return hasConflictingSteps(stepLists)
  }

  /**
   * Find all pairs of trails that have conflicts and return detailed comparison results.
   * Useful for showing which specific trails conflict and how.
   *
   * @param namedTrails A map of trail name/identifier to its natural language steps
   * @return A list of conflicts, each containing the two trail names and their differences
   */
  fun findConflicts(namedTrails: Map<String, List<String>>): List<TrailConflict> {
    val conflicts = mutableListOf<TrailConflict>()
    val trailEntries = namedTrails.entries.toList()

    for (i in trailEntries.indices) {
      for (j in i + 1 until trailEntries.size) {
        val (name1, steps1) = trailEntries[i]
        val (name2, steps2) = trailEntries[j]

        val result = compareStepsOnly(steps1, steps2)
        if (!result.isMatch) {
          conflicts.add(TrailConflict(name1, name2, result))
        }
      }
    }

    return conflicts
  }

  companion object {
    /**
     * Shared default instance for convenience.
     */
    val Default: TrailComparator by lazy { TrailComparator() }
  }
}

/**
 * Represents a conflict between two trails.
 */
data class TrailConflict(
  val trailName1: String,
  val trailName2: String,
  val comparisonResult: TrailComparisonResult
)
