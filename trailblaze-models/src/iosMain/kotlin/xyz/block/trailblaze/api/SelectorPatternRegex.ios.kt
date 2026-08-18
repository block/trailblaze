package xyz.block.trailblaze.api

/**
 * iOS: Kotlin/Native implements `kotlin.text.Regex` with the full JVM-compatible option set —
 * `\Q...\E` quote sections and [RegexOption.DOT_MATCHES_ALL] included — so this actual is
 * identical to the JVM and wasmJs ones. Selector matching semantics must not vary by platform:
 * a recorded trail that matches on the host has to match the same way on-device.
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
