plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.vanniktech.maven.publish)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.dependency.guard)
  alias(libs.plugins.dagp)
  application
}

application {
  mainClass.set("xyz.block.trailblaze.report.ReportMainKt")
}

tasks.named<JavaExec>("run") {
  // Allow passing custom JVM args via -PappJvmArgs="..." for memory-intensive workloads
  // Example: ./gradlew :trailblaze-report:run -PappJvmArgs="-Xmx20g -XX:MaxMetaspaceSize=1g" --args="./logs"
  if (project.hasProperty("appJvmArgs")) {
    jvmArgs = (project.property("appJvmArgs") as String).split(" ")
  }
}

// Task to generate CI test results artifact
// Usage: ./gradlew :trailblaze-report:generateTestResultsArtifacts --args="./logs --output results.json"
tasks.register<JavaExec>("generateTestResultsArtifacts") {
  group = "application"
  description = "Generate CI test results artifact from logs directory"
  classpath = sourceSets["main"].runtimeClasspath
  mainClass.set("xyz.block.trailblaze.report.GenerateTestResultsCliCommandKt")
}

// Task to generate the run index — the report's device matrix over one or more test_report.json
// files, with each cell linking out to that run's own report. See RunIndexGenerator for why CI
// needs this alongside the embedded report.
// Usage: ./gradlew :trailblaze-report:generateRunIndex --args="a/test_report.json b/test_report.json --viewer-base-url <url> --output index.html"
tasks.register<JavaExec>("generateRunIndex") {
  group = "application"
  description = "Generate the run index HTML from one or more Trailblaze test_report.json files"
  classpath = sourceSets["main"].runtimeClasspath
  mainClass.set("xyz.block.trailblaze.report.GenerateRunIndexCliCommandKt")
}

// Bundle the interactive run-report renderer from its TypeScript modules into the single plain-JS
// resource its consumers load: the Trail Runner web app (in :trailblaze-host, which depends on
// this module) fetches it as a classic browser <script>, and RunReportGenerator copies it beside
// the bun driver. `bun build --format=iife` bundles the whole module graph (entry
// run-report-core.ts) into one self-executing classic script; the entry assigns the export surface
// onto `window` for the browser, and the `--footer` below restores the CommonJS surface for the
// bun driver's require() (bun's bundler captures `module` inside the IIFE, so the export hop rides
// on the __TRAILBLAZE_RUN_REPORT_CORE__ global the entry publishes). The viewer script embedded
// into exported report HTML is itself prebuilt during this bundle via the bun macro in
// run-report-viewer-bundle.macro.ts. workingDir is pinned to the source dir so the bundler's
// module-path comments stay relative and the artifact is byte-identical across machines. bun is a
// hard build prerequisite repo-wide, same as the SDK bundlers.
val bundleRunReportCore by tasks.registering(Exec::class) {
  group = "trailblaze"
  description = "Bundles the run-report-*.ts modules into the run-report-core.js JAR resource (bun build --format=iife)."
  val srcDir = layout.projectDirectory.dir("src/main/resources/xyz/block/trailblaze/trailrunner/web/app")
  val out = layout.buildDirectory.file(
    "generated-resources/run-report/xyz/block/trailblaze/trailrunner/web/app/run-report-core.js",
  )
  // Includes *.js, not just *.ts: this directory also holds hand-written classic scripts
  // (zip-report-core.js) that a bun macro reads at transpile time, so a .js-only edit must still
  // invalidate the task — otherwise a stale copy ships until the next clean build.
  inputs.files(fileTree(srcDir) { include("*.ts", "*.js") }.filter { !it.name.endsWith(".test.ts") })
  // Out-of-directory modules the bundle inlines (run-report-selectors.ts imports the selector
  // engine's typed wrapper, which types itself off the generated selectors bindings) — declared so
  // an edit there re-bundles instead of shipping a stale viewer until the next clean build.
  // fileTrees, not named files: `inputs.files` contributes nothing for a path that doesn't exist,
  // so naming the two .ts files directly would silently stop covering anything the day either is
  // renamed — reintroducing the stale-viewer bug with no signal. A tree over each source dir keeps
  // covering it (and any sibling module the wrapper starts importing).
  inputs.files(
    fileTree(layout.projectDirectory.dir("../trailblaze-selector-engine-js/src/typescript")) { include("**/*.ts") },
    fileTree(layout.projectDirectory.dir("../sdks/typescript/src/generated")) { include("**/*.ts") },
  ).withPropertyName("selectorEngineWrapperSources")
  // The report CLI sources, which the bundle reaches OUT of srcDir for: run-report-core.ts imports
  // the attachment/event-stream helpers from ../report/run-report-events.ts (shared with the bun
  // driver). Same undeclared-input hazard bakeViewerShell documents for its reportCliSources.
  inputs.files(
    fileTree(layout.projectDirectory.dir("src/main/resources/xyz/block/trailblaze/report")) {
      include("**/*.ts")
      exclude("**/*.test.ts")
    },
  ).withPropertyName("reportCliSources")
  outputs.file(out)
  workingDir(srcDir)
  commandLine(
    "bun", "build", "run-report-core.ts",
    "--format=iife",
    "--target=browser",
    // CommonJS surface for bun consumers (run-report-cli.ts): bun's bundler captures `module`
    // inside the IIFE, so the exports hop through the global the entry module publishes. A no-op
    // in classic browser scripts.
    "--footer",
    "// (--footer from :trailblaze-report bundleRunReportCore) CommonJS surface for bun consumers.\n" +
      "if (typeof module !== 'undefined' && module.exports) module.exports = globalThis.__TRAILBLAZE_RUN_REPORT_CORE__;",
    "--outfile", out.get().asFile.absolutePath,
  )
}

