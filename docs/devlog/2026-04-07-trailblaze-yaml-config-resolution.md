---
title: "Workspace Config Resolution: .trailblaze/ and trailblaze-config/ Conventions"
type: decision
date: 2026-04-07
status: partially-implemented
---

# Trailblaze Decision 036: Workspace Config Resolution

## Summary

Trailblaze config (`trailblaze.yaml`) uses two directory conventions depending on context:
- **User-level (desktop):** `~/.trailblaze/trailblaze.yaml`
- **Classpath/bundled (all platforms):** `trailblaze-config/` — required because Android's AGP strips dot-prefixed directories from assets and Java resources.

Walk-up resolution and stacking merge from multiple ancestor directories were proposed but **have not been implemented**. `LlmConfigLoader` still resolves project config from `./trailblaze.yaml` via `File(".")`.

## Context

The original `LlmConfigLoader` resolves project config from `./trailblaze.yaml` — literally `File(".")`, the process CWD. This creates problems:

1. **Subdirectory breakage** — `cd trails && trailblaze run test.yaml` silently loses all project-level LLM config because `trails/trailblaze.yaml` doesn't exist.
2. **Multi-workspace repos** — A repo with `trails-banking/` and `trails-storefront/` can't have different target apps or model defaults per directory.
3. **Bare config file** — `trailblaze.yaml` at repo root clutters the root.

### How Other CLIs Handle This

| Tool | Project config | User config | Resolution |
|------|---------------|-------------|------------|
| **Git** | `.git/config` | `~/.gitconfig` | Walk up to find `.git/` |
| **Cargo** | `.cargo/config.toml` | `~/.cargo/config.toml` | Walk up, merge all ancestors |
| **npm** | `.npmrc` | `~/.npmrc` | Walk up, merge all ancestors |
| **ESLint v9** | `eslint.config.js` | none | Walk up, first match wins |
| **Prettier** | `.prettierrc` | none | Walk up, first match wins |
| **Ruff** | `.ruff.toml` | `~/.config/ruff/ruff.toml` | Walk up, first match wins |
| **Maestro** | `.maestro/config.yaml` | none | No walk — explicit dir arg only |

### Why Not Maestro's Approach

Maestro has no LLM/auth config — it doesn't need layering. Trailblaze has shared concerns (API keys, provider auth) that should be inherited across sub-workspaces without duplication.

## Decision

### The Android constraint: `trailblaze-config/`

Android's Gradle Plugin (AGP) automatically strips dot-prefixed directories from both assets and Java resources. This means `.trailblaze/` cannot be used for classpath-bundled config that needs to work on Android.

The solution is two directory conventions (see `TrailblazeConfigPaths.kt`):

| Constant | Value | Used for |
|----------|-------|----------|
| `DOT_TRAILBLAZE_DIR` | `.trailblaze` | User-level desktop config (`~/.trailblaze/`) |
| `CONFIG_DIR` | `trailblaze-config` | Classpath-bundled resources (all platforms) |
| `PROVIDERS_DIR` | `trailblaze-config/providers` | Built-in LLM provider YAML definitions |

### What was implemented

#### Desktop resolution (`LlmConfigLoader`)

Loading order (later overrides earlier):

```
1. ~/.trailblaze/trailblaze.yaml              # personal: API keys, preferred model
     |
2. ./trailblaze.yaml                          # project-level: providers, shared config
     |
3. TRAILBLAZE_DEFAULT_MODEL env var           # CI override
     |
   Final config
```

**Note:** Project-level config is still resolved from CWD only (`File(".")`). Walk-up resolution has not been implemented.

#### Android resolution (`AndroidLlmClientResolver`)

```
1. classpath: trailblaze-config/trailblaze.yaml   # bundled project defaults
     |
2. Instrumentation arg: trailblaze.llm.default_model
     |
3. Auto-detect from available provider tokens
     |
   Final config
```

#### Built-in provider definitions

Provider YAML files are bundled at `trailblaze-config/providers/{provider_id}.yaml` on the classpath. `BuiltInLlmModelRegistry` discovers them via classpath resource loading with a core-provider fallback on Android.

### What was NOT implemented (future work)

The following were proposed in the original version of this decision but remain unimplemented:

1. **Walk-up resolution** — resolving config by walking up from CWD to filesystem root. `LlmConfigLoader` still uses `File(".")`.
2. **Stacking merge** — merging multiple `.trailblaze/trailblaze.yaml` files from ancestor directories.
3. **Project-level `.trailblaze/` directory** — project config is still a bare `trailblaze.yaml` at the project root, not `.trailblaze/trailblaze.yaml`.
4. **`trailblaze config` command** — debuggability command showing resolved config with source attribution.
5. **Migration/deprecation** — no deprecation warnings for bare `trailblaze.yaml`.

### Multi-workspace example (future)

If walk-up resolution is implemented:

```
my-repo/
├── .trailblaze/
│   └── trailblaze.yaml              # llm auth, org-wide defaults
├── trails-banking/
│   ├── .trailblaze/
│   │   └── trailblaze.yaml          # target: banking
│   └── send-money.trail.yaml
├── trails-storefront/
│   ├── .trailblaze/
│   │   └── trailblaze.yaml          # target: storefront, different model
│   └── checkout.trail.yaml
```

### Relative trail paths (future)

When trail YAML files reference other trails (e.g., a future `runTrail:` directive), paths resolve relative to the **calling file's parent directory**, not CWD. This follows Maestro's `runFlow` convention and is independent of config resolution.

### The `trailblaze-config/` directory (classpath)

```
trailblaze-config/
├── trailblaze.yaml                # bundled project defaults
└── providers/                     # built-in LLM provider definitions
    ├── anthropic.yaml
    ├── google.yaml
    ├── ollama.yaml
    ├── openrouter.yaml
    └── openai.yaml
```

## Open Questions

- **Stop sentinel**: Should walk-up stop at a marker (like `.git/`)? Cargo stops at filesystem root. Git stops at `.git/`.
- **Merge semantics for lists**: When merging `targets:` or `mcpServers:` lists, should child configs replace or append?
- **`trailblaze init`**: Should there be an `init` command that scaffolds `.trailblaze/trailblaze.yaml`?
- **Unifying the two conventions**: Can walk-up resolution use `trailblaze-config/` on-disk too, or should it stay `.trailblaze/` for user-facing directories and only use `trailblaze-config/` for classpath resources?

## Related Documents

- [030: LLM Provider Configuration](2026-02-04-llm-provider-configuration.md) — YAML schema for LLM config
- [031: App Target Configuration](2026-02-04-app-target-configuration.md) — App target schema and loading order
- Current implementation: `LlmConfigLoader.kt` (`projectDir: File? = File(".")`)
- Path constants: `TrailblazeConfigPaths.kt`
