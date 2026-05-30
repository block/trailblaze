package xyz.block.trailblaze.bundle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Direct unit tests for [renderToolMapEntry] and its helpers (`escapeJsDocComment`,
 * `isSafeTsIdentifier`). The shared renderer is the single source of truth for the
 * `TrailblazeToolMap` entry block across both [TrailblazeTrailmapBundler.renderBindings] and
 * [WorkspaceClientDtsGenerator.renderClientDts] — a regression here would fail both
 * downstream emitter test suites simultaneously, which is harder to diagnose than a
 * direct test pointing at the offending helper or branch.
 *
 * These tests pin the exact rendered output line-by-line where the shape is load-bearing,
 * and exercise each edge case the renderer's branches care about.
 */
class ToolMapEntryRendererTest {

  @Test
  fun `renderToolMapEntry no params emits args Record string never plus result string`() {
    val rendered = renderToolMapEntry(
      ToolMapEntry(
        name = "noargs",
        description = "Takes no args.",
        params = emptyList(),
      ),
    )
    val expected = """
      |    /**
      |     * Takes no args.
      |     */
      |    noargs: {
      |      args: Record<string, never>;
      |      result: string;
      |    };
      |
    """.trimMargin()
    assertEquals(expected, rendered)
  }

  @Test
  fun `renderToolMapEntry with mixed required and optional params nests under args`() {
    val rendered = renderToolMapEntry(
      ToolMapEntry(
        name = "demo",
        description = "Demo tool.",
        params = listOf(
          ToolMapParam(name = "required1", tsType = "string", description = "First.", optional = false),
          ToolMapParam(name = "optional1", tsType = "number", description = null, optional = true),
        ),
      ),
    )
    // Required and optional both render with consistent 8-space indent under `args: {`.
    assertTrue("rendered: $rendered") { rendered.contains("      args: {") }
    assertTrue("rendered: $rendered") { rendered.contains("        /** First. */") }
    assertTrue("rendered: $rendered") { rendered.contains("        required1: string;") }
    assertTrue("rendered: $rendered") { rendered.contains("        optional1?: number;") }
    assertTrue("rendered: $rendered") { rendered.contains("      };") }
    assertTrue("rendered: $rendered") { rendered.contains("      result: string;") }
  }

  @Test
  fun `renderToolMapEntry hyphenated tool name renders as quoted property`() {
    val rendered = renderToolMapEntry(
      ToolMapEntry(
        name = "hyphen-tool",
        description = null,
        params = emptyList(),
      ),
    )
    assertTrue("rendered: $rendered") { rendered.contains("    \"hyphen-tool\": {") }
    assertFalse("rendered: $rendered") { rendered.contains("    hyphen-tool: {") }
  }

  @Test
  fun `renderToolMapEntry hyphenated param name renders as quoted key`() {
    val rendered = renderToolMapEntry(
      ToolMapEntry(
        name = "demo",
        description = null,
        params = listOf(
          ToolMapParam(name = "kebab-case", tsType = "string", description = null, optional = false),
        ),
      ),
    )
    assertTrue("rendered: $rendered") { rendered.contains("        \"kebab-case\": string;") }
  }

  @Test
  fun `renderToolMapEntry escapes embedded JSDoc closer in description`() {
    val rendered = renderToolMapEntry(
      ToolMapEntry(
        name = "demo",
        description = "Has */ inside.",
        params = emptyList(),
      ),
    )
    // The literal JSDoc closer is split with a space so it doesn't prematurely close the
    // surrounding JSDoc block in the rendered output.
    assertTrue("rendered: $rendered") { rendered.contains("     * Has * / inside.") }
    assertFalse("raw closer leaked: $rendered") {
      rendered.lineSequence().any { it.contains("Has */ inside.") }
    }
  }

  @Test
  fun `renderToolMapEntry escapes embedded JSDoc closer in param description`() {
    val rendered = renderToolMapEntry(
      ToolMapEntry(
        name = "demo",
        description = null,
        params = listOf(
          ToolMapParam(name = "x", tsType = "string", description = "Bad */ here.", optional = false),
        ),
      ),
    )
    assertTrue("rendered: $rendered") { rendered.contains("        /** Bad * / here. */") }
  }

  @Test
  fun `renderToolMapEntry with sourceAttribution adds Source line in JSDoc`() {
    val rendered = renderToolMapEntry(
      ToolMapEntry(
        name = "demo",
        description = "Demo.",
        params = emptyList(),
        sourceAttribution = "./tools/demo.ts",
      ),
    )
    assertTrue("rendered: $rendered") { rendered.contains("     * Source: ./tools/demo.ts") }
    // A blank `     *` separator line precedes the Source line when both description and
    // attribution are present.
    assertTrue("rendered: $rendered") { rendered.contains("     * Demo.\n     *\n     * Source:") }
  }

