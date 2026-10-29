---
title: "Scripted Tools Know Which Computer They Run On"
type: decision
date: 2026-10-15
---

# Scripted Tools Know Which Computer They Run On

## Summary

A scripted tool can read `ctx.host.os` (`"macos"`, `"windows"` or `"linux"`) and `ctx.host.arch`
(`"arm64"` or `"x64"`). Both are `null` when the tool runs on an Android device. Separately,
`exec` now finds Windows commands by their bare name, so one `argv` works on every host. Tools get
no environment-variable reader on `ctx`, on purpose.

## Why we needed it

Trailblaze now runs on Windows hosts, and a tool written on a Mac broke there in two ways:

- **Commands.** `exec(["sdkmanager", "--list"])` failed, because Java's process launcher finds
  only `.exe` files. `sdkmanager` is `sdkmanager.bat`. Java also looks in the working directory
  before `PATH`.
- **Everything else that differs by host**, such as path separators and install locations. A tool
  had no reliable way to ask which OS it was on.

## What we built

- **`ctx.host.os` and `ctx.host.arch`.** Both script runtimes (in-process QuickJS and subprocess)
  get them from the JVM that runs the tool. The Android build reports `null`, since there the JVM
  is the device itself (`os.name` reads `Linux`).
- **`exec` resolves bare names the way a Windows command prompt does.** `adb` becomes `adb.exe`
  and `sdkmanager` becomes `sdkmanager.bat`. Directories are tried in `PATH` order and extensions
  in `PATHEXT` order. Lookup uses the environment the child process will get, so a caller that
  passes its own `Path` picks the program. Names with a path or an extension pass through
  unchanged. A bare name that `PATH` lacks fails with "not found on PATH", rather than letting
  Windows look in the working directory first.

## Decisions

- **On `ctx.host`, not a new top-level field.** We first built `ctx.hostPlatform`. But `ctx.host` is
  already where a tool reaches the host (`ctx.host.<name>(args)` calls a Kotlin host function).
  `ctx.host.os === "windows"` reads better and keeps it all in one place. `os` and `arch` are now
  reserved: a host function with either name is skipped at discovery with a warning.
- **`null` on a device rather than a guess.** On a device there is no host computer, and the
  device is already described by `ctx.device`.
- **Two fields, each a closed set.** `aarch64` and `arm64` both read `arm64`; `amd64`, `x86_64` and
  `x64` all read `x64`. Any other architecture reads `null`, so that the TypeScript types can list
  every value and a typo fails type-checking. Trailblaze only ships for those two architectures. An
  x64 JVM on Windows on Arm reports `x64`, which is what the programs it starts must be.
- **`os` is the OS family, not the distro.** Ubuntu and Red Hat both read `linux`. We added no
  version numbers or distro names, because nothing needed them. If a tool ever does, the distro
  belongs in a separate field, so `os` keeps meaning the same thing.
- **No environment variables on `ctx`.** Three reasons:
  - Most tools run inside the long-running daemon, so they would see the environment the daemon
    started with, not the shell that ran `trailblaze run`. A variable exported afterwards stays
    invisible until the daemon restarts.
  - A trail that quietly reads machine state passes on one laptop and fails in CI, with nothing
    in the trail saying why.
  - Environments hold secrets, and a one-line reader makes it easy to copy a token into a tool
    result or the session log.

  Each real need already has a path:
  - Values a trail needs: `--memory` / `--secret`, read through `ctx.memory`.
  - Machine configuration: a host function, so the read lives in one reviewed place.
  - Subprocess tools: `process.env`, as before.
  - Last resort: shelling out with an argv chosen by `ctx.host.os` (`printenv NAME`, or the
    cmd.exe equivalent on Windows).

  `exec` already passes the environment to the commands it runs, so a CLI that reads its own
  configuration needs none of these. If a tool ever needs to read the environment directly, the
  shape to build is variables declared in the tool's spec, returned only if declared and treated
  as sensitive in logs.
- **Refuse batch calls cmd.exe would reinterpret, rather than escape them.** Windows starts a `.bat`
  or `.cmd` file through cmd.exe, which re-reads its path and arguments: `%NAME%` and `!NAME!`
  expand, and `&`, `|`, `<`, `>` and `^` act as operators. Escaping arguments reliably for cmd.exe is a well-known
  failure area, so `exec` refuses a batch call with an argument containing any of those
  characters, a double quote, or a line break. The error says to write
  `["cmd", "/c", ...]` when cmd.exe's reading is intended. Plain arguments, including ones with
  spaces, run normally.

## Known gaps

- On a timeout, the host kills the process and every process it started, because a batch file's
  real work runs in a child that cmd.exe starts. On Windows the child would otherwise keep the
  output pipe open, and the timeout would never return. On an Android device only the process
  itself is killed, because Android has no API that lists a process's children.
- No CI job runs these tests on Windows. The Windows lookup and the batch-call refusal are unit
  tested with explicit `windows = true` inputs on macOS and Linux.
