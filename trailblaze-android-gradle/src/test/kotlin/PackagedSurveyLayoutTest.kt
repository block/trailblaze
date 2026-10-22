import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The plugin can't depend on `trailblaze-common`, so it copies the packaged-survey layout. A drift
 * between the two packages surveys where the device never looks, and every trail asking for one
 * fails with "this runtime packaged no surveys".
 */
class PackagedSurveyLayoutTest {

  @Test
  fun `the plugin packages surveys at the layout the device reads`() {
    val source = File(System.getProperty("trailblaze.surveyBundlesSource")).readText()
    fun constant(name: String) =
      Regex("""const val $name: String = "([^"]*)"""").find(source)?.groupValues?.get(1)
        ?: error("SurveyBundles.$name not found")

    assertEquals(constant("PACKAGED_DIR"), PACKAGED_SURVEYS_DIR)
    assertEquals(constant("PACKAGED_BUNDLE_SUFFIX"), PACKAGED_SURVEY_BUNDLE_SUFFIX)
    assertEquals(constant("PACKAGED_ERROR_SUFFIX"), PACKAGED_SURVEY_ERROR_SUFFIX)
  }
}
