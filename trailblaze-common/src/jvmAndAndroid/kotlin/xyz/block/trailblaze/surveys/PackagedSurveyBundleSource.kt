package xyz.block.trailblaze.surveys

import java.io.File
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The surveys a test APK packaged, for `ctx.surveys` on a device: the device has no esbuild, so the
 * APK build bundles every survey file and ships it under [SurveyBundles.PACKAGED_DIR] (the Android
 * Gradle plugin's `trailmap { }` block, or `trailblaze inprocess make-test-apk`).
 *
 * A survey is found the way the daemon finds one, by its export name in the packaged survey files'
 * source, so the same names resolve and the same mistakes are refused.
 *
 * @param paths every packaged file, relative to [SurveyBundles.PACKAGED_DIR].
 * @param read a packaged file's text by that relative path, or null when it is not packaged.
 * @param targets the APK's targets: id → platform → app ids, for a survey scoped to a target.
 */
class PackagedSurveyBundleSource(
  paths: () -> List<String>,
  private val read: (String) -> String?,
  targets: () -> Map<String, Map<String, List<String>>>,
) : SurveyBundleSource {

  private val sources: List<Pair<String, String>> by lazy {
    paths().filter { SurveyBundles.isSurveyFile(it.substringAfterLast('/')) }.sorted().mapNotNull { path -> read(path)?.let { path to it } }
  }
  private val targets by lazy(targets)

  override fun load(name: String): SurveyBundle {
    val exporting = sources.filter { (_, text) -> name in SurveyBundles.exportedSurveyNames(text) }
    if (exporting.size > 1) {
      throw IllegalStateException(
        "Survey $name is exported by ${exporting.joinToString(" and ") { it.first }}. Surveys are named by their export, so rename one.",
      )
    }
    val (file, text) = exporting.firstOrNull()
      ?: throw SurveyNotFoundException(
        "No survey named $name. Surveys are named by their export, and this test APK packages: " +
          sources.flatMap { SurveyBundles.exportedSurveyNames(it.second) }.sorted().joinToString().ifEmpty { "(none)" } + ".",
      )
    val bundle = read(file + SurveyBundles.PACKAGED_BUNDLE_SUFFIX)
      ?: if (SurveyBundles.exportedSurveys(text).first { it.name == name }.requiresFinishedSession) {
        // Such a file often cannot be bundled for the sandbox at all; answer as its bundle would.
        finishedSessionOnly(name)
      } else {
        throw IllegalStateException(
          SurveyBundles.unbuildableMessage(
            name,
            file,
            read(file + SurveyBundles.PACKAGED_ERROR_SUFFIX)
              ?: "The test APK packaged this survey file without its bundle; rebuild the APK.",
          ),
        )
      }
    return SurveyBundle(source = bundle, file = file, targets = targets)
  }

  /** A bundle that answers like the survey runtime does for a survey that runs only after the session. */
  private fun finishedSessionOnly(name: String): String {
    val result = buildJsonObject {
      put("survey", name)
      put("passed", false)
      putJsonArray("findings") {}
      put("waitedMs", 0)
      put("message", "Survey $name sets requiresFinishedSession: true, so it runs only after the session ends and cannot run mid-trail.")
    }
    return "var ${SurveyBundles.GLOBAL_NAME} = { runLive: async () => ($result) };"
  }

  companion object {
    /** The surveys packaged under [root], laid out as under [SurveyBundles.PACKAGED_DIR]. */
    fun forDirectory(root: File, targets: () -> Map<String, Map<String, List<String>>> = { emptyMap() }): PackagedSurveyBundleSource =
      PackagedSurveyBundleSource(
        paths = { root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath }.toList() },
        read = { path -> File(root, path).takeIf { it.isFile }?.readText() },
        targets = targets,
      )
  }
}
