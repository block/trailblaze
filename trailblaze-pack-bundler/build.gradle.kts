plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

// JVM-only by design — the bundler runs at build time (Gradle plugin) and at CLI time
// (`trailblaze bundle`), both JVM contexts. KMP would add complexity for no benefit.
//
// **Why this module is intentionally lean.** The bundler defines its own `@Serializable`
// view of the pack-manifest YAML (see `BundlerPackManifest`) rather than depending on
// `:trailblaze-models`. The trailblaze-models graph pulls in koog, MCP, and other heavy
// runtime deps that the build-logic Gradle plugin cannot afford on its configuration-phase
// classpath. The bundler reads only a tiny slice of the manifest schema (`target.tools:` +
// per-tool `inputSchema`); duplicating that slice keeps both consumers (this module's main
// build, and the build-logic plugin via shared Kotlin sources) light. Schema drift between
// the bundler's slice and trailblaze-models' authoritative shape is mitigated by kaml's
// `strictMode = false` — extra fields are ignored, the bundler only fails when a field it
// actually reads is missing or wrong-typed.
//
// **Consumed by:**
//   1. The `trailblaze.bundle` Gradle plugin in `build-logic/`. That build pulls these
//      sources via `sourceSets["main"].kotlin.srcDir(...)` because build-logic is an
//      includedBuild and can't take a `project(":trailblaze-pack-bundler")` dep directly.
//      Same compiled output, two callers, one source-of-truth.
//   2. The `trailblaze bundle` CLI subcommand (separate, follow-up PR) — depends on this
//      module via standard `implementation(project(":trailblaze-pack-bundler"))`.

dependencies {
  implementation(libs.kaml)
  implementation(libs.kotlinx.serialization.core)

  // [WorkspaceClientDtsGenerator] takes `ToolDescriptor` and `PackScriptedToolFile` directly
  // (frozen interface contract from #2749 PR-C). These are `compileOnly` because:
  //   1. The Gradle plugin (build-logic) does NOT use the generator — only the daemon does.
  //      Build-logic source-includes this module's `src/main/kotlin` via a `srcDir` composition
  //      (see `build-logic/build.gradle.kts:50`); a normal `implementation` would force build-logic
  //      to also pull koog + trailblaze-models onto its lean configuration-phase classpath.
  //      `compileOnly` keeps the bundler's own compile working without imposing a runtime dep on
  //      callers that don't construct the generator.
  //   2. Build-logic excludes this single file from its srcDir to avoid the same runtime-dep
  //      bleedover for build-logic's compileKotlin task. See the matching `kotlin.exclude(...)` in
  //      `build-logic/build.gradle.kts`.
  // The runtime consumer (`:trailblaze-host`) already has `koog-agents-tools` and
  // `:trailblaze-common` (transitive `:trailblaze-models`) in its classpath.
  compileOnly(libs.koog.agents.tools)
  compileOnly(project(":trailblaze-models"))

  testImplementation(kotlin("test"))
  testImplementation(kotlin("test-junit"))
  // Tests construct real `ToolDescriptor` and `PackScriptedToolFile` instances, so the test
  // classpath needs the real deps (not just compileOnly).
  testImplementation(libs.koog.agents.tools)
  testImplementation(project(":trailblaze-models"))
}

tasks.named<Test>("test") {
  useJUnit()
}
