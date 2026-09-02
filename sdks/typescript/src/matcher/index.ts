// Public entry point for `@trailblaze/scripting/matcher` — the selector resolver a
// trailmap tool (or its `*.test.ts`) uses to match a `TrailblazeNodeSelector` against a
// captured view hierarchy:
//
//   import { resolve, resolveText, type TrailblazeNode } from "@trailblaze/scripting/matcher";
//
//   const result = resolve(root, { androidAccessibility: { textRegex: "Add item" } });
//   if (result.kind === "singleMatch") { … }
//
// **Why a subpath and not `@trailblaze/scripting`.** The matcher surface is ~25 symbols
// with generic names (`resolve`, `aggregate`, `findFirst`, `hitTest`), and only a small
// minority of tools need any of them. Folding them into the package main would put those
// names in every scripted tool's namespace and grow the `dist/index.js` bundle that the
// on-device IIFE bundler walks for every tool. A subpath keeps the cost on the consumers
// who ask for it — same reasoning as `@trailblaze/scripting/testing`.
//
// **Why it has to exist at all.** Before this module the only way to reach the resolver
// was a relative path into the SDK's own source tree
// (`../../../../sdks/typescript/src/matcher/resolver.js` and deeper). That resolves only
// from a trailmap sitting at exactly the depth the author wrote it at — a trailmap
// vendored into a consumer repo at `trailblaze-config/trailmaps/<id>/tools/` is one level
// shallower than the framework repo's `trails/config/trailmaps/<id>/tools/`, so the
// identical import lands above the consumer's repo root and `trailblaze check` fails with
// `Cannot find module`. A package specifier is depth-independent: the per-trailmap
// tsconfig's `@trailblaze/scripting` paths mapping is re-derived on every
// `trailblaze check` (see `PerTrailmapTsconfigEmitter`), so it resolves from any depth in
// any repo.
//
// **Bounds.** Deliberately re-exported from `./trailblaze-node.js` and NOT from
// `../generated/selectors.js` — both declare a structurally identical
// `{ left, top, right, bottom }` interface (the generated one mirrors Kotlin's nested
// `TrailblazeNode.Bounds`), so re-exporting both would be a duplicate-name error for no
// gain. The two are mutually assignable; a consumer holding either can pass it here.
//
// Only TYPES come from `../generated/selectors.js` on purpose: type imports are erased,
// so the runtime bundle esbuild produces for this entry point is exactly the three
// matcher modules with no further transitive pull-in. The `selectors` factory (a runtime
// value) stays on `@trailblaze/scripting`.

export {
  resolve,
  resolveToCenter,
  type ResolveResult,
} from "./resolver.js";

export {
  hasIdentifiableProperties,
  isInteractive,
  resolveText,
  IOS_AXE_INTERACTIVE_ROLES,
  type AndroidCollectionInfo,
  type AndroidCollectionItemInfo,
  type AndroidRangeInfo,
  type DriverNodeDetail,
  type DriverNodeDetailAndroidAccessibility,
  type DriverNodeDetailAndroidMaestro,
  type DriverNodeDetailAndroidView,
  type DriverNodeDetailCompose,
  type DriverNodeDetailIosAxe,
  type DriverNodeDetailIosMaestro,
  type DriverNodeDetailWeb,
} from "./driver-node-detail.js";

export {
  aggregate,
  boundsCenterX,
  boundsCenterY,
  boundsContains,
  boundsContainsPoint,
  boundsHeight,
  boundsIntersects,
  boundsWidth,
  centerPoint,
  findAllNodes,
  findFirst,
  hitTest,
  withRefs,
  type Bounds,
  type TrailblazeNode,
} from "./trailblaze-node.js";

export type {
  DriverNodeMatchAndroidAccessibility,
  DriverNodeMatchAndroidMaestro,
  DriverNodeMatchAndroidView,
  DriverNodeMatchCompose,
  DriverNodeMatchIosAxe,
  DriverNodeMatchIosMaestro,
  DriverNodeMatchWeb,
  MatchDescriptor,
  TrailblazeNodeSelector,
} from "../generated/selectors.js";
