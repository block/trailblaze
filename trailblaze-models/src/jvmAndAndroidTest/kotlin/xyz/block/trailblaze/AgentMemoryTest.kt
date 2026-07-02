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
  fun `interpolateVariables maps unknown tokens to empty string`() {
    val memory = AgentMemory()
    assertEquals("hi  world", memory.interpolateVariables("hi {{nope}} world"))
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
  fun `delete on a sensitive key also drops the sensitive marker`() {
    // Without this, a future remember(k) would inherit a stale sensitive marker — confusing
    // for callers that expected a non-sensitive value to flow through envelopes.
    val memory = AgentMemory()
    memory.rememberSensitive("pin", "1234")
    memory.delete("pin")
    assertFalse("pin" in memory.sensitiveKeys)
  }

  @Test
  fun `clear empties the sensitive set`() {
    val memory = AgentMemory()
    memory.rememberSensitive("pin", "1234")
    memory.clear()
    assertEquals(0, memory.sensitiveKeys.size)
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