// Same pattern for the performance-analysis report (the perf-*.ts sibling module graph): one
// bundled perf-core.js resource that PerformanceAnalysisGenerator copies beside its bun driver
// (perf-report-cli.ts). See bundleRunReportCore above for the --footer / determinism rationale.
val bundlePerfReportCore by tasks.registering(Exec::class) {
  group = "trailblaze"
  description = "Bundles the perf-*.ts modules into the perf-core.js JAR resource (bun build --format=iife)."
  val srcDir = layout.projectDirectory.dir("src/main/resources/xyz/block/trailblaze/trailrunner/web/app")
  val out = layout.buildDirectory.file(
    "generated-resources/perf-report/xyz/block/trailblaze/trailrunner/web/app/perf-core.js",
  )
  inputs.files(fileTree(srcDir) { include("*.ts", "*.js") }.filter { !it.name.endsWith(".test.ts") })
  outputs.file(out)
  workingDir(srcDir)
  commandLine(
    "bun", "build", "perf-core.ts",
    "--format=iife",
    "--target=browser",
    "--footer",
    "// (--footer from :trailblaze-report bundlePerfReportCore) CommonJS surface for bun consumers.\n" +
      "if (typeof module !== 'undefined' && module.exports) module.exports = globalThis.__TRAILBLAZE_PERF_REPORT_CORE__;",
    "--outfile", out.get().asFile.absolutePath,
  )
}

