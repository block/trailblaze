package xyz.block.trailblaze.report

/**
 * The files a linked-media report writes into a session's directory for the report to load: today
 * the event streams, as `report-events-<16 hex>.js` (`eventsSidecarFile` in run-report-cli.ts, which
 * also leaves a `.<pid>.tmp` beside it while it writes).
 *
 * They are derived from the session's own event logs and are only meaningful to the report built
 * beside them, so anything that packages a session for someone else (an archive, an upload) leaves
 * them out rather than carry a second copy of the events.
 */
object ReportSidecarFiles {
  // Case-insensitive: on macOS and Windows "report-events-<hash>.JS" is the same file.
  private val SIDECAR_NAME = Regex("""report-events-[0-9a-f]{16}\.js(\.\d+\.tmp)?""", RegexOption.IGNORE_CASE)

  /** Whether [fileName], a file directly in a session directory, is a report sidecar. */
  fun isSidecar(fileName: String): Boolean = SIDECAR_NAME.matches(fileName)
}
