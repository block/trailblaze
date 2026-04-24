---
title: "YAML-Defined Tools (the `tools:` mode)"
type: decision
date: 2026-04-20
---

# Trailblaze Decision 037: YAML-Defined Tools (the `tools:` mode)

## Context

Trailblaze tool definitions in `trailblaze-config/tools/*.yaml` currently support exactly
one authoring mode: **Kotlin class reference**.

```yaml
id: compose_click
class: xyz.block.trailblaze.compose.driver.tools.ComposeClickTool
```

`ToolYamlConfig` (`trailblaze-models/src/commonMain/kotlin/xyz/block/trailblaze/config/ToolYamlConfig.kt`)
parses this into an `id` + FQCN. `ToolYamlLoader`
(`trailblaze-common/src/jvmAndAndroid/kotlin/xyz/block/trailblaze/config/ToolYamlLoader.kt`)
discovers every `trailblaze-config/tools/*.yaml` on the classpath, reflects the FQCN via
`Class.forName`, and returns a `Map<ToolName, KClass<out TrailblazeTool>>`. LLM descriptors
(`TrailblazeToolDescriptor`) are then generated reflectively from each Kotlin class by
`KClass.toKoogToolDescriptor()`
(`trailblaze-models/src/jvmAndAndroid/kotlin/xyz/block/trailblaze/toolcalls/TrailblazeKoogToolExt.kt`):
`@TrailblazeToolClass(name = …)` → tool name, `@LLMDescription` on the class → tool
description, data class primary constructor → parameters (with per-param `@LLMDescription`).

### The problem

A significant fraction of existing Kotlin tools exist solely to wrap a single Maestro
command with a typed data class and a description. Two representative examples:

- `EraseTextTrailblazeTool`
  (`trailblaze-common/src/jvmAndAndroid/kotlin/xyz/block/trailblaze/toolcalls/commands/EraseTextTrailblazeTool.kt`)
  — extends `MapsToMaestroCommands`, its only logic is `listOf(EraseTextCommand(charactersToErase))`.
- `TapOnElementWithTextTrailblazeTool`
  (`trailblaze-common/src/jvmAndAndroid/kotlin/xyz/block/trailblaze/toolcalls/commands/TapOnElementWithTextTrailblazeTool.kt`)
  — a bit more work: it regex-escapes the text and wraps it in `.*…*` for substring match.

These tools add compile-time friction — a new Kotlin class plus a YAML registration plus a
rebuild — for what is, in the simple case, just "emit these Maestro commands with these
params." Test engineers without Kotlin expertise are blocked on this pattern today.

### `MaestroTrailblazeTool` already accepts inline commands

`MaestroTrailblazeTool`
(`trailblaze-common/src/jvmAndAndroid/kotlin/xyz/block/trailblaze/toolcalls/commands/MaestroTrailblazeTool.kt`)
already holds an inline list of Maestro commands as `List<JsonObject>` and executes them
opaquely. In trail YAML this looks like:

```yaml
- tools:
    - maestro:
        commands:
          - eraseText:
              charactersToErase: 5
```

So the YAML syntax for inline tool lists is already in use by trail files. We can reuse the
same parsing path for tool definitions.

### `DelegatingTrailblazeTool` is already the right abstraction

`DelegatingTrailblazeTool`
(`trailblaze-common/src/jvmAndAndroid/kotlin/xyz/block/trailblaze/toolcalls/DelegatingTrailblazeTool.kt`)
is already the established pattern for a tool that expands into a list of
`ExecutableTrailblazeTool` at runtime, given an execution context. A YAML-defined tool is
exactly this: context in, tool list out.

### Connection to Decision 025

Decision 025 (Scripted Tools Vision) laid out the long-term vision of TypeScript/QuickJS
scripted tools for **conditional** logic. That's a future mode 3 (`script:`). This decision
adds mode 2 (`tools:`) — the **static composition** case, where the tool's expansion is a
fixed list of inlined tool calls with param substitution, no conditionals needed. Mode 2
and mode 3 share the same YAML schema backbone for declaring tool identity, description,
and parameters.

## Decision

### Extend `ToolYamlConfig` to support three authoring modes

Exactly one of `class:` / `tools:` / `script:` must be present in each tool YAML file.

- **`class:`** (existing) — Kotlin-backed. Description and parameters come from reflection
  on the referenced class. `description` and `parameters` MUST NOT appear in the YAML.
- **`tools:`** (new, this decision) — inline list of Trailblaze tool calls using the same
  syntax as trail YAML steps. `description` and `parameters` are REQUIRED in the YAML since
  there is no Kotlin data class to reflect.
- **`script:`** (future, Decision 025) — compiled JavaScript source reference. `description`
  and `parameters` REQUIRED in the YAML, same rationale.

### Schema

```yaml
id: <toolName>                    # required, all modes (must follow tool naming convention)
description: |                    # required for tools:/script:, forbidden for class:
  Multi-line LLM description.
parameters:                       # required for tools:/script:, forbidden for class:
  - name: <paramName>
    type: string | integer | boolean | number
    required: true | false
    default: <value>              # optional
    description: <LLM param description>

# exactly one of:
class: <FQCN>                     # existing mode
tools: [ ... ]                    # new mode (this decision)
script: { source: "..." }         # future mode
```

