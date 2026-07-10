package xyz.block.trailblaze.api

/**
 * Canonical statement of Maestro's `Orchestra.REGEX_OPTIONS`, locked by
 * `matcher-parity-fixtures.json`. Two sibling copies must stay in sync: the vendored
 * `Orchestra.kt` `REGEX_OPTIONS` (trailblaze-android) and the inlined options in
 * `PropertyUniqueness` (trailblaze-common) — module boundaries prevent sharing one constant.
 *
 * expect/actual because `RegexOption.DOT_MATCHES_ALL` exists on every target this module
 * compiles for (JVM, Android, Wasm) but not in the common `RegexOption` API, so naming it in
 * commonMain fails metadata compilation once the wasm target is enabled
 * (`-Ptrailblaze.wasm=true`, the release build). Every actual is the same three options.
 */
internal expect val MAESTRO_REGEX_OPTIONS: Set<RegexOption>
