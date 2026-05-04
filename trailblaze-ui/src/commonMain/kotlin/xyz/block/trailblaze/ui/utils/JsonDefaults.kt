package xyz.block.trailblaze.ui.utils

import kotlinx.serialization.json.Json

/**
 * Shared `kotlinx.serialization.Json` instances used by the desktop app's read paths so
 * decoding settings are documented in one place rather than re-declared inline at each
 * call site.
 *
 * **Why not `TrailblazeJsonInstance`?** That instance lives in `trailblaze-models`'s
 * `jvmAndAndroid` source set (not `commonMain` or `wasmJsMain`, which is where this
 * desktop-app code runs), is heavyweight (its `by lazy` initializer triggers
 * classpath-wide tool-serializer registration via `TrailblazeSerializationInitializer`),
 * and is scoped to polymorphic `TrailblazeLog` decoding. It's the wrong tool for parsing
 * a single `NetworkEvent` from an NDJSON line or a `CaptureMetadataModel` from a JSON
 * file — those are plain `@Serializable` types with no polymorphism, and forcing the
 * tool registry to seal early on first read of an unrelated artifact would be a layering
 * violation.
 *
 * These are intentionally read-side only. Capture writers elsewhere in the codebase
 * (e.g. `WebNetworkCapture`) configure their own encoders since their needs differ
 * (`encodeDefaults`, `explicitNulls`, etc.).
 */
internal object JsonDefaults {
  /**
   * Forward-compatible parser for our own JSON formats that may add fields over time.
   * Tolerates unknown keys so an older client can still decode a newer schema's payload
   * — used for things like `capture_metadata.json`.
   */
  val FORWARD_COMPATIBLE: Json = Json {
    ignoreUnknownKeys = true
  }

  /**
   * Lenient parser for external NDJSON streams where individual lines may be malformed
   * (torn trailing line from a JVM crash mid-write, encoding edge cases). Adds
   * [Json.isLenient] on top of [FORWARD_COMPATIBLE] for relaxed JSON syntax handling;
   * callers still wrap each decode in `runCatching` to skip unrecoverable lines.
   */
  val LENIENT: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
  }
}
