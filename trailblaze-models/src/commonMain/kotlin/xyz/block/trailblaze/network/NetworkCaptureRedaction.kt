package xyz.block.trailblaze.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import xyz.block.trailblaze.toolcalls.isCredentialFieldName
import xyz.block.trailblaze.toolcalls.withCredentialFieldsMasked

/**
 * What every captured exchange passes through before it is written to `network.ndjson`, so a run
 * that signs in can be shared without its credentials.
 *
 * Masks, keeping each key so a reader still sees what was sent:
 *  - credential headers (`Authorization`, `Cookie`, `Set-Cookie`, anything carrying a token,
 *    session, secret or API key);
 *  - credential-named fields ([isCredentialFieldName]) in a URL's query and in a JSON or
 *    form-encoded body, at any depth; a JSON body that doesn't parse is masked whole;
 *  - every value in `secrets` (the session's known secrets, such as a password a tool typed),
 *    wherever it appears in a header, URL or body, raw or in its URL- or JSON-encoded form.
 *
 * Pure: the caller supplies the secrets, so any capture writer can share one set of rules.
 */
object NetworkCaptureRedaction {

  /** Whether a header's value is a credential. Names are matched case-insensitively. */
  fun isSensitiveHeader(name: String): Boolean {
    if (isCredentialFieldName(name)) return true
    val normalized = name.lowercase()
    return SENSITIVE_HEADER_PARTS.any { it in normalized }
  }

  fun redactHeaders(headers: Map<String, String>, secrets: Collection<String> = emptyList()): Map<String, String> {
    val forms = secretForms(secrets)
    return headers.mapValues { (key, value) ->
      if (isSensitiveHeader(key)) REDACTED_VALUE else replaceSecrets(value, forms)
    }
  }

  /** [url] with credential-named query parameters and known secrets masked. */
  fun redactUrl(url: String, secrets: Collection<String> = emptyList()): String {
    val forms = secretForms(secrets)
    val queryStart = url.indexOf('?')
    if (queryStart < 0) return replaceSecrets(url, forms)
    val fragmentStart = url.indexOf('#', queryStart).takeIf { it >= 0 } ?: url.length
    val query = redactFormEncoded(url.substring(queryStart + 1, fragmentStart))
    return replaceSecrets(url.substring(0, queryStart + 1) + query + url.substring(fragmentStart), forms)
  }

  /** A text body with credential-named fields (JSON or form-encoded) and known secrets masked. */
  fun redactBodyText(text: String, contentType: String?, secrets: Collection<String> = emptyList()): String {
    val forms = secretForms(secrets)
    val type = contentType?.lowercase().orEmpty()
    val structured = when {
      "x-www-form-urlencoded" in type -> redactFormEncoded(text)
      // A JSON body that doesn't parse (cut short at the capture's size limit) can't be searched
      // for its credential fields, so none of it is kept.
      "json" in type -> redactJson(text) ?: REDACTED_VALUE
      text.trimStart().let { it.startsWith("{") || it.startsWith("[") } -> redactJson(text) ?: text
      else -> text
    }
    return replaceSecrets(structured, forms)
  }

  /**
   * [bytes] as written to the capture. A text body goes through [redactBodyText]; any other body
   * has each known secret's UTF-8 bytes masked, since a binary payload has no fields to read.
   */
  fun redactBody(
    bytes: ByteArray,
    contentType: String?,
    isText: Boolean,
    secrets: Collection<String> = emptyList(),
  ): ByteArray {
    if (isText) {
      val text = bytes.decodeToString()
      val redacted = redactBodyText(text, contentType, secrets)
      return if (redacted == text) bytes else redacted.encodeToByteArray()
    }
    val forms = secretForms(secrets)
    if (forms.isEmpty()) return bytes
    var out = bytes
    for (form in forms) out = replaceBytes(out, form.encodeToByteArray(), REDACTED_VALUE.encodeToByteArray())
    return out
  }

