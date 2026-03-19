package xyz.block.trailblaze.report

import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.GitUtils
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Resolves the WASM report template and trailblaze-ui project directory.
 *
 * Template resolution order:
 * 1. Local file at git root: `trailblaze_report_template.html` (dev mode — freshly built)
 * 2. Classpath resource: `/trailblaze_report_template.html` (production — bundled in JAR)
 * 3. If in a dev environment with trailblaze-ui source, build the template via Gradle (one-time)
 */
object ReportTemplateResolver {

  private const val TEMPLATE_FILENAME = "trailblaze_report_template.html"

  /**
   * Resolves the report template file, checking local git root first,
   * then falling back to the classpath (bundled in JAR).
   * In development, if no template exists but the trailblaze-ui source is present,
   * builds it automatically (one-time).
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

    // 4. Dev mode: build the template if trailblaze-ui source exists
    val trailblazeUiDir = findTrailblazeUiDir()
    if (trailblazeUiDir != null && gitRoot != null) {
      Console.log("[ReportTemplate] No template found — building from source (one-time)...")
      val built = buildReportTemplate(gitRoot)
      if (built) {
        // Check all known locations after build
        return localTemplate?.takeIf { it.exists() }
          ?: findBuildOutputTemplate(gitRoot)
      }
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
    // Internal monorepo: opensource/trailblaze-report/build/report-template/trailblaze_report.html
    val internalPath = File(gitRoot, "opensource/trailblaze-report/build/report-template/trailblaze_report.html")
    if (internalPath.exists()) return internalPath
    // Standalone: trailblaze-report/build/report-template/trailblaze_report.html
    val standalonePath = File(gitRoot, "trailblaze-report/build/report-template/trailblaze_report.html")
    if (standalonePath.exists()) return standalonePath
    return null
  }

  /**
   * Builds the report template by running the Gradle `generateReportTemplate` task.
   * This is only called during development when no template exists yet.
   * The task builds the WASM UI and generates a blank template HTML with it embedded.
   *
   * @return true if the build succeeded.
   */
  private fun buildReportTemplate(gitRoot: File): Boolean {
    val gradleTask = ":trailblaze-report:generateReportTemplate"
    Console.log("[ReportTemplate] Running: ./gradlew $gradleTask")
    Console.log("[ReportTemplate] This may take a few minutes on first run.")

    try {
      val gradlew = File(gitRoot, "gradlew")
      if (!gradlew.exists()) return false

      val process = ProcessBuilder(gradlew.absolutePath, gradleTask, "--no-daemon")
        .directory(gitRoot)
        .redirectErrorStream(true)
        .start()

      // Stream output so the user can see progress
      process.inputStream.bufferedReader().forEachLine { line ->
        Console.log("[ReportTemplate] $line")
      }

      val completed = process.waitFor(10, TimeUnit.MINUTES)
      if (!completed) {
        process.destroyForcibly()
        Console.error("[ReportTemplate] Build timed out after 10 minutes")
        return false
      }

      if (process.exitValue() != 0) {
        Console.error("[ReportTemplate] Build failed with exit code ${process.exitValue()}")
        return false
      }

      Console.log("[ReportTemplate] Template built successfully")
      return true
    } catch (e: Exception) {
      Console.error("[ReportTemplate] Failed to build template: ${e.message}")
      return false
    }
  }

  /**
   * Finds the trailblaze-ui project directory relative to the git root.
   * Checks both monorepo and standalone layouts.
   *
   * @return the trailblaze-ui directory, or null if not found.
   */
  fun findTrailblazeUiDir(): File? {
    val gitRoot = getGitRoot() ?: return null
    // Internal monorepo path
    val internalPath = File(gitRoot, "opensource/trailblaze-ui")
    if (internalPath.exists()) return internalPath
    // Standalone repo path
    val standalonePath = File(gitRoot, "trailblaze-ui")
    if (standalonePath.exists()) return standalonePath
    return null
  }

  fun getGitRoot(): File? = GitUtils.getGitRootViaCommand()?.let { File(it) }
}
