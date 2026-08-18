plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  id("trailblaze.bundle")
}

trailblazeBundle {
  trailmapsDir.set(layout.projectDirectory.dir("trails/config/trailmaps"))
  // Disable the per-trailmap `bundleTrailblazeTrailmap` half. The playwright-native
  // trailmap now uses the partial-descriptor authoring shape (each tool YAML carries
  // `name:` + `script:` + `supportedPlatforms:` shortcut only — description /
  // inputSchema / `_meta` gates come from the typed `.ts`'s
  // `trailblaze.tool<I>(spec, handler)` declaration via the runtime analyzer
  // enrichment in `AnalyzerScriptedToolEnrichment`). The standalone bundler
  // library can't run the AST analyzer (deliberate classpath isolation — see the
  // matching comment on `:examples:ios-contacts`'s build.gradle.kts and on
  // `:examples:wikipedia` for the canonical rationale). `compileTrailblazeWorkspace`
  // — the other half of this plugin — already runs analyzer-backed compile and
  // emits the per-trailmap `client.d.ts` the IDE consumes.
  bundleEnabled.set(false)
}

dependencies {
  testImplementation(project(":trailblaze-agent"))
  testImplementation(project(":trailblaze-common"))
  testImplementation(project(":trailblaze-host"))
  testImplementation(project(":trailblaze-models"))
  testImplementation(project(":trailblaze-playwright"))
  testImplementation(project(":trailblaze-report"))
  testImplementation(project(":trailblaze-tracing"))

  testImplementation(libs.koog.prompt.executor.openai)
  testImplementation(libs.koog.prompt.executor.clients)
  testImplementation(libs.koog.prompt.llm)
  testImplementation(libs.ktor.client.core)
  testImplementation(libs.kotlin.test)
  testImplementation(libs.kotlinx.datetime)
  testRuntimeOnly(libs.junit5.jupiter.engine)
}

tasks.test {
  useJUnitPlatform()
  // The tests resolve their trails from `user.dir`, so this must be the `opensource` root. Derive
  // it from this module's own path: `rootProject` differs when this build is included from another composite root.
  workingDir = projectDir.parentFile.parentFile

  // Gradle treats `workingDir` as @Internal, so the trails read through it are invisible to the
  // build cache. Without this, editing a trail restores the old result instead of replaying it.
  inputs.dir(workingDir.resolve("trails/playwright-native"))
    .withPropertyName("evalTrails")
    .withPathSensitivity(PathSensitivity.RELATIVE)

  // The trails navigate to these fixture pages through file:// URLs, so a page edit has to
  // invalidate the cached result the same way a trail edit does.
  inputs.dir(projectDir.resolve("sample-app"))
    .withPropertyName("sampleApp")
    .withPathSensitivity(PathSensitivity.RELATIVE)

  // Run tests in parallel — each test gets its own browser instance
  systemProperty("junit.jupiter.execution.parallel.enabled", "true")
  systemProperty("junit.jupiter.execution.parallel.mode.default", "concurrent")
}

// Don't run tests as part of "check" — only when explicitly requested via "test"
project.tasks.named("check") { dependsOn.removeIf { it.toString().contains("test") } }
