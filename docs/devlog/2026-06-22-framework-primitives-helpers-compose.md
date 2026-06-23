---
title: "Framework provides primitives; helpers are not framework"
type: decision
date: 2026-06-22
---

# Framework provides primitives; helpers are not framework

## Summary

The framework's built-in tool surface is **low-level primitives only** — the
smallest, most general operations a driver/executor can perform, each with no
more configuration than the operation inherently needs. Ergonomic, task-shaped
*helpers* compose those primitives, but they are **not** framework tools: they
live in the consuming trailmap, or as an inline composition at the call site.

This was hammered out reviewing a "write a file to Downloads" tool. The honest
endpoint surprised the starting point, so the reasoning is worth recording.

## The decision

1. **The framework ships primitives.** A primitive is the lowest-level operation
   the executor can do, and it carries only the config that operation inherently
   needs. If a candidate tool wants a pile of optional knobs (owner, mode, MIME,
   relative path, target collection…), that's the tell it's a *composition*, not
   a primitive — don't ship it as a structured tool.

2. **The general primitive already exists: `android_adbShell`.** It's
   argv-complete, so the entire long tail is expressible today with zero new
   surface: `chmod`/`chown`/`restorecon`, `content insert --bind …`,
   `cmd media_scanner scan`, `base64 -d > path`. It never needs another param.

3. **Helpers are not framework.** A convenience that only adds a transform on top
   of a primitive (e.g. "UTF-8-encode this string, then write the bytes") carries
   no framework-general value. It belongs to whoever needs it — inline, or a tool
   in their own trailmap. A helper in the framework is a smell, not a feature.

## What ships

Exactly one new framework tool:

- **`android_writeBytesToFile(devicePath, base64Content)`** — writes raw bytes to
  an absolute device path (creating parents, overwriting). Backed by the new
  dual-mode `AndroidDeviceCommandExecutor.writeFileToDevice`: host = `adb push`
  (sync protocol); on-device = a direct `java.io.File` write, falling back to a
  temp-file + `cp` for paths that need the `shell` UID.

That's it. The original motivating shape — "write this setup JSON to Downloads"
— is a *composition* over this primitive (and, if the consumer needs the file
MediaStore-visible, an `android_adbShell` `cmd media_scanner scan`), owned by the
consuming app, not the framework.

## Why this one primitive earns a slot (and nothing else does)

`android_adbShell` already covers writing bytes *and* everything around it —
except one thing: it can't move a file **body** reliably.

- On the **host**, piping a body through `adb shell` stdin hits the
  EXIT-packet hang the executor already documents (it uses `adb push` to avoid it).
- **On-device**, passing a base64 blob as a shell argument hits `ARG_MAX` for
  large payloads.

So "get a (possibly large/binary) body onto the device" is the single capability
`adbShell` lacks — and that's about a **path**, not a directory or a provider.
`android_writeBytesToFile` does exactly that and nothing more: path + bytes, no
MIME, no MediaStore, no perms. Those stay on `adbShell`, which keeps the primitive
free of param sprawl.

## Key decisions (and the dead ends that got us here)

- **Bytes, not text.** Text is one encoding of bytes; tool args are a text-only
  wire, so bytes ride as base64. base64 carries **no** metadata — no MIME, no
  name (a `data:` URI puts MIME in a prefix *outside* the base64). And a
  filesystem path write has no MIME concept anyway, so the primitive needs no type
  config — the MIME question simply doesn't arise here.

- **Filesystem path, not "Downloads".** An earlier cut was Downloads-specific and
  MediaStore-backed. Two problems killed it: (1) it needed config to be honest —
  at minimum a `mimeType` (the executor hardcodes `text/json`, actively wrong for
  a binary payload) and a subdirectory — i.e. the param sprawl rule 1 forbids;
  (2) its only solid framework justification (the body-transfer hang) is about a
  path, not Downloads. MediaStore registration is separable and lives on
  `adbShell`. So the primitive is path-shaped; Downloads/MediaStore/MIME are
  caller compositions.

- **Dead end: one tool with a `base64: Boolean` flag.** Conflated text and binary
  on one surface and hid the layering. Removed.

- **Dead end: a UTF-8-text helper as a framework tool** (delegating to the byte
  tool). Cleaner than the flag, but still a helper in the framework — exactly what
  rule 3 forbids. The text convenience is `base64(utf8(x))` + call; that's
  caller-side. Removed.

- **Dead end: a Downloads-specific byte tool** (`android_writeBytesToDownloads`).
  See "Filesystem path, not Downloads" above. Replaced by the path primitive.

## Guidance for future framework tools

When adding a built-in, ask: *what is the most general operation the executor
performs, and how many knobs does it want?*

- Wants many knobs (perms, owner, MIME, provider columns, arbitrary collections)
  → it's `android_adbShell`, not a structured tool. Don't build it.
- A fixed-scope operation a general shell genuinely can't do (here: a hang-free,
  ARG_MAX-free body transfer) → ship a focused primitive with a minimal, stable
  signature.
- "A primitive plus a convenience step" → ship the primitive; the convenience is
  the caller's (inline, or their trailmap).

Conventions unchanged: framework primitives keep flat / `android_*` / `mobile_*`
names (per [trailmap-scoped tool naming](2026-05-27-trailmap-scoped-tool-naming.md)),
`surfaceToLlm = false` for composition primitives, dual-mode (`requiresHost`
defaulted false) when the executor method has both host and on-device actuals.

## Future work / watch items

- **Writing to public Downloads MediaStore-visibly** is now a composition:
  `android_writeBytesToFile` to the Download path, then (only if the consumer
  reads via a MediaStore query rather than the filesystem path) an
  `android_adbShell` `cmd media_scanner scan`. The executor's MediaStore-insert
  `writeFileToDownloads` still exists for Kotlin API callers that need an atomic
  registered write; it just isn't exposed as a tool.
- **UTF-8 → base64 in the scripted environment** can be a footgun (`btoa` isn't
  UTF-8-safe in QuickJS). If a consuming app's launch migration hits it, the answer
  is a helper in *that* trailmap, not a framework tool.
