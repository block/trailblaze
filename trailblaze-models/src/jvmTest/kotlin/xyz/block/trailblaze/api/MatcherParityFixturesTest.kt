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

  /** `class` discriminator so the fixture's `driverDetail` blocks decode as [DriverNodeDetail]. */
  private val fixtureJson = Json {
    classDiscriminator = "class"
    ignoreUnknownKeys = true
  }

  @Serializable
  private data class ParityCase(
    val name: String,
    val pattern: String,
    val text: String,
    val nativeMatches: Boolean,
    val maestroMatches: Boolean,
  )

  @Serializable
  private data class HintBridgeCase(
    val name: String,
    val hintTextRegex: String,
    val type: String? = null,
    val label: String? = null,
    val value: String? = null,
    val help: String? = null,
    val matches: Boolean,
  )

  @Serializable
  private data class HitTestCase(
    val name: String,
    val tree: TrailblazeNode,
    val x: Int,
    val y: Int,
    val expectedNodeId: Long? = null,
  )

  @Serializable
  private data class ParityFixtures(
    val cases: List<ParityCase>,
    val iosMaestroHintBridgeCases: List<HintBridgeCase> = emptyList(),
    val hitTestCases: List<HitTestCase> = emptyList(),
  )

  @Test
  fun `matching behavior agrees with the shared parity fixtures`() {
    val fixtureFile = locate("sdks/typescript/src/matcher/matcher-parity-fixtures.json")
    val fixtures = fixtureJson.decodeFromString<ParityFixtures>(fixtureFile.readText())
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

  // Node-shaped contract for the iosMaestro→iosAxe bridge's hintTextRegex leg (help on any
  // type; label/value only on text-input types). Same source of truth and drift guarantee as
  // `cases` — the TS mirror is matcher-parity.test.ts.
  @Test
  fun `hint bridge behavior agrees with the shared parity fixtures`() {
    val fixtureFile = locate("sdks/typescript/src/matcher/matcher-parity-fixtures.json")
    val fixtures = fixtureJson.decodeFromString<ParityFixtures>(fixtureFile.readText())
    check(fixtures.iosMaestroHintBridgeCases.isNotEmpty()) { "hint-bridge parity section is empty: $fixtureFile" }

    val failures = fixtures.iosMaestroHintBridgeCases.mapNotNull { case ->
      val target = TrailblazeNode(
        nodeId = 2,
        bounds = TrailblazeNode.Bounds(0, 0, 100, 50),
        driverDetail = DriverNodeDetail.IosAxe(
          type = case.type,
          label = case.label,
          value = case.value,
          help = case.help,
        ),
      )
      val root = TrailblazeNode(
        nodeId = 1,
        bounds = TrailblazeNode.Bounds(0, 0, 200, 100),
        children = listOf(target),
        driverDetail = DriverNodeDetail.IosAxe(),
      )
      val selector = TrailblazeNodeSelector.withMatch(DriverNodeMatch.IosMaestro(hintTextRegex = case.hintTextRegex))
      val matched =
        TrailblazeNodeSelectorResolver.resolve(root, selector) is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch
      if (matched != case.matches) {
        "  ${case.name}: hintTextRegex=[${case.hintTextRegex}] type=[${case.type}] label=[${case.label}] " +
          "value=[${case.value}] help=[${case.help}] expected matches=${case.matches}, got $matched"
      } else {
        null
      }
    }

    if (failures.isNotEmpty()) {
      fail(
        buildString {
          appendLine("${failures.size} hint-bridge parity fixture case(s) disagree with the Kotlin resolver:")
          failures.forEach { appendLine(it) }
          append(
            "Either the bridge's hintTextRegex leg drifted, or the fixture was changed without " +
              "updating this implementation. Fix the resolver (or the fixture) and keep the TS mirror " +
              "(sdks/typescript/src/matcher/resolver.ts) in lockstep.",
          )
        },
      )
    }
  }

  // Which node a touch at (x, y) acts on. Same source of truth and drift guarantee as `cases`
  // — the TS mirror is matcher-parity.test.ts driving `trailblaze-node.ts`'s `hitTest`. The
  // expected-analysis.txt golden only locks this Kotlin against its own Kotlin/JS compile, so
  // without these cases the hand-written TS port can drift silently.
  @Test
  fun `hit-test behavior agrees with the shared parity fixtures`() {
    val fixtureFile = locate("sdks/typescript/src/matcher/matcher-parity-fixtures.json")
    val fixtures = fixtureJson.decodeFromString<ParityFixtures>(fixtureFile.readText())
    check(fixtures.hitTestCases.isNotEmpty()) { "hit-test parity section is empty: $fixtureFile" }

    val failures = fixtures.hitTestCases.mapNotNull { case ->
      val hit = case.tree.hitTest(case.x, case.y)
      if (hit?.nodeId != case.expectedNodeId) {
        "  ${case.name}: hitTest(${case.x}, ${case.y}) expected nodeId=${case.expectedNodeId}, " +
          "got ${hit?.nodeId} (${hit?.describe()})"
      } else {
        null
      }
    }

    if (failures.isNotEmpty()) {
      fail(
        buildString {
          appendLine("${failures.size} hit-test parity fixture case(s) disagree with the Kotlin implementation:")
          failures.forEach { appendLine(it) }
          append(
            "Either TrailblazeNode.hitTest drifted, or the fixture was changed without updating " +
              "this implementation. Fix hitTest (or the fixture) and keep the TS mirror " +
              "(sdks/typescript/src/matcher/trailblaze-node.ts) in lockstep.",
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
