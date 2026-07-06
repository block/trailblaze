package xyz.block.trailblaze.toolcalls.commands

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlinx.serialization.json.Json
import maestro.ScrollDirection
import maestro.SwipeDirection
import org.junit.Test

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
}
