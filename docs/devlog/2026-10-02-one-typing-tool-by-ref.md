---
title: "One Typing Tool, Picked by Ref"
type: decision
date: 2026-10-02
---

# One Typing Tool, Picked by Ref

## Summary

The LLM now has a single typing tool, `type(text, ref?)`. It mirrors `tap`: the LLM names the field
by its snapshot ref, or leaves `ref` off to type into whatever field has focus. `type` is never
recorded. It expands into `inputText`, and when a ref was given that `inputText` carries the field's
selector. `inputText` is hidden from the LLM and stays the form recorded and hand-written trails use.
The rule underneath: **the LLM picks elements by ref; recordings hold selectors.**

## Why we needed it

`inputText` gained an optional `selector`, so a trail can tap a field and type into it in one step.
That folded recorded tap-then-type pairs into single steps. The LLM, though, never saw
that param. Selector-typed params (`TrailblazeNodeSelector`, `TrailblazeElementSelector`, and lists
of them) are stripped from every LLM tool schema by type in `TrailblazeKoogToolExt.kt`. There is no
per-property "hide from the LLM" flag; the strip is the only mechanism. So the LLM still had to type
into a field in two calls, `tap` then `inputText`, and the recording kept the unfolded pair.

Showing the LLM the selector was not an option. It cannot write a selector reliably: the right one
depends on which node in the tree uniquely identifies the field, and that is the recording
pipeline's job. What the LLM can name reliably is the ref it just read in the snapshot (`[y778]
"Email"`).

The first fix added a second tool, `inputTextOnElement(ref, text)`, beside `inputText`. It worked,
but the LLM then had two typing tools to choose between, and both descriptions went out with every
request.

## How `type` works

`type` is a `DelegatingTrailblazeTool` with `isRecordable = false`, which is the same shape as
`tap`. Its `toExecutableTrailblazeTools` returns the tools that actually run and get recorded:

- **No ref:** plain `inputText` into the focused field.
- **A ref that resolves to a selector:** `type` runs `tap`'s own ref resolution and lifts the
  selector out of the `TapOnByElementSelector` it produces. It then records one
  `inputText { selector }`. The recorded selector is exactly what a `tap` on that ref would have
  recorded, so tapping and typing can't drift apart.
- **A ref that resolves only to a coordinate tap:** `tapOnPoint` followed by plain `inputText`. This
  is what `tap` itself falls back to when no unique selector exists.

Reusing `tap`'s resolution instead of writing a second resolver keeps every fallback `tap` has
(coordinate taps, unknown-ref errors) and gives one place to fix them.

## What hiding `inputText` touched

`surfaceToLlm = false` does more than shorten the LLM's tool list:

- **Trails and scripted tools are unchanged.** A hidden class-backed tool still resolves by name, so
  a recorded `inputText` replays and `client.tools.inputText(...)` still runs.
- **`trailblaze tool inputText` is now refused.** The CLI's direct-tool path only accepts tools the
  agent can see, which is how `tapOnElementBySelector` already behaved. The CLI form is
  `trailblaze tool type text=... ref=...`.
- **Unknown-tool suggestions** only name LLM-visible tools, so a typo near `inputText` no longer
  suggests it.

`LlmToolsTakeNoSelectorsTest` holds the rule for class-backed tools: no LLM-visible tool offers a
selector param. Its companion test makes sure the check really recognizes a selector param, so the
rule can't pass without checking anything.

## Naming

We wanted one verb that sits next to `tap`. `type` is the verb web automation already uses (our
own `web_type` included). `inputText` reads better in a recorded trail, which is why it stays the
recorded name. The LLM sees the short verb; a person reading the YAML sees the descriptive one.

## Cost

On the default target, the two typing tools' descriptions cost 70 + 84 = 154 tokens on every LLM
request. `type` alone costs 116.

## Future Work

- `clearFirst` (empty the field before typing) is being added to `inputText`, and `type` will pass
  it through on all three branches.
- The same split applies to any element-picking tool: an LLM tool takes a `ref`, and the recorded
  tool takes a selector. New tools should follow it rather than expose a selector to the LLM.
