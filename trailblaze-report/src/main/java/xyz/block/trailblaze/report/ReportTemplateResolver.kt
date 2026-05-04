package xyz.block.trailblaze.report

import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.GitUtils
import java.io.File

/**
 * Resolves the WASM report template and trailblaze-ui project directory.
 *
 * Template resolution order:
 * 1. Local file at git root: `trailblaze_report_template.html` (dev mode — freshly built)
 * 2. Gradle build output: `trailblaze-report/build/report-template/` (previously built)
 * 3. Classpath resource: `/trailblaze_report_template.html` (production — bundled in JAR)
 */
object ReportTemplateResolver {

  private const val TEMPLATE_FILENAME = "trailblaze_report_template.html"

  /**
   * Resolves the report template file, checking local git root first,
   * then Gradle build output, then falling back to the classpath (bundled in JAR).
   *
   * @return the template [File], or null if not found.
   */
  fun resolveTemplate(): File? {
    val gitRoot = getGitRoot()

    // 1. Check git root for local template
    val localTemplate = gitRoot?.let { File(it, TEMPLATE_FILENAME) }
    if (localTemplate?.exists() == true) {
      Console.log("[ReportTemplate] Found local template at git root")
      return localTemplate
    }

    // 2. Check Gradle build output (dev mode — previously built but not at git root)
    val buildOutputTemplate = findBuildOutputTemplate(gitRoot)
    if (buildOutputTemplate != null) {
      Console.log("[ReportTemplate] Found template in Gradle build output")
      return buildOutputTemplate
    }

    // 3. Fall back to classpath (bundled in JAR)
    val bundledStream = ReportTemplateResolver::class.java.getResourceAsStream("/$TEMPLATE_FILENAME")
    if (bundledStream != null) {
      Console.log("[ReportTemplate] Using bundled template from classpath")
      val tempFile = File.createTempFile("trailblaze_report_template", ".html")
      tempFile.deleteOnExit()
      tempFile.outputStream().use { bundledStream.copyTo(it) }
      return tempFile
    }

    Console.log("[ReportTemplate] No report template found")
    return null
  }

  /**
   * Checks the Gradle build output directory for a previously built template.
   * The `generateReportTemplate` task outputs to `trailblaze-report/build/report-template/`.
   */
  private fun findBuildOutputTemplate(gitRoot: File?): File? {
    if (gitRoot == null) return null
    // Standalone layout: <trailblaze-report>/build/report-template/trailblaze_report.html
    val standalonePath = File(gitRoot, "trailblaze-report/build/report-template/trailblaze_report.html")
    if (standalonePath.exists()) return standalonePath
    // Nested layout: when Trailblaze is embedded as a subdirectory of a larger repo.
    val nestedPath = File(File(gitRoot, "opensource"), "trailblaze-report/build/report-template/trailblaze_report.html")
    return nestedPath.takeIf { it.exists() }
  }

  /**
   * Finds the trailblaze-ui project directory relative to the git root.
   * Supports both the standalone repo layout and a nested layout where
   * Trailblaze is embedded under a subdirectory of a larger repo.
   *
   * @return the trailblaze-ui directory, or null if not found.
   */
  fun findTrailblazeUiDir(): File? {
    val gitRoot = getGitRoot() ?: return null
    val standalonePath = File(gitRoot, "trailblaze-ui")
    if (standalonePath.exists()) return standalonePath
    val nestedPath = File(File(gitRoot, "opensource"), "trailblaze-ui")
    return nestedPath.takeIf { it.exists() }
  }

  fun getGitRoot(): File? = GitUtils.getGitRootViaCommand()?.let { File(it) }
}
