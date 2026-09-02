package xyz.block.trailblaze.report.utils

import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Test
import xyz.block.trailblaze.events.AttachmentRef
import xyz.block.trailblaze.events.SessionEvents

/**
 * Cross-language behavioral contract for the session-events artifact.
 *
 * `src/main/resources/xyz/block/trailblaze/report/session-events-parity-fixtures.json` is the
 * single source of truth for how event file names map to stream names, how NDJSON lines decode,
 * and which embedded objects a payload sweep reports as attachment refs, consumed by BOTH this
 * test (driving the real [SessionEvents.parseFileName] + [SessionEventsReader] +
 * [AttachmentRef.findAll]) and the TS mirror's `run-report-events.test.ts` (driving the real
 * `run-report-events.ts`). A semantic drift in either implementation fails that side's suite.
 *
 * To change either rule: update both implementations AND the fixture in the same change. Never
 * encode new semantics in only one language's tests. (Same fixture-parity pattern as
 * `MatcherParityFixturesTest` in :trailblaze-models.)
 */
class SessionEventsParityFixturesTest {

  @Serializable
  private data class FileNameCase(val file: String, val name: String?)

  /** `payload == null` means the line is skipped; `t == null` means no ordering timestamp. */
  @Serializable
  private data class LineCase(val line: String, val t: Long? = null, val payload: JsonElement? = null)

  /** `refs` is the exact expected sweep result in document order; `label == null` means absent. */
  @Serializable
  private data class AttachmentCase(val value: JsonElement, val refs: List<RefCase>)

  @Serializable
  private data class RefCase(val path: String, val mimeType: String, val sizeBytes: Long, val label: String? = null)

  @Serializable
  private data class ParityFixtures(
    val fileNames: List<FileNameCase>,
    val lines: List<LineCase>,
    val attachments: List<AttachmentCase>,
  )

  private val fixtures: ParityFixtures by lazy {
    // Repo-root-relative so the walk-up is robust to the anchor sitting at a different depth
    // across repo layouts, same as MatcherParityFixturesTest.
    val file = locate("trailblaze-report/src/main/resources/xyz/block/trailblaze/report/session-events-parity-fixtures.json")
    Json { ignoreUnknownKeys = true }.decodeFromString<ParityFixtures>(file.readText())
  }

  @Test
  fun `file-name parsing agrees with the shared parity fixtures`() {
    check(fixtures.fileNames.isNotEmpty())
    fixtures.fileNames.forEach { case ->
      assertEquals(case.name, SessionEvents.parseFileName(case.file), "file=${case.file}")
    }
  }

  @Test
  fun `line decoding agrees with the shared parity fixtures`() {
    check(fixtures.lines.isNotEmpty())
    val sessionDir = Files.createTempDirectory("events-parity").toFile()
    val eventsDir = File(sessionDir, SessionEvents.DIR_NAME).apply { mkdirs() }
    File(eventsDir, "parity.ndjson").writeText(fixtures.lines.joinToString("\n") { it.line })

    val stream = SessionEventsReader().read(sessionDir).single()
    val expected = fixtures.lines.filter { it.payload != null }
    assertEquals(expected.map { it.payload }, stream.events.map { it.data }, "decoded payloads (skipped lines excluded)")
    // The JVM reader represents "no ordering timestamp" as 0.
    assertEquals(expected.map { it.t ?: 0L }, stream.events.map { it.timeMs }, "ordering timestamps")
  }

  @Test
  fun `attachment detection agrees with the shared parity fixtures`() {
    check(fixtures.attachments.isNotEmpty())
    fixtures.attachments.forEach { case ->
      assertEquals(
        case.refs.map { AttachmentRef(path = it.path, mimeType = it.mimeType, sizeBytes = it.sizeBytes, label = it.label) },
        AttachmentRef.findAll(case.value),
        "value=${case.value}",
      )
    }
  }

  /**
   * Walk up from the JVM working dir to find the repo-root-anchored fixture. Same anchor pattern
   * as `MatcherParityFixturesTest.locate` — robust to invocation from any module's project dir.
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
