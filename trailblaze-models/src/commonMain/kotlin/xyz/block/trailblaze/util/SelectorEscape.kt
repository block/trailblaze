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
 * Escapes text for use in content selector fields (textRegex, contentDescriptionRegex,
 * labelRegex, etc.). Returns the text as-is when it contains no regex metacharacters
 * (producing cleaner YAML), or wraps in `\Q...\E` when escaping is needed.
 */
fun escapeForSelector(text: String): String =
  if (text.any { it in REGEX_METACHARACTERS }) Regex.escape(text) else text

/**
 * Escapes an identifier for use in className/resourceId/type selector fields. Like
 * [escapeForSelector] but uses [IDENTIFIER_REGEX_METACHARACTERS] (dot excluded), so
 * identifiers like `com.example.app:id/foo` are emitted without `\Q...\E` quoting.
 */
fun escapeForIdentifier(text: String): String =
  if (text.any { it in IDENTIFIER_REGEX_METACHARACTERS }) Regex.escape(text) else text
