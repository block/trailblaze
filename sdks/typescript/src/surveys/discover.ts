// Finds sessions on disk (directories or artifact zips) and surveys in source files.

import { spawn } from "node:child_process";
import { createHash } from "node:crypto";
import { existsSync, mkdirSync, mkdtempSync, readdirSync, realpathSync, statSync } from "node:fs";
import { availableParallelism, tmpdir } from "node:os";
import { basename, dirname, extname, join, resolve, sep } from "node:path";
import { pathToFileURL } from "node:url";

import { type SurveyDefinition, isSurvey, withId } from "./survey.js";
import { isSessionDirectory } from "./session.js";
import { findTrailmapFiles } from "./targets.js";
import * as surveysSdk from "./index.js";
import * as matcherSdk from "../matcher/index.js";

const MAX_DEPTH = 4;
const SURVEY_FILE = /\.survey\.(ts|js|mjs|mts)$/;
const SKIP_DIRS = new Set(["node_modules", ".git", "build", "dist", ".gradle"]);

export interface DiscoverSessionsOptions {
  /** Where zips are unpacked. Defaults to a fresh temp directory per run. */
  extractDir?: string;
  log?: (message: string) => void;
  /**
   * Called with each directory a zip was unpacked into. A caller that wants to look at what was in
   * a zip BESIDES its sessions has no other way to find it: the extraction target is named after a
   * hash of the zip's own path, and the return value is session directories only.
   */
  onExtract?: (extractedRoot: string) => void;
}

/**
 * Resolves each input to zero or more session directories. An input may be a session directory, a
 * directory containing sessions (any depth up to four), a session zip, or a directory of zips. Zips
 * are unpacked with the system `unzip`, once per run, into `extractDir`.
 */
export async function discoverSessions(inputs: ReadonlyArray<string>, options: DiscoverSessionsOptions = {}): Promise<string[]> {
  const found: string[] = [];
  const seen = new Set<string>();
  let extractDir = options.extractDir;
  const ensureExtractDir = (): string => {
    const dir = extractDir ?? (extractDir = mkdtempSync(join(tmpdir(), "trailblaze-sessions-")));
    mkdirSync(dir, { recursive: true });
    return dir;
  };

  const add = (dir: string) => {
    const abs = resolve(dir);
    if (!seen.has(abs)) {
      seen.add(abs);
      found.push(abs);
    }
  };

  /** Whether `path` really lands inside `root`, following every link on the way. */
  const inside = (root: string, path: string): boolean => {
    try {
      const realRoot = realpathSync(root);
      const realPath = realpathSync(path);
      return realPath === realRoot || realPath.startsWith(realRoot + sep);
    } catch {
      return false;
    }
  };

  // The input being walked, and whether it lies inside the extract directory. Decided once the
  // directory exists rather than when the input starts: it is created by the first unzip, partway
  // through an input that may contain it.
  let currentInput = "";
  let currentInputInside: boolean | undefined;
  /** Whether `path`, met below an input the caller named, leads into the extract directory. */
  const intoExtractDir = (path: string): boolean => {
    if (extractDir === undefined || !existsSync(extractDir)) return false;
    currentInputInside ??= inside(extractDir, currentInput);
    return !currentInputInside && inside(extractDir, path);
  };

  const unpacking = new Map<string, Promise<string | undefined>>();
  const limit = limiter(Math.max(1, Math.min(availableParallelism(), 8)));
  // Set when discovery fails, so the unzips still queued never start.
  let stopped = false;
  const unpack = (zip: string): Promise<string | undefined> => {
    let out = unpacking.get(zip);
    if (out === undefined) {
      const dir = ensureExtractDir();
      out = limit(() => (stopped ? Promise.resolve(undefined) : extractZip(zip, dir, options.log)));
      unpacking.set(zip, out);
    }
    return out;
  };

  // `confineTo` is set for everything below an unpacked archive. A zip can carry symlinks and
  // `unzip` recreates them, so a link named `logs` pointing at the agent's own log directory would
  // otherwise be walked as though the archive had contained those sessions — and their contents
  // would go into snapshots that are published. Nothing constrains a directory the caller named
  // itself, which is the ordinary case and is the caller's own filesystem either way.
  const walk = async (path: string, depth: number, confineTo?: string): Promise<void> => {
    if (!existsSync(path)) {
      options.log?.(`skipping missing path: ${path}`);
      return;
    }
    if (confineTo !== undefined && !inside(confineTo, path)) {
      options.log?.(`skipping ${path}: it leads outside the archive it came from`);
      return;
    }
    // The extract directory is reached only through the zips it unpacks. Met while walking an input
    // that contains it, it holds archives still being unpacked (they start early, below) and the
    // leftovers of earlier runs: walking it would accept a half-written session from a zip that then
    // fails to unpack, or a session whose zip is gone. Anything that resolves into it is skipped, so
    // a link pointing inside it is skipped too. A caller who names the extract directory, or a folder
    // inside it, gets it walked.
    if (confineTo === undefined && depth > 0 && intoExtractDir(path)) {
      return;
    }
    const stat = statSync(path);
    if (stat.isFile()) {
      if (extname(path).toLowerCase() === ".zip") {
        const out = await unpack(path);
        if (out) {
          options.onExtract?.(out);
          await walk(out, depth + 1, out);
        }
      }
      return;
    }
    if (!stat.isDirectory()) return;
    if (isSessionDirectory(path)) {
      add(path);
      return;
    }
    if (depth >= MAX_DEPTH) return;
    const children = readdirSync(path).sort().filter((name) => !SKIP_DIRS.has(name)).map((name) => join(path, name));
    // A directory of zips is the ordinary input — a week of sessions — and unpacking them one after
    // another was minutes of a build. They start together, a few at a time, and are still walked in
    // name order, so the sessions come back in the same order as before.
    for (const child of children) {
      if (extname(child).toLowerCase() !== ".zip" || (confineTo !== undefined && !inside(confineTo, child))) continue;
      // The walk skips it, so it would never be awaited.
      if (confineTo === undefined && intoExtractDir(child)) continue;
      if (statSync(child, { throwIfNoEntry: false })?.isFile()) unpack(child);
    }
    for (const child of children) await walk(child, depth + 1, confineTo);
  };

  try {
    for (const input of inputs) {
      currentInput = resolve(input);
      currentInputInside = undefined;
      await walk(currentInput, 0);
    }
  } catch (error) {
    // Zips unpack ahead of the walk. A failure must not leave them writing into the extract
    // directory after the caller has been told discovery is over.
    stopped = true;
    await Promise.allSettled(unpacking.values());
    throw error;
  }
  // Every unzip started was awaited by the walk that reached it. This makes sure of it: nothing may
  // still be writing into the extract directory once the caller has the sessions.
  await Promise.allSettled(unpacking.values());
  return found;
}

