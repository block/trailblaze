plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.dependency.guard)
  alias(libs.plugins.vanniktech.maven.publish)
}

configurations.all {
  exclude(group = "ai.koog", module = "prompt-executor-bedrock-client")
  exclude(group = "ai.koog", module = "prompt-executor-dashscope-client")
  exclude(group = "ai.koog", module = "prompt-executor-deepseek-client")
  exclude(group = "ai.koog", module = "prompt-executor-mistralai-client")
}

dependencies {
  api(libs.ktor.server.core)
  api(libs.ktor.server.netty)

  implementation(project(":trailblaze-models"))
  implementation(project(":trailblaze-common"))
  implementation(project(":trailblaze-capture"))
  implementation(project(":trailblaze-report"))
  implementation(project(":trailblaze-scripting-mcp-common"))
  implementation(project(":trailblaze-scripting-subprocess"))
  // In-process QuickJS launcher for catalog/framework scripted tools (e.g. openUrl). The daemon
  // shares the SAME in-process registration the host path uses (LazyYamlScriptedToolRegistration)
  // rather than synthesizing a subprocess — see InProcessScriptedToolLauncher.
  implementation(project(":trailblaze-quickjs-tools"))
  // OkHttp-backed `fetch` for in-process scripted tools, kept out of the lean engine module — the
  // daemon installs it (localhost-only) so MCP-launched scripted tools can reach a bound `fetch`
  // the same way the host run path does. See `:trailblaze-scripting-fetch`.
  implementation(project(":trailblaze-scripting-fetch"))

  implementation(libs.okhttp)
  implementation(libs.ktor.server.core.jvm)
  implementation(libs.ktor.network.tls.certificates)
  implementation(libs.ktor.client.okhttp)
  implementation(libs.ktor.client.logging)
  implementation(libs.ktor.server.content.negotiation)
  implementation(libs.ktor.server.cors)
  implementation(libs.ktor.server.sse)
  implementation(libs.ktor.server.websockets)
  implementation(libs.ktor.serialization.kotlinx.json)
  implementation(libs.coroutines)
  implementation(libs.koog.agents)
  implementation(libs.koog.agents.tools)
  implementation(libs.koog.agents.mcp)
  implementation(libs.koog.prompt.executor.clients)
  implementation(libs.kotlinx.datetime)
  implementation(libs.ktor.http)
  implementation(libs.ktor.serialization)
  implementation(libs.ktor.utils)
  implementation(libs.kotlinx.serialization.core)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.maestro.orchestra.models) { isTransitive = false }
  implementation(libs.mcp.sdk)

  // Explicit kotlin-reflect dependency to ensure correct version
  implementation(libs.kotlin.reflect)

  runtimeOnly(libs.jackson.module.kotlin)
  runtimeOnly(libs.slf4j.simple)

  testImplementation(libs.ktor.client.okhttp)
  testImplementation(libs.ktor.server.test.host)
  testImplementation(libs.ktor.server.cio)
  testImplementation(libs.ktor.server.content.negotiation)
  testImplementation(libs.ktor.serialization.kotlinx.json)
  testImplementation(libs.kotlin.test.junit4)
  testImplementation(libs.junit)
  testImplementation("io.ktor:ktor-client-cio:${libs.versions.ktor.get()}")

  testRuntimeOnly(libs.kotlin.test.junit4)
}

tasks.test {
  useJUnit()
  exclude("**/integration/**")
  exclude("**/HttpMcpToolExecutorTest*")
}

tasks.register<Test>("integrationTest") {
  description = "Runs integration tests that require a running Trailblaze server and connected devices."
  group = "verification"
  useJUnit()
  testClassesDirs = sourceSets.test.get().output.classesDirs
  classpath = sourceSets.test.get().runtimeClasspath
  include("**/integration/**")
  include("**/HttpMcpToolExecutorTest*")
}

dependencyGuard {
  configuration("runtimeClasspath")
}
