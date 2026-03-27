plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.vanniktech.maven.publish)
  alias(libs.plugins.dependency.guard)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.dagp)
}

dependencies {
  api(project(":trailblaze-common"))
  api(libs.koog.agents.tools)
  api(libs.koog.agents.ext)
  api(libs.koog.prompt.llm)
  api(libs.koog.prompt.model)

  implementation(project(":trailblaze-tracing"))
  implementation(libs.exp4j)
  implementation(libs.coroutines)
  implementation(libs.kotlinx.serialization.core)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.koog.prompt.executor.clients)
  implementation(libs.kotlinx.datetime)

  runtimeOnly(libs.kotlin.reflect)
  testImplementation(libs.kotlin.test)
  testImplementation(libs.assertk)
}

tasks.test {
  useJUnit() // Configure Gradle to use JUnit 4
}

tasks.register<Test>("updateSystemPromptBaselines") {
  description = "Regenerate system prompt baseline files. Commit the updated files after running."
  group = "verification"
  testClassesDirs = tasks.test.get().testClassesDirs
  classpath = tasks.test.get().classpath
  useJUnit()
  environment("UPDATE_BASELINES", "true")
  filter {
    includeTestsMatching("*.SystemPromptBaselineTest")
  }
}

dependencyGuard {
  configuration("runtimeClasspath") {
    modules = true
  }
}
