---
title: "android_adbShell is transport-aware: raw argv on-device, shell string on host"
type: decision
date: 2026-06-30
---

# android_adbShell is transport-aware: raw argv on-device, shell string on host

`android_adbShell` now picks its dispatch form by transport. On the host (dadb →
`adbd`) it keeps the shell-escaped string plus the trailing exit-code sentinel,
because `adbd` evaluates the command through `sh -c`. On-device it sends the
**raw argv tokens** (no escaping, no sentinel) through
`AndroidDeviceCommandExecutor.executeShellCommandArgs`, because the on-device
transport (`UiAutomationConnection.executeShellCommand` → `Runtime.exec`) has no
shell — it whitespace-splits and execs the tokens directly. The two paths are
selected by a new `AndroidDeviceCommandExecutor.usesShellInterpreter` flag
(`true` on the JVM/host actual, `false` on the Android/on-device actual). The
on-device path is additionally bounded by a 60s interruptible timeout.

## Background

A tool authored as a `.ts` scripted tool can compose `android_adbShell`, and the
same composition is expected to work whether the call is dispatched on the daemon
JVM or on the device's QuickJS bundle path. Those two ends are **not**
shell-equivalent, and that mismatch was previously invisible:

- **Host:** the command travels over the dadb wire to `adbd`, which runs it via
  `sh -c`. Shell quoting and a `; <sentinel> $?` tail are honored, so the tool
  shell-escapes every argv element (making metacharacters literal — the
  load-bearing injection-safety property) and recovers the program's exit code
  from the sentinel line.
- **On-device:** `UiAutomationConnection.executeShellCommand` hands the string to
  `Runtime.exec`, which splits on whitespace and execs the tokens **with no shell
  interpreter**. The exact same shell-escaped string is then fatal: a token like
  `su` becomes the literal program name `'su'` (quotes included) →
  `Cannot run program "'su'"`, and the sentinel tail is exec'd as literal
  arguments rather than evaluated.

This surfaced when a device-target launch flow was moved from a Kotlin
orchestrator to TypeScript. The Kotlin path dispatched a privileged
package-disable (`su root pm disable <authenticator-pkg>`, needed only on certain
hardware emulators) through `executeShellCommandArgs` — the join-with-spaces,
**no-escaping** path — so `su` resolved correctly. The TypeScript port routed the
same step through `android_adbShell` on the on-device transport, which
shell-escaped it. The launch failed on the first device call.

Worse, it didn't fail *fast*: `Runtime.exec`'s `IOException` is raised in the
separate UiAutomation process and "cannot cross the Binder," so the caller was
left blocked on the result pipe. With no per-call bound, the run hung until the
session inactivity watchdog abandoned it (~13 min) — every affected test
reported as a TIMEOUT with no driver log, screenshot, or tool log.

## Decision

1. **Make the transport explicit.** Add `usesShellInterpreter` to
   `AndroidDeviceCommandExecutor` so callers can ask whether the device side runs
   a shell, rather than each caller hard-coding the `sh -c` assumption.
2. **Branch `android_adbShell` on it.** Host → unchanged (escape + sentinel +
   exit-code detection). On-device → raw argv via `executeShellCommandArgs`, no
   escaping and no sentinel (the no-shell transport offers no exit-code channel;
   only a failed *launch* throws, and that is surfaced as an error).
3. **Bound the on-device dispatch.** Run it on an interruptible IO dispatcher
   under a 60s cap so a wedged or missing-program exec fails fast with a clear
   error instead of hanging until the watchdog. Generous for a real shell-out on
   a slow CI emulator; far under the ~13-min watchdog.

The pure render decision is exercised by unit tests (`joinCommandRawArgv` must
*not* quote `su`, in contrast to `joinCommandAsShellString` which must) so a
regression that re-introduces escaping on the no-shell path fails a fast unit
test rather than a multi-minute device hang.

## Consequences

- On-device `android_adbShell` cannot observe a non-zero program exit (there is
  no shell to capture `$?`). A non-zero exit returns the program's stdout as
  success; only a launch failure (missing binary, exec error) becomes an error.
  This matches the pre-existing `executeShellCommandArgs` contract.
- On-device argv tokens must be whitespace-free (already enforced by
  `executeShellCommandArgs`) — a single token with an embedded space cannot
  survive `Runtime.exec` re-splitting and is rejected up front.
- The host path is unchanged, including its exit-code sentinel and
  `TRAILBLAZE_ADB_TIMEOUT_MS` bound.
