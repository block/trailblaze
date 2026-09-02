package xyz.block.trailblaze.host.golden

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.zip.ZipFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import xyz.block.trailblaze.util.Console

/**
 * Resolves a snapshot-baseline reference into the snapshot screenshots of a PREVIOUS run, so the
 * current run's `takeSnapshot` captures can be diffed against a known-good run instead of golden
 * files checked into the repo.
 *
 * A reference is one of:
 *  - an `http(s)` URL to a session logs zip (e.g. the CI artifact store's
 *    `results/trail/<test_key>/<device>/latest_success.zip`, or any per-run zip),
 *  - a local path to such a zip,
 *  - a local path to an already-extracted session directory.
 *
 * The session zips CI publishes contain one top-level directory (the session id) holding the
 * session's `*.json` log entries and screenshot PNGs — the same layout as a local
 * `logs/<sessionId>/` directory.
 *
 * Baseline log entries are read with a schema-free JSON scan (class discriminator + the two
 * snapshot fields) rather than the full [xyz.block.trailblaze.logs.client.TrailblazeLog]
 * decoder, so a baseline produced by an older or newer build still resolves as long as snapshot
 * logs keep their `displayName`/`screenshotFile` fields.
 */
object SnapshotBaselineSource {

  /** Wire value of the polymorphic `class` discriminator for snapshot log entries. */
  private const val SNAPSHOT_LOG_CLASS_SUFFIX = ".TrailblazeSnapshotLog"

