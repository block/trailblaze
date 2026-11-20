package xyz.block.trailblaze.config

import kotlinx.serialization.Serializable

/**
 * One entry of a `memory:` block — a memory key that a trailmap's or toolset's tools, trails or run
 * setup share, declared so the authors who depend on it can see it. A trailmap's typed surface
 * (`tools/trailblaze-client.d.ts`) lists every key declared by the trailmap, by the trailmaps in its
 * `dependencies:` closure, and by the toolsets that closure loads. Memory is one flat namespace, so
 * a key is declared once in that reach; `trailblaze check` fails a second declaration.
 *
 * ```yaml
 * memory:
 *   appearance:
 *     description: The run's appearance (dark, light or system), applied at sign-in.
 * ```
 *
 * Descriptive only. Memory takes any key at run time, a trail never has to declare the keys it
 * uses, and each reader still parses its own input — a declaration documents and types a key, it
 * never gates one.
 */
@Serializable
data class MemoryKeyDeclaration(
  /**
   * What the key is for. Shown as the key's doc comment in the typed surface. Required, but checked
   * by [problems] rather than the decoder, so a missing one is reported instead of failing the parse.
   */
  val description: String = "",
) {
  companion object {
    private val KEY_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*$")

    /**
     * Every reason [keys] is malformed, each naming its key; empty when it is well formed.
     */
    fun problems(keys: Map<String, MemoryKeyDeclaration>): List<String> = keys.flatMap { (name, key) ->
      buildList {
        if (!KEY_NAME.matches(name)) add("memory key '$name': a name is letters, digits and underscores, starting with a letter")
        if (key.description.isBlank()) add("memory key '$name': `description` says what the key is for")
      }
    }
  }
}
