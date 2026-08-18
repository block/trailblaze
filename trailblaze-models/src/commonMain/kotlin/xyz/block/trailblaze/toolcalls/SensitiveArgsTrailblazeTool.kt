package xyz.block.trailblaze.toolcalls

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.util.Console

/**
 * Marker for [TrailblazeTool]s whose args carry secret material — credentials, session tokens,
 * fetched auth payloads — that must never land in persisted session logs (which ship as CI
 * artifacts).
 *
 * The log-encode boundary (`TrailblazeTool.toLogPayload()` in `trailblaze-common`, plus the
 * `TrailblazeToolLog` construction sites that encode an authored raw-args wrapper) replaces each
 * named arg's value with [REDACTED_TOOL_ARG_PLACEHOLDER] via [withSensitiveArgsRedacted].
 * Execution and wire encoding are deliberately untouched — the dispatch target still receives
 * the real values.
 *
 * Trade-off this marker accepts: a recording generated from a session log of such a tool carries
 * the placeholder, not the real value. That is intended — secret material must be re-supplied at
 * authoring time (e.g. via `{{memory}}` tokens), never round-tripped through logs.
 */
interface SensitiveArgsTrailblazeTool {
  /**
   * Serialized property names (top-level keys of the tool's arg object) whose values are masked
   * in persisted log payloads. Implement as a `get()`-only val (not a constructor param) so it
   * stays out of the tool's serialized shape and generated schemas.
   */
  val sensitiveArgNames: Set<String>
}

/** Value written in place of a sensitive arg's value in persisted log payloads. */
const val REDACTED_TOOL_ARG_PLACEHOLDER: String = "<redacted>"

/**
 * A **scripted** tool's sensitive-arg declaration, as read off its `_meta` at registration.
 *
 * A Kotlin class-backed tool implements [SensitiveArgsTrailblazeTool] directly and needs none of
 * this. A scripted tool's declaration arrives as data from a source the runtime does not control —
 * an advertised MCP `_meta` from an external server, a hand-written bundle `spec._meta` — so it has
 * a state a Kotlin implementation cannot have: **present but unreadable**.
 *
 * That third state is why this is a type rather than a `Set<String>`. Every other `_meta` key
 * degrades to its default on a malformed shape, which is a safe no-op for a registration gate. For
 * this key the default is "mask nothing", so degrading would turn an author's `password` into
 * plaintext in a shipped CI artifact — the exact leak the declaration exists to prevent. Making the
 * unreadable case explicit forces every consumer to answer it.
 */
sealed interface DeclaredSensitiveArgs {

  /** Resolve to the arg names to mask, given the top-level [argNames] the tool was called with. */
  fun resolve(argNames: Set<String>): Set<String>

  /** No declaration — the tool has no secret args. Masks nothing. */
  data object None : DeclaredSensitiveArgs {
    override fun resolve(argNames: Set<String>): Set<String> = emptySet()
  }

  /** A well-formed declaration. Masks exactly [names]. */
  data class Named(val names: Set<String>) : DeclaredSensitiveArgs {
    override fun resolve(argNames: Set<String>): Set<String> = names
  }

  /**
   * The key was present but not a list of strings, so *which* args are secret is unknowable.
   *
   * Resolves to **every** arg. The author said this tool handles secrets; with no way to tell
   * which, treating all of them as secret is the only reading that can't leak. The cost is an
   * over-redacted log (and a recording made from it that can't be replayed verbatim) — loud,
   * recoverable, and visible in the very artifact the author would check. The alternative,
   * masking nothing, is silent and unrecoverable.
   *
   * Reached only by the paths that bypass descriptor load-time validation. An author writing a
   * `.tool.yaml` or a `.ts` spec gets a hard error long before this.
   */
  data object AllArgsDeclarationUnreadable : DeclaredSensitiveArgs {
    override fun resolve(argNames: Set<String>): Set<String> = argNames
  }

  companion object {
    /** The namespaced `_meta` key every authoring surface lowers a sensitive-arg declaration to. */
    const val META_KEY: String = "trailblaze/sensitiveArgNames"

    /**
     * Read [META_KEY] off a tool's `_meta` object. Absent → [None]; a list of strings → [Named];
     * anything else → [AllArgsDeclarationUnreadable].
     *
     * Shared by both scripted runtimes' `_meta` parsers so the fail-closed reading can't drift
     * between them — a tool that masks on one runtime and leaks on the other would be the worst
     * possible outcome for a control whose whole job is to be uniform.
     */
    fun fromMeta(meta: JsonObject?): DeclaredSensitiveArgs {
      val raw = meta?.get(META_KEY) ?: return None
      val array = raw as? JsonArray ?: return unreadable(raw)
      if (array.isEmpty()) return None
      val names = array.map { element ->
        val primitive = element as? JsonPrimitive ?: return unreadable(raw)
        if (!primitive.isString) return unreadable(raw)
        primitive.content
      }
      return Named(names.toSet())
    }

    /**
     * The masked artifact shows the *effect* of an unreadable declaration but not its *cause* — an
     * author who typo'd their `_meta` sees every arg redacted, which is indistinguishable from a
     * tool that meant to declare them all. Say so once, at the read, so the over-masking is
     * self-diagnosing rather than a puzzle.
     *
     * Only the unreadable branch logs; [None] and [Named] — every well-formed tool, on every
     * registration — stay silent.
     *
     * Reports the value's SHAPE, never its content. This is the one function in the codebase
     * holding a `_meta` value that was supposed to describe secrets and turned out to be
     * something else; writing it into a log would be the failure mode this whole type exists to
     * prevent. The shape plus the key name is enough — the author can see what they wrote.
     */
    private fun unreadable(raw: JsonElement): DeclaredSensitiveArgs {
      Console.error(
        "[sensitive-args] `_meta.$META_KEY` must be a list of argument names (e.g. `[password]`) " +
          "but is a ${raw::class.simpleName}. Masking EVERY argument of this tool in persisted " +
          "logs, since which ones are secret can't be read. Fix the declaration to mask only " +
          "the arguments you meant.",
      )
      return AllArgsDeclarationUnreadable
    }
  }
}

/**
 * Returns a copy of this payload with every present top-level key in [sensitiveArgNames] replaced
 * by [REDACTED_TOOL_ARG_PLACEHOLDER]. Absent keys are ignored; when nothing matches, returns
 * `this` unchanged. Only top-level keys are inspected — that is the shape every tool's arg object
 * encodes to.
 */
fun OtherTrailblazeTool.withSensitiveArgsRedacted(sensitiveArgNames: Set<String>): OtherTrailblazeTool {
  if (sensitiveArgNames.none { raw.containsKey(it) }) return this
  return copy(
    raw = JsonObject(
      raw.mapValues { (key, value) ->
        if (key in sensitiveArgNames) JsonPrimitive(REDACTED_TOOL_ARG_PLACEHOLDER) else value
      },
    ),
  )
}
