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

  testImplementation(kotlin("test"))
  testImplementation(kotlin("test-junit"))
}

tasks.named<Test>("test") {
  useJUnit()
}
