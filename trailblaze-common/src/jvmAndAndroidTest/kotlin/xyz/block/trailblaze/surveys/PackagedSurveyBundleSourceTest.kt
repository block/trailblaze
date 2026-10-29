package xyz.block.trailblaze.surveys

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PackagedSurveyBundleSourceTest {

  private val checkout = "trailmaps/shop/surveys/checkout.survey.ts"
  private val checkoutSource = "export const checkoutDone = trailblaze.survey({ description: \"Checkout finished\", finding: { analytics: { name: \"done\" } } });"

  private fun source(files: Map<String, String>, targets: Map<String, Map<String, List<String>>> = emptyMap()) =
    PackagedSurveyBundleSource(paths = { files.keys.toList() }, read = { files[it] }, targets = { targets })

  @Test
  fun `serves the packaged bundle of the file exporting the name, with the APK's targets`() {
    val targets = mapOf("shop" to mapOf("android" to listOf("com.example.shop")))
    val bundle = source(
      mapOf(checkout to checkoutSource, checkout + SurveyBundles.PACKAGED_BUNDLE_SUFFIX to "// built"),
      targets,
    ).load("checkoutDone")

    assertEquals("// built", bundle.source)
    assertEquals(checkout, bundle.file)
    assertEquals(targets, bundle.targets)
  }

  @Test
  fun `an unknown name lists what the APK packages`() {
    val e = assertFailsWith<SurveyNotFoundException> { source(mapOf(checkout to checkoutSource)).load("stray") }
    assertTrue(e.message!!.contains("this test APK packages: checkoutDone."), e.message)
  }

  @Test
  fun `a name exported by two files is refused, naming both`() {
    val other = "trailmaps/other/surveys/other.survey.ts"
    val e = assertFailsWith<IllegalStateException> {
      source(mapOf(checkout to checkoutSource, other to checkoutSource)).load("checkoutDone")
    }
    assertTrue(e.message!!.contains("exported by $other and $checkout"), e.message)
  }

  @Test
  fun `a survey packaged without its bundle reports why the build could not make one`() {
    val e = assertFailsWith<IllegalStateException> {
      source(mapOf(checkout to checkoutSource, checkout + SurveyBundles.PACKAGED_ERROR_SUFFIX to "Could not resolve \"node:fs\"")).load("checkoutDone")
    }
    assertTrue(e.message!!.contains("Could not resolve \"node:fs\""), e.message)
    assertTrue(e.message!!.contains("requiresFinishedSession: true"), e.message)

    val unexplained = assertFailsWith<IllegalStateException> { source(mapOf(checkout to checkoutSource)).load("checkoutDone") }
    assertTrue(unexplained.message!!.contains("rebuild the APK"), unexplained.message)
  }

  @Test
  fun `a survey that runs only after the session answers so without a bundle`() {
    val weekly = "trailmaps/shop/surveys/weekly.survey.ts"
    val bundle = source(
      mapOf(weekly to "export const weeklyTotals = survey({ description: \"Weekly totals\", requiresFinishedSession: true, finding: {} });"),
    ).load("weeklyTotals")

    assertTrue(bundle.source.startsWith("var ${SurveyBundles.GLOBAL_NAME} = "), bundle.source)
    assertTrue(bundle.source.contains("runs only after the session ends"), bundle.source)
  }

  @Test
  fun `only survey files are read for exports`() {
    val e = assertFailsWith<SurveyNotFoundException> {
      source(mapOf("trailmaps/shop/surveys/notes.ts" to checkoutSource)).load("checkoutDone")
    }
    assertTrue(e.message!!.contains("packages: (none)."), e.message)
  }
}
