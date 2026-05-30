---
name: trailblaze-validate-oob
description: |
  Use when validating or evaluating Trailblaze's out-of-box (OOB)
  user experience — does what the `trailblaze` skill claims actually
  match the real installed CLI? Triggers on requests to "validate the
  Trailblaze skill", "test the Trailblaze OOB experience", "evaluate
  Trailblaze UX", "check if the skill matches the CLI", "audit the
  Trailblaze CLI for new-user friction", or running an OOB regression
  check after the framework changes.
---

# Validating Trailblaze's out-of-box experience

This skill encodes the methodology for honestly evaluating whether
Trailblaze's user-facing CLI matches what a fresh agent / new user
would intuitively expect. It's the framework's own UX regression
test, run by an agent simulating cold-start use.

The goal is to catch counter-intuitive defaults, broken claims in the
`trailblaze` skill, and friction that the team has gone CLI-blind on.

## When to run a validation pass

- After any change to the `trailblaze` CLI surface (commands, flags,
  default behavior)
- After any change to the `trailblaze` Claude skill
  (the sibling `trailblaze/SKILL.md`) or its references
- Periodically (monthly?) even with no changes, to catch drift
- When a new rung of the adoption ladder gets added to the skill

## What "good" looks like

After a validation pass, **a fresh agent following only the
`trailblaze` skill should be able to complete the scenario with zero
surprises**. Specifically:

- Every command the skill says to run actually exists with the
  documented flags and argument shape
- Every output the skill describes matches what the CLI actually
  prints
- The basic loop (drive → save → replay → inspect) works end-to-end
  with no undocumented gotchas
- Failure modes the skill mentions are real failure modes; the
  recovery steps actually recover
- Errors the user might hit have clear `hint:` lines pointing at the
  fix
- No "workaround" paragraphs in the skill that exist to paper over
  CLI behavior the user wouldn't naturally guess

If the validation surfaces something the agent couldn't have guessed
from the skill alone, that's either a skill update OR a CLI fix —
and the latter is usually preferable.

## Methodology

### Step 1: Install the binary fresh

Use the actual end-user install path, NOT `./trailblaze` from the
repo (which goes through Gradle on every call and isn't what users
hit):

```bash
./scripts/install-trailblaze-source.sh
```

The script builds the uber JAR and installs it under
`~/.trailblaze/install/` with a symlink onto the system PATH. The
specific symlink location depends on your platform (homebrew on
macOS, `/usr/local/bin/` on Intel macs / Linux, …) — defer to the
script's stdout for the exact path rather than memorizing one. Then
verify:

```bash
which trailblaze         # confirms which binary the shell will pick up
trailblaze --version     # records the version under test (capture this in the report)
```

If the script fails (missing JDK, Gradle error, symlink-permission
failure), **that's itself a finding**: file it as an "OOB blocker"
and spawn a CLI fix chip *before* trying to run the validation —
don't shim around it from inside the skill.

### Step 2: Spawn a fresh-context subagent

The subagent must have **no prior Trailblaze knowledge**. Their only
source of truth is the `trailblaze` skill file. They must not infer
commands from the repo's source code or from any other docs.

The right tool for this is a general-purpose agent (read-only is
sufficient since the validation only runs `--help` and discovery
commands, never destructive actions).

If the subagent can't proceed at this step — no device connected,
target app not installed, network or auth required, etc. — that's a
gap too: an OOB experience that requires undocumented setup is itself
a finding. Record what setup was needed and what error the subagent
hit, file it the same way as an OOB blocker, and resume the
validation once the setup gap is closed.

### Step 3: Give the subagent a concrete scenario

A scenario must:

- Be a realistic user task ("drive an Android emulator", "save a
  flow as a trail", "replay a trail and inspect what happened")
- Be doable using ONLY commands the skill describes
- Have a clear "I would now act on a device" stopping point — the
  validation is **read-only**; don't actually drive devices or
  modify state

Scenarios that have worked well:

- **Rung 1**: "Drive a device. List devices, snapshot one, look at
  the toolbox to find available actions, identify how you'd act on
  an element. Stop before actually executing the action."
- **Rung 2**: "Save the current session as a `.trail.yaml`. Then
  replay one. Then generate a report. Then look up past results."
- **Rung 3**: (when written) "Compose your own agent surface — write
  a custom typed tool, install a trailmap, curate what tools the
  agent sees."

### Step 4: Subagent prompt template

The validation prompt should always:

1. Tell the subagent it's a Claude Code session that just loaded the
   skill — no prior Trailblaze knowledge
2. Specify the **installed `trailblaze` binary on PATH** as the test
   surface (NOT `./trailblaze`)
3. Give the concrete scenario
4. Instruct them to follow ONLY what the skill says; gaps are gaps
5. Require structured output: **Inaccuracies**, **Gaps**,
   **Ambiguities**, **What works**, **Suggestions**
6. Cite quoted skill text and actual CLI output side-by-side for
   each finding

### Step 5: Triage findings

For each finding the subagent reports:

| Finding type | Right action |
|---|---|
| Skill claims X; CLI does Y | Update the skill OR spawn a CLI fix chip. Lean toward CLI fix if X is the more intuitive design. |
| Skill is silent; agent had to guess | Add to the skill. Note explicitly if it's a workaround vs intentional behavior. |
| Skill is ambiguous; agent picked one interpretation | Tighten the skill text. |
| Skill claim worked exactly as described | Keep — note what worked so it doesn't accidentally regress. |

**Bias toward fixing the CLI**, not the skill. If a fresh agent
guessed differently than the current CLI, the agent is usually
modeling the intuitive design — and the CLI is the thing diverging
from intuition.

### Step 6: Run a second pass after fixes

After applying fixes (either in the skill or via spawned CLI chips),
**run the validation again** with a different fresh-context
subagent. Two passes catch issues the first pass's fixes
introduced and confirm the fixes hold against another cold reader.

Don't reuse the same subagent — context contamination defeats the
"fresh new user" model.

## What to NOT do

- **Don't run validation against `./trailblaze`** — that's the
  Gradle-wrapped dev launcher, not the end-user binary. Findings
  there ("first call triggers a Gradle build", "needs
  `local.properties`") are dev-environment concerns, not OOB UX
  problems.
- **Don't pre-bias the subagent** with answers. Don't say "verify
  that `tap` is the tool name" — let them discover it from the skill
  and the CLI.
- **Don't have the subagent execute destructive actions.** No
  driving devices, no modifying state, no committing files. Read-only
  validation only — `--help`, `device list`, `snapshot`, etc.
- **Don't ignore a subagent finding because "the current CLI design
  is what it is".** That's how counter-intuitive designs calcify.

## What a healthy validation report looks like

A useful report has all of:

- **Inaccuracies** with quoted skill text + actual CLI output for
  each
- **Verified claims** — explicitly list what held up, so the team
  knows what's safe to keep
- **Gaps** the subagent had to guess past
- **Suggestions** — concrete edits to skill OR concrete CLI fixes
- Cap around 500 words; brevity forces precision

If the report is just "looks fine to me," the methodology wasn't
adversarial enough — re-prompt with a more pointed scenario.

## After validation: distribution

Findings flow three ways — the first one is non-optional, the other
two depend on what surfaced:

1. **Durable record of the pass itself.** Before spawning any chips
   or editing any skill, post a short summary to a persistent surface
   — a GitHub issue on the project's issue tracker (preferred), or
   the relevant team chat thread, or a tracked markdown file. Include:
   date, the rung exercised, the `trailblaze --version` you captured
   in Step 1, a one-line outcome ("clean", "N findings, M chipped"),
   and links to any chips or follow-up PRs. The chat transcript of
   the validation is *not* a durable artifact — without this, "did
   we last validate after the X CLI change?" is unanswerable.
2. **Skill updates** — edit the sibling `trailblaze/SKILL.md`
   directly, run the sensitive-terms scanner, commit, PR.
3. **CLI fix chips** — spawn a focused chip for the framework
   behavior change, with the validation finding as motivation in
   the chip's prompt.

Skill updates and CLI chips should reference the validation pass
that surfaced the finding (the issue/Slack URL from #1), so future
contributors can trace why a change was made.
