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
}
