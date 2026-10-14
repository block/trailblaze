package xyz.block.trailblaze.trailhead

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class EventMatchTest {

  private fun match(json: String) = EventMatch.parse(Json.parseToJsonElement(json).jsonObject)
  private fun record(json: String) = Json.parseToJsonElement(json)

  private val post = record("""{"method":"POST","url":"/v1/Items/9","status":201,"ok":true,"body":{"kind":"item","id":"i-7"}}""")

  @Test
  fun `scalars match by equality and numbers compare numerically`() {
    assertTrue(match("""{"method":"POST","status":201.0,"ok":true}""").matches(post))
    assertFalse(match("""{"method":"GET"}""").matches(post))
    assertFalse(match("""{"status":"201"}""").matches(post))
  }

  @Test
  fun `a regex tests the value's string form with its flags`() {
    assertTrue(match("""{"url":{"${'$'}regex":"/v1/items","${'$'}flags":"i"}}""").matches(post))
    assertFalse(match("""{"url":{"${'$'}regex":"/v1/items"}}""").matches(post))
    assertTrue(match("""{"body":{"${'$'}regex":"\"id\":\"i-7\""}}""").matches(post))
  }

  @Test
  fun `a nested object is a subset match`() {
    assertTrue(match("""{"body":{"kind":"item"}}""").matches(post))
    assertFalse(match("""{"body":{"kind":"order"}}""").matches(post))
  }

  @Test
  fun `a missing key fails, except against a null match`() {
    assertFalse(match("""{"error":"x"}""").matches(post))
    assertTrue(match("""{"error":null}""").matches(post))
    assertTrue(EventMatch.parse(JsonObject(emptyMap())).matches(post))
  }

  @Test
  fun `a bad pattern or an array fails at parse instead of matching nothing`() {
    assertFailsWith<IllegalArgumentException> { match("""{"ids":["a"]}""") }
    assertFailsWith<Exception> { match("""{"url":{"${'$'}regex":"("}}""") }
  }

  @Test
  fun `a flag the match can't honor fails at parse, and g is accepted`() {
    val dotAll = assertFailsWith<IllegalArgumentException> { match("""{"url":{"${'$'}regex":"a.b","${'$'}flags":"is"}}""") }
    assertTrue(dotAll.message!!.contains("'s'"), dotAll.message)
    assertTrue(match("""{"url":{"${'$'}regex":"/v1/items","${'$'}flags":"gi"}}""").matches(post))
  }
}
