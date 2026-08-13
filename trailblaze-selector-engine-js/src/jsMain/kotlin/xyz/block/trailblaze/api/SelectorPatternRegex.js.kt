package xyz.block.trailblaze.api

import kotlin.js.RegExp

/**
 * Kotlin/JS: `kotlin.text.Regex` delegates to the native ECMAScript `RegExp`, which cannot
 * parse Java/Kotlin's `\Q...\E` quote sections (what `escapeForSelector` writes into recorded
 * selectors — in JS, `\Q` is an identity escape for a literal `Q`, so such patterns silently
 * never match), has no `RegexOption` for dotAll, and treats leading inline flags (`(?i)`) as
 * a syntax error. This actual is a 1:1 Kotlin port of the TypeScript matcher's translation
 * (`sdks/typescript/src/matcher/resolver.ts` — quote-section translation, leading-inline-flag
 * stripping, lookaround full-match wrap, `toRegexSafe` degrade), whose behavior is locked by
 * the shared `matcher-parity-fixtures.json`. The engine's `engine-parity.test.ts` runs this
 * compiled actual against that same fixture.
 *
 * Standard Unicode property escapes (`\p{L}`) compile with the `u` flag; a stray `\E` with
 * no open `\Q` throws to mirror Java's compile error. Remaining gaps, shared with the TS
 * matcher and caught by the parity fixtures if they ever bite: non-leading inline flags,
 * possessive quantifiers, and Java-only `\p{...}` property names — such a pattern fails the
 * compile here and falls back to the caller's literal-equality leg, which is safe (the
 * selector still resolves somehow) but stricter than the JVM.
 */
internal actual fun selectorPatternRegexMatches(
  pattern: String,
  text: String,
  maestroDialect: Boolean,
): Boolean {
  // Orchestra's REGEX_OPTIONS (IGNORE_CASE | DOT_MATCHES_ALL | MULTILINE) as ECMAScript flags.
  val baseFlags = if (maestroDialect) "ism" else ""
  val stripped = stripLeadingInlineFlags(pattern)
  val flags = combineFlags(baseFlags, added = stripped.added, removed = stripped.removed)
  val regex: RegExp? = try {
    val translated = translateQuoteSections(stripped.pattern)
    // Unicode property escapes (`\p{L}`) need the `u` flag in JS — without it they're
    // Annex-B identity escapes that silently match the LITERAL text `p{L}`, diverging from
    // Java (where `\p{...}` always means the property). Added only when the pattern uses
    // one: `u` also makes parsing stricter. Java-only property names (`\p{Alpha}`) fail the
    // `u` compile and fall through to the catch — the safe literal-fallback direction.
    val compileFlags = if (hasUnicodePropertyEscape(translated)) flags + "u" else flags
    // Probe-compile the user pattern ALONE before wrapping. An invalid pattern can fuse with
    // the wrapper into a valid-but-garbage regex (e.g. `[unclosed` + the wrapper's trailing
    // `(?![\s\S])` — the wrapper's `]` closes the dangling character class), so validity must
    // be judged on the bare pattern.
    RegExp(translated, compileFlags)
    RegExp(fullMatchWrap(translated), compileFlags)
  } catch (_: Throwable) {
    if (maestroDialect) {
      // Maestro's StringUtils.toRegexSafe: invalid regex → escaped literal, same flags.
      // Escape the ORIGINAL pattern (not the flag-stripped one), like the JVM actual.
      RegExp(fullMatchWrap(escapeForRegExp(pattern)), baseFlags)
    } else {
      null
    }
  }
  return regex != null && regex.test(text)
}

/** ECMAScript flag letters this translator understands (`i`, `s`, `m`). */
private const val SUPPORTED_JS_FLAGS = "ism"

/**
 * Wraps a pattern so it must match the ENTIRE input, mirroring Kotlin's
 * `Regex(p).matches(t)`. Lookarounds on `[\s\S]` instead of `^...$` because the wrapper must
 * stay ABSOLUTE under the `m` flag — with `m` (the Maestro dialect's default), `^`/`$` become
 * per-line, which would let pattern `ok` match the second line of `"book\nok"` while Kotlin's
 * full-input `matches()` rejects it. `(?<![\s\S])` holds only at input start, `(?![\s\S])`
 * only at input end; inner `^`/`$` written by the author keep their per-line meaning.
 */
private fun fullMatchWrap(pattern: String): String = "(?<![\\s\\S])(?:$pattern)(?![\\s\\S])"

private val REGEXP_METACHARS = Regex("""[.*+?^${'$'}{}()|\[\]\\/]""")

