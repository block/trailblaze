package xyz.block.trailblaze.toolcalls

/**
 * SINGLE source of truth for the handful of **recordable** framework tools that the generated
 * recordable-tool surface (`PerTrailmapClientDtsEmitter`) deliberately does NOT emit — so they can
 * stay hand-typed in the SDK's `sdks/typescript/src/built-in-tools.ts` without the two declaring the
 * same `TrailblazeToolMap` key. (A duplicate key with a different shape is a TS2717 error that would
 * break the whole trail-recording validation compilation.)
 *
 * A tool lands here only when the reflection/descriptor codegen path can't model it *correctly*:
 *
 *  - `mobile_maestro` — a custom serializer maps the recorded `{ commands: [...] }` payload onto the
 *    class's single `yaml: String` constructor param, so reflection would lower it to the WRONG shape
 *    (`{ yaml: string }`) that doesn't match how it's actually recorded. Hand-typed with `commands`.
 *  - `mobile_listInstalledApps` / `mobile_listInstalledAppsDetailed` — structured result types the
 *    generator would otherwise flatten to `result: string`; hand-typed to preserve the typed result.
 *
 * ## Why this is one shared constant, not two hand-kept copies
 *
 * The exclusion is consumed from two modules that can't share a private field:
 * [xyz.block.trailblaze.host.PerTrailmapClientDtsEmitter] (skips these when generating) and
 * `BuiltInToolsBindingDriftTest` (allows these — and only these — to overlap the hand file). Keeping
 * a copy in each is exactly the "slips / gets out of sync" hazard. Both now import THIS set, so they
 * cannot diverge. `BuiltInToolsBindingDriftTest` additionally asserts every name here is a real
 * recordable tool, so a rename/removal fails loudly instead of silently rotting.
 */
object HandCuratedRecordableTools {
  val NAMES: Set<String> = setOf(
    "mobile_maestro",
    "mobile_listInstalledApps",
    "mobile_listInstalledAppsDetailed",
  )
}
