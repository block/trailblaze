package xyz.block.trailblaze.bundle

/**
 * Domain exception for any author-correctable problem the bundler runs into. Sealed so
 * callers can pattern-match on the failure mode (CLI: present a directed fix; Gradle
 * plugin: add fix-up suggestions to the build error; daemon: structured-log the kind for
 * dashboards) — all subtypes share the human-readable message in [message].
 *
 * **Unchecked-translation contract.** This is a `RuntimeException`, not a checked
 * exception. Every consumer is expected to catch it at the boundary and translate to
 * its own error idiom:
 *
 *  - **Gradle plugin** (`BundleTrailblazePackTask`): translate to `GradleException` so
 *    failures render with the standard "Build failed" framing.
 *  - **`trailblaze bundle` CLI** (planned): translate to nonzero exit code + stderr
 *    message.
 *  - **Daemon startup** (planned): translate to a structured log entry + 5xx response if
 *    the bundle step is part of session bootstrap.
 *
 * The bundler itself stays Gradle-/CLI-/daemon-agnostic so the same library serves all
 * three contexts. Subtypes are open for future additions; callers should default-branch
 * (`else`) on the `when` to handle new modes safely.
 */
sealed class TrailblazePackBundleException(message: String, cause: Throwable? = null) :
  RuntimeException(message, cause) {

  /** `packsDir` doesn't exist or isn't a directory — config error, not the bundler's fault. */
  class MissingPacksDir(message: String) : TrailblazePackBundleException(message)

  /**
   * `target.tools:` references a tool name that doesn't match any scripted-tool descriptor
   * discovered under the pack's tools directory.
   */
  class UnknownScriptedToolName(message: String) : TrailblazePackBundleException(message)

  /**
   * A scripted-tool descriptor candidate under `<pack>/tools/` resolved (via symlink) outside
   * the pack directory, or its canonical path could not be computed (symlink loop, filesystem
   * quirk). Mirrors the runtime loader's `PackSource.readFilesystemSibling` containment
   * guarantee so the bundler refuses to decode files the runtime would reject. The optional
   * [cause] threads through the underlying I/O failure when present (canonicalization throws
   * `IOException` on symlink cycles on some JVMs/OSes).
   */
  class EscapesPackDirectory(message: String, cause: Throwable? = null) :
    TrailblazePackBundleException(message, cause)

  /** A scripted tool's YAML decode failed — kaml threw on shape, missing required field, etc. */
  class MalformedScriptedTool(message: String, cause: Throwable? = null) :
    TrailblazePackBundleException(message, cause)

  /** A pack manifest's YAML decode failed — same as [MalformedScriptedTool], but for `pack.yaml`. */
  class MalformedManifest(message: String, cause: Throwable? = null) :
    TrailblazePackBundleException(message, cause)

  /** Two scripted tools share a `name` — would collide in TrailblazeToolMap declaration merging. */
  class DuplicateToolName(message: String) : TrailblazePackBundleException(message)

  /** A scripted tool's `name` field is empty or whitespace-only after decoding. */
  class BlankToolName(message: String) : TrailblazePackBundleException(message)

  /** A property's `inputSchema` declares an enum constraint that's empty or otherwise unusable. */
  class InvalidInputSchema(message: String) : TrailblazePackBundleException(message)

  /** A `dependencies:` chain forms a cycle — fail loudly with the offending chain. */
  class CyclicDependencies(message: String) : TrailblazePackBundleException(message)

  /** Two workspace packs declare the same `id:` — pack ids are the dep-graph key, must be unique. */
  class DuplicatePackId(message: String) : TrailblazePackBundleException(message)

  /**
   * A scripted-tool descriptor's `script:` field references a JavaScript source
   * (`.js`/`.mjs`/`.cjs`). The typed-surface shape itself is derived from the YAML
   * descriptor's `inputSchema:` / `description:`, not the script source — but the
   * authoring-language policy is TS-only so the file an author edits matches the file the
   * runtime (`bun`, daemon-spawned subprocess) loads. A `.js` file slipping past this
   * guard leaves a workspace where `foo.js` and `foo.ts` can both satisfy
   * `script: ./foo.*` ambiguously, and per-pack codegen + tsconfig drift apart silently.
   * Migration is mechanical: rename the file and update the descriptor's `script:` field.
   */
  class JsToolFileNotAllowed(message: String) : TrailblazePackBundleException(message)
}