### Example migration: `eraseText`

A clean fit. The Kotlin class exists only to wrap a single Maestro `eraseText` command with
a typed optional param.

```yaml
id: eraseText
description: |
  Erases characters from the currently focused text field.
  - If charactersToErase is omitted or null, ALL text in the field is erased.
  - If a number is provided, that many characters are erased from the end.
  - Use this BEFORE inputText when you need to replace existing text in a field
    (e.g. a search bar or form field that already has content).
parameters:
  - name: charactersToErase
    type: integer
    required: false
    description: "Number of characters to erase from the end (omit to erase all)"
tools:
  - maestro:
      commands:
        - eraseText:
            charactersToErase: "{{charactersToErase}}"
```

### Example migration: `tapOnElementWithText`

A messier fit that exposes the boundary of mode 2.

```yaml
id: tapOnElementWithText
description: |
  Tap on an element containing the provided text (substring match). The text does not need
  to be an exact match — it will find elements where the provided text appears anywhere
  within the element's text.
parameters:
  - name: text
    type: string
    required: true
    description: "Required text (must be a meaningful substring visible on screen)"
  - name: index
    type: integer
    required: false
    default: 0
    description: "0-based index among matching elements"
  # id, enabled, selected omitted here for brevity
tools:
  - maestro:
      commands:
        - tapOn:
            text: "{{text}}"
            index: "{{index}}"
```

This version loses the Kotlin implementation's `".*${Regex.escape(text)}.*"` wrapping. That
regex-escape-then-anchor step is real pre-processing that can't be expressed in static YAML.
Options going forward:

- Accept the behavior change (Maestro's `tapOn.text` is already regex-based, so any
  special characters in `text` would behave differently).
- Keep the Kotlin class for this tool and only migrate the clean cases.
- Wait for `script:` mode (Decision 025) where a short JS snippet can do the escaping.

**Principle:** tools with non-trivial pre-processing (regex escaping, conditional branching,
memory mutation, HTTP calls) stay in Kotlin. `tools:` mode is for the straight-line
"unpack params, emit tool calls" case.

### Runtime design

1. **`ToolYamlConfig` gains optional fields** for `description`,
   `parameters: List<TrailblazeToolParameterConfig>`, `tools: List<JsonObject>` (mirroring
   the `MaestroTrailblazeTool.commands` shape), and `script` (placeholder). Load-time
   validation enforces the one-of rule and mode-specific required-field rules.

2. **New `TrailblazeToolParameterConfig`** — serializable model: `name`, `type`, `required`,
   `default`, `description`. Types enumerated to the same primitive set the existing
   `KType.asToolType()` handles.

3. **New `YamlDefinedTrailblazeTool`** — implements `DelegatingTrailblazeTool`. Carries a
   reference to its `ToolYamlConfig` plus the caller-supplied param values. Its
   `toExecutableTrailblazeTools(ctx)` walks the `tools:` JSON tree, substitutes
   `{{paramName}}` tokens with the caller-supplied values, and returns the deserialized list
   of executable tools via the same path trail YAML steps use.

4. **`ToolYamlLoader` return type changes** from
   `Map<ToolName, KClass<out TrailblazeTool>>` to `Map<ToolName, ToolDefinition>`, where
   `ToolDefinition` is a sealed interface:

   - `ClassBacked(kClass: KClass<out TrailblazeTool>)`
   - `InlineTools(config: ToolYamlConfig)`
   - `Scripted(config: ToolYamlConfig)` (later)

   All downstream consumers — `ToolNameResolver`, `TrailblazeSerializationInitializer`,
   descriptor generation — branch on this sealed type.

5. **LLM descriptor generation branches on mode.** For `ClassBacked`,
   `KClass.toKoogToolDescriptor()` stays unchanged. For `InlineTools`, a new function builds
   `TrailblazeToolDescriptor` directly from `ToolYamlConfig.description` +
   `ToolYamlConfig.parameters` — no reflection involved.

6. **Polymorphic JSON serialization.** Today the `class` discriminator resolves to a
   specific Kotlin class. For YAML-defined tools, many distinct tool names all map to the
   same `YamlDefinedTrailblazeTool` Kotlin class. The fix: register each YAML tool name
   in the polymorphic table with a custom serializer that reads the tool name from the
   incoming JSON, looks up the matching `ToolYamlConfig`, and constructs a
   `YamlDefinedTrailblazeTool` with the right config + params. On a receiving JVM that
   does **not** have the YAML file loaded (e.g. a log server), deserialization falls through
   to the existing `OtherTrailblazeTool` path
   (`trailblaze-models/src/commonMain/kotlin/xyz/block/trailblaze/logs/client/temp/OtherTrailblazeTool.kt`)
   — the tool name and raw params are preserved even without the YAML. This is a critical
   invariant: **YAML-defined tools must deserialize on a JVM that has never seen the YAML,
   without crashing.**

