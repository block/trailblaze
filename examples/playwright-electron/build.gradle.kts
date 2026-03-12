plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
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

val sampleAppDir = project.projectDir.resolve("sample-app")

val npmInstallElectron by tasks.registering(Exec::class) {
  description = "Install Electron via npm in the sample-app directory"
  workingDir = sampleAppDir
  commandLine("npm", "install")
  inputs.file(sampleAppDir.resolve("package.json"))
  outputs.dir(sampleAppDir.resolve("node_modules"))
  // Only run when the test task is explicitly in the execution graph (not during `check`)
  onlyIf { gradle.taskGraph.hasTask(tasks.test.get()) }
}

tasks.test {
  useJUnitPlatform()
  dependsOn(npmInstallElectron)
  workingDir = rootProject.projectDir.resolve("opensource")

  // Pass paths to the Electron binary and app directory
  systemProperty(
    "trailblaze.test.electron.binary",
    sampleAppDir.resolve("node_modules/.bin/electron").absolutePath,
  )
  systemProperty(
    "trailblaze.test.electron.app.dir",
    sampleAppDir.absolutePath,
  )

  // Run headless by default — no Electron windows pop up on CI or dev machines
  systemProperty(
    "trailblaze.test.electron.headless",
    System.getProperty("trailblaze.test.electron.headless") ?: "true",
  )

  // Run tests in parallel — each test gets its own Electron instance on a unique port
  systemProperty("junit.jupiter.execution.parallel.enabled", "true")
  systemProperty("junit.jupiter.execution.parallel.mode.default", "concurrent")
}

// Don't run tests as part of "check" — only when explicitly requested via "test"
project.tasks.named("check") { dependsOn.removeIf { it.toString().contains("test") } }
