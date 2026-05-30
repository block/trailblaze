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

## CLI invocations in docs

Public-facing docs (`docs/**/*.md`, `examples/*/README.md`) refer to the CLI as
**`trailblaze`** — the binary an installed user has on their `PATH`. This is
the audience the docs are written for.

**Framework developers working out of a source checkout of this repo** should
mentally swap `trailblaze` → `./trailblaze` (the wrapper script at the OSS repo
root that rebuilds + dispatches to the source-built CLI). Don't bake `./` into
docs or example READMEs — that's a dev-loop detail, not a contract for the
audience we're writing for.

The same convention applies to commands an AI assistant suggests to the
developer in this repo: if you're walking a contributor through driving a
device from this checkout, use `./trailblaze`. If you're updating user-facing
docs, use `trailblaze`.