7. **Interpolation.** Walk the parsed `tools:` JSON tree and substitute `{{paramName}}`
   tokens inside string values using the caller-supplied param values. Memory variables
   (`${var}` and `{{memVar}}`) continue to resolve later during downstream tool execution
   via `AgentMemory.interpolateVariables()`
   (`trailblaze-models/src/commonMain/kotlin/xyz/block/trailblaze/AgentMemory.kt`).

   Rules for v1:

   - When the `{{paramName}}` token is the **entire value** of a YAML key, the substitution
     produces a correctly-typed JSON primitive (int → `JsonPrimitive(Int)`, boolean →
     `JsonPrimitive(Boolean)`, etc.).
   - When the token is **embedded** in a larger string, the param's string representation
     is spliced in.
   - Optional params that are omitted resolve to JSON `null` (no key-drop magic in v1).

### Work items

1. Extend `ToolYamlConfig` with `description`, `parameters`, `tools`, `script` fields plus
   one-of validation.
2. Add `TrailblazeToolParameterConfig` serializable model.
3. Add `YamlDefinedTrailblazeTool` implementing `DelegatingTrailblazeTool`, with JSON-tree
   `{{paramName}}` interpolation.
4. Migrate `ToolYamlLoader` to return a sealed `ToolDefinition`.
5. Update `ToolNameResolver` and `TrailblazeSerializationInitializer` to route by
   `ToolDefinition` variant.
6. Branch LLM descriptor generation on mode; add a YAML-driven descriptor builder.
7. Register polymorphic serializer entries for YAML-defined tool names; verify the
   `OtherTrailblazeTool` fallback still catches unknown names on unseeded JVMs.
8. Migrate `eraseText.yaml` and `tapOnElementWithText.yaml` as the first two examples.
   Keep the original Kotlin classes `@Deprecated`-marked for one release cycle rather than
   deleted, so external consumers have a migration window.
9. Tests:
   - YAML → `TrailblazeToolDescriptor` round-trip (description + params come through).
   - Param interpolation: typed whole-value tokens, embedded-in-string tokens, omitted
     optional params.
   - JSON log serialization round-trip on a JVM that has the YAML.
   - Fallback to `OtherTrailblazeTool` on a JVM that does **not** have the YAML.

### Open questions

- **Null vs key-drop for optional params.** v1 picks JSON `null`. Do we need a `{{?param}}`
  syntax to drop the key entirely? This matters for Maestro commands that distinguish
  "absent" from "null."
- **Typed interpolation.** Is the whole-value-token rule enough, or do we need explicit
  coercion like `{{int:param}}` / `{{bool:param}}`?
- **Scope of memory visibility inside a `tools:` expansion.** Do declared params shadow
  memory vars of the same name? Proposal: yes — caller-supplied params win on conflict.
- **Recording.** Should `YamlDefinedTrailblazeTool` itself be recordable, or only the
  expanded tool calls (matching the recording model Decision 025 proposes for `script:`)?
  Proposal: record the top-level YAML tool call so trails remain readable, **and** the
  expanded tool calls underneath — mirroring how delegating tools log today.
- **YAML versioning.** If a `tools:`-defined YAML changes after a recording was taken,
  replays may diverge. Hashing or versioning the YAML is out of scope for v1 — flag as a
  follow-up.

## Consequences

**Positive:**

- Tools that are just "call these N tools with these params" no longer need Kotlin code or
  a rebuild cycle.
- Schema backbone is shared with `script:` mode (Decision 025), so we're not painting
  ourselves into a corner before that lands.
- Existing `class:` mode is untouched — anything non-trivial (conditional logic, HTTP,
  string pre-processing, memory mutation) stays Kotlin.
- Clean migration target for the long tail of "single-Maestro-command wrapper" Kotlin
  tools.

**Negative:**

- Two (soon three) tool definition modes increases cognitive load for contributors. We'll
  need a short docs section on "which mode do I use?"
- Interpolation semantics for nullable/typed params are a real design surface and a
  likely source of bugs; v1 rules are intentionally conservative.
- LLM descriptor generation now has two code paths (reflection vs YAML-driven) that must
  stay semantically aligned — add tests that verify a migrated tool's descriptor matches
  the reflected descriptor of its previous Kotlin implementation.
- Server/log-viewer code paths grow a new failure mode: YAML-defined tools that the viewer
  JVM hasn't been shipped with. The `OtherTrailblazeTool` fallback covers it, but the
  failure mode must be tested explicitly.

## Related Decisions

- Decision 005: Tool Naming Conventions — YAML-defined tools must follow the same
  `<namespace>_<action>` naming, validated at load time.
- Decision 009: Kotlin as Primary Language — YAML tools are an additive layer, not a
  replacement; complex logic stays Kotlin.
- Decision 025: Scripted Tools Vision (TypeScript/QuickJS) — this decision is the static
  composition stepping stone before the TS/QuickJS mode lands.
- Decision 029: Custom Tool Architecture — extends the custom tool surface introduced
  there with a no-Kotlin authoring path.
