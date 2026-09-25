package xyz.block.trailblaze.logs.client

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import xyz.block.trailblaze.toolcalls.TrailblazeToolDescriptor

/**
 * Identifies and resolves the once-per-session tool catalogs that
 * [TrailblazeLog.TrailblazeToolCatalogLog] carries.
 *
 * Single source of truth for the id, shared by the writer (which decides whether a catalog is new)
 * and every reader (which looks one up). If the two ever computed it differently, request logs
 * would point at catalogs that were never written.
 */
object TrailblazeToolCatalog {

  /**
   * Deliberately NOT [TrailblazeJsonInstance] / [TrailblazeJson.defaultWithoutToolsInstance]: the
   * id must depend only on the descriptors, and those instances are configurable (the latter is
   * even a reassignable `var`), so a catalog's id could change mid-session without its content
   * changing. A descriptor is plain serializable data with no polymorphic or contextual fields,
   * so a fixed local instance encodes it fully.
   */
  private val hashJson = Json {
    prettyPrint = false
    encodeDefaults = true
  }

  /**
   * Content hash of [descriptors], as 16 lowercase hex chars.
   *
   * ORDER-SENSITIVE — two catalogs holding the same tools in a different order hash differently
   * and each get their own log. Callers sort by name first (the writer does), which makes the id
   * depend on the tool set rather than on whatever order the registry happened to enumerate.
   *
   * FNV-1a rather than a real digest: `commonMain` reaches iOS/Native/wasm, where there is no
   * `MessageDigest`, and this is a dedupe key within a single session (a handful of catalogs, not
   * an adversarial input) rather than a security boundary.
   */
  fun idFor(descriptors: List<TrailblazeToolDescriptor>): String {
    val canonical = hashJson.encodeToString(ListSerializer(TrailblazeToolDescriptor.serializer()), descriptors)
    return fnv1a64Hex(canonical.encodeToByteArray())
  }

  /**
   * Standard 64-bit FNV-1a over UTF-8 bytes, so any other language's stock implementation
   * reproduces an id. Hashing UTF-16 code units instead agrees only on ASCII, and tool
   * descriptions routinely carry em-dashes and quotes.
   */
  internal fun fnv1a64Hex(bytes: ByteArray): String {
    var hash = FNV_OFFSET_BASIS
    for (byte in bytes) {
      hash = hash xor (byte.toLong() and 0xFF)
      hash *= FNV_PRIME
    }
    return hash.toHexString16()
  }

  /** Every catalog a session wrote, keyed by id, for use with [resolveToolOptions]. */
  fun catalogsIn(logs: List<TrailblazeLog>): Map<String, List<TrailblazeToolDescriptor>> = logs
    .filterIsInstance<TrailblazeLog.TrailblazeToolCatalogLog>()
    .associate { it.toolCatalogId to it.toolOptions }

  /**
   * The descriptors [log] was offered, from either log generation.
   *
   * Reads the catalog when the log points at one, and falls back to the log's inline
   * [TrailblazeLog.TrailblazeLlmRequestLog.toolOptions] for logs written before the split. Returns
   * empty when a log names a catalog that isn't in [catalogs] — which happens legitimately when a
   * caller holds only part of a session (a single log file, a streamed tail that joined late), so
   * treat it as "unknown", not "no tools were offered".
   */
  fun resolveToolOptions(
    log: TrailblazeLog.TrailblazeLlmRequestLog,
    catalogs: Map<String, List<TrailblazeToolDescriptor>>,
  ): List<TrailblazeToolDescriptor> {
    val catalogId = log.toolCatalogId ?: return log.toolOptions
    return catalogs[catalogId] ?: emptyList()
  }

  /**
   * Names of the tools [log] was offered, from either log generation and WITHOUT the catalog.
   *
   * Post-split logs carry the names inline, so the common "list what was available" and "how many
   * were available" reads never have to find and parse the catalog.
   */
  fun toolNames(log: TrailblazeLog.TrailblazeLlmRequestLog): List<String> =
    if (log.toolCatalogId != null) log.toolNames else log.toolOptions.map { it.name }

  /** How many tools [log] was offered. See [toolNames] — needs no catalog. */
  fun toolCount(log: TrailblazeLog.TrailblazeLlmRequestLog): Int =
    if (log.toolCatalogId != null) log.toolNames.size else log.toolOptions.size

  private const val FNV_OFFSET_BASIS = -3750763034362895579L // 14695981039346656037 unsigned
  private const val FNV_PRIME = 1099511628211L

  private fun Long.toHexString16(): String {
    val digits = "0123456789abcdef"
    val chars = CharArray(16)
    for (i in 0 until 16) {
      chars[15 - i] = digits[((this ushr (i * 4)) and 0xF).toInt()]
    }
    return chars.concatToString()
  }
}
