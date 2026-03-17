import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.jetbrains.compose.multiplatform)
}

kotlin {
  compilerOptions {
    freeCompilerArgs.addAll(
      "-opt-in=androidx.compose.ui.test.ExperimentalTestApi",
    )
  }
}

dependencies {
  testImplementation(project(":trailblaze-agent"))
  testImplementation(project(":trailblaze-common"))
  testImplementation(project(":trailblaze-compose"))
  testImplementation(project(":trailblaze-host"))
  testImplementation(project(":trailblaze-models"))
  testImplementation(project(":trailblaze-report"))
  testImplementation(project(":trailblaze-tracing"))

  testImplementation(compose.desktop.currentOs)
  testImplementation(libs.compose.ui.test.junit4)

  testImplementation(libs.koog.prompt.executor.openai)
  testImplementation(libs.koog.prompt.executor.clients)
  testImplementation(libs.koog.prompt.llm)
  testImplementation(libs.ktor.client.core)
  testImplementation(libs.ktor.client.okhttp)
  testImplementation(libs.ktor.client.content.negotiation)
  testImplementation(libs.ktor.serialization.kotlinx.json)
  testImplementation(libs.kotlin.test)
  testImplementation(libs.kotlin.test.junit4)
  testImplementation(libs.kotlinx.datetime)
  testImplementation(libs.assertk)
}

// Exclude JUnit 5 to avoid classpath conflicts: Compose UI test (runComposeUiTest) requires
// JUnit 4, and having both on the classpath causes runner selection issues.
configurations.testImplementation {
  exclude(group = "org.jetbrains.kotlin", module = "kotlin-test-junit5")
}

tasks.test {
  useJUnit()
  workingDir = rootProject.projectDir.resolve("opensource")
}

// Don't run tests as part of "check" — only when explicitly requested via "test"
project.tasks.named("check") { dependsOn.removeIf { it.toString().contains("test") } }
val testSourceSet: SourceSet = the<SourceSetContainer>()["test"]
tasks.register<JavaExec>("runSampleTodoRpcServer") {
  group = "application"
  description = "Runs SampleTodoApp with Compose RPC server enabled on the default port."
  dependsOn(tasks.named("testClasses"))
  classpath = testSourceSet.runtimeClasspath
  mainClass.set("xyz.block.trailblaze.compose.driver.SampleRpcServerMainKt")
  args("todo")
}
tasks.register<JavaExec>("runSampleShowcaseRpcServer") {
  group = "application"
  description = "Runs SampleWidgetShowcase with Compose RPC server enabled on the default port."
  dependsOn(tasks.named("testClasses"))
  classpath = testSourceSet.runtimeClasspath
  mainClass.set("xyz.block.trailblaze.compose.driver.SampleRpcServerMainKt")
  args("showcase")
}
