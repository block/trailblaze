# Working with AI Agents in this Repository

This file captures conventions for AI coding assistants (Claude Code, Cursor,
Aider, etc.) working in this repo. Humans are welcome to read it too.

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
