// The workspace a survey run belongs to, found the way the trailblaze CLI finds it, so running
// surveys needs no paths: every survey the workspace carries loads, and the trailmaps beside them
// say which sessions belong to which target.

import { existsSync, statSync } from "node:fs";
import { dirname, join, resolve } from "node:path";

import { loadSurveys, type LoadedSurvey } from "./discover.js";
import { loadTargetCatalog, type TargetCatalog } from "./targets.js";

/** The two config-dir layouts, checked in this order at each ancestor, as the CLI does. */
const CONFIG_DIRS = ["trailblaze-config", join("trails", "config")];

/**
 * The workspace config dir that owns `from`: `TRAILBLAZE_CONFIG_DIR` when it names a directory,
 * otherwise the closest ancestor's `trailblaze-config/` or `trails/config/` holding a
 * `trailblaze.yaml`.
 * Undefined outside any workspace.
 */
export function findWorkspaceConfigDir(from: string = process.cwd()): string | undefined {
  const override = process.env.TRAILBLAZE_CONFIG_DIR;
  if (override) {
    const dir = resolve(override);
    if (existsSync(dir) && statSync(dir).isDirectory()) return dir;
    // As the CLI does: a stale override must not hide the workspace the walk-up finds.
    console.warn(`TRAILBLAZE_CONFIG_DIR='${override}' is not a directory — ignoring.`);
  }
  let dir = resolve(from);
  if (existsSync(dir) && statSync(dir).isFile()) dir = dirname(dir);
  for (;;) {
    for (const candidate of CONFIG_DIRS) {
      const anchor = join(dir, candidate, "trailblaze.yaml");
      if (existsSync(anchor) && statSync(anchor).isFile()) return join(dir, candidate);
    }
    const parent = dirname(dir);
    if (parent === dir) return undefined;
    dir = parent;
  }
}

export interface Workspace {
  configDir: string;
  /** `<configDir>/surveys/` (surveys for any target) plus every trailmap's `surveys/`. */
  surveys: LoadedSurvey[];
  /** Which app ids each of the workspace's targets owns. */
  targets: TargetCatalog;
}

/** Every survey a workspace carries, with the target catalog its trailmaps define. */
export async function loadWorkspace(configDir: string, log?: (message: string) => void): Promise<Workspace> {
  const trailmaps = join(configDir, "trailmaps");
  const shared = join(configDir, "surveys");
  const hasShared = existsSync(shared) && statSync(shared).isDirectory();
  // A workspace without trailmaps is normal; passing the missing dir would log it on every run.
  const trailmapDirs = existsSync(trailmaps) ? [trailmaps] : [];
  const surveys = await loadSurveys(hasShared ? [shared] : [], log, { trailmaps: trailmapDirs });
  return { configDir, surveys, targets: trailmapDirs.length ? loadTargetCatalog(trailmapDirs, log) : {} };
}