async function extractZip(zipPath: string, extractDir: string, log?: (m: string) => void): Promise<string | undefined> {
  // Two builds both upload `logs.zip`; keying the target on the name alone would hand the second
  // zip the first one's extracted sessions. The source path disambiguates. Its size and modification
  // time do the same over time: a zip replaced at the same path is a different archive, and reusing
  // the old one's unpacked copy would hand back sessions it no longer holds.
  const { size, mtimeMs } = statSync(zipPath);
  const tag = createHash("sha1").update(`${resolve(zipPath)}\0${size}\0${mtimeMs}`).digest("hex").slice(0, 10);
  const target = join(extractDir, `${basename(zipPath, ".zip")}-${tag}`);
  if (existsSync(target) && readdirSync(target).length > 0) return target;
  mkdirSync(target, { recursive: true });
  const proc = await new Promise<{ error?: Error; status: number | null; stderr: string }>((done) => {
    let stderr = "";
    const child = spawn("unzip", ["-q", "-o", zipPath, "-d", target], { stdio: ["ignore", "ignore", "pipe"] });
    child.stderr.setEncoding("utf8").on("data", (chunk: string) => (stderr += chunk));
    child.on("error", (error) => done({ error, status: null, stderr }));
    child.on("close", (status) => done({ status, stderr }));
  });
  if (proc.error) {
    log?.(`unzip could not be run for ${zipPath}: ${proc.error.message}`);
    return undefined;
  }
  if (proc.status !== 0) {
    log?.(`unzip failed for ${zipPath} (exit ${proc.status}): ${proc.stderr.trim()}`);
    return undefined;
  }
  return target;
}

