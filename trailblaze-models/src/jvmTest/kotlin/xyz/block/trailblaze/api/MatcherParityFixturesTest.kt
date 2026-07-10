package xyz.block.trailblaze.api

import java.io.File
import kotlin.test.Test
import kotlin.test.fail
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Cross-language behavioral contract for text-pattern matching.
 *
 * `sdks/typescript/src/matcher/matcher-parity-fixtures.json` is the single source of truth for
 * expected matching behavior, consumed by BOTH this test (driving the real
 * [TrailblazeNodeSelectorResolver]) and the TS mirror's `matcher-parity.test.ts` (driving the
 * real `resolver.ts`). A semantic drift in either implementation fails that side's suite.
 *
 * To change matching semantics: update both implementations AND the fixture in the same
 * change. Never encode new semantics in only one language's tests.
 */
class MatcherParityFixturesTest {

  @Serializable
  private data class ParityCase(
    val name: String,
    val pattern: String,
    val text: String,
    val nativeMatches: Boolean,
    val maestroMatches: Boolean,
  )

  @Serializable
  private data class ParityFixtures(val cases: List<ParityCase>)

  @Test
  fun `matching behavior agrees with the shared parity fixtures`() {
    val fixtureFile = locate("sdks/typescript/src/matcher/matcher-parity-fixtures.json")
    val fixtures = Json { ignoreUnknownKeys = true }.decodeFromString<ParityFixtures>(fixtureFile.readText())
    check(fixtures.cases.isNotEmpty()) { "parity fixture file is empty: $fixtureFile" }

    // Every case runs through BOTH dialects (native shape asserting `nativeMatches`, Maestro
    // shape asserting `maestroMatches`) and, within each dialect, TWO different match fields.
    // All `*Regex` fields of a shape share the one `matchesPattern`, so this locks the
    // semantics as field-uniform: a future per-field fork of the matching logic fails here.
    val fields = listOf<Triple<String, (ParityCase) -> Pair<DriverNodeDetail, DriverNodeMatch>, (ParityCase) -> Boolean>>(
      Triple(
        "native/textRegex",
        { case ->
          DriverNodeDetail.AndroidAccessibility(text = case.text) to
            DriverNodeMatch.AndroidAccessibility(textRegex = case.pattern)
        },
        { it.nativeMatches },
      ),
      Triple(
        "native/contentDescriptionRegex",
        { case ->
          DriverNodeDetail.AndroidAccessibility(contentDescription = case.text) to
            DriverNodeMatch.AndroidAccessibility(contentDescriptionRegex = case.pattern)
        },
        { it.nativeMatches },
      ),
      Triple(
        "maestro/textRegex",
        { case ->
          DriverNodeDetail.AndroidMaestro(text = case.text) to
            DriverNodeMatch.AndroidMaestro(textRegex = case.pattern)
        },
        { it.maestroMatches },
      ),
      Triple(
        "maestro/accessibilityTextRegex",
        { case ->
          DriverNodeDetail.AndroidMaestro(accessibilityText = case.text) to
            DriverNodeMatch.AndroidMaestro(accessibilityTextRegex = case.pattern)
        },
        { it.maestroMatches },
      ),
      // iosMaestro carries the same MAESTRO dialect as androidMaestro — exercised explicitly so
      // its dialect wiring can't silently revert to native while all other tests stay green.
      Triple(
        "maestro/iosMaestro.textRegex",
        { case ->
          DriverNodeDetail.IosMaestro(text = case.text) to
            DriverNodeMatch.IosMaestro(textRegex = case.pattern)
        },
        { it.maestroMatches },
      ),
    )

    val failures = fixtures.cases.flatMap { case ->
      fields.mapNotNull { (fieldName, build, expected) ->
        val (detail, match) = build(case)
        val target = TrailblazeNode(
          nodeId = 2,
          bounds = TrailblazeNode.Bounds(0, 0, 100, 50),
          driverDetail = detail,
        )
        val root = TrailblazeNode(
          nodeId = 1,
          bounds = TrailblazeNode.Bounds(0, 0, 200, 100),
          children = listOf(target),
          driverDetail = DriverNodeDetail.AndroidAccessibility(),
        )
        val selector = TrailblazeNodeSelector.withMatch(match)
        val matched =
          TrailblazeNodeSelectorResolver.resolve(root, selector) is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch
        if (matched != expected(case)) {
          "  ${case.name} [$fieldName]: pattern=[${case.pattern}] text=[${case.text}] expected matches=${expected(case)}, got $matched"
        } else {
          null
        }
      }
    }

    if (failures.isNotEmpty()) {
      fail(
        buildString {
          appendLine("${failures.size} parity fixture case(s) disagree with the Kotlin resolver:")
          failures.forEach { appendLine(it) }
          append(
            "Either the resolver's matching semantics drifted, or the fixture was changed without " +
              "updating this implementation. Fix the resolver (or the fixture) and keep the TS mirror " +
              "(sdks/typescript/src/matcher/resolver.ts) in lockstep.",
          )
        },
      )
    }
  }

  /**
   * Walk up from the JVM working dir to find the repo-root-anchored file. Same anchor pattern as
   * `BundlerYamlSchemaDriftTest.locate` — robust to invocation from any module's project dir and
   * to the anchor sitting at a different depth across repo layouts.
   */
  private fun locate(repoRelativePath: String): File {
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    while (dir != null) {
      val candidate = File(dir, repoRelativePath)
      if (candidate.isFile) return candidate
      dir = dir.parentFile
    }
    fail("Could not locate $repoRelativePath by walking up from ${System.getProperty("user.dir")}.")
  }
}
