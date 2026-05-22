package xyz.block.trailblaze.cli.shortcut

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.config.ToolYamlConfig
import xyz.block.trailblaze.waypoint.WaypointLoader

/**
 * Confirms the hand-rolled emitter produces YAML that round-trips through `ToolYamlConfig`'s
 * loader. That handshake is the load-bearing contract — the generated yamls have to be
 * accepted by the same loader the runtime uses, otherwise the auto-PR lands a file the
 * framework can't read.
 */
class ShortcutYamlEmitterTest {

  @Test
  fun `emit produces YAML that round-trips through ToolYamlConfig loader`() {
    val yaml = ShortcutYamlEmitter.emit(
      shortcutId = "auto-from__to__to",
      fromWaypointId = "pack/from",
      toWaypointId = "pack/to",
      description = "test description",
      body = ShortcutProposer.ToolBody.TapOnElementBySelector(
        selector = TrailblazeNodeSelector(
          androidAccessibility = DriverNodeMatch.AndroidAccessibility(
            resourceIdRegex = "^com\\.example:id/button$",
          ),
        ),
        selectorDescription = "Resource id",
      ),
    )
    val parsed = WaypointLoader.yaml.decodeFromString(ToolYamlConfig.serializer(), yaml)
    parsed.validate()
    assertEquals("auto-from__to__to", parsed.id)
    val sc = parsed.shortcut ?: error("expected shortcut block, got null")
    assertEquals("pack/from", sc.from)
    assertEquals("pack/to", sc.to)
    assertEquals(1, parsed.toolsList?.size, "tools list should have one entry")
  }

  @Test
  fun `emit handles scroll body as a tools-list entry`() {
    val yaml = ShortcutYamlEmitter.emit(
      shortcutId = "auto-x__to__y",
      fromWaypointId = "pack/x",
      toWaypointId = "pack/y",
      description = "scroll fwd",
      body = ShortcutProposer.ToolBody.Scroll(forward = true),
    )
    val parsed = WaypointLoader.yaml.decodeFromString(ToolYamlConfig.serializer(), yaml)
    parsed.validate()
    assertTrue(yaml.contains("scroll:"))
    assertTrue(yaml.contains("forward: true"))
  }

  @Test
  fun `emit escapes double-quotes and backslashes in descriptions`() {
    val yaml = ShortcutYamlEmitter.emit(
      shortcutId = "auto-id",
      fromWaypointId = "p/a",
      toWaypointId = "p/b",
      description = """has "quotes" and \backslash""",
      body = ShortcutProposer.ToolBody.PressBack,
    )
    // Confirm loader accepts the escaped scalar.
    val parsed = WaypointLoader.yaml.decodeFromString(ToolYamlConfig.serializer(), yaml)
    assertEquals("""has "quotes" and \backslash""", parsed.description)
  }

  @Test
  fun `emit throws on selectors with non-android driver matchers`() {
    // Pin the new requireSelectorIsEmittable guard. v1 ships android-first; a future
    // iOS/web synthesizer that accidentally hands a non-android matcher to the emitter
    // must fail loudly here rather than silently dropping the constraint and shipping
    // a wrong shortcut.
    val iosSelector = TrailblazeNodeSelector(
      iosMaestro = xyz.block.trailblaze.api.DriverNodeMatch.IosMaestro(
        textRegex = "^Foo$",
      ),
    )
    val ex = kotlin.runCatching {
      ShortcutYamlEmitter.emit(
        shortcutId = "auto-x__to__y",
        fromWaypointId = "pack/x",
        toWaypointId = "pack/y",
        description = "test",
        body = ShortcutProposer.ToolBody.TapOnElementBySelector(
          selector = iosSelector,
          selectorDescription = "iOS",
        ),
      )
    }.exceptionOrNull()
    assertTrue(ex is IllegalArgumentException, "expected IllegalArgumentException; got $ex")
    assertTrue(
      ex!!.message?.contains("iosMaestro") == true,
      "exception message must name the unsupported matcher; got: ${ex.message}",
    )
  }