// Bake the standalone report viewer (the data-less shell `build-viewer-shell.sh` produces) into
// this module's JAR resources. The shell can only be BUILT here — bun macros over this source tree
// plus the Gradle-built selector engine — but it is NEEDED wherever the CLI runs: publishing it to
// a CDN, self-hosting it, opening a `?zip=` link. Building it into the JAR at build time means a
// distributed `trailblaze` binary emits it as a plain resource copy — no bun, no Gradle, no source
// checkout at the consumption site — and the emitted viewer always matches the renderer that
// binary generates reports with, because they shipped together.
val bakeViewerShell by tasks.registering(Exec::class) {
  group = "trailblaze"
  description = "Builds the standalone report viewer shell into this module's JAR resources (bun run viewer-shell-cli.ts)."
  val srcDir = layout.projectDirectory.dir("src/main/resources/xyz/block/trailblaze/trailrunner/web/app")
  val out = layout.buildDirectory.file(
    "generated-resources/viewer-shell/xyz/block/trailblaze/report/report-viewer.html",
  )
  // Same input surface as bundleRunReportCore above (the shell embeds the same viewer bundle via
  // the run-report-html macros), plus the selector engine bundle the shell's own macro packs.
  inputs.files(fileTree(srcDir) { include("*.ts", "*.js") }.filter { !it.name.endsWith(".test.ts") })
  inputs.files(
    fileTree(layout.projectDirectory.dir("../trailblaze-selector-engine-js/src/typescript")) { include("**/*.ts") },
    fileTree(layout.projectDirectory.dir("../sdks/typescript/src/generated")) { include("**/*.ts") },
  ).withPropertyName("selectorEngineWrapperSources")
  // The report CLI source, which the shell reaches OUT of srcDir for: run-report-shell-html.ts
  // pulls in selector-engine-bundle.macro.ts, and that macro imports `packSelectorEngine` from
  // ../../../report/run-report-cli.ts. Undeclared, an edit to how the engine is packed leaves this
  // task UP-TO-DATE and the JAR keeps a viewer built against the old packing — the exact drift
  // baking the shell in is meant to make impossible. A tree, not the named file, for the reason
  // spelled out on bundleRunReportCore's wrapper sources: `inputs.files` contributes nothing for a
  // path that doesn't exist, so naming it would silently stop covering anything the day it moves.
  inputs.files(
    fileTree(layout.projectDirectory.dir("src/main/resources/xyz/block/trailblaze/report")) {
      include("**/*.ts")
      exclude("**/*.test.ts")
    },
  ).withPropertyName("reportCliSources")
  // The built engine bundle, as a FileCollection off the producing task so this carries the task
  // dependency without hardcoding that module's output path (the copySelectorEngineResource
  // precedent below). Passed to the macro explicitly rather than relying on its repo-relative
  // default, which is exactly the kind of implicit path a module relocation would silently break.
  val engineBundle = files(project(":trailblaze-selector-engine-js").tasks.named("bundleSelectorEngine"))
  inputs.files(engineBundle).withPropertyName("selectorEngineBundle")
  outputs.file(out)
  workingDir(srcDir)
  commandLine("bun", "run", "./viewer-shell-cli.ts", out.get().asFile.absolutePath)
  doFirst {
    out.get().asFile.parentFile.mkdirs()
    environment("TRAILBLAZE_SELECTOR_ENGINE_BUNDLE", engineBundle.singleFile.absolutePath)
  }
  // The guards build-viewer-shell.sh applies, because every failure they catch is silent in a
  // browser: a truncated document, quirks mode from a stray pre-doctype write, or — the one only a
  // packager can cause — a shell missing the selector engine, whose Inspector then never shows a
  // suggestion. The engine is a task dependency here so its absence is a wiring bug, not an
  // environment limitation, and a JAR must never carry the degraded shell.
  doLast {
    val html = out.get().asFile.readText()
    check(html.startsWith("<!doctype html>")) { "viewer shell does not begin with the doctype" }
    check(html.contains("data-tb-shell")) { "generated file is not a viewer shell (no data-tb-shell marker)" }
    check(html.contains("id=\"tb-selector-engine\"")) { "viewer shell is missing the selector engine bundle" }
    check(html.trimEnd().endsWith("</html>")) { "viewer shell is truncated (no closing </html>)" }
  }
}

