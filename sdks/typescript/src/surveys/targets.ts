// Targets as the workspace defines them, so "this survey is for target X" covers every app id
// that target declares rather than repeating the list in each survey.
//
// A session records the target its trail named (`summary.target`) and the app it drove
// (`summary.appId`). Either is enough to place a session under a target once the catalog says which
// app ids belong to it, which also covers sessions run without a target, or before one existed.

import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { basename, join } from "node:path";

// The matching half lives in `scope.ts`, which a browser page can import; this file reads disk.
export { sessionMatchesTarget, targetsOf } from "./scope.js";
export type { TargetCatalog, TargetDefinition } from "./scope.js";
import type { TargetCatalog, TargetDefinition } from "./scope.js";

const SKIP_DIRS = new Set(["node_modules", ".git", "build", "dist", ".gradle"]);
const MAX_DEPTH = 5;

/**
 * Reads every `trailmap.yaml` under `inputs` (files or directories) into a catalog. A trailmap
 * contributes a target when it has a top-level `id` and `target.platforms.<platform>.app_ids`.
 * Parsing relies on the Bun runtime's YAML support, which the surveys CLI already runs under.
 */
export function loadTargetCatalog(inputs: ReadonlyArray<string>, log?: (message: string) => void): TargetCatalog {
  const files: string[] = [];
  const walk = (path: string, depth: number) => {
    if (!existsSync(path)) {
      log?.(`skipping missing trailmap path: ${path}`);
      return;
    }
    const st = statSync(path);
    if (st.isFile()) {
      if (basename(path) === "trailmap.yaml") files.push(path);
      return;
    }
    if (!st.isDirectory() || depth > MAX_DEPTH) return;
    for (const name of readdirSync(path)) {
      if (SKIP_DIRS.has(name)) continue;
      walk(join(path, name), depth + 1);
    }
  };
  for (const input of inputs) walk(input, 0);

  const catalog: TargetCatalog = {};
  for (const file of files) {
    const def = targetFromTrailmap(parseYaml(readFileSync(file, "utf8")), file);
    if (!def) continue;
    if (catalog[def.id]) log?.(`target ${def.id} is defined by both ${catalog[def.id]!.file} and ${file}; keeping the first`);
    else catalog[def.id] = def;
  }
  return catalog;
}

/** Extracts a target definition from a parsed trailmap document, or undefined when it declares no apps. */
export function targetFromTrailmap(doc: unknown, file?: string): TargetDefinition | undefined {
  const root = asRecord(doc);
  const id = typeof root?.id === "string" ? root.id : undefined;
  const platforms = asRecord(asRecord(root?.target)?.platforms);
  if (!id || !platforms) return undefined;
  const byPlatform: Record<string, string[]> = {};
  for (const [platform, spec] of Object.entries(platforms)) {
    const ids = asRecord(spec)?.app_ids;
    if (Array.isArray(ids)) byPlatform[platform] = ids.filter((x): x is string => typeof x === "string");
  }
  const appIds = [...new Set(Object.values(byPlatform).flat())];
  if (appIds.length === 0) return undefined;
  return { id, appIds, platforms: byPlatform, file };
}

declare const Bun: { YAML?: { parse(text: string): unknown } } | undefined;

function parseYaml(text: string): unknown {
  if (typeof Bun === "undefined" || !Bun?.YAML) {
    throw new Error("Reading trailmap.yaml needs the Bun runtime (Bun.YAML); build the target catalog in code instead");
  }
  return Bun.YAML.parse(text);
}

function asRecord(v: unknown): Record<string, unknown> | undefined {
  return v !== null && typeof v === "object" && !Array.isArray(v) ? (v as Record<string, unknown>) : undefined;
}
