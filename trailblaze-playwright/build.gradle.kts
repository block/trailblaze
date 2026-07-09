plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
}

dependencies {
  api(project(":trailblaze-common"))
  api(project(":trailblaze-agent"))
  implementation(project(":trailblaze-tracing"))
  // PlaywrightVideoRecordDir lives in trailblaze-capture so the BrowserContext setup
  // can hand off the video output dir and finalize callback to the capture stream
  // without that module having to depend on Playwright APIs.
  implementation(project(":trailblaze-capture"))
  api(libs.playwright)

  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlinx.datetime)
  implementation(libs.koog.agents.tools)
  implementation(libs.skiko)

  testImplementation(libs.kotlin.test.junit4)
  testImplementation(libs.assertk)
}

tasks.test {
  useJUnit()
  // Forwarded into the forked test JVM so `-Dtrailblaze.playwright.runBenchmarks=true` on the
  // Gradle command line actually reaches PlaywrightBoundsEnrichmentBenchmarkTest's Assume gate.
  val runBenchmarks = System.getProperty("trailblaze.playwright.runBenchmarks") ?: "false"
  systemProperty("trailblaze.playwright.runBenchmarks", runBenchmarks)
  // Only stream stdout/stderr when benchmarks are explicitly enabled — the benchmark test's
  // println output is the point of running it, but forcing this on for every plain
  // `./gradlew :trailblaze-playwright:test` run would make normal CI/local logs noisy.
  testLogging.showStandardStreams = runBenchmarks == "true"
}

// Don't run tests as part of "check" — only when explicitly requested via "test"
project.tasks.named("check") { dependsOn.removeIf { it.toString().contains("test") } }