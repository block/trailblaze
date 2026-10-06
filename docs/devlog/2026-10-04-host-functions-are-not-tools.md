---
title: "Host Functions Are Not Tools"
type: decision
date: 2026-10-04
---

# Host Functions Are Not Tools

## Summary

Scripted tools can now call Kotlin helpers as `ctx.host.<name>(args)`. A host function is not a
tool. Calling one writes no step to the session log or report, keeps the cached screen, and can't be
seen by the LLM or captured by a recording. The rule underneath: **make something a tool only when a
trail or the LLM must call it, or its calls must show up as steps.**

## Why we needed it

A script could reach Kotlin only through `ctx.tools`, so every Kotlin helper a script needed became a
hidden tool (`surfaceToLlm = false`). That had three costs:

- **Every call was a step.** The session log and report recorded the call, with its arguments and its
  result. For a credential lookup, that result was the password.
- **Every call dropped the cached screen.** Any tool that isn't read-only invalidates the snapshot,
  so the next real step paid for a fresh capture it didn't need.
- **Two registrations per helper.** Typing the call needed a `.tool.yaml` and a toolset entry, for
  something no trail ever names.

## What we built

- **Kotlin side.** A host function is a `@Serializable` class whose properties are its arguments. It
  implements `TrailblazeHostFunction<R>`, and `@TrailblazeHostFunctionClass` gives its name and
  result type. It is registered by `trails/config/trailmaps/<trailmap>/host/<name>.host.yaml`
  (`id:` and `class:`). One dispatcher decodes the arguments, rejects keys the class doesn't
  declare, runs the function, and encodes the result.
- **Both script runtimes.** In-process QuickJS calls a synchronous `__trailblazeHost` binding.
  Subprocess scripts send a new `call_host` action to the existing callback endpoint. Neither path
  touches tool dispatch, so neither path can log a step or invalidate the snapshot.
- **Typing.** The per-trailmap `trailblaze-client.d.ts` gains a `TrailblazeHostFunctionMap`. It
  covers every host function registered by the trailmap or its dependencies, typed from the Kotlin
  classes. A misspelled argument or an unknown function fails `tsc`.
- **Logging.** Each call leaves a trace span and one daemon log line with the name, how long it took,
  and whether it succeeded. Arguments and results are never logged.

## Decisions

- **Separate namespace from tools.** `ctx.host` and `ctx.tools` never resolve each other's names.
  That lets a helper keep its tool name while it migrates: the tool and the host function can exist
  side by side.
- **Descriptors outside `tools/`.** Any `.yaml` under `tools/` is read as a scripted-tool
  descriptor, so host descriptors live in their own `host/` directory.
- **Runs where the script runs.** A host function runs on the host daemon, or on the device for a
  trail running entirely inside a test APK. There is no device-to-host forwarding.
- **Counts against the recursion cap.** A host function gets the full execution context, so it
  could dispatch a scripted tool. A host call is gated and adds a level of depth exactly like a
  tool call.
- **Errors never repeat values.** Arguments are decoded strictly at every level, and a decode or
  encode failure reports what failed and where, never the JSON involved — either side can carry
  credentials.