  @Test
  fun `emit round-trips selectors with hierarchy + spatial + boolean state through ToolYamlConfig loader`() {
    // Pin the extended field round-trip. Earlier versions only emitted a small subset
    // (resourceIdRegex/textRegex/contentDescriptionRegex/uniqueId/composeTestTagRegex/
    // isSelected/isClickable); a regression that drops any of containsDescendants,
    // above/below, or the broader boolean state set would silently strip the
    // constraint from the emitted YAML and ship a too-loose shortcut.
    val leaf = DriverNodeMatch.AndroidAccessibility(
      textRegex = "^Settings$",
      isClickable = true,
      inputType = 32,
      collectionItemRowIndex = 2,
    )
    val selector = TrailblazeNodeSelector(
      androidAccessibility = leaf,
      containsDescendants = listOf(
        TrailblazeNodeSelector(
          androidAccessibility = DriverNodeMatch.AndroidAccessibility(textRegex = "^child-a$"),
        ),
        TrailblazeNodeSelector(
          androidAccessibility = DriverNodeMatch.AndroidAccessibility(textRegex = "^child-b$"),
        ),
      ),
      above = TrailblazeNodeSelector(
        androidAccessibility = DriverNodeMatch.AndroidAccessibility(textRegex = "^above-anchor$"),
      ),
    )
    val yaml = ShortcutYamlEmitter.emit(
      shortcutId = "auto-x__to__y",
      fromWaypointId = "pack/x",
      toWaypointId = "pack/y",
      description = "round-trip",
      body = ShortcutProposer.ToolBody.TapOnElementBySelector(selector = selector, selectorDescription = "Text"),
    )
    val parsed = WaypointLoader.yaml.decodeFromString(ToolYamlConfig.serializer(), yaml)
    parsed.validate()
    // Confirm key shape survives via raw text checks — round-tripping the JsonObject
    // tools list back into a TrailblazeNodeSelector requires the full agent runtime,
    // which is out of scope for this unit test. Raw text assertions are sufficient to
    // pin the contract: every field name must appear in the output.
    assertTrue(yaml.contains("textRegex: \"^Settings\$\""), "leaf textRegex present")
    assertTrue(yaml.contains("isClickable: true"), "isClickable boolean present")
    assertTrue(yaml.contains("inputType: 32"), "inputType present")
    assertTrue(yaml.contains("collectionItemRowIndex: 2"), "collection row index present")
    assertTrue(yaml.contains("containsDescendants:"), "containsDescendants block present")
    assertTrue(yaml.contains("child-a"), "first descendant present")
    assertTrue(yaml.contains("child-b"), "second descendant present")
    assertTrue(yaml.contains("above:"), "above block present")
    assertTrue(yaml.contains("above-anchor"), "above anchor selector present")
  }

  // `yamlQuote` round-trip coverage moved to `TrailblazeNodeSelectorYamlEmitterTest` —
  // the shared emitter owns the quote helper now and `ShortcutYamlEmitter` calls
  // through to it. Keeping a duplicate here would just shadow the canonical test.