// Stage the Kotlin/JS selector engine bundle (the daemon's selector generator/resolver compiled to
// JS by :trailblaze-selector-engine-js — see that module's build file) into this module's JAR
// resources, where RunReportGenerator stages it beside the bun driver so the report can embed it
// for the UI Inspector's selector suggestions. Same consume-a-generator-task pattern as
// :trailblaze-models's copyTypescriptSdkResources → bundleTrailblazeSdkDts: the bundle is a build
// artifact (never committed), regenerated whenever its Kotlin sources change and UP-TO-DATE-skipped
// otherwise. `bundleSelectorEngine` skips cleanly when `bun` isn't on PATH; the Copy then stages
// nothing and RunReportGenerator degrades to reports without suggestions.
//
// `from(<task provider>)` carries the bundle's declared OUTPUT plus its implicit task dependency, so
// there's no `dependsOn` and no hardcoded `dist/…` path to drift when that module relocates its
// output.
val copySelectorEngineResource by tasks.registering(Copy::class) {
  group = "trailblaze"
  description = "Stages the Kotlin/JS selector engine bundle into build/ for inclusion in this module's JAR resources."
  from(project(":trailblaze-selector-engine-js").tasks.named("bundleSelectorEngine"))
  into(layout.buildDirectory.dir("generated-resources/selector-engine/xyz/block/trailblaze/report"))
}

sourceSets {
  main {
    resources.srcDir(
      bundleRunReportCore.map { layout.buildDirectory.dir("generated-resources/run-report").get() },
    )
    resources.srcDir(
      bundlePerfReportCore.map { layout.buildDirectory.dir("generated-resources/perf-report").get() },
    )
    resources.srcDir(
      copySelectorEngineResource.map { layout.buildDirectory.dir("generated-resources/selector-engine").get() },
    )
    resources.srcDir(
      bakeViewerShell.map { layout.buildDirectory.dir("generated-resources/viewer-shell").get() },
    )
  }
}

tasks.named<org.gradle.language.jvm.tasks.ProcessResources>("processResources") {
  dependsOn(bundleRunReportCore)
  dependsOn(bundlePerfReportCore)
  dependsOn(copySelectorEngineResource)
  dependsOn(bakeViewerShell)
  // The bun test co-located with the run-report modules (run-report-core.test.ts) lives under
  // resources so it can import them directly; keep it out of the packaged JAR.
  // Same for the cross-language parity fixture the tests share with the Kotlin suite.
  exclude("**/*.test.ts")
  exclude("**/session-events-parity-fixtures.json")
  exclude("**/sprite-metadata-parity-fixtures.json")
  exclude("**/web-hierarchy-merge-fixtures.json")
  // TypeScript module sources + ambient types + tsconfig for the run-report renderer: the packaged
  // artifact is the bundled run-report-core.js from `bundleRunReportCore` above (the bun
  // driver run-report-cli.ts IS packaged — bun executes TS natively).
  exclude("**/trailrunner/web/app/*.ts")
  exclude("**/xyz/block/trailblaze/tsconfig.json")
}

dependencies {
  implementation(project(":trailblaze-capture"))
  implementation(project(":trailblaze-common"))
  implementation(project(":trailblaze-models"))
  implementation(project(":trailblaze-tracing"))
  implementation(libs.kotlinx.datetime)
  // OpenTelemetry's own exporters, rather than a hand-written OTLP encoder: the wire format has
  // enough traps (hex-string ids, uint64 timestamps as strings) that maintaining it ourselves buys
  // nothing, and this way the gRPC endpoint works too.
  api(platform(libs.opentelemetry.bom))
  api(libs.opentelemetry.sdk)
  implementation(libs.opentelemetry.exporter.otlp)
  implementation(libs.opentelemetry.exporter.logging.otlp)
  implementation(libs.clikt)
  implementation(libs.maestro.orchestra.models) { isTransitive = false }
  implementation(libs.kotlinx.serialization.core)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kaml)

  runtimeOnly(libs.slf4j.simple)

  testImplementation(libs.kotlin.test.junit4)
}

tasks.test {
  useJUnit()
}

dependencyGuard {
  configuration("runtimeClasspath") {
    modules = true
  }
}
