#!/usr/bin/env bun
// Runs surveys over sessions from the command line.
//
//   bun src/surveys/cli.ts --target square                 # from anywhere inside a workspace
//   bun src/surveys/cli.ts --target square ./sessions-dir  # over a downloaded batch instead
//
// Run inside a workspace, it finds the workspace the way the trailblaze CLI does and loads every
// survey it carries: `<config>/surveys/` and each trailmap's `surveys/`. `--surveys` and
// `--trailmaps` replace that with explicit paths. Sessions default to ~/.trailblaze/logs and may be
// session directories, directories of sessions, artifact zips, or directories of zips. With no
// `--out`, the markdown report goes to stdout; with `--out`, JSON goes to the file and a one-line
// per-session summary goes to stdout.

import { runSurveyCli } from "./cli-main.js";

process.exitCode = await runSurveyCli(process.argv.slice(2));
