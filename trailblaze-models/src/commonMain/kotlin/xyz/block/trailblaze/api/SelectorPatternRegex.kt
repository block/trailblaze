package xyz.block.trailblaze.api

/**
 * Platform chokepoint for the regex leg of selector text matching: compiles [pattern] and
 * reports whether it full-matches [text]. The literal-equality fallback stays in the caller
 * ([TrailblazeNodeSelectorResolver.matchesPattern]); this function only answers the regex
 * question. Returns false when a NATIVE-dialect pattern fails to compile.
 *
 * [maestroDialect] selects Maestro's Orchestra semantics: `IGNORE_CASE | DOT_MATCHES_ALL |
 * MULTILINE` (the canonical statement of `Orchestra.REGEX_OPTIONS`, locked by
 * `matcher-parity-fixtures.json`; one sibling copy must stay in sync — the vendored
 * `Orchestra.kt` in trailblaze-android), plus the `toRegexSafe` degrade: an invalid pattern
 * is retried as an escaped literal with the same options. Public because downstream KMP
 * modules share the chokepoint too — `PropertyUniqueness` in trailblaze-common routes its
 * Maestro-semantics matching here instead of keeping an inlined copy of the options.
 *
 * expect/actual for two reasons:
 * - `RegexOption.DOT_MATCHES_ALL` exists on JVM, Android, and Wasm but not in the common
 *   `RegexOption` API, so naming it in commonMain fails metadata compilation.
 * - On Kotlin/JS, `Regex` delegates to the native ECMAScript `RegExp`, which cannot parse
 *   Java/Kotlin's `\Q...\E` quote sections (what [xyz.block.trailblaze.util.escapeForSelector]
 *   emits into recorded selectors) and exposes no `RegexOption` for dotAll. The JS actual
 *   (in the selector-engine-js module, which compiles these sources for the browser)
 *   translates quote sections and leading inline flags to ECMAScript equivalents — the same
 *   translation the TypeScript matcher (`sdks/typescript/src/matcher/resolver.ts`) ships,
 *   locked by the same `matcher-parity-fixtures.json` contract.
 */
expect fun selectorPatternRegexMatches(
  pattern: String,
  text: String,
  maestroDialect: Boolean,
): Boolean