  @Test
  fun `renderToolMapEntry with null sourceAttribution omits Source line`() {
    val rendered = renderToolMapEntry(
      ToolMapEntry(
        name = "demo",
        description = "Demo.",
        params = emptyList(),
        sourceAttribution = null,
      ),
    )
    assertFalse("rendered: $rendered") { rendered.contains("Source:") }
  }

  @Test
  fun `renderToolMapEntry with blank sourceAttribution skips the source line`() {
    // Tightened in lead-dev review: `isNullOrBlank()` so empty / whitespace-only strings
    // are treated the same as `null` (no degenerate `* Source: ` bare line). Pins the
    // contract so a future change can't drift back to a literal `!= null` check.
    val renderedEmpty = renderToolMapEntry(
      ToolMapEntry(
        name = "demo",
        description = "Demo.",
        params = emptyList(),
        sourceAttribution = "",
      ),
    )
    val renderedWhitespace = renderToolMapEntry(
      ToolMapEntry(
        name = "demo",
        description = "Demo.",
        params = emptyList(),
        sourceAttribution = "   ",
      ),
    )
    assertFalse("empty-attr rendered Source: $renderedEmpty") { renderedEmpty.contains("Source:") }
    assertFalse("blank-attr rendered Source: $renderedWhitespace") { renderedWhitespace.contains("Source:") }
  }

  @Test
  fun `escapeJsDocComment replaces only the JSDoc closer`() {
    assertEquals("hello", escapeJsDocComment("hello"))
    assertEquals("a * / b", escapeJsDocComment("a */ b"))
    // Multiple occurrences are all escaped.
    assertEquals("* /one* /two", escapeJsDocComment("*/one*/two"))
    // Empty input.
    assertEquals("", escapeJsDocComment(""))
  }

  @Test
  fun `isSafeTsIdentifier accepts the standard identifier subset`() {
    assertTrue(isSafeTsIdentifier("foo"))
    assertTrue(isSafeTsIdentifier("foo_bar"))
    assertTrue(isSafeTsIdentifier("foo123"))
    assertTrue(isSafeTsIdentifier("_underscore_start"))
    assertTrue(isSafeTsIdentifier("\$dollar"))
    assertTrue(isSafeTsIdentifier("A"))
  }

  @Test
  fun `isSafeTsIdentifier rejects names with disallowed characters or leading digit`() {
    assertFalse(isSafeTsIdentifier(""))
    assertFalse(isSafeTsIdentifier("1startsWithDigit"))
    assertFalse(isSafeTsIdentifier("has-hyphen"))
    assertFalse(isSafeTsIdentifier("has space"))
    assertFalse(isSafeTsIdentifier("has.dot"))
    assertFalse(isSafeTsIdentifier("has/slash"))
  }

  @Test
  fun `argsLiteralTsType overrides params decomposition`() {
    // Lead-dev review #9: when [argsLiteralTsType] is set, it MUST win over a non-empty
    // [params] list — the analyzer-driven path replaces the YAML-flat decomposition with
    // the full typed-source shape. Pinned at the renderer layer rather than going through
    // WorkspaceClientDtsGenerator so a regression here fails close to the offending branch.
    val rendered = renderToolMapEntry(
      ToolMapEntry(
        name = "typed",
        description = null,
        params = listOf(
          ToolMapParam(name = "shouldBeIgnored", tsType = "string", description = null, optional = false),
        ),
        argsLiteralTsType = "{ a: number; b: string }",
      ),
    )
    assertTrue("expected literal args to win: $rendered") {
      rendered.contains("args: { a: number; b: string };")
    }
    assertFalse("params decomposition must NOT appear when literal overrides: $rendered") {
      rendered.contains("shouldBeIgnored")
    }
  }

  @Test
  fun `resultTsType non-null overrides default string`() {
    val rendered = renderToolMapEntry(
      ToolMapEntry(
        name = "typed_result",
        description = null,
        params = emptyList(),
        resultTsType = "{ formatted: string; count: number }",
      ),
    )
    assertTrue("expected typed result: $rendered") {
      rendered.contains("result: { formatted: string; count: number };")
    }
    assertFalse("default `result: string;` must not appear when override is set: $rendered") {
      rendered.contains("result: string;")
    }
  }

