package xyz.block.trailblaze.report

import java.io.File

/**
 * The standalone report viewer bundled into this module's JAR — ONE self-contained HTML page that
 * renders a full interactive report from a session archive in the browser (`?zip=<archive-url>`,
 * paste, or drop), with no daemon and no backend.
 *
 * Baked in at build time by the `bakeViewerShell` task in this module's build file, because the
 * shell can only be *built* from this source tree (bun macros plus the Gradle-built selector
 * engine) but is *needed* wherever the CLI runs — publishing to a CDN, self-hosting, serving
 * `?zip=` links. A distributed binary therefore emits it as a plain resource copy, and the copy
 * always matches the renderer that binary generates reports with, because they shipped together.
 */
object ViewerShellResource {

  /** Classpath location the `bakeViewerShell` task packages the shell under. */
  const val RESOURCE_PATH: String = "xyz/block/trailblaze/report/report-viewer.html"

  /**
   * Write the bundled viewer shell to [destination], creating parent directories as needed.
   * Returns false when the resource is absent — a build that skipped `bakeViewerShell` (an IDE
   * classpath, a hand-rolled distribution), which callers surface rather than treat as fatal.
   */
  fun copyTo(destination: File): Boolean {
    val stream = ViewerShellResource::class.java.classLoader.getResourceAsStream(RESOURCE_PATH) ?: return false
    destination.parentFile?.mkdirs()
    stream.use { input -> destination.outputStream().use { output -> input.copyTo(output) } }
    return true
  }
}
