---
title: "Trail Metadata Takes Any YAML Shape"
type: decision
date: 2026-09-23
---

# Trail Metadata Takes Any YAML Shape

## Summary

`config.metadata:` values used to be strings only. They can now be a string, a list, or a map,
nested to any depth. This is a deliberate breaking change: older Trailblaze versions can't parse a
trail or results file whose metadata holds a list or a map.

```yaml
config:
  metadata:
    jira: PROJ-123
    owners: [payments, checkout]
    tracker:
      ticket: PROJ-123
      suites: [smoke, nightly]
```

## Key Decisions

**First-class type, not a side channel.** We first tried a separate `metadataLists` field next to
the string map, which kept binary compatibility. That meant every reader had to check two places,
and it made lists second-class. Since a breaking change was acceptable, metadata is now
`Map<String, TrailMetadataValue>` (a sealed type: `StringValue`, `ListValue`, `MapValue`), and all
~30 call sites were migrated. Callers read it with `metadata.string(key)`, `.list(key)`, and
`.map(key)`. Use `leaves` to search a value of any shape and `displayText` to show one.

**Go all the way to arbitrary nesting now.** Lists alone would have solved the immediate need.
Metadata is mostly informational, so the point is to let each team shape it however they like. Doing lists now and maps later would have meant a second breaking
change for the same field.

**Leaves stay strings.** `ticket: 1017` still reads as `"1017"`, exactly as the old string map did.
YAML scalar typing (numbers, booleans, `yes`/`no`) is a trap, and we don't want a reader to have to
guess which type it got back. JSON numbers and booleans in a session log read back as their text
too.

**Null is the only rejection.** An empty value (`owner:`) or `~` anywhere fails with its path, e.g.
`owner.team`. That's almost always an authoring mistake, and there's no string to read it as.

**Keys with a meaning read a string, and never coerce another shape.** A few keys are more than
informational. The reserved `source` / `sourceReason` bridge keys lower into `TrailConfig.source`,
the report uses `owner` as the run's subtitle and Owner sort, and integrations such as CI tooling
can read their own keys to change behavior. Every such reader uses `metadata.string(key)`. A list
or map under one of these keys either errors or stays plain metadata. It is never coerced, so a
one-item list `[false]` can't pass for `false`.

**A list under the reserved `source` key is not a source bridge.** Only a string value under a
reserved bridge key lowers into `TrailConfig.source`. Anything else under that key stays as the
author's own metadata.

## How It Works

- `TrailMetadataValueSerializer` uses a `SerialKind.CONTEXTUAL` descriptor, so kaml hands over the
  raw node whatever its shape. With any other kind, kaml refuses a scalar, list, or map before the
  serializer sees it. The JSON path reads `decodeJsonElement()`, so session logs written before this
  change still decode.
- On the wire and in reports, lists are JSON arrays and maps are JSON objects.
- The DTO TypeScript codegen can't see the shape of a CONTEXTUAL descriptor (it would emit
  `unknown`). It now has a small `customWireShapes` table that emits a named, recursive alias:
  `string | TrailMetadataValue[] | { [key: string]: TrailMetadataValue }`.
- The trail YAML JSON Schema accepts any non-null value. The report's Info tab renders nested values
  as `ticket: PROJ-123, suites: [smoke, nightly]`.

## Gotchas

- **Precompiled JVM callers can link and still fail at runtime.** Type erasure keeps the `metadata`
  property ABI as `Map` even though its values changed from `String` to `TrailMetadataValue`. A
  caller compiled against the old type can get a `ClassCastException` when it reads a value as a
  `String` or serializes a config it constructed with string values. Recompile consumers against
  the new models when updating their Trailblaze pin.
- **Version skew.** A consumer pinned to an older Trailblaze fails to parse list or map metadata.
  Keep trails that older pins read string-only until those pins move to a version with this change.
- **Test comparisons still compile.** A test that compares a `TrailMetadataValue` to a raw `String`
  with `isEqualTo` or `assertEquals` still compiles (both sides are `Any`) but fails at runtime. When
  migrating, compare with `metadataOf(...)` or read with `.string(key)`.

## Links

- Model and serializer: `trailblaze-models/.../yaml/TrailMetadata.kt`
- Tests: `TrailMetadataTest`
