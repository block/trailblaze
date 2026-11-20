// Declared memory keys: the names a trailmap's `memory:` block, its dependencies' blocks and its
// toolsets' blocks give to the memory keys its scripts share. Dependency-free, so both the full
// and the slim in-process entry carry it.

/**
 * The declared memory keys grouped by the trailmap each belongs to — the trailmap that declares it,
 * or the one shipping the toolset that does. Augmented by the generated `trailblaze-client.d.ts`;
 * each key's type is its own name.
 */
// eslint-disable-next-line @typescript-eslint/no-empty-interface
export interface TrailblazeMemoryKeysByTrailmap {}

/** A declared memory key, from any trailmap in reach. `never` in a trailmap that declares none. */
export type TrailblazeMemoryKey = {
  [Trailmap in keyof TrailblazeMemoryKeysByTrailmap]: Extract<
    keyof TrailblazeMemoryKeysByTrailmap[Trailmap],
    string
  >;
}[keyof TrailblazeMemoryKeysByTrailmap];

/**
 * Every declared memory key, by trailmap: `memoryKeys.myapp.appearance` is `"appearance"`. Typing
 * `memoryKeys.` lists the trailmaps in reach and then their keys, with each key's declaration on
 * hover. Memory is still one flat namespace — the trailmap only says whose key it is, and the
 * value is the bare name `ctx.memory` takes.
 */
export const memoryKeys: TrailblazeMemoryKeysByTrailmap = new Proxy(
  {},
  {
    get: (_trailmaps, trailmap) =>
      typeof trailmap === "string"
        ? new Proxy({}, { get: (_keys, name) => (typeof name === "string" ? name : undefined) })
        : undefined,
  },
) as TrailblazeMemoryKeysByTrailmap;

/** A key `ctx.memory` takes: any string, with the declared ones offered first. */
export type TrailblazeMemoryKeyArg = TrailblazeMemoryKey | (string & {});
