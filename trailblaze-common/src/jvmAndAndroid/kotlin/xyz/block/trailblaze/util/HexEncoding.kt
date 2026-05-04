package xyz.block.trailblaze.util

/**
 * Lowercase hex encoding of [this] bytes.
 *
 * Shared by every callsite that emits a digest as a string identifier — currently
 * `WorkspaceContentHasher` in trailblaze-common (drift-detection hash) and
 * `WorkspaceCompileBootstrap` in trailblaze-host (compile-cache invalidation hash).
 * The two were independently inlining `joinToString("") { "%02x".format(it) }`; if a
 * future need (uppercase, base64, different format) comes up, having one helper means
 * one edit instead of N.
 *
 * Public because trailblaze-host needs to import it across the module boundary —
 * Kotlin `internal` doesn't cross Gradle modules. New callers should consider whether
 * they really need hex encoding (it's ~2x the bytes of a binary digest); use this only
 * when emitting a hash to a log line, file, or HTTP response that humans / git read.
 */
fun ByteArray.toLowerHex(): String =
  joinToString("") { "%02x".format(it) }
