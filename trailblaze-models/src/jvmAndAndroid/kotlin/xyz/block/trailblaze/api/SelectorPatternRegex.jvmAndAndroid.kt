package xyz.block.trailblaze.api

/**
 * JVM/Android: `kotlin.text.Regex` wraps `java.util.regex`, which natively supports the full
 * pattern surface selectors use (`\Q...\E` quote sections, inline flags, dotAll). See the
 * expect declaration for the contract and the Maestro `toRegexSafe` degrade.
 */
internal actual fun selectorPatternRegexMatches(
  pattern: String,
  text: String,
  maestroDialect: Boolean,
): Boolean {
  val options: Set<RegexOption> =
    if (maestroDialect) {
      setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE)
    } else {
      emptySet()
    }
  val regex = try {
    Regex(pattern, options)
  } catch (_: IllegalArgumentException) {
    if (!maestroDialect) return false
    // Maestro's StringUtils.toRegexSafe: invalid regex → escaped literal, same options.
    Regex(Regex.escape(pattern), options)
  }
  return regex.matches(text)
}
