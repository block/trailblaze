package xyz.block.trailblaze.scripting

/**
 * Renders the in-process scripted-tool registration wrapper from the ONE committed template,
 * `sdks/typescript/tools/in-process-wrapper-template.mjs`.
 *
 * ### Why a template instead of three string-builders
 *
 * The wrapper JS — a `__client` callTool/Proxy shim + `__normalizeResult` + registration onto
 * `globalThis.__trailblazeTools` — used to be synthesized by hand in three separate places tied
 * together only by a `SISTER-IMPL-TAG` comment + a byte-diff gate: this daemon-time bundler, the
 * build-time `BundleAuthorToolsTask` (build-logic plugin), and `:trailblaze-common`'s framework
 * bundler (a `build.gradle.kts` build script). Those three live in three classpaths that can't
 * import each other (runtime JVM module, Gradle plugin, build script), so a plain shared Kotlin
 * function wasn't reachable by all three.
 *
 * The committed template file IS reachable by all three:
 *  - the two Gradle bundlers read it directly off disk under `sdks/typescript/tools/`;
 *  - this runtime module reads it from the classpath (staged into the JAR by
 *    `:trailblaze-host`'s `copyScriptedToolWrapperTemplate` Copy task). Reading from the
 *    classpath — rather than walking up to the SDK directory — keeps wrapper synthesis working
 *    even when the daemon resolved esbuild from PATH and the SDK dir can't be located.
 *
 * The template carries four placeholder tokens, each on its own line where applicable:
 *  - `// __TRAILBLAZE_HEADER__` — replaced with the per-site banner comment.
 *  - `__TRAILBLAZE_IMPORT_SOURCE__` — the `./<file>` relative import target.
 *  - `// __TRAILBLAZE_PRELUDE__` — the single-export named-handler lookup + type check (this
 *    daemon-time form). The multi-export Gradle forms substitute an empty string, which removes
 *    the whole token line.
 *  - `// __TRAILBLAZE_REGISTRATION__` — the registration footer (single-export by tool name here;
 *    multi-export enumeration in the Gradle forms).
 *
 * Substituting a whole `// __TOKEN__\n` line means an empty replacement removes the line cleanly
 * (no stray blank). The blank lines that both forms share are baked into the template literally.
 */
internal object InProcessScriptedToolWrapperTemplate {

  /** Classpath location of the staged template (see `:trailblaze-host` build's Copy task). */
  private const val TEMPLATE_RESOURCE_PATH =
    "/xyz/block/trailblaze/scripting/in-process-wrapper-template.mjs"

  const val HEADER_TOKEN_LINE: String = "// __TRAILBLAZE_HEADER__\n"
  const val IMPORT_SOURCE_TOKEN: String = "__TRAILBLAZE_IMPORT_SOURCE__"
  const val PRELUDE_TOKEN_LINE: String = "// __TRAILBLAZE_PRELUDE__\n"
  const val REGISTRATION_TOKEN_LINE: String = "// __TRAILBLAZE_REGISTRATION__\n"

  /** Read the committed template text from the classpath, raising a directed error if it's absent. */
  private val template: String by lazy {
    val stream = InProcessScriptedToolWrapperTemplate::class.java.getResourceAsStream(TEMPLATE_RESOURCE_PATH)
      ?: error(
        "Scripted-tool wrapper template not found at classpath:$TEMPLATE_RESOURCE_PATH. It is staged " +
          "into this module's JAR from sdks/typescript/tools/in-process-wrapper-template.mjs by the " +
          "copyScriptedToolWrapperTemplate Gradle task — a missing resource means that task didn't run " +
          "or the source file was deleted.",
      )
    stream.use { it.readBytes().toString(Charsets.UTF_8) }
  }

  /**
   * The raw template text. Exposed so a content-addressed bundle cache (the daemon's
   * `DaemonScriptedToolBundler.bundlerProfileFingerprint`) can fold the template bytes into its
   * key — because the template now drives the generated wrapper, a template edit must invalidate
   * stale cached bundles the same way an `in-process.ts` edit does. The Gradle bundlers get this
   * for free by declaring the template file as a task input; the daemon must mix it in explicitly.
   */
  internal val rawTemplate: String get() = template

  /**
   * Render the wrapper TS source. [header], [prelude], and [registration] are the per-site blocks;
   * [importSource] is the relative import target (`./<file>`). The token lines are substituted
   * whole so an empty [prelude] removes its line entirely.
   */
  fun render(
    header: String,
    importSource: String,
    prelude: String,
    registration: String,
  ): String =
    template
      .replace(HEADER_TOKEN_LINE, header)
      .replace(IMPORT_SOURCE_TOKEN, importSource)
      .replace(PRELUDE_TOKEN_LINE, prelude)
      .replace(REGISTRATION_TOKEN_LINE, registration)
}
