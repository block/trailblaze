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
  // koog-agents-ext (beta stream) is load-bearing through transitive resolution: it pulls in
  // `prompt-executor-llms-all` (Koog's umbrella aggregator) which is the artifact that
  // actually carries `ai.koog.prompt.executor.llms.MultiLLMPromptExecutor`, plus `agents-core`
  // / `prompt-processor` / `rag-base`. Removing it cascades into compile errors in every
  // downstream that uses `MultiLLMPromptExecutor` (BlockDynamicLlmClient,
  // TrailblazeHostDynamicLlmClientProvider, KoogMcpAgent). Keep until a follow-up adds
  // explicit aliases for `prompt-executor-llms-all` + `agents-core` so this dep can be
  // un-load-bearing'd.
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
  // Always rerun: this task writes to source-controlled `.txt` baselines, and the
  // source prompt `.md` is not a declared input — Gradle could otherwise mark the
  // task UP-TO-DATE after a prior run and silently skip the regen, leaving stale
  // baselines on disk.
  outputs.upToDateWhen { false }
}

dependencyGuard {
  configuration("runtimeClasspath") {
    modules = true
  }
}
