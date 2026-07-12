package xyz.block.trailblaze

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.block.trailblaze.util.Console

/**
 * Applies a scripted tool's result-side memory diff back into [AgentMemory].
 *
 * A `@trailblaze/scripting` handler's `ctx.memory.set(...)` / `.delete(...)` writes are buffered on
 * the JS side and flushed onto the tool result's `_meta.trailblaze` envelope as `memoryDelta`
 * (a `Record<String, String>` of sets) and `memoryDeletions` (a `List<String>` of removed keys) —
 * see the TypeScript SDK's `attachMemoryDeltaToResult`. This is the symmetric counterpart that
 * merges that diff into the host's shared [AgentMemory] after a successful dispatch, so the next
 * tool's `ctx.memory.get(...)` sees the write.
 *
 * **Why this lives in `:trailblaze-models` and not in the scripting envelope module.** BOTH scripted
 * dispatch runtimes need to apply the same diff, and they sit in modules that can't share the
 * scripting-envelope code:
 *  - the subprocess / MCP runtime (`SubprocessTrailblazeTool`, via
 *    `TrailblazeContextEnvelope.applyResultMemoryDelta` which delegates here), and
 *  - the in-process / on-device QuickJS runtime (`QuickJsTrailblazeTool`), whose module is
 *    deliberately dependency-lean (no `:trailblaze-scripting-mcp-common`).
 * Homing the pure logic next to [AgentMemory] lets both reach it without dragging the transport
 * shims onto the on-device APK, and keeps it unit-testable in isolation (see `AgentMemoryTest`).
 *
 * **Layering trade-off, acknowledged.** `:trailblaze-models` otherwise holds data
 * classes/domain types, not wire-format-aware parsing — the `_meta.trailblaze.{memoryDelta,
 * memoryDeletions}` JSON-key knowledge here is a transport-layer concern by the letter of that
 * convention. The alternative — a transport-agnostic `AgentMemory.applyDelta(sets: Map<String,
 * String>, deletions: List<String>)` with each runtime module doing its own JSON decode — was
 * rejected because it would recreate the exact bug this file exists to fix: two independent
 * decode call sites is two places a future JSON-shape change (or the field-rename risk already
 * flagged on [xyz.block.trailblaze.scripting.mcp.TrailblazeContextEnvelope]'s constants) can
 * silently diverge. Trading a small, deliberate layering exception for a single decode path is
 * the better bet here — don't "fix" this by moving the JSON parsing back out without solving
 * that duplication problem first.
 *
 * No-op (returns false) when [resultMeta] is null, carries no `trailblaze` envelope, or has neither
 * `memoryDelta` nor `memoryDeletions`. Malformed entries (a non-string set value, a non-string
 * deletion key) are skipped individually rather than failing the whole apply — a producer-side bug
 * that emits one bad entry must not sabotage the other writes in the same delta.
 *
 * Sensitivity is host-owned and enforced by [AgentMemory] itself, so sets and deletions are
 * applied uniformly here: [AgentMemory.remember] self-routes a marked key through
 * [AgentMemory.rememberSensitive] (overwrites stay redacted), and [AgentMemory.delete] keeps the
 * sensitivity marker. A scripted tool — which never sees sensitive values in its snapshot — thus
 * can neither leak a sensitive value into logs nor unmark a sensitive key.
 *
 * **Same key in both `memoryDelta` and `memoryDeletions`.** The well-behaved TS SDK can never
 * produce this — `createMemory`'s buffer is a single `Map` keyed by last-write-wins, so a key is
 * drained into exactly one of `sets` or `deletions`, never both (see `memory.ts`'s `drainDelta`).
 * This function is nonetheless a standalone, defensive entry point that can be handed a delta from
 * any producer, so the behavior for that otherwise-impossible shape is still pinned deliberately:
 * sets are applied before deletions, so **deletion wins** on a same-key collision.
 *
 * Returns true when at least one set or deletion was applied.
 */
fun applyScriptedToolMemoryDelta(memory: AgentMemory, resultMeta: JsonObject?): Boolean {
  if (resultMeta == null) return false
  val trailblaze = resultMeta[META_KEY_TRAILBLAZE] as? JsonObject ?: return false
  var applied = false
  (trailblaze[META_KEY_MEMORY_DELTA] as? JsonObject)?.forEach { (k, v) ->
    val prim = v as? JsonPrimitive
    if (prim == null || !prim.isString) {
      // Diagnostic breadcrumb for the "skipped individually" contract documented above — a
      // producer bug that emits a non-string set value would otherwise disappear with zero
      // trace, which is exactly the class of silent memory-write loss this file exists to fix.
      Console.log(
        "[ScriptedToolMemoryDelta] skipped non-string memoryDelta entry: key=$k " +
          "value=${v::class.simpleName}",
      )
      return@forEach
    }
    // remember() itself keeps a sensitive key's overwrite redacted (the marker is sticky).
    memory.remember(k, prim.content)
    applied = true
  }
  (trailblaze[META_KEY_MEMORY_DELETIONS] as? JsonArray)?.forEach { entry ->
    val prim = entry as? JsonPrimitive
    if (prim == null || !prim.isString) {
      Console.log(
        "[ScriptedToolMemoryDelta] skipped non-string memoryDeletions entry: " +
          "value=${entry::class.simpleName}",
      )
      return@forEach
    }
    // delete() itself keeps the sensitivity marker (the host owns that lifecycle) and records
    // the explicit-deletion signal in deletedKeys uniformly for sensitive and plain keys.
    memory.delete(prim.content)
    applied = true
  }
  return applied
}

/** Top-level `_meta` bucket holding the Trailblaze envelope. Mirrors the TS `META_KEY_TRAILBLAZE`. */
private const val META_KEY_TRAILBLAZE: String = "trailblaze"

/** `_meta.trailblaze.memoryDelta` — sets. Mirrors the TS `META_KEY_MEMORY_DELTA`. */
private const val META_KEY_MEMORY_DELTA: String = "memoryDelta"

/** `_meta.trailblaze.memoryDeletions` — removed keys. Mirrors the TS `META_KEY_MEMORY_DELETIONS`. */
private const val META_KEY_MEMORY_DELETIONS: String = "memoryDeletions"
