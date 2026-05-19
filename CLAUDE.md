# Working with AI Agents in this Repository

This file captures conventions for AI coding assistants (Claude Code, Cursor,
Aider, etc.) working in this repo. Humans are welcome to read it too.

## Commit attribution

Trailblaze attributes commits to the **human developer** who owns the change,
not to the AI assistant that helped author it. Please do not add
`Co-Authored-By:` trailers naming Claude, Cursor, GPT, or any other AI agent
to commit messages. The developer's name on the `Author:` line is the record
of authorship the project relies on for code review, blame, and release notes.

If you use an AI assistant while preparing a change, that's expected and
encouraged — just keep the assistant out of the commit metadata. The same
applies to PR descriptions: prose generated with AI help is fine, but don't
sign them on the assistant's behalf or include `🤖 Generated with …` footers.

If you would like to acknowledge AI help on a particular change, do it in the
PR description body in the developer's own words rather than as a structured
trailer that downstream tooling will treat as a real author.