  @Test
  fun `resultTsType null falls back to result string`() {
    // Pin the today-default fallback so a future change to the default doesn't silently
    // shift the rendered shape for every YAML-only tool.
    val rendered = renderToolMapEntry(
      ToolMapEntry(
        name = "yaml_only",
        description = null,
        params = emptyList(),
      ),
    )
    assertTrue("expected fallback `result: string;`: $rendered") {
      rendered.contains("result: string;")
    }
  }

  @Test
  fun `frameworkMetadata null emits no tag lines`() {
    // Null metadata → renderer behaves identically to pre-tag world. Regression guard so a
    // future change to the null-handling branch can't accidentally start emitting empty
    // tag separators or default-valued tags.
    val rendered = renderToolMapEntry(
      ToolMapEntry(
        name = "demo",
        description = "Demo tool.",
        params = emptyList(),
        frameworkMetadata = null,
      ),
    )
    assertFalse("no @trailblaze tag should appear: $rendered") { rendered.contains("@trailblaze") }
  }

  @Test
  fun `frameworkMetadata all defaults emits no tag lines`() {
    // The common case (built-in surfaced-to-llm + recordable + device-capable + non-
    // verification + non-trailhead + non-host-callback) must render with no tag noise.
    val rendered = renderToolMapEntry(
      ToolMapEntry(
        name = "demo",
        description = "Demo tool.",
        params = emptyList(),
        frameworkMetadata = ToolFrameworkMetadata(),
      ),
    )
    assertFalse("no @trailblaze tag should appear for all-defaults: $rendered") {
      rendered.contains("@trailblaze")
    }
  }

  @Test
  fun `frameworkMetadata requiresHost true emits @trailblazeHostOnly tag`() {
    val rendered = renderToolMapEntry(
      ToolMapEntry(
        name = "android_adbShell",
        description = "Run an adb shell command.",
        params = emptyList(),
        frameworkMetadata = ToolFrameworkMetadata(requiresHost = true),
      ),
    )
    assertTrue("expected @trailblazeHostOnly: $rendered") {
      rendered.contains("     * @trailblazeHostOnly")
    }
    // Description and the tag are separated by a blank `     *` line so the JSDoc block
    // visually groups prose-vs-tags.
    assertTrue("expected blank separator between description and tag: $rendered") {
      rendered.contains("     * Run an adb shell command.\n     *\n     * @trailblazeHostOnly")
    }
  }

  @Test
  fun `frameworkMetadata surfaceToLlm false emits @trailblazeHiddenFromLlm tag`() {
    val rendered = renderToolMapEntry(
      ToolMapEntry(
        name = "web_evaluate",
        description = "Run JS in the page.",
        params = emptyList(),
        frameworkMetadata = ToolFrameworkMetadata(surfaceToLlm = false),
      ),
    )
    assertTrue("expected @trailblazeHiddenFromLlm: $rendered") {
      rendered.contains("     * @trailblazeHiddenFromLlm")
    }
  }

  @Test
  fun `frameworkMetadata isRecordable false emits @trailblazeNotRecordable tag`() {
    val rendered = renderToolMapEntry(
      ToolMapEntry(
        name = "wrapper",
        description = "Wrapper tool.",
        params = emptyList(),
        frameworkMetadata = ToolFrameworkMetadata(isRecordable = false),
      ),
    )
    assertTrue("expected @trailblazeNotRecordable: $rendered") {
      rendered.contains("     * @trailblazeNotRecordable")
    }
  }

  @Test
  fun `frameworkMetadata trailheadTo non-empty emits tag with waypoint value`() {
    val rendered = renderToolMapEntry(
      ToolMapEntry(
        name = "openCheckoutLibrary",
        description = "Bootstrap to the checkout library waypoint.",
        params = emptyList(),
        frameworkMetadata = ToolFrameworkMetadata(trailheadTo = "square/checkout/library"),
      ),
    )
    assertTrue("expected @trailblazeTrailheadTo with waypoint: $rendered") {
      rendered.contains("     * @trailblazeTrailheadTo square/checkout/library")
    }
  }

  @Test
  fun `frameworkMetadata trailheadTo with embedded JSDoc closer is escaped`() {
    // Defense-in-depth: a `trailheadTo` value containing `*/` would prematurely close the
    // surrounding JSDoc block. Even though today the value comes from a Kotlin annotation source
    // (limiting practical exposure), the renderer must defend the same way `escapeJsDocComment`
    // defends descriptions and param comments. Pin the escape so a future change can't drift
    // back to a raw interpolation.
    val rendered = renderToolMapEntry(
      ToolMapEntry(
        name = "exploitable",
        description = null,
        params = emptyList(),
        frameworkMetadata = ToolFrameworkMetadata(trailheadTo = "bad*/value"),
      ),
    )
    assertTrue("expected escaped trailhead tag: $rendered") {
      rendered.contains("@trailblazeTrailheadTo bad* /value")
    }
    assertFalse("raw JSDoc closer must not leak into the tag line: $rendered") {
      rendered.lineSequence().any { it.contains("bad*/value") }
    }
  }