/** Escapes a string so it compiles as a literal inside an ECMAScript `RegExp`. */
private fun escapeForRegExp(s: String): String =
  s.replace(REGEXP_METACHARS) { "\\${it.value}" }

/**
 * Translates Java-style `\Q...\E` quoted sections into JS-escaped literals. Mirrors Java
 * semantics: an unterminated `\Q` quotes to the end of the pattern, a `\Q` preceded by an
 * escaping backslash (`\\Q`) is NOT a quote start, and a stray `\E` with no open quote
 * section is a SYNTAX ERROR (Java: "Unmatched closing \E") — thrown here so the caller's
 * catch takes the same fallback path Java's failed compile does. Without the throw, JS
 * would accept `foo\E` as the identity escape `fooE` and silently match different text
 * than the JVM.
 */
private fun translateQuoteSections(pattern: String): String {
  if (!pattern.contains("\\Q") && !pattern.contains("\\E")) return pattern
  val out = StringBuilder(pattern.length + 16)
  var i = 0
  while (i < pattern.length) {
    val ch = pattern[i]
    if (ch == '\\' && i + 1 < pattern.length) {
      val next = pattern[i + 1]
      if (next == 'Q') {
        // Quote section: literal until `\E` or end of pattern.
        val end = pattern.indexOf("\\E", i + 2)
        val literal = if (end == -1) pattern.substring(i + 2) else pattern.substring(i + 2, end)
        out.append(escapeForRegExp(literal))
        i = if (end == -1) pattern.length else end + 2
      } else if (next == 'E') {
        // Java rejects an \E that has no open quote section; mirror it.
        throw IllegalArgumentException("Unmatched closing \\E in pattern")
      } else {
        // Any other escape (including `\\`): copy both chars so the escaped char can't be
        // misread as a quote start.
        out.append(ch).append(next)
        i += 2
      }
    } else {
      out.append(ch)
      i += 1
    }
  }
  return out.toString()
}

/**
 * True when [pattern] (post quote-section translation) contains a regex Unicode property
 * escape — an odd number of backslashes followed by `p{`/`P{`. Text that came from a
 * translated `\Q...\E` section can't false-positive: its backslashes are escaped (`\\p`),
 * making the run even.
 */
private fun hasUnicodePropertyEscape(pattern: String): Boolean {
  for (i in 0 until pattern.length - 2) {
    if (pattern[i] == '\\' && (pattern[i + 1] == 'p' || pattern[i + 1] == 'P') && pattern[i + 2] == '{') {
      var backslashesBefore = 0
      var j = i - 1
      while (j >= 0 && pattern[j] == '\\') {
        backslashesBefore++
        j--
      }
      if (backslashesBefore % 2 == 0) return true
    }
  }
  return false
}

private class StrippedFlags(val pattern: String, val added: String, val removed: String)

// `(?` + 0+ on-flags + optional `-` + off-flags + `)`, at start only. The `[a-z]` classes
// can't match `(?:`/`(?=`/`(?!` group syntax; require at least one flag char overall.
private val LEADING_INLINE_FLAGS = Regex("""^\(\?([a-z]*)(?:-([a-z]+))?\)""")

/**
 * Strips a leading Java-style inline-flag group — `(?i)`, `(?is)`, `(?-i)`, `(?s-i)` — and
 * reports which ECMAScript flags it adds/removes. Java honors inline toggles natively, so
 * the JVM actual needs no counterpart. Unsupported Java flag letters (e.g. `x`) are silently
 * dropped, like the TS matcher. Only a LEADING group is translated; mid-pattern toggles fail
 * the wrapper compile and fall through to the caller's literal path.
 */
private fun stripLeadingInlineFlags(pattern: String): StrippedFlags {
  val match = LEADING_INLINE_FLAGS.find(pattern)
  if (match == null || (match.groupValues[1].isEmpty() && match.groupValues[2].isEmpty())) {
    return StrippedFlags(pattern, added = "", removed = "")
  }
  fun keep(chars: String): String =
    chars.toCharArray().distinct().filter { it in SUPPORTED_JS_FLAGS }.joinToString("")
  return StrippedFlags(
    pattern = pattern.substring(match.value.length),
    added = keep(match.groupValues[1]),
    removed = keep(match.groupValues[2]),
  )
}

/** Base flags + leading inline additions − leading inline removals, deduped, order-stable. */
private fun combineFlags(base: String, added: String, removed: String): String {
  val set = LinkedHashSet<Char>()
  base.forEach { set.add(it) }
  added.forEach { set.add(it) }
  removed.forEach { set.remove(it) }
  return set.joinToString("")
}
