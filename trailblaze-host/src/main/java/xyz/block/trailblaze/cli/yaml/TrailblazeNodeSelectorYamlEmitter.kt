package xyz.block.trailblaze.cli.yaml

import xyz.block.trailblaze.api.TrailblazeNodeSelector

/**
 * Single source of truth for hand-rolled `TrailblazeNodeSelector` YAML emission.
 *
 * Two callers want the same byte shape (so authors who run `suggest-selector` can paste
 * the result directly into a waypoint YAML, and a future `shortcut.yaml` round-trips
 * through the same loader the runtime uses):
 *
 *  - `ShortcutYamlEmitter` writes whole-file output to a `StringBuilder`.
 *  - `WaypointSuggestSelectorCommand` streams interactive output via `Console.log`.
 *
 * They previously kept private copies of the recursion + per-field `?.let { … }` ladder.
 * That drifted: `isMultiLine` landed in the shortcut emitter but not the suggest-selector
 * emitter, so any selector touching `isMultiLine` would silently drop the constraint in
 * one path but not the other. Consolidating the ladder behind a single [SelectorYamlSink]
 * removes the only place where the two could ever disagree on the field set.
 *
 * Output style (load-bearing, intentionally matches the hand-authored `*.waypoint.yaml`
 * and `*.shortcut.yaml` files in the repo):
 *  - 2-space indent.
 *  - Double-quoted scalar values with backslash + double-quote escapes only (see
 *    [yamlQuote]). `\Q…\E` literal regex blocks survive verbatim — they're valid YAML
 *    scalars and the runtime regex engine needs the backslashes intact.
 *  - Boolean state fields emit `<name>: <value>` only when the value is non-null. The
 *    generator never sets booleans to `false` (it uses `null` for "unconstrained"), so
 *    in practice this only ever emits `true` lines, but emitting raw `$it` lets a
 *    deliberately-set `false` round-trip through `ToolYamlConfig` without being dropped.
 *  - `inputType: 0` is treated as "unconstrained" (skipped) — `0` is Android's
 *    `InputType.TYPE_NULL`, which the generator emits as a default for nodes that
 *    don't expose an input type and is not a meaningful selector constraint. Every
 *    other nullable scalar follows the uniform "null = unconstrained, non-null =
 *    constraint" rule.
 *
 * Both callers are android-first; iOS / web emission is gated by
 * [requireSelectorIsEmittable] until the platform-aware versions land.
 */
object TrailblazeNodeSelectorYamlEmitter {

  /**
   * Generic sink for one YAML line of output. The shared emitter never appends partial
   * lines, so the sink is intentionally line-oriented (matches both `Console.log` and a
   * `StringBuilder.appendLine` adapter exactly without any buffering on either side).
   */
  fun interface SelectorYamlSink {
    fun line(text: String)
  }

