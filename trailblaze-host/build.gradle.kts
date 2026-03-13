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
  implementation(project(":trailblaze-compose"))
  @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
  implementation(compose.uiTest)
  implementation(project(":trailblaze-playwright"))
  implementation(project(":trailblaze-report"))
  implementation(project(":trailblaze-server"))
  implementation(project(":trailblaze-ui"))

  // Compose dependencies for JVM UI code moved from trailblaze-ui
  implementation(compose.desktop.currentOs)
  implementation(compose.ui)
  implementation(compose.runtime)
  implementation(compose.foundation)
  implementation(compose.material3)
  implementation(compose.uiTooling)
  implementation(compose.preview)
  implementation(compose.components.resources)
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
    baselineMap = rootProject.extra["trailblazePlatformBaselineMap"] as (String) -> String
  }
}
