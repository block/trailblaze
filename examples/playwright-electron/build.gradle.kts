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
}

val downloadElectronBinary by tasks.registering(Exec::class) {
  description = "Download the Electron platform binary"
  workingDir = sampleAppDir
  commandLine("sh", "provision-electron.sh")
  dependsOn(npmInstallElectron)
  outputs.upToDateWhen { false }
}

tasks.test {
  useJUnitPlatform()
  dependsOn(downloadElectronBinary)
  // The tests resolve their trails from `user.dir`, so this must be the `opensource` root. Derive
  // it from this module's own path: `rootProject` differs when this build is included from another composite root.
  workingDir = projectDir.parentFile.parentFile

  // Gradle treats `workingDir` as @Internal, so the trails read through it are invisible to the
  // build cache. Without this, editing a trail restores the old result instead of replaying it.
  inputs.dir(workingDir.resolve("trails/playwright-electron"))
    .withPropertyName("evalTrails")
    .withPathSensitivity(PathSensitivity.RELATIVE)

  // The Electron app Trailblaze launches. Named files rather than the directory: `npmInstallElectron`
  // writes `node_modules` into it, and a task's inputs must not contain another task's outputs.
  inputs.files(
    sampleAppDir.resolve("main.js"),
    sampleAppDir.resolve("package.json"),
    // Selects the Electron runtime this eval launches, so a change to it must re-run the tests.
    sampleAppDir.resolve("provision-electron.sh"),
  )
    .withPropertyName("electronApp")
    .withPathSensitivity(PathSensitivity.RELATIVE)

  // `main.js` renders playwright-native's fixture page, so this eval depends on that module's tree.
  inputs.dir(workingDir.resolve("examples/playwright-native/sample-app"))
    .withPropertyName("sharedSampleApp")
    .withPathSensitivity(PathSensitivity.RELATIVE)

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
