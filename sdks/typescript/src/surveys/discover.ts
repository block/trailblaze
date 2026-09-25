// Finds sessions on disk (directories or artifact zips) and surveys in source files.

import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { existsSync, mkdirSync, mkdtempSync, readdirSync, realpathSync, statSync } from "node:fs";
import { tmpdir } from "node:os";
import { basename, extname, join, resolve, sep } from "node:path";
import { pathToFileURL } from "node:url";

import { type SurveyDefinition, isSurvey, withId } from "./survey.js";
import { isSessionDirectory } from "./session.js";

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
    const stat = statSync(path);
    if (stat.isFile()) {
      if (extname(path).toLowerCase() === ".zip") {
        const out = await extractZip(path, ensureExtractDir(), options.log);
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
    for (const name of readdirSync(path).sort()) {
      if (SKIP_DIRS.has(name)) continue;
      await walk(join(path, name), depth + 1, confineTo);
    }
  };

  for (const input of inputs) await walk(resolve(input), 0);
  return found;
}

async function extractZip(zipPath: string, extractDir: string, log?: (m: string) => void): Promise<string | undefined> {
  // Two builds both upload `logs.zip`; keying the target on the name alone would hand the second
  // zip the first one's extracted sessions. The source path disambiguates.
  const tag = createHash("sha1").update(resolve(zipPath)).digest("hex").slice(0, 10);
  const target = join(extractDir, `${basename(zipPath, ".zip")}-${tag}`);
  if (existsSync(target) && readdirSync(target).length > 0) return target;
  mkdirSync(target, { recursive: true });
  const proc = spawnSync("unzip", ["-q", "-o", zipPath, "-d", target], { stdio: ["ignore", "ignore", "pipe"], encoding: "utf8" });
  if (proc.error) {
    log?.(`unzip could not be run for ${zipPath}: ${proc.error.message}`);
    return undefined;
  }
  if (proc.status !== 0) {
    log?.(`unzip failed for ${zipPath} (exit ${proc.status}): ${(proc.stderr ?? "").trim()}`);
    return undefined;
  }
  return target;
}

export interface LoadedSurvey {
  definition: SurveyDefinition;
  /** Absolute path of the module the survey was exported from. */
  file: string;
  exportName: string;
}

/**
 * Imports survey modules and returns every export that came out of `survey()`. A path that
 * is a file is imported whatever its name; a directory is walked for `*.survey.ts` (or `.js`).
 * The export name becomes the survey id; two modules exporting the same name is an error, as
 * it would be for tools.
 */
export async function loadSurveys(inputs: ReadonlyArray<string>, log?: (message: string) => void): Promise<LoadedSurvey[]> {
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
  for (const input of inputs) walk(resolve(input), 0);

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
