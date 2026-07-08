package xyz.block.trailblaze.toolcalls.commands

import assertk.assertThat
import assertk.assertions.isEqualTo
import maestro.ScrollDirection
import maestro.SwipeDirection
import org.junit.Test
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.maestro.TrailblazeScrollStartPosition

/**
 * LLMs regularly emit enum values in lowercase regardless of the schema's casing (the tool
 * descriptions themselves sometimes prompt lowercase — see objectiveStatus). Every LLM-facing
 * enum tool parameter must decode case-insensitively via [CaseInsensitiveEnumSerializer];
 * these decode through the real tool serializers so the annotation wiring is covered.
 */
class CaseInsensitiveToolEnumsTest {

  @Test
  fun `scrollUntilTextIsVisible decodes lowercase direction and scrollStartPosition`() {
    val tool = TrailblazeJsonInstance.decodeFromString(
      ScrollUntilTextIsVisibleTrailblazeTool.serializer(),
      """{"text":"Settings","direction":"up","scrollStartPosition":"bottom"}""",
    )
    assertThat(tool.direction).isEqualTo(ScrollDirection.UP)
    assertThat(tool.scrollStartPosition).isEqualTo(TrailblazeScrollStartPosition.BOTTOM)
  }

  @Test
  fun `swipe decodes lowercase direction`() {
    val tool = TrailblazeJsonInstance.decodeFromString(
      SwipeTrailblazeTool.serializer(),
      """{"direction":"down"}""",
    )
    assertThat(tool.direction).isEqualTo(SwipeDirection.DOWN)
  }

  @Test
  fun `objectiveStatus decodes the lowercase status its own description prompts for`() {
    val tool = TrailblazeJsonInstance.decodeFromString(
      ObjectiveStatusTrailblazeTool.serializer(),
      """{"explanation":"done","status":"in_progress"}""",
    )
    assertThat(tool.status).isEqualTo(Status.IN_PROGRESS)
  }

  @Test
  fun `launchApp decodes lowercase launchMode`() {
    val tool = TrailblazeJsonInstance.decodeFromString(
      LaunchAppTrailblazeTool.serializer(),
      """{"appId":"com.example.app","launchMode":"force_restart"}""",
    )
    assertThat(tool.launchMode).isEqualTo(LaunchAppTrailblazeTool.LaunchMode.FORCE_RESTART)
  }

  @Test
  fun `elementRetriever decodes lowercase locatorType`() {
    val tool = TrailblazeJsonInstance.decodeFromString(
      ElementRetrieverTrailblazeTool.serializer(),
      """{"identifier":"the button","locatorType":"resource_id","value":"btn"}""",
    )
    assertThat(tool.locatorType)
      .isEqualTo(ElementRetrieverTrailblazeTool.LocatorType.RESOURCE_ID)
  }

  @Test
  fun `textMatchMode decodes lowercase`() {
    assertThat(
      TrailblazeJsonInstance.decodeFromString(TextMatchMode.serializer(), "\"prefix\""),
    ).isEqualTo(TextMatchMode.PREFIX)
  }
}
