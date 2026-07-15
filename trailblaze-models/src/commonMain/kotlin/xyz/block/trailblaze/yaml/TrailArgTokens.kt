package xyz.block.trailblaze.yaml

/**
 * The `{{args.x}}` token grammar. Tokens are **dotted paths only, no expressions — permanently**:
 * a token body is `namespace.name(.path)*`, nothing else. The reason is Trailblaze-specific — tokens
 * are LLM-writable, so an expression-bearing token (`{{args.count + 1}}`, `{{args.x | upper}}`) would
 * be evaluating model output. Concatenation is already expressed by embedding a token in a string;
 * anything computed is the caller's job at bind time or a scripted tool's job.
 *
 * This validator concerns itself ONLY with the `args.` namespace (the one this feature introduces).
 * Bare `{{x}}` and `{{memory.x}}` tokens are the pre-existing memory grammar and are intentionally
 * left untouched here — tightening those is a separate, later grammar move.
 */
/**
 * Thrown when a `{{args.x}}` token is not a valid dotted path (it carries an expression). A distinct
 * type — not a bare [IllegalArgumentException] — so the tool-dispatch interpolation boundary
 * ([xyz.block.trailblaze.toolcalls.interpolateMemoryInTool]) can recognize and rethrow this specific
 * hard-error rather than have it silently caught and mislabeled by a class-serializer round-trip's
 * broad `catch (e: Exception)`. Subclasses [IllegalArgumentException] so existing
 * `assertFailsWith<IllegalArgumentException>` call sites remain valid.
 */
class MalformedArgTokenException(message: String) : IllegalArgumentException(message)

object TrailArgTokens {
  const val ARGS_PREFIX: String = "args."

  /** Matches `${...}` and `{{...}}`; group 1/2 is the raw body between the delimiters. */
  private val TOKEN_REGEX = Regex("""\$\{([^}]*)\}|\{\{([^}]*)\}\}""")

  /** `name` or `name.sub.sub` — each segment a plain identifier, no whitespace or operators. */
  private val DOTTED_PATH_REGEX = Regex("""[a-zA-Z_][a-zA-Z0-9_]*(\.[a-zA-Z_][a-zA-Z0-9_]*)*""")

  /** One path segment: what a DECLARED arg name must be, so `{{args.<name>}}` can address it. */
  private val ARG_NAME_REGEX = Regex("""[a-zA-Z_][a-zA-Z0-9_]*""")

  /**
   * True when [name] is a legal declared-arg name — a single identifier segment. A name outside
   * this grammar is unreferenceable (`{{args.user-id}}` reads as a malformed token) or
   * mis-referenced (a dotted `a.b` name collides with dotted-path field access into arg `a`),
   * so it is rejected at declaration parse rather than surfacing as a confusing token error.
   */
  fun isValidArgName(name: String): Boolean = ARG_NAME_REGEX.matches(name)

  /** A single `args.` token occurrence in some text. [body] is the content between the delimiters. */
  data class ArgTokenRef(val raw: String, val body: String)

  /** True when [body] (a token's content) refers to the `args.` namespace. */
  fun isArgsToken(body: String): Boolean = body.startsWith(ARGS_PREFIX)

  /** True when [body] is a well-formed dotted path (the only legal token shape). */
  fun isValidDottedPath(body: String): Boolean = DOTTED_PATH_REGEX.matches(body)

  /**
   * The top-level argument name a `args.` token addresses: `args.reply_to.email` -> `reply_to`.
   * Callers should pass only bodies for which [isArgsToken] is true.
   */
  fun topLevelArgName(body: String): String = body.removePrefix(ARGS_PREFIX).substringBefore('.')

  /** Every `args.` token occurrence in [text], in source order (duplicates preserved). */
  fun scanArgsTokens(text: String): List<ArgTokenRef> =
    TOKEN_REGEX.findAll(text).mapNotNull { m ->
      val body = m.groupValues[1].ifEmpty { m.groupValues[2] }
      if (isArgsToken(body)) ArgTokenRef(raw = m.value, body = body) else null
    }.toList()

  /**
   * The malformed-token error message for [body] (a token content that starts with `args.` but is
   * not a valid dotted path — i.e. it carries an expression). Shared by check-time and runtime so
   * both surfaces read identically.
   */
  fun malformedTokenMessage(body: String): String =
    "Malformed args token `{{$body}}` — trail tokens are dotted paths only (`args.name` or " +
      "`args.name.field`), never expressions. Compute values before the run (shell / a scripted tool), " +
      "not inside a token."

  /**
   * Validate every `args.` token in [text] against the [declaredArgNames]. Returns a list of
   * human-readable errors (empty = clean):
   *  - a token that is not a valid dotted path (an expression) — always an error;
   *  - a token whose top-level name isn't declared under `config.args:`.
   *
   * A declared-but-unused arg is fine (not reported) — declaring more than a trail references is a
   * harmless superset, and a shared arg set across a family of trails is a legitimate pattern.
   */
  fun validate(text: String, declaredArgNames: Set<String>): List<String> {
    val errors = mutableListOf<String>()
    for (ref in scanArgsTokens(text)) {
      if (!isValidDottedPath(ref.body)) {
        errors += malformedTokenMessage(ref.body)
        continue
      }
      val name = topLevelArgName(ref.body)
      if (name !in declaredArgNames) {
        errors += "Undeclared arg reference `{{${ref.body}}}` — declare `$name` under `config.args:` " +
          "or remove the reference. Declared args: ${declaredArgNames.sorted()}."
      }
    }
    return errors
  }
}
