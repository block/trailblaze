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

  /** A pack-relative tool path failed validation (blank, absolute, escapes the pack dir, …). */
  class InvalidPackRelativePath(message: String) : TrailblazePackBundleException(message)

  /** A scripted-tool YAML referenced from a pack manifest doesn't exist on disk. */
  class MissingScriptedToolFile(message: String) : TrailblazePackBundleException(message)

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
}