  @Test
  fun `frameworkMetadata trailheadTo empty string emits no tag`() {
    // The annotation's default is the empty string — only non-empty waypoint values
    // mark a tool as a trailhead. Pin the empty-string-is-default contract.
    val rendered = renderToolMapEntry(
      ToolMapEntry(
        name = "regular",
        description = "Regular tool, not a trailhead.",
        params = emptyList(),
        frameworkMetadata = ToolFrameworkMetadata(trailheadTo = ""),
      ),
    )
    assertFalse("@trailblazeTrailheadTo must not appear for empty waypoint: $rendered") {
      rendered.contains("@trailblazeTrailheadTo")
    }
  }

  @Test
  fun `frameworkMetadata trailheadTo whitespace-only emits no tag`() {
    // Parity with the `sourceAttribution` check in `renderToolMapEntry` — whitespace-only
    // strings are treated the same as empty (no tag emitted). Without this, a value like
    // "   " would render `@trailblazeTrailheadTo    ` with a payload that's literally
    // whitespace — a useless tag line that bloats the rendered .d.ts.
    val rendered = renderToolMapEntry(
      ToolMapEntry(
        name = "regular",
        description = "Regular tool, not a trailhead.",
        params = emptyList(),
        frameworkMetadata = ToolFrameworkMetadata(trailheadTo = "   "),
      ),
    )
    assertFalse("@trailblazeTrailheadTo must not appear for whitespace-only waypoint: $rendered") {
      rendered.contains("@trailblazeTrailheadTo")
    }
  }

  @Test
  fun `frameworkMetadata multiple non-default fields emit all tags in declaration order`() {
    // Order is declaration-order of fields on `ToolFrameworkMetadata`: surfaceToLlm,
    // isRecordable, requiresHost, trailheadTo. Pin so a reordering of the data class fields
    // (which would shift the rendered output) becomes a deliberate test update rather than
    // an invisible byte-diff churn.
    val rendered = renderToolMapEntry(
      ToolMapEntry(
        name = "weirdTool",
        description = "Hidden, non-recordable host tool with a trailhead.",
        params = emptyList(),
        frameworkMetadata = ToolFrameworkMetadata(
          surfaceToLlm = false,
          isRecordable = false,
          requiresHost = true,
          trailheadTo = "square/checkout/library",
        ),
      ),
    )
    val tagsBlock = """
      |     * @trailblazeHiddenFromLlm
      |     * @trailblazeNotRecordable
      |     * @trailblazeHostOnly
      |     * @trailblazeTrailheadTo square/checkout/library
    """.trimMargin()
    assertTrue("expected all four tags in declaration order: $rendered") {
      rendered.contains(tagsBlock)
    }
  }

  @Test
  fun `frameworkMetadata tags coexist with sourceAttribution separated by blank line`() {
    // Render order: description → blank `     *` → Source: line → blank `     *` → tags.
    // Pin so a future change can't drop either separator and silently fuse the regions.
    val rendered = renderToolMapEntry(
      ToolMapEntry(
        name = "demo",
        description = "Demo.",
        params = emptyList(),
        sourceAttribution = "./tools/demo.ts",
        frameworkMetadata = ToolFrameworkMetadata(requiresHost = true),
      ),
    )
    val expectedBlock = """
      |     * Demo.
      |     *
      |     * Source: ./tools/demo.ts
      |     *
      |     * @trailblazeHostOnly
    """.trimMargin()
    assertTrue("expected description + source + tag layout: $rendered") {
      rendered.contains(expectedBlock)
    }
  }

  @Test
  fun `frameworkMetadata tags emit without description`() {
    // A tool with only operational metadata and no prose description still emits a valid
    // JSDoc block — the tags stand alone. Defends against a future regression where the
    // empty-description branch swallows the tag emission.
    val rendered = renderToolMapEntry(
      ToolMapEntry(
        name = "tagsOnly",
        description = null,
        params = emptyList(),
        frameworkMetadata = ToolFrameworkMetadata(requiresHost = true),
      ),
    )
    assertTrue("expected @trailblazeHostOnly even without description: $rendered") {
      rendered.contains("     * @trailblazeHostOnly")
    }
  }
}
