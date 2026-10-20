package xyz.block.trailblaze.events

import java.io.File
import java.io.RandomAccessFile
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import xyz.block.trailblaze.trailhead.EventMatch

/**
 * Reads an append-only session stream the way `grep` does: from a byte offset, through one reused
 * buffer, and with a byte search for the literals a match needs before any line is decoded. A line
 * that lacks one of [needles] is never turned into a `String`, let alone parsed as JSON, so a query
 * costs a scan of the bytes after its starting point plus a parse of the few candidate lines.
 *
 * Offsets are positions in the file, which only grows. A line with no newline yet is still being
 * written: it is never visited, and the offset a scan returns stops before it, so the next scan
 * picks it up whole.
 */
class EventStreamScanner(
  private val needles: List<Needle> = emptyList(),
  private val maxLineBytes: Int = DEFAULT_MAX_LINE_BYTES,
  private val bufferBytes: Int = DEFAULT_BUFFER_BYTES,
  /**
   * Treat the bytes after the last newline as a whole line, for a stream whose writer has finished
   * (a teardown read): its final record may lack a newline, and nothing will finish it.
   */
  private val finishedStream: Boolean = false,
) {

  /** Bytes a candidate line must contain; [ignoreCase] folds ASCII letters only. */
  class Needle(bytes: ByteArray, val ignoreCase: Boolean = false) {
    val bytes: ByteArray = if (ignoreCase) ByteArray(bytes.size) { lower(bytes[it]) } else bytes

    override fun toString(): String = String(bytes, Charsets.UTF_8) + if (ignoreCase) " (i)" else ""
  }

  /** What a scan touched, so a caller can report its cost. */
  class Stats {
    var bytesRead: Long = 0
    var lines: Int = 0
    var candidates: Int = 0
    var oversized: Int = 0
  }

  /**
   * Visits the candidate lines in `[from, until)` oldest first, where [until] is capped at the file's
   * length when the scan starts: bytes appended mid-scan wait for the next one, so a scan of a live
   * stream always ends. [visit] returns false to stop. Returns the offset just after the last
   * complete line read, the place to resume from.
   */
  fun forward(file: File, from: Long, stats: Stats, until: Long = Long.MAX_VALUE, visit: (String) -> Boolean): Long {
    RandomAccessFile(file, "r").use { raf ->
      val length = minOf(raf.length(), until)
      if (from >= length) return from
      raf.seek(from)
      val buf = ByteArray(bufferBytes)
      var carry = ByteArray(0)
      var carryLen = 0
      var carryOversized = false
      var bufStart = from
      var resumeAt = from
      while (bufStart < length) {
        val n = raf.read(buf, 0, minOf(bufferBytes.toLong(), length - bufStart).toInt())
        if (n <= 0) break
        stats.bytesRead += n
        var i = 0
        while (i < n) {
          val nl = indexOfNewline(buf, i, n)
          if (nl < 0) {
            // The rest of the buffer starts a line the next read finishes.
            val part = n - i
            if (carryOversized || carryLen + part > maxLineBytes) {
              carryOversized = true
              carryLen = 0
            } else {
              if (carry.size < carryLen + part) carry = carry.copyOf(maxOf(carry.size * 2, carryLen + part))
              System.arraycopy(buf, i, carry, carryLen, part)
              carryLen += part
            }
            break
          }
          val keepGoing = if (carryLen == 0 && !carryOversized) {
            line(buf, i, nl - i, stats, visit)
          } else {
            val part = nl - i
            val oversized = carryOversized || carryLen + part > maxLineBytes
            val result = if (oversized) {
              stats.lines++
              stats.oversized++
              true
            } else {
              if (carry.size < carryLen + part) carry = carry.copyOf(carryLen + part)
              System.arraycopy(buf, i, carry, carryLen, part)
              line(carry, 0, carryLen + part, stats, visit)
            }
            carryLen = 0
            carryOversized = false
            result
          }
          resumeAt = bufStart + nl + 1
          if (!keepGoing) return resumeAt
          i = nl + 1
        }
        bufStart += n
      }
      if (finishedStream && (carryLen > 0 || carryOversized)) {
        if (carryOversized) {
          stats.lines++
          stats.oversized++
        } else {
          line(carry, 0, carryLen, stats, visit)
        }
        resumeAt = length
      }
      return resumeAt
    }
  }

  /**
   * Visits the candidate lines in `[from, until)` newest first, reading backwards from [until]
   * (capped at the file's length) so a "latest match" query touches only the tail. [from] must be a
   * line start (0, a mark, or a cursor). Returns the offset just after the last complete line, the
   * place to resume from.
   */
  fun backward(file: File, from: Long, stats: Stats, until: Long = Long.MAX_VALUE, visit: (String) -> Boolean): Long {
    RandomAccessFile(file, "r").use { raf ->
      val buf = ByteArray(bufferBytes)
      var chunkEnd = minOf(raf.length(), until)
      var resumeAt = from
      var sawNewline = false
      var tail = ByteArray(0)
      var tailOversized = false
      while (chunkEnd > from) {
        val chunkStart = maxOf(from, chunkEnd - bufferBytes)
        val n = (chunkEnd - chunkStart).toInt()
        raf.seek(chunkStart)
        raf.readFully(buf, 0, n)
        stats.bytesRead += n
        var segmentEnd = n
        var j = n - 1
        while (j >= 0) {
          if (buf[j] == NEWLINE) {
            if (!sawNewline) {
              // Whatever follows the file's last newline is a line still being written.
              sawNewline = true
              resumeAt = chunkStart + j + 1
            } else if (tailOversized || tail.size + (segmentEnd - j - 1) > maxLineBytes) {
              stats.lines++
              stats.oversized++
            } else {
              val whole = ByteArray(segmentEnd - j - 1 + tail.size)
              System.arraycopy(buf, j + 1, whole, 0, segmentEnd - j - 1)
              System.arraycopy(tail, 0, whole, segmentEnd - j - 1, tail.size)
              if (!line(whole, 0, whole.size, stats, visit)) return resumeAt
            }
            tail = ByteArray(0)
            tailOversized = false
            segmentEnd = j
          }
          j--
        }
        if (sawNewline && segmentEnd > 0) {
          if (tailOversized || tail.size + segmentEnd > maxLineBytes) {
            tailOversized = true
            tail = ByteArray(0)
          } else {
            tail = buf.copyOfRange(0, segmentEnd) + tail
          }
        }
        chunkEnd = chunkStart
      }
      if (sawNewline && (tail.isNotEmpty() || tailOversized)) {
        if (tailOversized) {
          stats.lines++
          stats.oversized++
        } else {
          line(tail, 0, tail.size, stats, visit)
        }
      }
      return resumeAt
    }
  }

  private fun line(bytes: ByteArray, offset: Int, rawLength: Int, stats: Stats, visit: (String) -> Boolean): Boolean {
    val length = if (rawLength > 0 && bytes[offset + rawLength - 1] == CARRIAGE_RETURN) rawLength - 1 else rawLength
    stats.lines++
    if (length == 0) return true
    if (length > maxLineBytes) {
      stats.oversized++
      return true
    }
    for (needle in needles) {
      if (contains(bytes, offset, length, needle)) continue
      // A missing literal rejects the line only if no encoding of the line could have hidden it.
      if (mayHideLiteral(bytes, offset, length, needle)) continue
      return true
    }
    stats.candidates++
    return visit(String(bytes, offset, length, Charsets.UTF_8))
  }

  companion object {
    const val DEFAULT_BUFFER_BYTES: Int = 64 * 1024
    const val DEFAULT_MAX_LINE_BYTES: Int = 1_000_000

    private const val NEWLINE: Byte = '\n'.code.toByte()
    private const val CARRIAGE_RETURN: Byte = '\r'.code.toByte()

    /**
     * Whether [needle]'s absence from the raw bytes still leaves the decoded line able to contain it:
     * any character may be written as a `\uXXXX` escape, and a case-insensitive match also folds
     * non-ASCII letters, such as the dotless `ı` and the Kelvin sign, onto ASCII ones.
     */
    private fun mayHideLiteral(bytes: ByteArray, offset: Int, length: Int, needle: Needle): Boolean {
      val end = offset + length
      for (i in offset until end) {
        val b = bytes[i]
        if (b == BACKSLASH && i + 1 < end && bytes[i + 1] == LOWER_U) return true
        if (needle.ignoreCase && b < 0) return true
      }
      return false
    }

    private const val BACKSLASH: Byte = '\\'.code.toByte()
    private const val LOWER_U: Byte = 'u'.code.toByte()

    /** The shortest literal worth searching for; shorter runs reject too little to pay for the search. */
    private const val MIN_NEEDLE_CHARS = 3

    /**
     * Printable ASCII that every JSON encoder writes as itself. Left out: `"` and `\` (always
     * escaped), `/` (escaped by Android's `org.json`), and `<>&='` (escaped by Gson's HTML-safe
     * default). A needle drawn only from these bytes is on disk exactly as written, whichever
     * producer encoded the line, so the byte prefilter can only ever drop a line that cannot match.
     */
    private fun isVerbatim(c: Char): Boolean = c in ' '..'~' && c !in "\"\\/<>&='"

    private const val REGEX_META = ".^$*+?()[]{}|\\"

    /**
     * The literals a line must contain for [match] to accept it. Each is necessary, never
     * sufficient: [EventMatch] still decides every candidate. An exact string that is all verbatim
     * characters is searched with its quotes; otherwise its longest verbatim run stands in for it.
     * Booleans contribute their keyword. Numbers contribute nothing: they compare numerically, so
     * `200` also matches `2e2`. `null` contributes nothing (it also accepts an absent key), and a
     * regex contributes only when it is a plain literal.
     */
    fun needlesFor(match: JsonObject?): List<Needle> {
      if (match == null) return emptyList()
      val out = mutableListOf<Needle>()
      collect(match, out)
      return out.distinctBy { String(it.bytes, Charsets.UTF_8) to it.ignoreCase }
    }

    private fun collect(element: JsonElement, out: MutableList<Needle>) {
      when (element) {
        is JsonObject ->
          if (element.containsKey(EventMatch.REGEX_KEY)) {
            regexNeedle(element)?.let(out::add)
          } else {
            element.values.forEach { collect(it, out) }
          }
        is JsonNull -> Unit
        is JsonPrimitive -> primitiveNeedle(element)?.let(out::add)
        is JsonArray -> Unit
      }
    }

    private fun primitiveNeedle(value: JsonPrimitive): Needle? {
      if (value.isString) {
        val text = value.content
        if (text.length >= MIN_NEEDLE_CHARS - 2 && text.all(::isVerbatim)) {
          return Needle("\"$text\"".toByteArray(Charsets.UTF_8))
        }
        return longestVerbatimRun(text)?.let { Needle(it.toByteArray(Charsets.UTF_8)) }
      }
      return value.booleanOrNull?.let { Needle(it.toString().toByteArray(Charsets.UTF_8)) }
    }

    private fun regexNeedle(regex: JsonObject): Needle? {
      val source = (regex[EventMatch.REGEX_KEY] as? JsonPrimitive)?.contentOrNull ?: return null
      if (source.any { it in REGEX_META }) return null
      val flags = (regex[EventMatch.FLAGS_KEY] as? JsonPrimitive)?.contentOrNull.orEmpty()
      // A regex against an array or object sees its compact JSON, while the line on disk may space
      // its separators, so a run never spans one.
      val run = longestVerbatimRun(source, breakAt = JSON_SEPARATORS) ?: return null
      return Needle(run.toByteArray(Charsets.UTF_8), ignoreCase = 'i' in flags)
    }

    private const val JSON_SEPARATORS = ",:"

    private fun longestVerbatimRun(text: String, breakAt: String = ""): String? {
      var best: String? = null
      var start = -1
      for (i in 0..text.length) {
        val verbatim = i < text.length && isVerbatim(text[i]) && text[i] !in breakAt
        if (verbatim && start < 0) start = i
        if (!verbatim && start >= 0) {
          val run = text.substring(start, i)
          if (run.length >= MIN_NEEDLE_CHARS && (best == null || run.length > best.length)) best = run
          start = -1
        }
      }
      return best
    }

    private fun lower(b: Byte): Byte = if (b in 'A'.code.toByte()..'Z'.code.toByte()) (b + 32).toByte() else b

    private fun indexOfNewline(buf: ByteArray, from: Int, until: Int): Int {
      var i = from
      while (i < until) {
        if (buf[i] == NEWLINE) return i
        i++
      }
      return -1
    }

    internal fun contains(haystack: ByteArray, offset: Int, length: Int, needle: Needle): Boolean {
      val pattern = needle.bytes
      val m = pattern.size
      if (m == 0) return true
      if (m > length) return false
      val first = pattern[0]
      val last = offset + length - m
      var i = offset
      while (i <= last) {
        val b = if (needle.ignoreCase) lower(haystack[i]) else haystack[i]
        if (b == first) {
          var k = 1
          while (k < m) {
            val c = if (needle.ignoreCase) lower(haystack[i + k]) else haystack[i + k]
            if (c != pattern[k]) break
            k++
          }
          if (k == m) return true
        }
        i++
      }
      return false
    }
  }
}
