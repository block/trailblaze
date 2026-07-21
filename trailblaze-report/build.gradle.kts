import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

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

abstract class PrepareReportTemplateDirTask : DefaultTask() {
  @get:Input abstract val wasmEnabled: org.gradle.api.provider.Property<Boolean>

  @get:OutputDirectory abstract val templateBuildDir: DirectoryProperty

  @TaskAction
  fun prepare() {
    if (!wasmEnabled.get()) {
      throw GradleException(
        "generateReportTemplate requires WASM targets.\n" +
          "Run with: ./gradlew :trailblaze-report:generateReportTemplate -Ptrailblaze.wasm=true"
      )
    }
    val outputDir = templateBuildDir.get().asFile
    if (!outputDir.mkdirs() && !outputDir.isDirectory) {
      throw GradleException("Could not create report template output directory ${outputDir.absolutePath}")
    }
  }
}

val reportWasmEnabled = providers.gradleProperty("trailblaze.wasm").map(String::toBoolean).orElse(true)
val reportTemplateBuildDir = layout.buildDirectory.dir("report-template")

val prepareReportTemplateDir by tasks.registering(PrepareReportTemplateDirTask::class) {
  wasmEnabled.set(reportWasmEnabled)
  templateBuildDir.set(reportTemplateBuildDir)
}

val generateReportTemplate by tasks.registering(JavaExec::class) {
  description = "Generates a blank report template HTML with embedded WASM UI (requires -Ptrailblaze.wasm=true)"
  group = "report"
  if (reportWasmEnabled.get()) {
    // Register the webpack distribution (the embedded JS + WASM bundle) as an INPUT, not just a
    // `dependsOn`. A bare `dependsOn` orders the tasks but does NOT tie this task's up-to-date
    // state to the bundle's contents — so editing the Compose report UI would re-run the webpack
    // build yet leave `generateReportTemplate` UP-TO-DATE, embedding a stale WASM bundle in the
    // generated template. Declaring the output as an input makes Gradle re-run us whenever the
    // bundle changes (and correctly stay UP-TO-DATE when it doesn't).
    //
    // Register the webpack task's OUTPUT FILES (resolved lazily) rather than the task object: a
    // `KotlinWebpack` task isn't serializable by the configuration cache, so passing the task
    // provider straight to `inputs.files(...)` fails the build with `cannot serialize object of
    // type 'org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpack'`. Mapping to
    // `outputs.files` stores only the file collection in the cache and keeps the implicit task
    // dependency, which `dependsOn` also pins explicitly.
    val webpackTask = project(":trailblaze-ui").tasks.named("wasmJsBrowserProductionWebpack")
    dependsOn(webpackTask)
    inputs.files(webpackTask.map { it.outputs.files })
      .withPropertyName("wasmDist")
      .withPathSensitivity(PathSensitivity.RELATIVE)
  }
  classpath = sourceSets["main"].runtimeClasspath
  mainClass.set("xyz.block.trailblaze.report.ReportMainKt")
  dependsOn(prepareReportTemplateDir)
  args(reportTemplateBuildDir.get().asFile.absolutePath)
  jvmArgs("-Dtrailblaze.rootDir=${rootProject.projectDir.absolutePath}")
  outputs.file(reportTemplateBuildDir.map { it.file("trailblaze_report.html") })
}

// Transpile the interactive run-report renderer from its TypeScript source into the plain-JS
// resource its consumers load: the Trail Runner web app (in :trailblaze-host, which depends on
// this module) fetches it as a classic browser <script>, and RunReportGenerator copies it beside
// the bun driver. `bun build --no-bundle` is a type-strip only pass (no bundling, no syntax
// lowering), so the emitted file keeps the classic-script + guarded-CommonJS shape the source is
// written in — including the serialized RUN_REPORT_VIEWER function. bun is a hard build
// prerequisite repo-wide (see AGENTS.md), same as the SDK bundlers.
val transpileRunReportCore by tasks.registering(Exec::class) {
  group = "trailblaze"
  description = "Transpiles run-report-core.ts into the run-report-core.js JAR resource (bun build --no-bundle)."
  val src = layout.projectDirectory.file(
    "src/main/resources/xyz/block/trailblaze/trailrunner/web/app/run-report-core.ts",
  )
  val out = layout.buildDirectory.file(
    "generated-resources/run-report/xyz/block/trailblaze/trailrunner/web/app/run-report-core.js",
  )
  inputs.file(src)
  outputs.file(out)
  commandLine(
    "bun", "build", src.asFile.absolutePath,
    "--no-bundle",
    "--outfile", out.get().asFile.absolutePath,
  )
}

sourceSets {
  main {
    resources.srcDir(
      transpileRunReportCore.map { layout.buildDirectory.dir("generated-resources/run-report").get() },
    )
  }
}

tasks.named<org.gradle.language.jvm.tasks.ProcessResources>("processResources") {
  dependsOn(transpileRunReportCore)
  // The bun test co-located with run-report-core.ts (run-report-core.test.ts) lives under
  // resources so it can `require("./run-report-core.ts")`; keep it out of the packaged JAR.
  // Same for the cross-language parity fixture the tests share with the Kotlin suite.
  exclude("**/*.test.ts")
  exclude("**/session-events-parity-fixtures.json")
  // TypeScript source + ambient types + tsconfig for the run-report renderer: the packaged
  // artifact is the transpiled run-report-core.js from `transpileRunReportCore` above (the bun
  // driver run-report-cli.ts IS packaged — bun executes TS natively).
  exclude("**/trailrunner/web/app/run-report-core.ts")
  exclude("**/trailrunner/web/app/run-report-types.d.ts")
  exclude("**/xyz/block/trailblaze/tsconfig.json")
}

dependencies {
  implementation(project(":trailblaze-capture"))
  implementation(project(":trailblaze-common"))
  implementation(project(":trailblaze-models"))
  implementation(libs.kotlinx.datetime)
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