  /**
   * Emit [selector] as YAML to [sink]. [indent] is the column the driver-match key
   * (`androidAccessibility:`) starts at; children indent by `+2` recursively.
   *
   * Fails fast via [requireSelectorIsEmittable] on selectors that carry a non-android
   * driver matcher — both callers ship android-first, and a silent skip on the
   * `iosMaestro` / `iosAxe` / `web` / `compose` / `androidMaestro` branches would
   * produce a YAML that looks fine to a human reader but matches a different element
   * (or none) at runtime.
   */
  fun emit(selector: TrailblazeNodeSelector, indent: Int, sink: SelectorYamlSink) {
    requireSelectorIsEmittable(selector)
    val pad = " ".repeat(indent)
    val childPad = " ".repeat(indent + 2)
    selector.androidAccessibility?.let { m ->
      sink.line("${pad}androidAccessibility:")
      m.classNameRegex?.let { sink.line("$childPad" + "classNameRegex: ${yamlQuote(it)}") }
      m.resourceIdRegex?.let { sink.line("$childPad" + "resourceIdRegex: ${yamlQuote(it)}") }
      m.textRegex?.let { sink.line("$childPad" + "textRegex: ${yamlQuote(it)}") }
      m.contentDescriptionRegex?.let { sink.line("$childPad" + "contentDescriptionRegex: ${yamlQuote(it)}") }
      m.hintTextRegex?.let { sink.line("$childPad" + "hintTextRegex: ${yamlQuote(it)}") }
      m.labeledByTextRegex?.let { sink.line("$childPad" + "labeledByTextRegex: ${yamlQuote(it)}") }
      m.stateDescriptionRegex?.let { sink.line("$childPad" + "stateDescriptionRegex: ${yamlQuote(it)}") }
      m.paneTitleRegex?.let { sink.line("$childPad" + "paneTitleRegex: ${yamlQuote(it)}") }
      m.roleDescriptionRegex?.let { sink.line("$childPad" + "roleDescriptionRegex: ${yamlQuote(it)}") }
      m.composeTestTagRegex?.let { sink.line("$childPad" + "composeTestTagRegex: ${yamlQuote(it)}") }
      m.uniqueId?.let { sink.line("$childPad" + "uniqueId: ${yamlQuote(it)}") }
      m.isEnabled?.let { sink.line("$childPad" + "isEnabled: $it") }
      m.isClickable?.let { sink.line("$childPad" + "isClickable: $it") }
      m.isCheckable?.let { sink.line("$childPad" + "isCheckable: $it") }
      m.isChecked?.let { sink.line("$childPad" + "isChecked: $it") }
      m.isSelected?.let { sink.line("$childPad" + "isSelected: $it") }
      m.isFocused?.let { sink.line("$childPad" + "isFocused: $it") }
      m.isEditable?.let { sink.line("$childPad" + "isEditable: $it") }
      m.isScrollable?.let { sink.line("$childPad" + "isScrollable: $it") }
      m.isPassword?.let { sink.line("$childPad" + "isPassword: $it") }
      m.isHeading?.let { sink.line("$childPad" + "isHeading: $it") }
      m.isMultiLine?.let { sink.line("$childPad" + "isMultiLine: $it") }
      m.inputType?.takeIf { it != 0 }?.let { sink.line("$childPad" + "inputType: $it") }
      m.collectionItemRowIndex?.let { sink.line("$childPad" + "collectionItemRowIndex: $it") }
      m.collectionItemColumnIndex?.let { sink.line("$childPad" + "collectionItemColumnIndex: $it") }
    }
    selector.containsChild?.let {
      sink.line("${pad}containsChild:")
      emit(it, indent + 2, sink)
    }
    selector.childOf?.let {
      sink.line("${pad}childOf:")
      emit(it, indent + 2, sink)
    }
    selector.containsDescendants?.takeIf { it.isNotEmpty() }?.let { ds ->
      sink.line("${pad}containsDescendants:")
      ds.forEach { d ->
        sink.line("$pad  -")
        emit(d, indent + 4, sink)
      }
    }
    selector.above?.let {
      sink.line("${pad}above:")
      emit(it, indent + 2, sink)
    }
    selector.below?.let {
      sink.line("${pad}below:")
      emit(it, indent + 2, sink)
    }
    selector.leftOf?.let {
      sink.line("${pad}leftOf:")
      emit(it, indent + 2, sink)
    }
    selector.rightOf?.let {
      sink.line("${pad}rightOf:")
      emit(it, indent + 2, sink)
    }
    selector.index?.let { sink.line("${pad}index: $it") }
  }

  /**
   * Fail-fast guard for selector shapes the type system allows but neither caller
   * currently synthesizes. Both callers ship `androidAccessibility`-only today; a future
   * producer (including the existing `androidMaestro` or `compose` paths if they're
   * ever piped through) that hands in a matcher this emitter doesn't know about must
   * fail loudly rather than produce a YAML that silently drops the unsupported
   * constraint. The error message names every unsupported driver — including the
   * Android-but-non-accessibility ones — to keep the failure self-describing.
   */
  private fun requireSelectorIsEmittable(selector: TrailblazeNodeSelector) {
    val unsupportedDrivers = listOfNotNull(
      selector.androidMaestro?.let { "androidMaestro" },
      selector.iosMaestro?.let { "iosMaestro" },
      selector.iosAxe?.let { "iosAxe" },
      selector.web?.let { "web" },
      selector.compose?.let { "compose" },
    )
    require(unsupportedDrivers.isEmpty()) {
      "TrailblazeNodeSelectorYamlEmitter only supports androidAccessibility-driver " +
        "matchers today; got unsupported driver(s): ${unsupportedDrivers.joinToString(", ")}. " +
        "Support for other drivers arrives when the upstream synthesizers (ShortcutProposer, " +
        "WaypointSuggestSelectorCommand) are platform-aware."
    }
  }

  /**
   * Double-quoted YAML scalar with backslash + double-quote escapes.
   *
   * `internal` so `WaypointShortcutVerifyCommand.buildTrailYaml` can route every
   * interpolated scalar through the same escape semantics the selector emitter uses —
   * keeps one code path for the "what makes a YAML scalar safe" question so a future
   * escape-rule change doesn't have to be mirrored in two places.
   */
  internal fun yamlQuote(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
