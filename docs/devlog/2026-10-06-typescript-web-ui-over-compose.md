---
title: "A TypeScript Web UI Instead of Compose"
type: decision
date: 2026-10-06
---

# A TypeScript Web UI Instead of Compose

## Summary

Trailblaze's UI is now a TypeScript web app, Trail Runner, served by the daemon. It replaces the
Compose Multiplatform desktop app and the Compose-for-Web (WASM) report. This supersedes
[Desktop Application](2026-01-28-desktop-application.md).

The biggest reason is speed of iteration on reports with coding agents. Bun rebuilds a report in a
few seconds and a browser refresh shows the change, and the reports come out much smaller. It also fits how Trailblaze is used now: agents work through the CLI and MCP, and
people mostly read reports.

## Why

### Agents iterate on reports much faster

This was the biggest win. Reports are what people look at, so they're the UI we change most. In
TypeScript, a coding agent can change a report, rebuild it with Bun in about three seconds, refresh
the browser and check the result. Compose Hot Reload worked for the desktop app but not for the
WASM report, so every report change meant a Kotlin compile and a WASM build before anyone could
see it. Faster rebuilds turned report work from a slow loop into a quick
one, and the reports improved much faster as a result.

### Reports got much smaller

The Compose report had to ship the whole Compose runtime as WebAssembly. Every report carried about
**7.5 MB** of fixed runtime (two WASM binaries plus a JS loader) before any session data. When the
TypeScript report shipped, its viewer was **43 KB** of plain JS and CSS, and the same run's report
came out about 21× smaller. The viewer has gained many features since and is now about **0.9 MB**
uncompressed (viewer script plus CSS), still about 8× smaller than the WASM runtime. The WASM report
also needed its own build step, and the CLI jar had to bundle its template.

### The CLI and MCP became the main interface

Most trail authoring and running now happens through agents driving the CLI and MCP. People mostly
interact with Trailblaze by reading run reports. Trail Runner (what `trailblaze app` opens) is
still useful, but it's no longer where anyone spends their day. We found ourselves rarely opening
the desktop app at all. A secondary UI no longer justified a large native app with its own build
and release concerns.

### The IDE plugin option stopped mattering

Part of the reason for building the UI in Compose was to keep the door open to shipping it as an
IDE plugin, since IntelliJ-based IDEs can host Compose UI. That never happened, and it no longer
makes sense: people work in coding agents now, not IDEs. Without that option, Compose had no
advantage over a web UI.

### We get the web ecosystem

We use existing libraries and design frameworks instead of building our own: React, the CodeMirror
and Monaco editors (Monaco with language-server support), Lucide icons and highlight.js. In Compose,
an editor with YAML linting or a language server was a project of its own.

### It only works because of the daemon

None of this would be possible if the UI owned any logic. The daemon owns devices, sessions, runs,
trails and settings, and exposes all of it over HTTP RPC. The TypeScript types for that API are
generated from the Kotlin DTOs, so the UI stays in sync with the Kotlin types. The UI is a client
of the daemon, the same way the CLI and MCP clients are. Swapping Compose for TypeScript meant
replacing one client, not moving logic.

## What it cost

The biggest loss is a single codebase. With Compose, everything was 100% Kotlin and shared through
Kotlin Multiplatform: the UI used the same models, the same YAML and JSON serialization and the same
utilities as the agent, the daemon and the CLI. There was one implementation of everything and
nothing to drift.

Now there are two sides, Kotlin and TypeScript. To keep them from drifting, we generate TypeScript
bindings from the Kotlin source for the parts they share, such as the daemon's DTOs and the selector
types. Every change to those Kotlin types means regenerating the bindings, and CI fails if they're
stale. Anything not generated is written twice.

Smaller costs:

- Trail Runner loads React, CodeMirror and its other libraries from a public CDN, so the first load
  needs network access.
- Opening the app on macOS now means a native window around a web view rather than a Compose
  window. Everywhere else it opens in the browser.

## What we got from it

- **The same UI runs everywhere.** The browser on Linux and headless hosts, and a native window
  on macOS. The Compose app only ran as a macOS desktop app.
- **One renderer for the app and for reports.** The report you get from "Share as HTML" in Trail
  Runner and the one `trailblaze report` writes come from the same code.
- **Less code and a smaller CLI.** Deleting the Compose app removed about 58k lines. The shipped
  jar is 17.6 MB (8.7%) smaller.
- **The daemon is server-only on every platform.** No window, no tray icon, and no GUI-only code
  paths.

## What stays in Compose

The Compose driver for testing Compose desktop apps is a separate thing and is unchanged.
