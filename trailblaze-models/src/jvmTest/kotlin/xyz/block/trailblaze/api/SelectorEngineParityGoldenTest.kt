package xyz.block.trailblaze.api

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * JVM side of the cross-platform selector-engine parity lock.
 *
 * Runs [TrailblazeSelectorAnalyzer]'s string-in/string-out boundary — the REAL daemon
 * classes — over the committed hierarchies in `trailblaze-selector-engine-js/parity/` and
 * asserts the output matches the committed golden
 * (`trailblaze-selector-engine-js/parity/expected-analysis.txt`). The Kotlin/JS engine's
 * `engine-parity.test.ts` (run by `:trailblaze-selector-engine-js:check` under bun)
 * recomputes the same corpus with the compiled bundle and byte-compares against the same
 * file — green on both sides proves the browser engine behaves byte-identically to the
 * daemon's JVM path: same strategies, same match sets, same centers, same serialized
 * selector text.
 *
 * When the engine's behavior changes intentionally (new strategy, minimizer change, escape
 * change), regenerate the golden and commit it alongside the change:
 *
 * ```
 * ./gradlew :trailblaze-models:jvmTest --tests "*SelectorEngineParityGoldenTest*" \
 *   -Dtrailblaze.updateSelectorEngineGolden=true
 * ```
 */
class SelectorEngineParityGoldenTest {

  private val parityDir = File("../trailblaze-selector-engine-js/parity").canonicalFile
  private val goldenFile = File(parityDir, "expected-analysis.txt")

  private val treeJsonParser = Json {
    classDiscriminator = "class"
    ignoreUnknownKeys = true
    isLenient = true
  }

  /** Corpus line format shared with `engine-parity.test.ts` — keep the two in sync. */
  private fun computeCorpus(): String {
    val out = StringBuilder()
    for (name in PARITY_TREES) {
      val treeFile = File(parityDir, name)
      assertTrue(treeFile.isFile, "Missing parity fixture: $treeFile")
      val treeJson = treeFile.readText()
      val root = treeJsonParser.decodeFromString(TrailblazeNode.serializer(), treeJson)
      val nodes = root.aggregate()
      for (n in nodes) {
        out.append(name).append('\t').append(n.nodeId).append('\t')
          .append(TrailblazeSelectorAnalyzer.computeSelectorAnalysisJson(treeJson, n.nodeId.toString()))
          .append('\n')
      }
      for (n in nodes) {
        val b = n.bounds ?: continue
        val x = (b.left + b.right) / 2
        val y = (b.top + b.bottom) / 2
        out.append("tap\t").append(name).append('\t').append(x).append(',').append(y).append('\t')
          .append(TrailblazeSelectorAnalyzer.resolveTapTargetJson(treeJson, x, y))
          .append('\n')
      }
    }
    return out.toString()
  }

  @Test
  fun goldenCorpusMatchesCommittedExpectations() {
    val corpus = computeCorpus()
    if (System.getProperty("trailblaze.updateSelectorEngineGolden") == "true") {
      goldenFile.writeText(corpus)
      println("Regenerated $goldenFile (${corpus.lines().size - 1} lines). Commit it alongside the engine change.")
      return
    }
    assertTrue(
      goldenFile.isFile,
      "Committed golden missing at $goldenFile. Regenerate with " +
        "-Dtrailblaze.updateSelectorEngineGolden=true (see class kdoc).",
    )
    assertEquals(
      goldenFile.readText(),
      corpus,
      "Selector-engine golden corpus drifted from the committed expectations. If the engine's " +
        "behavior changed intentionally, regenerate: ./gradlew :trailblaze-models:jvmTest " +
        "--tests \"*SelectorEngineParityGoldenTest*\" -Dtrailblaze.updateSelectorEngineGolden=true " +
        "and commit the updated expected-analysis.txt.",
    )
  }

  companion object {
    /**
     * Keep in sync with the tree list in `engine-parity.test.ts`. `tree.json` /
     * `tree-large.json` are captured androidAccessibility hierarchies; the rest are small
     * hand-built trees, one per remaining generator dialect, so all six dialects in the
     * bundle are byte-compared rather than merely compiled.
     */
    val PARITY_TREES = listOf(
      "tree.json",
      "tree-large.json",
      "tree-compose.json",
      "tree-web.json",
      "tree-ios-axe.json",
      "tree-ios-maestro.json",
      "tree-android-maestro.json",
    )
  }
}
