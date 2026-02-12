package maestro.ai

/**
 * Satisfies the minimum runtime requirements of Maestro without having to include `maestro-ai` artifact.
 * Maestro's "AI" Implementation is linked to their backend and it would be bloat and confusing to include.
 *
 * IMPORTANT: DO NOT DELETE THIS FILE
 * - Maestro's internal code references this interface at runtime
 * - Without this stub, Maestro will fail with ClassNotFoundException on Android
 * - This is a minimal interface stub, not an actual AI implementation
 * - Required for Android (on-device) builds
 */
@Suppress("UNUSED")
@Deprecated(
  "This is a runtime stub to satisfy Maestro dependencies. Do not use or implement.",
  level = DeprecationLevel.HIDDEN
)
private interface AIPredictionEngine
