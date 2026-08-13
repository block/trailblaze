package xyz.block.trailblaze.util

/**
 * Regex metacharacters that require escaping for selector patterns.
 * When text contains none of these, it can be used as-is (it's a valid regex
 * that matches literally). When it contains any, wrap in `\Q...\E`.
 *
 * Used by [escapeForSelector] for content fields (textRegex, contentDescriptionRegex,
 * ariaDescriptorRegex, etc.) where `.` should match a literal dot.
 */
val REGEX_METACHARACTERS = setOf(
  '\\', '^', '$', '.', '|', '?', '*', '+', '(', ')', '[', ']', '{', '}',
)

/**
 * Metacharacters for identifier fields such as `classNameRegex` and `resourceIdRegex`.
 * Identical to [REGEX_METACHARACTERS] except `.` is omitted: dots in identifiers like
 * `com.example.app:id/foo` or `android.widget.TextView` are treated as wildcards (match
 * any char), which is acceptable because no real element has a class name or resource ID
 * differing from another only at a dot position.
 */
val IDENTIFIER_REGEX_METACHARACTERS = REGEX_METACHARACTERS - '.'

/**
 * Wraps [text] in a `\Q...\E` quote section, mirroring `java.util.regex.Pattern.quote` —
 * which is what `Regex.escape` delegates to on the JVM. An embedded `\E` (which would
 * terminate the quote early) is handled the way `Pattern.quote` handles it: close the
 * section, emit an escaped `\E`, reopen.
 *
 * Implemented by hand rather than via `Regex.escape` because `Regex.escape` is
 * platform-dependent: JVM/Android emit `\Q...\E`, but Kotlin/JS escapes per-character.
 * Selectors are generated on more than one platform (the daemon's JVM recorder and the
 * report's Kotlin/JS selector engine), and the generated TEXT must be byte-identical
 * everywhere — it is shown to users, written into trails, and byte-compared by the
 * engine's cross-platform parity fixtures. Internal so every selector-producing call site
 * (the escape helpers below, `stableTextAnchorRegex`, `SelectorTemplating`) quotes through
 * this one platform-stable implementation.
 */
internal fun quoteAsRegexLiteral(text: String): String {
  val firstEmbedded = text.indexOf("\\E")
  if (firstEmbedded == -1) return "\\Q$text\\E"
  val out = StringBuilder(text.length + 16)
  out.append("\\Q")
  var current = 0
  var next = firstEmbedded
  while (next != -1) {
    out.append(text, current, next)
    out.append("\\E\\\\E\\Q")
    current = next + 2
    next = text.indexOf("\\E", current)
  }
  out.append(text, current, text.length)
  out.append("\\E")
  return out.toString()
}

/**
 * Escapes text for use in content selector fields (textRegex, contentDescriptionRegex,
 * labelRegex, etc.). Returns the text as-is when it contains no regex metacharacters
 * (producing cleaner YAML), or wraps in `\Q...\E` when escaping is needed.
 */
fun escapeForSelector(text: String): String =
  if (text.any { it in REGEX_METACHARACTERS }) quoteAsRegexLiteral(text) else text

/**
 * Inverse of [escapeForSelector] for the whole-string `\\Q...\\E` form it emits: returns
 * the bare literal it stands for, or null when [value] is not a plain quoted literal (so
 * the caller keeps it untouched). Deliberately conservative -- an intentional regex such
 * as a stable-head anchor (`Item \\d+`) never starts with `\\Q`, and a malformed or
 * multi-section value (`\\Qa\\E\\Qb\\E`) is rejected rather than partially un-escaped.
 */
fun unescapeForSelector(value: String?): String? {
  if (value == null || !value.startsWith("\\Q") || !value.endsWith("\\E")) return null
  val inner = value.substring(2, value.length - 2)
  return if (inner.isEmpty() || inner.contains("\\E")) null else inner
}

/**
 * Escapes an identifier for use in className/resourceId/type selector fields. Like
 * [escapeForSelector] but uses [IDENTIFIER_REGEX_METACHARACTERS] (dot excluded), so
 * identifiers like `com.example.app:id/foo` are emitted without `\Q...\E` quoting.
 */
fun escapeForIdentifier(text: String): String =
  if (text.any { it in IDENTIFIER_REGEX_METACHARACTERS }) quoteAsRegexLiteral(text) else text
