plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.vanniktech.maven.publish)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.dependency.guard)
  alias(libs.plugins.dagp)
  application
}

application {
  mainClass.set("xyz.block.trailblaze.report.ReportMainKt")
}

tasks.named<JavaExec>("run") {
  // Allow passing custom JVM args via -PappJvmArgs="..." for memory-intensive workloads
  // Example: ./gradlew :trailblaze-report:run -PappJvmArgs="-Xmx20g -XX:MaxMetaspaceSize=1g" --args="./logs"
  if (project.hasProperty("appJvmArgs")) {
    jvmArgs = (project.property("appJvmArgs") as String).split(" ")
  }
}

dependencies {
  implementation(project(":trailblaze-common"))
  implementation(project(":trailblaze-models"))
  implementation(libs.kotlinx.datetime)
  implementation(libs.maestro.orchestra.models) { isTransitive = false }
  implementation(libs.kotlinx.serialization.core)
  implementation(libs.kotlinx.serialization.json)

  runtimeOnly(libs.slf4j.simple)
}

tasks.test {
  useJUnit()
}

dependencyGuard {
  configuration("runtimeClasspath") {
    modules = true
  }
}
