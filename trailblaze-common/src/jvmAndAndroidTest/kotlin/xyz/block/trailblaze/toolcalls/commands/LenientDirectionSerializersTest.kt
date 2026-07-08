package xyz.block.trailblaze.toolcalls.commands

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.messageContains
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import maestro.ScrollDirection
import maestro.SwipeDirection
import org.junit.Test
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance

class LenientDirectionSerializersTest {

  @Test
  fun `scroll direction decodes lowercase`() {
    assertThat(
      Json.decodeFromString(LenientScrollDirectionSerializer, "\"down\""),
    ).isEqualTo(ScrollDirection.DOWN)
  }

  @Test
  fun `swipe direction decodes mixed case with whitespace`() {
    assertThat(
      Json.decodeFromString(LenientSwipeDirectionSerializer, "\" Up \""),
    ).isEqualTo(SwipeDirection.UP)
  }

  @Test
  fun `encodes canonical enum name`() {
    assertThat(
      Json.encodeToString(LenientScrollDirectionSerializer, ScrollDirection.LEFT),
    ).isEqualTo("\"LEFT\"")
  }

  // Invalid values must fail with SerializationException, NOT IllegalArgumentException —
  // BaseTrailblazeAgent.resolveDynamicTool deliberately catches only IllegalStateException
  // and SerializationException; anything else propagates and kills the run.
  @Test
  fun `invalid direction throws SerializationException listing valid values`() {
    assertFailure {
      Json.decodeFromString(LenientScrollDirectionSerializer, "\"forward\"")
    }.isInstanceOf(SerializationException::class)
      .messageContains("UP")
  }

  // Decodes through the tool's own serializer so the @Serializable(with = ...) annotation
  // wiring on the direction property is exercised, not just the serializer object.
  @Test
  fun `swipe tool decodes lowercase direction via property annotation`() {
    val tool = TrailblazeJsonInstance.decodeFromString(
      SwipeTrailblazeTool.serializer(),
      """{"direction":"down"}""",
    )
    assertThat(tool.direction).isEqualTo(SwipeDirection.DOWN)
  }

  @Test
  fun `scroll tool decodes lowercase direction via property annotation`() {
    val tool = TrailblazeJsonInstance.decodeFromString(
      ScrollUntilTextIsVisibleTrailblazeTool.serializer(),
      """{"text":"Settings","direction":"up"}""",
    )
    assertThat(tool.direction).isEqualTo(ScrollDirection.UP)
  }
}
