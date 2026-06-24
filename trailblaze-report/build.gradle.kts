import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
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
    dependsOn(":trailblaze-ui:wasmJsBrowserProductionWebpack")
  }
  classpath = sourceSets["main"].runtimeClasspath
  mainClass.set("xyz.block.trailblaze.report.ReportMainKt")
  dependsOn(prepareReportTemplateDir)
  args(reportTemplateBuildDir.get().asFile.absolutePath)
  jvmArgs("-Dtrailblaze.rootDir=${rootProject.projectDir.absolutePath}")
  outputs.file(reportTemplateBuildDir.map { it.file("trailblaze_report.html") })
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
