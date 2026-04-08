---
name: devlog
description: Write or update a devlog entry in the devlog directory. Use when the user asks to write a devlog, record a decision, document what happened, or says "write up what we did".
---

# Devlog

Write or update a devlog entry in the devlog directory.

## Devlog Format

Devlog entries are development journal posts that capture decisions, discoveries, and plans as work happens. They're written for the team — concise, honest, and useful for future reference.

**Filename:** `YYYY-MM-DD-<topic-slug>.md`

Use today's date and a short kebab-case topic slug.

## Entry Structure

```markdown
---
title: "Short Descriptive Title"
type: devlog
date: YYYY-MM-DD
---

# Title

## Summary
1-3 sentences on what this entry covers.

## <Sections as needed>
Use whatever sections make sense for the content. Common ones:
- What Changed
- Key Decisions (and rationale)
- What We Learned
- Open Questions
- Future Work

Keep it direct. No filler. Write like you're explaining to a teammate who will read this in 3 months.
```

## Front Matter Fields

| Field | Required | Values |
| :--- | :--- | :--- |
| `title` | Yes | Short descriptive title |
| `type` | Yes | `decision` (architectural/technical choice) or `devlog` (development note) |
| `date` | Yes | `YYYY-MM-DD` format |

Use `type: decision` when recording a significant architectural or technical choice. Use `type: devlog` for development notes, debugging sessions, and implementation details.

## Guidelines

- **Be opinionated.** Capture *why* decisions were made, not just what happened.
- **Include the dead ends.** What didn't work and why is often more valuable than what did.
- **Link to context.** Reference PRs, branches, test names, file paths — make it traceable.
- **One entry per topic.** Don't combine unrelated work. Multiple entries on the same day is fine.

## Before Writing

1. Check existing devlog entries to avoid duplicating a topic
2. If updating an existing topic, consider appending to the existing entry rather than creating a new one
3. Review the current conversation context for decisions, discoveries, and rationale worth capturing

## Invocation

When the user says `/devlog`, ask what topic to write about if it's not clear from context. If you've been working on something substantial in the current session, suggest writing about that.