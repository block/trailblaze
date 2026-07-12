package xyz.block.trailblaze

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertContains
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Coverage for the [AgentMemory] surface. The class is intentionally small (it backs the
 * host's per-session scratchpad), but the surface is shared across the YAML-interpolation
 * path AND the scripted-tool memoryDelta apply — both need `has`/`delete` to land cleanly
 * alongside the existing `rememberSensitive`/`sensitiveKeys` redaction path.
 */
class AgentMemoryTest {

  @Test
  fun `remember and retrieve via variables map`() {
    val memory = AgentMemory()
    memory.remember("name", "Ada")
    assertEquals("Ada", memory.variables["name"])
  }

  @Test
  fun `has reports membership after remember`() {
    val memory = AgentMemory()
    assertFalse(memory.has("k"))
    memory.remember("k", "v")
    assertTrue(memory.has("k"))
  }

  @Test
  fun `delete removes a key`() {
    val memory = AgentMemory()
    memory.remember("k", "v")
    memory.delete("k")
    assertFalse(memory.has("k"))
    assertNull(memory.variables["k"])
  }

  @Test
  fun `delete on a missing key is a no-op`() {
    val memory = AgentMemory()
    memory.delete("never-set")
    assertFalse(memory.has("never-set"))
  }

  @Test
  fun `interpolateVariables substitutes both syntaxes`() {
    val memory = AgentMemory()
    memory.remember("first", "Ada")
    memory.remember("last", "Lovelace")
    assertEquals("Ada Lovelace", memory.interpolateVariables("{{first}} \${last}"))
  }

  @Test
  fun `interpolateVariablesInJson resolves tokens in nested objects and arrays and passes non-strings through`() {
    val memory = AgentMemory()
    memory.remember("email", "merchant@example.com")
    memory.remember("id", "42")
    val input = buildJsonObject {
      put("email", "\${email}")
      put("nested", buildJsonObject { put("who", "{{email}}") })
      put(
        "list",
        buildJsonArray {
          add(JsonPrimitive("id=\${id}"))
          add(JsonPrimitive("literal"))
        },
      )
      put("count", JsonPrimitive(7))
      put("flag", JsonPrimitive(true))
      put("nothing", JsonNull)
    }
    val out = memory.interpolateVariablesInJson(input).jsonObject
    assertEquals("merchant@example.com", out["email"]!!.jsonPrimitive.content)
    assertEquals("merchant@example.com", out["nested"]!!.jsonObject["who"]!!.jsonPrimitive.content)
    assertEquals("id=42", out["list"]!!.jsonArray[0].jsonPrimitive.content)
    assertEquals("literal", out["list"]!!.jsonArray[1].jsonPrimitive.content)
    // Non-string scalars pass through untouched (not stringified).
    assertEquals(7, out["count"]!!.jsonPrimitive.int)
    assertEquals(true, out["flag"]!!.jsonPrimitive.boolean)
    assertEquals(JsonNull, out["nothing"])
  }

  @Test
  fun `interpolateVariablesInJson is a no-op when memory is empty`() {
    val memory = AgentMemory()
    val input = buildJsonObject { put("email", "\${email}") }
    // Empty-variables short-circuit returns the element unchanged; the token is left intact.
    assertEquals("\${email}", memory.interpolateVariablesInJson(input).jsonObject["email"]!!.jsonPrimitive.content)
  }

  @Test
  fun `interpolateVariables leaves unknown tokens in place as literals`() {
    // A typo'd token must arrive at the device/assertion as the visible literal (plus a
    // diagnostic log), not silently blank the string. Known tokens resolve as before.
    val memory = AgentMemory()
    memory.remember("known", "V")
    assertEquals("V {{nope}} \${also_nope}", memory.interpolateVariables("{{known}} {{nope}} \${also_nope}"))
  }

  @Test
  fun `interpolateVariables leaves unknown tokens in place when memory is empty`() {
    val memory = AgentMemory()
    assertEquals("hi {{nope}} world", memory.interpolateVariables("hi {{nope}} world"))
  }

  @Test
  fun `interpolateVariables blank-unknown mode resolves unknown tokens to empty string`() {
    // The TRAILBLAZE_MEMORY_BLANK_UNKNOWN_TOKENS=1 kill-switch path. The env read sits
    // outside this overload so both modes are pinnable without process-global env mutation.
    val memory = AgentMemory()
    memory.remember("known", "V")
    assertEquals("V  world", memory.interpolateVariables("{{known}} {{nope}} world", blankUnknownTokens = true))
  }

  @Test
  fun `interpolateVariables is single-pass — a resolved value containing a token is not re-resolved`() {
    val memory = AgentMemory()
    memory.remember("a", "{{b}}")
    memory.remember("b", "x")
    assertEquals("{{b}}", memory.interpolateVariables("{{a}}"))
  }

  @Test
  fun `interpolateVariablesInJson leaves unknown tokens as literals when memory is non-empty`() {
    // The recorded-replay path: with non-empty memory the tree IS walked, and an unknown
    // token nested anywhere in the args must survive as its literal.
    val memory = AgentMemory()
    memory.remember("known", "V")
    val input = buildJsonObject {
      put("resolved", "\${known}")
      put("typo", "{{knwon}}")
      put("nested", buildJsonObject { put("inner", "id=\${missing}") })
    }
    val out = memory.interpolateVariablesInJson(input).jsonObject
    assertEquals("V", out["resolved"]!!.jsonPrimitive.content)
    assertEquals("{{knwon}}", out["typo"]!!.jsonPrimitive.content)
    assertEquals("id=\${missing}", out["nested"]!!.jsonObject["inner"]!!.jsonPrimitive.content)
  }

  // ---------- the `memory.` token prefix: {{memory.x}} / ${memory.x} alias bare {{x}} / ${x} ----------
  // The qualified spelling is the canonical trail-file grammar going forward (#4737 phase 1);
  // bare tokens remain fully supported (existing recordings + LLM-authored tokens). These tests
  // assert alias-EQUIVALENCE — `memory.x` behaves exactly like bare `x` — rather than pinning the
  // unknown-token outcome, so they hold across a change to unknown-token handling (#4731).

  @Test
  fun `memory-prefixed tokens resolve identically to bare tokens for known keys`() {
    val memory = AgentMemory()
    memory.remember("first", "Ada")
    memory.remember("last", "Lovelace")
    assertEquals(
      memory.interpolateVariables("{{first}} \${last}"),
      memory.interpolateVariables("{{memory.first}} \${memory.last}"),
    )
    assertEquals("Ada Lovelace", memory.interpolateVariables("{{memory.first}} \${memory.last}"))
  }

  @Test
  fun `memory-prefixed unknown token behaves exactly like a bare unknown token`() {
    // Equivalence is computed against the engine's own bare-token behavior (modulo the token's
    // spelling), so this holds whether unknown tokens blank or are left as literals.
    val memory = AgentMemory()
    memory.remember("known", "V")
    val bare = memory.interpolateVariables("[{{nope}}]")
    val prefixed = memory.interpolateVariables("[{{memory.nope}}]")
    assertEquals(bare.replace("{{nope}}", "{{memory.nope}}"), prefixed)
  }

  @Test
  fun `memory-prefixed unknown token behaves like a bare unknown token when memory is empty`() {
    val memory = AgentMemory()
    val bare = memory.interpolateVariables("[\${nope}]")
    val prefixed = memory.interpolateVariables("[\${memory.nope}]")
    assertEquals(bare.replace("\${nope}", "\${memory.nope}"), prefixed)
  }

  @Test
  fun `memory-prefixed token resolves a sensitive key with redaction semantics unchanged`() {
    // Interpolation is where a --secret value is injected into tool args — the qualified
    // spelling must inject the same value, and must not perturb the sensitivity marker.
    val memory = AgentMemory()
    memory.rememberSensitive("pin", "1234")
    assertEquals(memory.interpolateVariables("{{pin}}"), memory.interpolateVariables("{{memory.pin}}"))
    assertEquals("1234", memory.interpolateVariables("{{memory.pin}}"))
    assertContains(memory.sensitiveKeys, "pin")
  }

  @Test
  fun `memory-prefixed token falls back to a key literally named with the prefix when the bare key is absent`() {
    // Nothing stops remember("memory.foo", …). With no bare `foo`, the literal dotted key is
    // the only candidate and must keep resolving.
    val memory = AgentMemory()
    memory.remember("memory.foo", "literal-value")
    assertEquals("literal-value", memory.interpolateVariables("{{memory.foo}}"))
  }

  @Test
  fun `memory-prefixed token prefers the stripped key when both it and the literal dotted key exist`() {
    // The documented collision rule: prefix-strip wins; the shadowed literal key is reported
    // via a diagnostic log (not asserted here — log output isn't part of the contract).
    val memory = AgentMemory()
    memory.remember("foo", "stripped-value")
    memory.remember("memory.foo", "literal-value")
    assertEquals("stripped-value", memory.interpolateVariables("{{memory.foo}}"))
    // The bare spelling is unaffected by the collision.
    assertEquals("stripped-value", memory.interpolateVariables("{{foo}}"))
  }

  @Test
  fun `single-pass property holds for memory-prefixed tokens — a resolved value containing one is not re-resolved`() {
    val memory = AgentMemory()
    memory.remember("a", "{{memory.b}}")
    memory.remember("b", "x")
    assertEquals("{{memory.b}}", memory.interpolateVariables("{{memory.a}}"))
  }

  @Test
  fun `interpolateVariablesInJson resolves memory-prefixed tokens in nested structures`() {
    val memory = AgentMemory()
    memory.remember("email", "merchant@example.com")
    val input = buildJsonObject {
      put("dollar", "\${memory.email}")
      put("nested", buildJsonObject { put("curly", "{{memory.email}}") })
    }
    val out = memory.interpolateVariablesInJson(input).jsonObject
    assertEquals("merchant@example.com", out["dollar"]!!.jsonPrimitive.content)
    assertEquals("merchant@example.com", out["nested"]!!.jsonObject["curly"]!!.jsonPrimitive.content)
  }

  @Test
  fun `clear removes all entries`() {
    val memory = AgentMemory()
    memory.remember("a", "1")
    memory.remember("b", "2")
    memory.clear()
    assertEquals(0, memory.variables.size)
  }

  @Test
  fun `rememberSensitive stores value and marks key as sensitive`() {
    val memory = AgentMemory()
    memory.rememberSensitive("pin", "1234")
    assertEquals("1234", memory.variables["pin"])
    assertContains(memory.sensitiveKeys, "pin")
  }

  @Test
  fun `delete on a sensitive key drops the value but keeps the sensitive marker`() {
    // The marker is a session-lifetime redaction promise (--secret / rememberSensitive):
    // host-side delete must not become an implicit unmark, or a later remember of the same key
    // would re-log the value in cleartext and re-expose it to scripting envelopes. Same rule
    // applyScriptedToolMemoryDelta enforces for scripted tools.
    val memory = AgentMemory()
    memory.rememberSensitive("pin", "1234")
    memory.delete("pin")
    assertFalse(memory.has("pin"))
    assertContains(memory.sensitiveKeys, "pin")
    // Still an explicit deletion — sensitivity doesn't fork the deletedKeys signal.
    assertContains(memory.deletedKeys, "pin")
  }

  @Test
  fun `remember on a key marked sensitive keeps the marker and stores the value`() {
    val memory = AgentMemory()
    memory.rememberSensitive("pin", "1234")
    memory.remember("pin", "5678")
    assertEquals("5678", memory.variables["pin"])
    assertContains(memory.sensitiveKeys, "pin")
  }

  @Test
  fun `sensitive marker survives host-side delete-then-remember`() {
    // The --secret lifecycle contract: once seedFrom marks a key sensitive, no sequence of
    // host-side remember/delete can unmark it, so the envelope/LLM/dumpMemory filters (which
    // read sensitiveKeys live) keep excluding the value for the rest of the session.
    val memory = AgentMemory()
    memory.seedFrom(
      yamlDefaults = null,
      cliSeeds = emptyMap(),
      cliSensitiveSeeds = mapOf("apiToken" to "s3cret"),
    )
    memory.delete("apiToken")
    memory.remember("apiToken", "s3cret-rotated")
    assertEquals("s3cret-rotated", memory.variables["apiToken"])
    assertContains(memory.sensitiveKeys, "apiToken")
    // The re-remember also un-tracks the deletion, exactly like a non-sensitive key.
    assertFalse("apiToken" in memory.deletedKeys)
  }

  @Test
  fun `clear empties the sensitive set`() {
    val memory = AgentMemory()
    memory.rememberSensitive("pin", "1234")
    memory.clear()
    assertEquals(0, memory.sensitiveKeys.size)
  }

  // ---------- deletedKeys: the explicit-deletion signal carried back to the host per-RPC ----------

  @Test
  fun `deletedKeys tracks an explicit delete`() {
    val memory = AgentMemory()
    memory.remember("k", "v")
    memory.delete("k")
    assertContains(memory.deletedKeys, "k")
  }

  @Test
  fun `remember after delete un-tracks the key`() {
    // Re-setting a key after deleting it is not a deletion — the host must not remove it.
    val memory = AgentMemory()
    memory.delete("k")
    assertContains(memory.deletedKeys, "k")
    memory.remember("k", "v")
    assertFalse("k" in memory.deletedKeys)
  }

  @Test
  fun `rememberSensitive after delete un-tracks the key`() {
    val memory = AgentMemory()
    memory.delete("pin")
    assertContains(memory.deletedKeys, "pin")
    memory.rememberSensitive("pin", "1234")
    assertFalse("pin" in memory.deletedKeys)
  }

  @Test
  fun `clear empties the deleted set`() {
    val memory = AgentMemory()
    memory.delete("k")
    memory.clear()
    assertEquals(0, memory.deletedKeys.size)
  }

  @Test
  fun `seeding via variables putAll does not populate deletedKeys`() {
    // The host seeds device-returned snapshots via variables.putAll, bypassing delete/remember —
    // a seeded key must never be mistaken for an explicit deletion.
    val memory = AgentMemory()
    memory.variables.putAll(mapOf("a" to "1", "b" to "2"))
    assertEquals(0, memory.deletedKeys.size)
  }

  // ---------- seedFrom: YAML defaults → CLI seeds → CLI sensitive seeds ----------

  @Test
  fun `seedFrom applies YAML defaults first then CLI seeds on top — CLI wins`() {
    val memory = AgentMemory()
    val resolved = memory.seedFrom(
      yamlDefaults = mapOf("user" to "ci-default", "accountTier" to "BASIC"),
      cliSeeds = mapOf("user" to "sam"),
      cliSensitiveSeeds = emptyMap(),
    )
    assertEquals("sam", memory.variables["user"]) // CLI overrode YAML
    assertEquals("BASIC", memory.variables["accountTier"]) // YAML preserved
    assertEquals(mapOf("user" to "sam", "accountTier" to "BASIC"), resolved)
  }

  @Test
  fun `seedFrom routes sensitive seeds through rememberSensitive`() {
    val memory = AgentMemory()
    memory.seedFrom(
      yamlDefaults = null,
      cliSeeds = emptyMap(),
      cliSensitiveSeeds = mapOf("password" to "hunter2"),
    )
    assertEquals("hunter2", memory.variables["password"])
    assertContains(memory.sensitiveKeys, "password")
  }

  @Test
  fun `seedFrom excludes sensitive keys from the returned resolved snapshot`() {
    // The returned map is what SessionStarted.resolvedInitialMemory carries — it MUST
    // omit sensitive values so the session log doesn't persist secrets.
    val memory = AgentMemory()
    val resolved = memory.seedFrom(
      yamlDefaults = mapOf("user" to "sam"),
      cliSeeds = mapOf("accountTier" to "PRO"),
      cliSensitiveSeeds = mapOf("password" to "hunter2"),
    )
    assertEquals(mapOf("user" to "sam", "accountTier" to "PRO"), resolved)
    assertFalse("password" in resolved)
  }

  @Test
  fun `seedFrom — sensitive seed wins on same-key collision with non-sensitive`() {
    // If a key appears in both cliSeeds and cliSensitiveSeeds, the sensitive tier
    // wins (and the key disappears from the returned snapshot). This is the cautious
    // direction: an author who passes the same key both ways probably wants the secret
    // semantics, and we'd rather under-share than over-share.
    val memory = AgentMemory()
    val resolved = memory.seedFrom(
      yamlDefaults = mapOf("token" to "yaml-default"),
      cliSeeds = mapOf("token" to "cli-non-secret"),
      cliSensitiveSeeds = mapOf("token" to "the-real-secret"),
    )
    assertEquals("the-real-secret", memory.variables["token"])
    assertContains(memory.sensitiveKeys, "token")
    assertFalse("token" in resolved)
  }

  @Test
  fun `seedFrom — null yamlDefaults and empty seeds is a no-op`() {
    val memory = AgentMemory()
    val resolved = memory.seedFrom(
      yamlDefaults = null,
      cliSeeds = emptyMap(),
      cliSensitiveSeeds = emptyMap(),
    )
    assertEquals(0, memory.variables.size)
    assertEquals(0, memory.sensitiveKeys.size)
    assertEquals(0, resolved.size)
  }

  @Test
  fun `seedFrom — yaml + sensitive collision (no cli) — sensitive value wins, key excluded from snapshot`() {
    // Two-tier collision: yaml has `token: yaml-value` and a sensitive seed has
    // `token: secret-value`. The previous test (`sensitive seed wins on same-key collision
    // with non-sensitive`) covers the three-tier case; this pins the two-tier case so a
    // refactor of seedFrom's collision logic can't regress the simpler cell of the matrix.
    val memory = AgentMemory()
    val resolved = memory.seedFrom(
      yamlDefaults = mapOf("token" to "yaml-value"),
      cliSeeds = emptyMap(),
      cliSensitiveSeeds = mapOf("token" to "secret-value"),
    )
    assertEquals("secret-value", memory.variables["token"])
    assertContains(memory.sensitiveKeys, "token")
    assertFalse("token" in resolved)
  }

  @Test
  fun `seedFrom — cli + sensitive collision (no yaml) — sensitive value wins, key excluded from snapshot`() {
    // Mirror of the above for the (cli + sensitive, no yaml) cell.
    val memory = AgentMemory()
    val resolved = memory.seedFrom(
      yamlDefaults = null,
      cliSeeds = mapOf("token" to "cli-value"),
      cliSensitiveSeeds = mapOf("token" to "secret-value"),
    )
    assertEquals("secret-value", memory.variables["token"])
    assertContains(memory.sensitiveKeys, "token")
    assertFalse("token" in resolved)
  }

  @Test
  fun `seeded values — including sensitive ones — resolve through token interpolation`() {
    // The end-to-end promise every runner path makes: a value seeded from
    // `config.memory:` / `--memory` / `--secret` is usable as a `{{var}}` token in
    // trail steps. Sensitive seeds are redacted from logs/snapshots but must still
    // interpolate — that's the whole point of `--secret password=…`.
    val memory = AgentMemory()
    memory.seedFrom(
      yamlDefaults = mapOf("user" to "ci-default"),
      cliSeeds = mapOf("user" to "sam"),
      cliSensitiveSeeds = mapOf("password" to "hunter2"),
    )
    assertEquals("sam signs in with hunter2", memory.interpolateVariables("{{user}} signs in with {{password}}"))
  }

  @Test
  fun `seeding activates JSON interpolation — an unknown token still survives as a literal once seeds are present`() {
    // Pins the interplay that wiring seeding into the V1 / ComposeRpc / Playwright / Revyl
    // runner paths activates. `interpolateVariablesInJson` short-circuits on EMPTY memory
    // (pinned by `…is a no-op when memory is empty` above), and on those paths memory was
    // effectively always empty — so token-like text in recorded tool args passed through
    // raw. Seeding flips them to non-empty memory, which activates interpolation; the
    // leave-literal semantics (#4731) must then hold on these newly-activated paths too —
    // an unknown token passes through unchanged rather than blanking to empty string.
    val memory = AgentMemory()
    memory.seedFrom(
      yamlDefaults = null,
      cliSeeds = mapOf("user" to "sam"),
      cliSensitiveSeeds = emptyMap(),
    )
    val input = buildJsonObject { put("url", "https://example.com/{{not-a-var}}") }
    val out = memory.interpolateVariablesInJson(input).jsonObject["url"]!!.jsonPrimitive.content
    assertEquals("https://example.com/{{not-a-var}}", out)
  }

  @Test
  fun `seedFrom — key already marked sensitive is excluded from the returned snapshot`() {
    // The sticky marker outlives seeding tiers: a key marked sensitive earlier in the session
    // that arrives again via a NON-sensitive tier is stored with sensitive semantics and must
    // not ride the returned snapshot into the persisted session log
    // (SessionStarted.resolvedInitialMemory).
    val memory = AgentMemory()
    memory.rememberSensitive("apiToken", "s3cret-original")
    val resolved = memory.seedFrom(
      yamlDefaults = null,
      cliSeeds = mapOf("apiToken" to "s3cret-reseeded", "user" to "sam"),
      cliSensitiveSeeds = emptyMap(),
    )
    assertEquals("s3cret-reseeded", memory.variables["apiToken"])
    assertContains(memory.sensitiveKeys, "apiToken")
    assertEquals(mapOf("user" to "sam"), resolved)
  }

  // ---- applyScriptedToolMemoryDelta -------------------------------------------------------------
  // The models-level entry point both scripted-tool runtimes apply through: the subprocess/MCP path
  // (via TrailblazeContextEnvelope.applyResultMemoryDelta, which delegates here) and the
  // in-process/on-device QuickJS path (QuickJsTrailblazeTool.execute, which calls this directly).

  private fun resultMetaWith(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
    buildJsonObject { put("trailblaze", buildJsonObject(build)) }

  @Test
  fun `applyScriptedToolMemoryDelta merges sets and reports applied`() {
    val memory = AgentMemory().apply { remember("existing", "old") }
    val applied = applyScriptedToolMemoryDelta(
      memory,
      resultMetaWith { put("memoryDelta", buildJsonObject { put("user", "ada"); put("existing", "new") }) },
    )
    assertTrue(applied)
    assertEquals("ada", memory.variables["user"])
    assertEquals("new", memory.variables["existing"])
  }

  @Test
  fun `applyScriptedToolMemoryDelta applies deletions`() {
    val memory = AgentMemory().apply {
      remember("keep", "k")
      remember("drop", "d")
    }
    applyScriptedToolMemoryDelta(
      memory,
      resultMetaWith { put("memoryDeletions", buildJsonArray { add(JsonPrimitive("drop")) }) },
    )
    assertFalse(memory.has("drop"))
    assertEquals("k", memory.variables["keep"])
  }

  @Test
  fun `applyScriptedToolMemoryDelta no-ops on null meta or missing trailblaze envelope`() {
    val memory = AgentMemory().apply { remember("k", "v") }
    assertFalse(applyScriptedToolMemoryDelta(memory, null))
    assertFalse(applyScriptedToolMemoryDelta(memory, buildJsonObject { put("other", "bucket") }))
    assertEquals("v", memory.variables["k"])
  }

  @Test
  fun `applyScriptedToolMemoryDelta preserves sensitive marker on overwrite and deletion`() {
    // A scripted tool never sees sensitive values in its snapshot, so it must not be able to leak a
    // sensitive value into logs (overwrite routes through rememberSensitive) nor unmark the key
    // (deletion drops the value but keeps the marker). Host owns the sensitivity lifecycle.
    val memory = AgentMemory().apply { rememberSensitive("pin", "1234") }
    applyScriptedToolMemoryDelta(memory, resultMetaWith { put("memoryDelta", buildJsonObject { put("pin", "5678") }) })
    assertEquals("5678", memory.variables["pin"])
    assertContains(memory.sensitiveKeys, "pin")

    applyScriptedToolMemoryDelta(memory, resultMetaWith { put("memoryDeletions", buildJsonArray { add(JsonPrimitive("pin")) }) })
    assertFalse(memory.has("pin"))
    assertContains(memory.sensitiveKeys, "pin")
    // Uniform with host delete(): the explicit-deletion signal is recorded for sensitive keys too.
    assertContains(memory.deletedKeys, "pin")
  }

  @Test
  fun `applyScriptedToolMemoryDelta skips non-string entries`() {
    // A producer-side bug that emits one bad entry must not sabotage the sibling writes.
    val memory = AgentMemory()
    applyScriptedToolMemoryDelta(
      memory,
      resultMetaWith { put("memoryDelta", buildJsonObject { put("good", "value"); put("bad", JsonPrimitive(42)) }) },
    )
    assertEquals("value", memory.variables["good"])
    assertNull(memory.variables["bad"])
  }

  @Test
  fun `applyScriptedToolMemoryDelta deletion wins when the same key appears in both sets and deletions`() {
    // Pins the documented precedence for an otherwise-impossible shape (the well-behaved TS SDK's
    // last-write-wins buffer can never emit the same key in both `memoryDelta` and
    // `memoryDeletions` — see `drainDelta` in memory.ts). This function is a standalone,
    // defensive entry point, so the behavior for a malformed delta from any other producer is
    // still pinned deliberately: sets apply before deletions, so deletion wins.
    val memory = AgentMemory().apply { remember("k", "old") }
    applyScriptedToolMemoryDelta(
      memory,
      resultMetaWith {
        put("memoryDelta", buildJsonObject { put("k", "new") })
        put("memoryDeletions", buildJsonArray { add(JsonPrimitive("k")) })
      },
    )
    assertFalse(memory.has("k"))
  }
}
