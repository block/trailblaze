package xyz.block.trailblaze.playwright.tools

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlinx.serialization.KSerializer
import org.junit.Test
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance

/**
 * Exhaustive backstop for every [xyz.block.trailblaze.yaml.serializers.CaseInsensitiveEnumSerializer]
 * declared in this module — the playwright counterpart of `CaseInsensitiveToolEnumsTest` in
 * `trailblaze-common`. Driven by each enum's `entries`, so adding a constant is covered
 * automatically; only a brand-new serializer needs a line here. This exists because the
 * serializer's constructor takes the constants as a caller-supplied list — a subset or a
 * stale list type-checks but silently makes the missing constants undecodable.
 */
class PlaywrightCaseInsensitiveEnumsTest {

  @Test
  fun `every constant of every case-insensitive serializer round-trips in any casing`() {
    assertRoundTripsEveryConstant(
      PlaywrightNativeNavigateTool.NavigationAction.Serializer,
      PlaywrightNativeNavigateTool.NavigationAction.entries,
    )
    assertRoundTripsEveryConstant(
      PlaywrightNativeScrollTool.ScrollDirection.Serializer,
      PlaywrightNativeScrollTool.ScrollDirection.entries,
    )
    assertRoundTripsEveryConstant(
      PlaywrightNativeVerifyValueTool.VerifyValueType.Serializer,
      PlaywrightNativeVerifyValueTool.VerifyValueType.entries,
    )
  }

  private fun <T : Enum<T>> assertRoundTripsEveryConstant(
    serializer: KSerializer<T>,
    entries: List<T>,
  ) {
    entries.forEach { constant ->
      val spellings = listOf(
        constant.name,
        constant.name.lowercase(),
        constant.name.lowercase().replaceFirstChar { it.uppercase() },
        " ${constant.name.lowercase()} ",
      )
      spellings.forEach { spelling ->
        assertThat(
          TrailblazeJsonInstance.decodeFromString(serializer, "\"$spelling\""),
          name = "decode '$spelling'",
        ).isEqualTo(constant)
      }
      assertThat(TrailblazeJsonInstance.encodeToString(serializer, constant))
        .isEqualTo("\"${constant.name}\"")
    }
  }
}
