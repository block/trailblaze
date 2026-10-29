---
title: "Retiring expectedText: Assertions Check Text Through the Selector"
type: decision
date: 2026-10-14
---

# Retiring expectedText: Assertions Check Text Through the Selector

## Summary

A recorded `assertVisible` now writes the element's exact text into its selector, so replay fails
when that text changes. That makes the optional `expectedText` field on `assertVisibleBySelector`
redundant, and it is being removed. The lesson is about API design more than about assertions:
**a core tool's fields reach every recording, trail and AI call, so a new one needs a lot of
consideration before it lands.** This one spread widely within months, and most
of the problem it was added to solve sat in the recorder.

## The gap expectedText filled

The AI asserts by pointing at an element on the current screen (`assertVisible(ref)`). The recorder
turns that ref into a selector that will find the same element on replay. The selector generator
keeps only what makes the element unique. For a button with a unique id, that is the id alone.

So "verify the button shows $5.00" recorded a selector that found the button by its id and never
looked at its text. Replay passed on $6.00. `expectedText` was added to close that gap: the AI
passed the value, the recording stored it beside the selector, and a post-pass compared the element's
text against it.

That did close the gap, but it was a second field beside the selector, and selectors already check
text.

## What it cost

Every recording, every trail and every AI call carries a core tool's fields. Within a few
months, `expectedText` was on a large share of recorded steps.

- **Most only repeated the selector.** A selector already requiring `textRegex: Library` also carried
  `expectedText: Library`. Those steps check the same thing twice.
- **The two checks read text differently.** The post-pass fell back to an empty field's hint,
  searched inside containers with no text of their own, treated all kinds of spaces alike, and had
  three match modes (EXACT, PREFIX, REGEX). Selectors do none of that. The two could disagree, and
  fixes such as multi-line text had to be made twice.
- **It hid a real bug.** The selector generator wrote number-tolerant text patterns, so "iPhone 17"
  also matched "iPhone 18". `expectedText` covered for that on steps that had it. Steps without it
  were silently loose.
- **Removing it is work.** Every step has to migrate before the field can go. That means a migration
  script, a replay of every step whose text moved, and a review round that found several ways the
  first version of the script got case, index and YAML handling wrong.

## The fix it needed

The recorder now pins the asserted element's text in the selector it already writes
(`TrailblazeNodeSelectorGenerator.withMatchedElementText`):

- **Exact text, not a tolerant pattern.** It replaces the generator's number-tolerant patterns. The
  one tolerance kept is a trailing item-count line ("Review sale / 3 items").
- **Case-sensitive on Maestro dialects.** Maestro selectors match case-insensitively, so the text is
  written with `(?-i)`.
- **The index is recounted or dropped.** A selector's `index` counts among the elements that match
  it, so adding text recounts the index, or drops it once the text alone is unique.
- **Never a secret.** No text is added from a password field, or from an element showing a value
  the trail remembered as sensitive. A recording is a file anyone can read, so those assertions
  check presence only.
- **Text from the right node.** A ref with no text of its own records the label inside it, which is
  what the AI saw it as.

No new field. Nothing for trail authors or the AI to learn.

A one-off migration script moves existing trails over: it deletes duplicates, narrows broad
patterns to the literal, folds the remaining text into `textRegex`, and lists anything it can't
express faithfully. Old trails keep working until they migrate. The field itself goes in a later
change, after a release.

**Update:** the text comparison, `textMatchMode` and the second check are gone. `expectedText` is
still read, but a step that sets it now fails with how to migrate. Trail loading ignores keys it
doesn't know, so deleting the field outright would have turned every unmigrated text check into a
presence check that quietly passes.

## Key decisions

- **The AI picks elements by ref; recordings hold selectors.** This is the rule from
  [One Typing Tool, Picked by Ref](2026-10-02-one-typing-tool-by-ref.md), applied to assertions. The
  AI can't see a replay, so it can't judge which properties make a selector hold up. The recorder
  can. The AI's assertion tools stay `assertVisible(ref)`, `assertNotVisibleWithText` and
  `assertWaypoint`. Neither the selector form nor the deprecated text-only form is shown to it.
- **The tool's purpose decides what gets pinned.** An assertion's claim is its text, so assertions
  always pin it. An action's claim is "this control", and its text often changes between runs
  (prices, counts, names), so `tap` and `type` keep preferring an id and use text only when the id
  isn't unique.
- **No `selectorPreference` parameter.** Letting the AI choose "text" or "id" for each call would be
  the same mistake again: a new field on core tools, picked by the one participant who never sees a
  replay. If a tap turns out to need its text (choosing a tip by its amount when every option has a
  positional id), the fix belongs in the generator.

## Before adding a field to a core tool

Ask whether the fields we already have can say it. Here they could: a selector checks text, and
the bug was that the recorder didn't ask it to. A second way to say the same thing will drift from
the first, and it costs every trail that picks it up.

## Open questions

- **Volatile text.** Every recorded assertion now pins its element's text, so an assertion on a
  timestamp or an order number fails on the next replay. For now the tool description steers the AI
  to assert a stable label beside the changing value instead. A presence-only opt-out is possible,
  but it would be a new field, so it needs evidence from real trails first.
- **Choosing the selector when a trail is saved.** Pinning text isn't always right. A presence-only
  selector (an id alone) survives copy changes and works in every locale from a single recording.
  Only the step's intent says which one fits, and the recorder can't see intent. One answer is a
  review when the trail is saved:
  - For each new or changed step, build the menu of selectors that resolve to the element on the
    session's captured screens (what `trailblaze waypoint suggest-selector` prints). Then ask which
    one fits the step's prompt and reason.
  - Ask the [decision engine](../configuration.md#decision-engine-experimental) first. It answers a multiple-choice question
    with a calibrated probability, so its pick applies only above a threshold.
  - Without it, ask the LLM the same question, in one call per save. The answer is limited to an
    option number, with "keep the recorder's choice" as an option.
  - If neither answers, or the answer isn't on the menu, the recorder's choice stands.
  - The save lists every step it changed and why.

  Every option already resolves to the element, so a weaker backend can only pick a less suitable
  selector, never a broken one. This would also settle the presence-only question without a tool
  field. To test it before wiring it in, score both backends offline against hand-labelled picks.
  Then change each asserted text: steps about a value must fail, and steps about presence must not.
- **Secret text from the generator.** `withMatchedElementText` never adds a password's or a
  remembered secret's text, but the selector generator can still choose that text to tell an
  element apart. That affects `tap` too, and it is tracked separately.