/** Runs at most `n` of the tasks it is handed at once, in the order they were handed over. */
function limiter(n: number): <T>(task: () => Promise<T>) => Promise<T> {
  let running = 0;
  const queue: Array<() => void> = [];
  return (task) =>
    new Promise((resolveTask, rejectTask) => {
      const start = () => {
        running++;
        task().then(resolveTask, rejectTask).finally(() => {
          running--;
          queue.shift()?.();
        });
      };
      if (running < n) start();
      else queue.push(start);
    });
}

export interface LoadedSurvey {
  definition: SurveyDefinition;
  /** Absolute path of the module the survey was exported from. */
  file: string;
  exportName: string;
}

export interface LoadSurveysOptions {
  /**
   * `trailmap.yaml` files, or directories walked for them. Each trailmap's `surveys/` directory is
   * loaded as though it had been passed as an input, so a target's surveys ship inside its
   * trailmap the way its tools do. A trailmap without one contributes nothing.
   */
  trailmaps?: ReadonlyArray<string>;
}

interface BunPluginHost {
  plugin(plugin: {
    name: string;
    setup(build: { module(specifier: string, load: () => { exports: object; loader: "object" }): void }): void;
  }): void;
}

let sdkServed = false;

/**
 * Serves `@trailblaze/scripting/surveys` and `/matcher` to survey files from this loader's own copy
 * of the SDK. A survey in another repo's trailmap then imports them with no installed package, and
 * whatever its own tsconfig `paths` point at (that repo's materialized SDK, absent where the
 * trailmap is staged), every survey shares the one copy the runner's types and classes come from.
 */
function serveSdkToSurveys(): void {
  const bun = (globalThis as { Bun?: BunPluginHost }).Bun;
  if (sdkServed || !bun) return;
  sdkServed = true;
  bun.plugin({
    name: "trailblaze-surveys-sdk",
    setup(build) {
      build.module("@trailblaze/scripting/surveys", () => ({ exports: surveysSdk, loader: "object" }));
      build.module("@trailblaze/scripting/matcher", () => ({ exports: matcherSdk, loader: "object" }));
    },
  });
}

/**
 * Imports survey modules and returns every export that came out of `survey()`. A path that
 * is a file is imported whatever its name; a directory is walked for `*.survey.ts` (or `.js`).
 * The export name becomes the survey id; two modules exporting the same name is an error, as
 * it would be for tools, including one in a trailmap and one passed directly.
 */
export async function loadSurveys(
  inputs: ReadonlyArray<string>,
  log?: (message: string) => void,
  options: LoadSurveysOptions = {},
): Promise<LoadedSurvey[]> {
  const fromTrailmaps = findTrailmapFiles(options.trailmaps ?? [], log)
    .map((file) => join(dirname(file), "surveys"))
    .filter((dir) => existsSync(dir) && statSync(dir).isDirectory());
  const files: string[] = [];
  const walk = (path: string, depth: number) => {
    if (!existsSync(path)) {
      log?.(`skipping missing survey path: ${path}`);
      return;
    }
    const stat = statSync(path);
    if (stat.isFile()) {
      files.push(path);
      return;
    }
    if (!stat.isDirectory() || depth > 8) return;
    for (const name of readdirSync(path).sort()) {
      if (SKIP_DIRS.has(name)) continue;
      const child = join(path, name);
      if (statSync(child).isDirectory()) walk(child, depth + 1);
      else if (SURVEY_FILE.test(name) && !/\.test\.[cm]?[jt]s$/.test(name)) files.push(child);
    }
  };
  for (const input of [...inputs, ...fromTrailmaps]) walk(resolve(input), 0);

  serveSdkToSurveys();
  const loaded: LoadedSurvey[] = [];
  const byId = new Map<string, string>();
  // A directory and one of its children both name the same file; load it once or every finding doubles.
  for (const file of [...new Set(files)]) {
    const mod = (await import(pathToFileURL(file).href)) as Record<string, unknown>;
    let count = 0;
    for (const [exportName, value] of Object.entries(mod)) {
      if (!isSurvey(value)) continue;
      const prior = byId.get(exportName);
      if (prior && prior !== file) {
        throw new Error(`survey "${exportName}" is exported by both ${prior} and ${file}; export names must be unique`);
      }
      byId.set(exportName, file);
      loaded.push({ definition: withId(value, exportName), file, exportName });
      count += 1;
    }
    if (count === 0) log?.(`no surveys exported by ${file}`);
  }
  return loaded;
}
