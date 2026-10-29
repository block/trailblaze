---
title: "runIf: The One Conditional Allowed in Trail YAML"
type: decision
date: 2026-10-13
---

# runIf: The One Conditional Allowed in Trail YAML

## Summary

Trail YAML has no logic. Branches, loops and retries belong in TypeScript tools, where they can be
named, typed and tested ([Maestro Scripting & Flow Control](2026-04-21-maestro-scripting-and-control-flow-comparison.md)).
`runIf` is the one exception: a recorded check of what is on screen, with a `then` and an optional
`else`. Its biggest job is keeping one-off conditionals from turning into one-off tools that fill
the tool catalog. This entry records why the exception exists and what stops it from growing.
Usage is documented in [Trailblaze Tools](../tools.md#branching-in-a-recorded-trail-runif).

## The problem only replay has

An AI-driven run doesn't need a conditional. The agent sees the screen: if a welcome popup is up,
it dismisses it, and if not, it moves on. A recording is a fixed list of tool calls replayed
without the LLM, though, so "dismiss the popup" replays as an unconditional tap and fails on every
run where the popup doesn't show.

Some screens vary from run to run: a one-time onboarding sheet, a permission prompt, a dialog that
loads before or after the screen behind it. No single run can tell you that. It shows up across
runs, so the conditional is added afterward by whoever edits the recording: a person, or a coding
agent reading the failed replays. For the same reason `runIf` is hidden from the run-time agent
(`surfaceToLlm: false`). The agent can't know a step is optional, and given the tool it would add
"just in case" branches that recordings then carry forever.

## The real win: no one-off tools

Without `runIf`, every guard becomes a named `.ts` tool: `dismissWelcomeSheetIfShown`,
`skipTipIfPresent`, and dozens more, each called by exactly one trail. They land in the shared tool
catalog, where authors and coding agents have to read past them to find the tools that matter, and
each needs a name, a test and an owner. A tool should earn its place by being reused. A popup that
appears in one trail hasn't earned one.

`runIf` keeps that conditional inside the one recording that needs it. The rule that logic goes in
a tool still holds: `runIf` *is* a tool, a TypeScript one shipped with the framework. The trail
grammar is unchanged. There is no `when:` or `if:` step keyword, and `runIf` appears only inside
`recording:` blocks, in the same `{ toolName: args }` shape as every other recorded call.

Recordings do nest `runIf`, a check inside another check's branch. It is ugly to read, and we
accept it: a nested `runIf` in one trail is still better than two tools that nobody else calls.

## Where it stops

Each limit below is enforced, not just documented:

- **One condition per `runIf`, nested at most two deep.** There is no `else if`, `switch`, loop or
  retry. The line is reuse, not complexity: once the same branch shows up in a second trail, it
  should become a named `.ts` tool.
- **Conditions are pass/fail.** A condition is a selector match or a tool call that succeeds. A
  tool's return value is ignored, so there are no expressions to evaluate.
- **A broken conditional fails loudly.** The whole tree is checked before anything runs, branches
  not taken included. Unknown keys, a malformed condition and nesting past two levels are all
  rejected. A condition tool that cannot run at all (an unknown name, a misspelled argument) fails
  the step instead of reading as "no".
- **No new syntax.** Being an ordinary tool, `runIf` gets type-checking and recording validation
  for free, and it can be replaced: a target that wants a different conditional can ship its own.

The checks were measured against the recordings that already existed before they shipped: none
nested deeper than two and none were malformed. So
the limits describe how conditionals were already used, and they break nothing.

## Dead ends

- **Document `runIf` as a pattern instead of shipping it.** Every team writes the same tool, each
  slightly different, and recordings stop being portable between targets.
- **A `when:` key on trail steps.** That is the Maestro model: logic in the flow, which a person
  has to read as a program, and a grammar change that every trail parser and tool would carry.
- **Treating a broken condition as false.** That was the original behavior. A typo in a condition
  silently took the `else` branch on every run and looked like a passing trail.

## Open questions

- Scripted tools that run in a separate process can't yet tell "the condition tool ran and failed"
  apart from "it couldn't run", so a misspelled condition still reads as false there. Only the
  in-process engine distinguishes the two.
- Nothing yet finds the same `runIf` branch repeated across trails, which is the signal that a
  named tool is missing. Today that depends on review.
