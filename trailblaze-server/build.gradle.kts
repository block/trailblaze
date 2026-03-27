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
  api(libs.ktor.server.netty) {
    exclude(group = "io.netty", module = "netty-codec-http2")
    because("Maestro has binary incompatible code with the new version of netty-codec-http2, so we use the old version from maestro")
  }
  api("io.netty:netty-codec-http2:4.1.79.Final") {
    because("Maestro has binary incompatible code with the new version of netty-codec-http2, so we use the old version from maestro")
  }

  implementation(project(":trailblaze-models"))
  implementation(project(":trailblaze-common"))
  implementation(project(":trailblaze-report"))

  implementation(libs.okhttp)
  implementation(libs.ktor.server.core.jvm)
  implementation(libs.ktor.network.tls.certificates)
  implementation(libs.ktor.client.okhttp)
  implementation(libs.ktor.client.logging)
  implementation(libs.ktor.server.content.negotiation)
  implementation(libs.ktor.server.cors)
  implementation(libs.ktor.server.sse)
  implementation(libs.ktor.serialization.kotlinx.json)
  implementation(libs.coroutines)
  implementation(libs.koog.agents)
  implementation(libs.koog.agents.tools)
  implementation(libs.koog.agents.mcp)
  implementation(libs.koog.prompt.executor.clients)
  implementation(libs.koog.prompt.executor.llms.all)
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
