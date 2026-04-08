plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.vanniktech.maven.publish)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.dependency.guard)
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

tasks.withType<Tar> {
  duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.withType<Zip> {
  duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

configurations.all {
  // Selenium comes transitively from Maestro and we do not use it.
  exclude(group = "org.seleniumhq.selenium")

  // Playwright driver-bundle (~194 MB) contains pre-packaged Node.js + driver binaries for all
  // platforms. Excluded to reduce uber JAR size; PlaywrightDriverManager downloads the driver
  // for the current platform on first use and caches it at ~/.cache/trailblaze/playwright-driver/.
  exclude(group = "com.microsoft.playwright", module = "driver-bundle")

  // Note: GraalVM JS/Rhino are NOT excluded — Maestro's Orchestra.initJsEngine() eagerly
  // initializes GraalJsEngine which requires org.graalvm.polyglot at runtime.

  // Unused LLM provider clients (~6 MB total including AWS SDK tree) pulled transitively by
  // ai.koog:agents-mcp → prompt-executor-llms-all. Trailblaze only uses Anthropic, Google,
  // OpenAI, OpenRouter, and Ollama — these are declared explicitly in the modules that need them.
  exclude(group = "ai.koog", module = "prompt-executor-bedrock-client")
  exclude(group = "ai.koog", module = "prompt-executor-dashscope-client")
  exclude(group = "ai.koog", module = "prompt-executor-deepseek-client")
  exclude(group = "ai.koog", module = "prompt-executor-mistralai-client")

  // AWS SDK for Bedrock (~5.7 MB) — only transitive dep of the excluded bedrock-client above.
  // No direct AWS SDK usage anywhere in the codebase.
  exclude(group = "aws.sdk.kotlin")
  exclude(group = "aws.smithy.kotlin")

  // Redis/Lettuce (~2.3 MB + deps) — transitive from ai.koog:prompt-cache-redis.
  // Trailblaze does not use Redis-based prompt caching.
  exclude(group = "io.lettuce")
  exclude(group = "redis.clients.authentication")
  exclude(group = "ai.koog", module = "prompt-cache-redis")

  // Reactor (~1.5 MB) — transitive from Lettuce Redis client. No direct usage in codebase.
  // Note: io.micrometer is NOT excluded — maestro-utils MetricsProvider depends on it.
  exclude(group = "io.projectreactor")

  // Apache HTTP Client 5 (~2 MB) — transitive from Koog's ktor-client-apache5.
  // Trailblaze uses ktor-client-okhttp, not Apache.
  exclude(group = "org.apache.httpcomponents.client5")
  exclude(group = "org.apache.httpcomponents.core5")
  exclude(group = "io.ktor", module = "ktor-client-apache5")

  // Note: io.grpc is NOT excluded — Maestro uses gRPC to communicate with its instrumentation
  // APK on Android and the XCTest runner on iOS (via maestro-client).
  // Maestro bundles both grpc-netty and grpc-okhttp. When both are on the classpath,
  // Netty's NameResolverProvider wins ServiceLoader priority and resolves localhost to a
  // DomainSocketAddress, which OkHttp's gRPC transport can't handle (ClassCastException).
  // Maestro's AndroidDriver uses OkHttp transport, so grpc-netty is not needed.
  exclude(group = "io.grpc", module = "grpc-netty")
}

dependencies {
  api(project(":trailblaze-agent"))
  implementation(project(":trailblaze-capture"))

  api(libs.maestro.orchestra)
  api(libs.maestro.client)
  api(libs.maestro.ios)
  api(libs.maestro.web)
  api(libs.maestro.ios.driver)
  api(libs.dadb)
  api(libs.okhttp)
  api(libs.jansi)
  api(libs.picocli)
  api(libs.ktor.client.okhttp)
  api(libs.slf4j.api)

  implementation(project(":trailblaze-common"))
  implementation(project(":trailblaze-revyl"))
  implementation(project(":trailblaze-compose"))
  implementation(libs.compose.ui.test.junit4)
  implementation(project(":trailblaze-playwright"))
  implementation(project(":trailblaze-report"))
  implementation(project(":trailblaze-server"))
  implementation(project(":trailblaze-ui"))

  // Compose dependencies for JVM UI code moved from trailblaze-ui
  implementation(compose.desktop.currentOs)
  implementation(libs.compose.ui)
  implementation(libs.compose.runtime)
  implementation(libs.compose.foundation)
  implementation(libs.compose.material3)
  implementation(libs.compose.ui.tooling)
  implementation(libs.compose.ui.tooling.preview)
  implementation(libs.compose.components.resources)
  implementation(libs.material.icons.extended)
  implementation(libs.multiplatform.markdown.renderer.m3)
  implementation(libs.compose.navigation)

  implementation(libs.ktor.client.logging)
  implementation(libs.koog.prompt.executor.anthropic)
  implementation(libs.koog.prompt.executor.google)
  implementation(libs.koog.prompt.executor.ollama)
  implementation(libs.koog.prompt.executor.openai)
  implementation(libs.koog.prompt.executor.openrouter)
  implementation(libs.koog.agents.tools)
  implementation(libs.mcp.sdk)

  // We're not actually leveraging playwright now, so let's keep it out of the app
  implementation(libs.playwright)

  implementation(libs.differ.jvm)
  implementation(project(":trailblaze-tracing"))
  testImplementation(libs.kotlin.test.junit4)
  testImplementation(libs.assertk)
}

tasks.test {
  useJUnit()
}

tasks.register<Test>("updateSystemPromptBaselines") {
  description = "Regenerate system prompt baseline files. Commit the updated files after running."
  group = "verification"
  testClassesDirs = tasks.test.get().testClassesDirs
  classpath = tasks.test.get().classpath
  useJUnit()
  environment("UPDATE_BASELINES", "true")
  filter {
    includeTestsMatching("*.ComposedSystemPromptBaselineTest")
  }
}

// Generate version.properties file with git version info
val generateVersionProperties by tasks.registering {
  val outputDir = layout.buildDirectory.dir("generated/resources/version")
  outputs.dir(outputDir)

  doLast {
    val dir = outputDir.get().asFile
    dir.mkdirs()
    val propsFile = File(dir, "version.properties")
    val gitTagVersion = rootProject.extra["gitTagVersion"] as String
    val gitVersionFull = rootProject.extra["gitVersionFull"] as String
    // Prefer: 1) semver from git tag, 2) explicit -Pversion from CLI, 3) git timestamp
    val cliVersion = project.version.toString()
    val version = when {
      gitTagVersion.isNotEmpty() -> gitTagVersion
      !cliVersion.endsWith("-SNAPSHOT") && cliVersion != "unspecified" -> cliVersion
      else -> gitVersionFull
    }
    val variant = rootProject.findProperty("trailblaze.variant")?.toString() ?: ""
    val content = buildString {
      appendLine("version=$version")
      if (variant.isNotEmpty()) appendLine("variant=$variant")
    }
    propsFile.writeText(content)
  }
}

// Add generated resources to source sets
sourceSets {
  main {
    resources.srcDir(generateVersionProperties.map { it.outputs.files.singleFile })
  }
}

tasks.named("processResources") {
  dependsOn(generateVersionProperties)
}

dependencyGuard {
  configuration("runtimeClasspath") {
    @Suppress("UNCHECKED_CAST")
    val map = rootProject.extra["trailblazePlatformBaselineMap"] as (String) -> String
    baselineMap = map
  }
}