  /** Each secret as it can appear on the wire: raw, URL-encoded and JSON-escaped; longest first. */
  fun secretForms(secrets: Collection<String>): List<String> =
    secrets.filter { it.isNotBlank() }
      .flatMap { listOf(it, formEncode(it), uriComponentEncode(it), jsonEscape(it)) }
      .distinct()
      .sortedByDescending { it.length }

  private fun replaceSecrets(text: String, forms: List<String>): String =
    forms.fold(text) { acc, form -> acc.replace(form, REDACTED_VALUE) }

  private fun redactFormEncoded(encoded: String): String {
    if (encoded.isEmpty()) return encoded
    return encoded.split('&').joinToString("&") { pair ->
      val eq = pair.indexOf('=')
      if (eq < 0) return@joinToString pair
      val key = pair.substring(0, eq)
      if (isCredentialFieldName(formDecode(key))) "$key=${formEncode(REDACTED_VALUE)}" else pair
    }
  }

  private fun redactJson(text: String): String? {
    val parsed = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: return null
    val redacted = parsed.withCredentialFieldsMasked(REDACTED_VALUE)
    return if (redacted == parsed) text else Json.encodeToString(JsonElement.serializer(), redacted)
  }

  private fun replaceBytes(haystack: ByteArray, needle: ByteArray, replacement: ByteArray): ByteArray {
    if (needle.isEmpty() || haystack.size < needle.size) return haystack
    var i = 0
    var found = false
    val out = ArrayList<Byte>(haystack.size)
    while (i < haystack.size) {
      if (i <= haystack.size - needle.size && matchesAt(haystack, needle, i)) {
        replacement.forEach { out.add(it) }
        i += needle.size
        found = true
      } else {
        out.add(haystack[i])
        i++
      }
    }
    return if (found) out.toByteArray() else haystack
  }

  private fun matchesAt(haystack: ByteArray, needle: ByteArray, at: Int): Boolean {
    for (j in needle.indices) if (haystack[at + j] != needle[j]) return false
    return true
  }

  private fun jsonEscape(value: String): String =
    Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(value)).removeSurrounding("\"")

  /** `application/x-www-form-urlencoded`, as a browser encodes a form field: space becomes `+`. */
  private fun formEncode(value: String): String = percentEncode(value, FORM_UNRESERVED, spaceAsPlus = true)

  /** `encodeURIComponent`, as a page script encodes a query value. */
  private fun uriComponentEncode(value: String): String = percentEncode(value, URI_COMPONENT_UNRESERVED, spaceAsPlus = false)

  private fun percentEncode(value: String, unreserved: String, spaceAsPlus: Boolean): String = buildString {
    for (byte in value.encodeToByteArray()) {
      val c = (byte.toInt() and 0xFF).toChar()
      when {
        c.isAsciiLetterOrDigit() || c in unreserved -> append(c)
        c == ' ' && spaceAsPlus -> append('+')
        else -> append('%').append(HEX[(byte.toInt() shr 4) and 0xF]).append(HEX[byte.toInt() and 0xF])
      }
    }
  }

  private fun formDecode(value: String): String = runCatching {
    val bytes = ArrayList<Byte>(value.length)
    var i = 0
    while (i < value.length) {
      val c = value[i]
      when {
        c == '+' -> { bytes.add(' '.code.toByte()); i++ }
        c == '%' && i + 2 < value.length -> {
          bytes.add(value.substring(i + 1, i + 3).toInt(16).toByte()); i += 3
        }
        else -> { c.toString().encodeToByteArray().forEach { bytes.add(it) }; i++ }
      }
    }
    bytes.toByteArray().decodeToString()
  }.getOrDefault(value)

  private fun Char.isAsciiLetterOrDigit(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

  private const val HEX = "0123456789ABCDEF"
  private const val FORM_UNRESERVED = "*-._"
  private const val URI_COMPONENT_UNRESERVED = "!'()*-._~"
  private val SENSITIVE_HEADER_PARTS = listOf("token", "secret", "password", "api-key", "apikey", "auth", "session", "csrf", "xsrf", "cookie")
}
