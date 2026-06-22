---
title: Scripted Tools — Project Layout
---

# Scripted Tools — Project Layout & Generated Files

A trailmap project is **source you author** plus **scaffolding the framework generates**.
Short version: commit your `.yaml` manifests, your `.ts` tools, and the one generated
`.gitignore`. Everything else is regenerated automatically and already ignored for you.

> **Writing the tools themselves?** Start with
> **[Scripted Tools (TypeScript)](scripted-tools-typed-authoring.md)** — the type-safe
> authoring surface (`trailblaze.tool<I, O>(...)`, typed inputs, composing tools). This page
> just covers the files that authoring leaves in your project.

```
my-project/
├── package.json                        generated · COMMIT   install bootstrap
└── trails/
    ├── .trailblaze/                     generated · ignore   vendored SDK + tsc cache
    └── config/
        ├── trailblaze.yaml              SOURCE   · commit    workspace config
        ├── dist/                        generated · ignore   compiled manifests
        └── trailmaps/myapp/
            ├── trailmap.yaml            SOURCE   · commit    the manifest
            ├── .gitignore               generated · COMMIT   ignores the two files below
            └── tools/
                ├── myapp_login.ts           SOURCE   · commit    your tool
                ├── myapp_login.test.ts      SOURCE   · commit    your test
                ├── tsconfig.json            generated · ignore   editor type-check config
                └── trailblaze-client.d.ts   generated · ignore   this trailmap's typed tool API
```

You only ever write the `.yaml` manifests and your
[type-safe `.ts` tools](scripted-tools-typed-authoring.md). Everything marked *generated* is
written by `trailblaze check` — you never edit or maintain it.

## What to commit

- **Your source** — the `.yaml` manifests and your `.ts` tools and tests.
- **The per-trailmap `.gitignore`** — the framework writes it; committing it is what hides
  the generated files for everyone who clones (it also travels with a vendored/published
  trailmap).
- **`package.json`** (first run only) — a tiny bootstrap that regenerates typings on a fresh
  `npm`/`bun install`.

That's it. Everything else — `trailblaze-client.d.ts`, `tsconfig.json`, `.trailblaze/`,
`dist/` — is generated, including `trailblaze-client.d.ts`, *this trailmap's typed tool API*
(the bindings that make `ctx.tools.<name>(args)` autocomplete against the exact set of tools
your trailmap can dispatch). You don't commit any of it, and you don't set up the ignoring
either — `check` writes the `.gitignore` and seeds your local `.git/info/exclude`, so generated
files never clutter `git status`.

## Why aren't the typed bindings committed?

They're coupled to your installed Trailblaze version: the tool surface — and so the generated
bindings — can change with every release, so a committed copy would just go stale. That tight
coupling is also why they're generated to disk rather than shipped as a versioned npm package —
the Trailblaze you have installed is the source of truth, and it writes types that match it.

So **you need Trailblaze installed to get autocomplete** — the types come from it — and you
already do. It generates them for you, usually without you asking: the daemon runs codegen on
your first device command, `trailblaze check` does it on demand, and `npm`/`bun install`
triggers it through the bootstrap `package.json`.

## More

- [Scripted Tools (TypeScript)](scripted-tools-typed-authoring.md) — writing a tool.
- [Trailmaps](trailmaps.md) — the `trailmap.yaml` schema and tool discovery.
- [Project Layout](project_layout.md) — workspace discovery and the `trails/` anchor.
- [CLI reference](CLI.md) — the `trailblaze check` command and its flags.