  private val lenientJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
  }

  /**
   * How much a baseline may write to disk: the downloaded archive, and separately the total of
   * everything extracted from it. A baseline is one session's screenshots and JSON logs, so a real
   * one is orders of magnitude under this. The budget is what bounds the two ways a reference can
   * fill the disk of the machine running the comparison: a URL that turns out to serve something
   * enormous, and an archive whose entries expand far past its own compressed size.
   */
  internal const val MAX_BASELINE_BYTES: Long = 2L * 1024 * 1024 * 1024

  /** Companion cap on entry *count*, so an archive of a million empty files is refused too. */
  internal const val MAX_BASELINE_ENTRIES: Int = 50_000

  /**
   * Largest session-log JSON entry the scan below will read. One log record — even a snapshot's
   * view hierarchy — is orders of magnitude under this; the cap is what stops a resolved archive
   * from being pulled wholly into memory by a `readText()` on a file whose size nothing checked.
   */
  internal const val MAX_LOG_ENTRY_BYTES: Long = 32L * 1024 * 1024

  /**
   * Wall-clock allowance for the response BODY, once the server has answered.
   *
   * Separate from [HttpRequest.timeout], which bounds only the wait for response headers.
   */
  internal val BODY_TIMEOUT: Duration = Duration.ofMinutes(10)

  /**
   * A baseline run's snapshots, resolved and ready to compare against.
   *
   * @param sessionDir The extracted (or local) baseline session directory.
   * @param snapshotsByName Snapshot screenshot files keyed by snapshot name, each list in
   *   capture order — so a trail that snapshots the same name twice compares each occurrence
   *   against the matching occurrence in the baseline.
   * @param sourceDescription Human-readable origin (URL or path) for log messages.
   */
  data class ResolvedBaseline(
    val sessionDir: File,
    val snapshotsByName: Map<String, List<File>>,
    val sourceDescription: String,
  )

  /**
   * Resolves [ref] into a [ResolvedBaseline], downloading/extracting into [workDir] as needed.
   *
   * Throws [IllegalStateException] with an actionable message when the reference cannot be
   * resolved — a caller that asked for baseline comparison must fail loudly rather than pass
   * silently with nothing compared.
   */
  fun resolve(ref: String, workDir: File): ResolvedBaseline {
    val sessionDir: File = when {
      ref.startsWith("http://") || ref.startsWith("https://") -> {
        val zipFile = File(workDir, "baseline-session.zip")
        download(ref, zipFile)
        extractZip(zipFile, File(workDir, "baseline-session"))
      }
      ref.endsWith(".zip") -> {
        val zipFile = File(ref)
        check(zipFile.isFile) { "Snapshot baseline zip not found: ${zipFile.absolutePath}" }
        extractZip(zipFile, File(workDir, "baseline-session"))
      }
      else -> {
        val dir = File(ref)
        check(dir.isDirectory) {
          "Snapshot baseline '$ref' is not an http(s) URL, a .zip file, or a directory"
        }
        dir
      }
    }

    val resolvedSessionDir = locateSessionDir(sessionDir)
      ?: error(
        "Snapshot baseline '$ref' contains no session log JSON files " +
          "(looked in ${sessionDir.absolutePath} and its direct subdirectories)",
      )

    return ResolvedBaseline(
      sessionDir = resolvedSessionDir,
      snapshotsByName = readSnapshotEntries(resolvedSessionDir),
      sourceDescription = ref,
    )
  }

  /** Fetches [url] into [destination], refusing a non-2xx status or a body past [maxBytes]. */
  internal fun download(
    url: String,
    destination: File,
    maxBytes: Long = MAX_BASELINE_BYTES,
    bodyTimeout: Duration = BODY_TIMEOUT,
  ) {
    destination.parentFile?.mkdirs()
    val client = HttpClient.newBuilder()
      .followRedirects(HttpClient.Redirect.NORMAL)
      .connectTimeout(Duration.ofSeconds(30))
      .build()
    val request = HttpRequest.newBuilder(URI.create(url))
      .timeout(Duration.ofMinutes(5))
      .GET()
      .build()
    // The body is streamed rather than handed straight to a file so the status can be rejected
    // before anything is written, and so the write stops at [MAX_BASELINE_BYTES] instead of
    // following whatever the server decides to send.
    val response = try {
      client.send(request, HttpResponse.BodyHandlers.ofInputStream())
    } catch (e: Exception) {
      // ConnectException and friends often carry a null message — name the class instead.
      error("Could not download snapshot baseline from $url: ${e.message ?: e::class.simpleName}")
    }
    try {
      response.body().use { body ->
        check(response.statusCode() in 200..299) {
          "Could not download snapshot baseline from $url: HTTP ${response.statusCode()}"
        }
        destination.outputStream().use { output ->
          copyWithDeadline(body, output, maxBytes, url, bodyTimeout)
        }
      }
    } catch (e: Throwable) {
      // A partial or refused download is not a baseline — leave nothing behind for a later step to
      // mistake for one.
      destination.delete()
      if (e is IllegalStateException) throw e
      error("Could not download snapshot baseline from $url: ${e.message ?: e::class.simpleName}")
    }
  }

  /**
   * Copies the response body under a wall-clock [timeout].
   *
   * `HttpRequest.timeout` bounds only the wait for response HEADERS, so with a streaming body
   * handler a server that answers 200 and then stalls leaves the copy blocked in `read()` with
   * nothing to end it — no failure, no partial file, a command that never returns. The transfer
   * therefore runs on its own thread; when the deadline passes the caller closes the stream, which
   * is what actually unblocks the read (cancelling the task alone would not).
   */
  private fun copyWithDeadline(
    body: InputStream,
    output: OutputStream,
    maxBytes: Long,
    source: String,
    timeout: Duration,
  ) {
    val worker = Executors.newSingleThreadExecutor { runnable ->
      Thread(runnable, "snapshot-baseline-download").apply { isDaemon = true }
    }
    try {
      val copy = worker.submit<Long> { copyBounded(body, output, maxBytes, maxBytes, source) }
      try {
        copy.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
      } catch (_: TimeoutException) {
        runCatching { body.close() }
        copy.cancel(true)
        error("Snapshot baseline download from $source stalled for more than ${timeout.toSeconds()}s")
      } catch (e: ExecutionException) {
        throw e.cause ?: e
      }
    } finally {
      worker.shutdownNow()
    }
  }

  /**
   * Copies at most [remaining] bytes and fails instead of writing past that. Returns the number of
   * bytes written. [budget] is the whole allowance [remaining] is left from, so a failure names the
   * limit the caller set rather than whatever was left of it.
   */
  private fun copyBounded(input: InputStream, output: OutputStream, remaining: Long, budget: Long, source: String): Long {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var written = 0L
    while (true) {
      val read = input.read(buffer)
      if (read < 0) return written
      written += read
      check(written <= remaining) { "Snapshot baseline '$source' is larger than the $budget byte limit" }
      output.write(buffer, 0, read)
    }
  }

  /** Extracts [zipFile] into [destination], refusing more than [maxEntries] or [maxBytes] total. */
  internal fun extractZip(
    zipFile: File,
    destination: File,
    maxBytes: Long = MAX_BASELINE_BYTES,
    maxEntries: Int = MAX_BASELINE_ENTRIES,
  ): File {
    destination.mkdirs()
    val canonicalDest = destination.canonicalFile
    try {
      ZipFile(zipFile).use { zip ->
        var entries = 0
        // The declared sizes in a zip's headers are attacker-controlled, so the budget is spent
        // against bytes actually written, carried across entries — otherwise a thousand entries
        // each just under the cap would still exhaust the disk.
        var extracted = 0L
        for (entry in zip.entries()) {
          val target = File(destination, entry.name)
          // Zip-slip guard: refuse entries that escape the extraction root.
          check(target.canonicalFile.toPath().startsWith(canonicalDest.toPath())) {
            "Snapshot baseline zip entry escapes extraction directory: ${entry.name}"
          }
          check(++entries <= maxEntries) {
            "Snapshot baseline zip ${zipFile.name} holds more than $maxEntries entries"
          }
          if (entry.isDirectory) {
            target.mkdirs()
          } else {
            target.parentFile?.mkdirs()
            zip.getInputStream(entry).use { input ->
              target.outputStream().use { output ->
                extracted += copyBounded(input, output, maxBytes - extracted, maxBytes, zipFile.name)
              }
            }
          }
        }
      }
    } catch (e: IllegalStateException) {
      throw e
    } catch (e: Exception) {
      error("Could not extract snapshot baseline zip ${zipFile.absolutePath}: ${e.message}")
    }
    return destination
  }

  /**
   * Finds the directory holding the session's `*.json` log entries: the directory itself, or —
   * for the CI zip layout, which nests everything under one `<sessionId>/` entry — a direct
   * subdirectory. Null when neither holds any JSON files.
   */
  private fun locateSessionDir(dir: File): File? {
    fun hasJsonLogs(candidate: File): Boolean =
      candidate.listFiles()?.any { it.isFile && it.extension == "json" } == true

    if (hasJsonLogs(dir)) return dir
    // `listFiles()` order is filesystem-dependent, so an archive carrying more than one session
    // would compare against an arbitrary one — and pass or fail for a reason the caller can't see.
    // Name the ambiguity instead; the caller can point at the session it meant.
    val candidates = dir.listFiles().orEmpty().filter { it.isDirectory && hasJsonLogs(it) }.sortedBy { it.name }
    check(candidates.size <= 1) {
      "Snapshot baseline holds ${candidates.size} sessions (${candidates.joinToString(", ") { it.name }}); " +
        "point the reference at one of them"
    }
    return candidates.firstOrNull()
  }

  /**
   * The key an unnamed `takeSnapshot` capture is grouped under. Its screenshot file name embeds
   * the session id and the capture's epoch millis, so keying on the file name would give every
   * unnamed snapshot an identity that can never recur — both runs would fall back the same way,
   * every unnamed snapshot would go unmatched, and the run would pass having compared nothing.
   * One shared bucket per session pairs them by capture order instead.
   */
  internal const val UNNAMED_SNAPSHOT_KEY = "(unnamed snapshot)"

  /**
   * Scans the session's log JSON files for snapshot entries and groups their screenshot files
   * by snapshot name, in capture (timestamp) order.
   *
   * A `screenshotFile` is session-relative by contract; one that resolves outside the session
   * directory is dropped rather than read, so archive JSON can't drive a filesystem read of an
   * arbitrary path (and can't copy one into the generated diff artifact).
   */
  internal fun readSnapshotEntries(
    sessionDir: File,
    maxEntryBytes: Long = MAX_LOG_ENTRY_BYTES,
  ): Map<String, List<File>> {
    data class Entry(val name: String, val file: File, val timestamp: String)

    val sessionRoot = sessionDir.canonicalFile.toPath()

    val entries = sessionDir.listFiles()
      .orEmpty()
      .filter { it.isFile && it.extension == "json" }
      .mapNotNull { file ->
        // Nothing upstream bounds an individual entry: the archive budget is a total, and a
        // directory reference skips extraction entirely. A single unbounded `readText()` is enough
        // to exhaust the comparing machine's heap, so refuse the file rather than the JVM.
        if (file.length() > maxEntryBytes) {
          Console.log(
            "[SnapshotBaseline] ⚠️ ignoring session log over $maxEntryBytes bytes: ${file.name}",
          )
          return@mapNotNull null
        }
        val obj = try {
          lenientJson.parseToJsonElement(file.readText()).jsonObject
        } catch (_: Exception) {
          return@mapNotNull null
        }
        val logClass = (obj["class"] as? JsonPrimitive)?.content ?: return@mapNotNull null
        if (!logClass.endsWith(SNAPSHOT_LOG_CLASS_SUFFIX)) return@mapNotNull null
        val screenshotFile = (obj["screenshotFile"] as? JsonPrimitive)
          ?.takeIf { it !is JsonNull }?.content ?: return@mapNotNull null
        val screenshot = File(sessionDir, screenshotFile)
        if (!screenshot.canonicalFile.toPath().startsWith(sessionRoot)) {
          Console.log("[SnapshotBaseline] ⚠️ ignoring snapshot screenshot outside the session: $screenshotFile")
          return@mapNotNull null
        }
        // A snapshot without a display name serializes `"displayName": null` — treat that as
        // absent rather than the literal string "null".
        val displayName = (obj["displayName"] as? JsonPrimitive)
          ?.takeIf { it !is JsonNull }?.content
        Entry(
          name = displayName ?: UNNAMED_SNAPSHOT_KEY,
          file = screenshot,
          // ISO-8601 instants order lexicographically; only used to order same-name duplicates.
          timestamp = (obj["timestamp"] as? JsonPrimitive)?.content ?: "",
        )
      }
      .sortedBy { it.timestamp }

    return entries.groupBy({ it.name }, { it.file })
  }
}
