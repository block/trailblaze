package xyz.block.trailblaze.api

/**
 * Wasm: Kotlin/Wasm implements `kotlin.text.Regex` in pure Kotlin (not the browser's
 * `RegExp`), so the full JVM-compatible pattern surface — `\Q...\E` quote sections, inline
 * flags, `DOT_MATCHES_ALL` — is available and this actual is identical to the JVM one.
 */
actual fun selectorPatternRegexMatches(
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