  @Test
  fun `emit covers every DriverNodeMatch AndroidAccessibility field (no silent drops)`() {
    // Insurance against the "new field added to AndroidAccessibility but the emitter
    // wasn't updated" failure mode. Construct a maximal AndroidAccessibility with every
    // non-default field set; the emitted YAML must contain each field's name. Without
    // this, a future field addition (or a regression deleting a `m.X?.let { … }` line
    // from emitSelectorBody) silently drops the constraint from every generated
    // shortcut.
    //
    // The companion `canonicalSelector covers every TrailblazeNodeSelector field` test
    // in ShortcutProposerTest pins fingerprint coverage; this pins emitter coverage.
    val maximal = DriverNodeMatch.AndroidAccessibility(
      classNameRegex = "^cls$",
      resourceIdRegex = "^rid$",
      uniqueId = "uid-7",
      composeTestTagRegex = "^tag$",
      textRegex = "^txt$",
      contentDescriptionRegex = "^desc$",
      hintTextRegex = "^hint$",
      labeledByTextRegex = "^lbl$",
      stateDescriptionRegex = "^state$",
      paneTitleRegex = "^pane$",
      roleDescriptionRegex = "^role$",
      isEnabled = true,
      isClickable = true,
      isCheckable = false,
      isChecked = true,
      isSelected = false,
      isFocused = true,
      isEditable = false,
      isScrollable = true,
      isPassword = false,
      isHeading = true,
      isMultiLine = false,
      inputType = 33,
      collectionItemRowIndex = 4,
      collectionItemColumnIndex = 9,
    )
    val yaml = ShortcutYamlEmitter.emit(
      shortcutId = "auto-x__to__y",
      fromWaypointId = "pack/x",
      toWaypointId = "pack/y",
      description = "field coverage",
      body = ShortcutProposer.ToolBody.TapOnElementBySelector(
        selector = TrailblazeNodeSelector(androidAccessibility = maximal),
        selectorDescription = "max",
      ),
    )
    // Required: every field name appears in the emitted YAML. Pair these to the
    // matchable property list so removing any `m.X?.let { … }` from the emitter
    // surfaces here. Doesn't pin the *value* — that's covered by the round-trip
    // tests above — only the *presence* of each field name on its own line.
    val expectedFieldNames = listOf(
      "classNameRegex",
      "resourceIdRegex",
      "uniqueId",
      "composeTestTagRegex",
      "textRegex",
      "contentDescriptionRegex",
      "hintTextRegex",
      "labeledByTextRegex",
      "stateDescriptionRegex",
      "paneTitleRegex",
      "roleDescriptionRegex",
      "isEnabled",
      "isClickable",
      "isCheckable",
      "isChecked",
      "isSelected",
      "isFocused",
      "isEditable",
      "isScrollable",
      "isPassword",
      "isHeading",
      "isMultiLine",
      "inputType",
      "collectionItemRowIndex",
      "collectionItemColumnIndex",
    )
    for (name in expectedFieldNames) {
      assertTrue(
        yaml.contains("$name:"),
        "emitter dropped DriverNodeMatch.AndroidAccessibility.$name from output. yaml=\n$yaml",
      )
    }
    // Loader must still accept it — emitting every field shouldn't break round-tripping.
    val parsed = WaypointLoader.yaml.decodeFromString(ToolYamlConfig.serializer(), yaml)
    parsed.validate()
  }

  @Test
  fun `emit always places tools block last (extractToolsBlock contract)`() {
    // Pins the contract WaypointShortcutVerifyCommand.extractToolsBlock depends on:
    // the emitter must emit `tools:` as the last top-level block (everything that
    // follows is the tools list itself, indented). Without this, a future stylistic
    // reordering that emitted `parameters:` after `tools:` would cause
    // extractToolsBlock to slice junk into the generated trail and replay would
    // break with a cryptic kaml diagnostic at run-time.
    val yaml = ShortcutYamlEmitter.emit(
      shortcutId = "auto-x__to__y",
      fromWaypointId = "pack/x",
      toWaypointId = "pack/y",
      description = "tools-last",
      body = ShortcutProposer.ToolBody.PressBack,
    )
    val lines = yaml.lines().filter { it.isNotBlank() }
    val toolsLineIdx = lines.indexOfFirst { it.trimEnd() == "tools:" }
    assertTrue(toolsLineIdx >= 0, "expected a top-level `tools:` line; got:\n$yaml")
    // Every non-blank line after the `tools:` header must be indented (i.e. part of
    // the tools list body). If a top-level non-indented line appears after `tools:`,
    // the contract is broken.
    for (i in (toolsLineIdx + 1) until lines.size) {
      val line = lines[i]
      assertTrue(
        line.startsWith(" ") || line.startsWith("\t"),
        "non-indented line after `tools:` would break extractToolsBlock — got `$line`. Full yaml:\n$yaml",
      )
    }
  }
}
