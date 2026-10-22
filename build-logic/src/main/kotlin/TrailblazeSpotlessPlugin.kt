import com.diffplug.gradle.spotless.SpotlessExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

class TrailblazeSpotlessPlugin : Plugin<Project> {
  override fun apply(project: Project) {
    project.plugins.apply("com.diffplug.spotless")

    val spotless = project.extensions.getByType(SpotlessExtension::class.java)
    spotless.kotlin { kotlin ->
      kotlin.target("**/*.kt", "**/*.kts")
      // Exclude with a file tree, never patterns: Spotless walks each pattern as its own tree of
      // the whole project dir, build/ included, and a file another task deletes mid-walk fails
      // this task. This tree skips build dirs as it walks; the target already leaves them out.
      // Keep the exclude: subtracting it stops Gradle reading the project dir as this task's
      // input, which overlaps dependencyGuard's output and fails task validation.
      kotlin.targetExclude(
        project.fileTree(project.projectDir) { tree ->
          tree.include("**/dependencies/*.txt")
          tree.exclude("**/build", "**/.gradle", ".git")
        }
      )
      kotlin.ktfmt().googleStyle() // 2-space indent to match IntelliJ

      // Use Console.log() / Console.error() instead of println().
      // Console routes output correctly on each platform (stdout/stderr on JVM,
      // Logcat on Android, console.log on Wasm) and can be redirected to stderr
      // for STDIO MCP transport mode.
      //
      // Opt out per-file with: // suppress:no-println
      kotlin.custom("no-println") { text ->
        if (text.contains("actual object Console") ||
            text.contains("@Test") ||
            text.contains("import org.junit") ||
            text.contains("// suppress:no-println")
        ) {
          return@custom text
        }
        val bannedPattern = Regex("""(?<!\.)(?<!\w)println\(|System\.out\.println\(|System\.err\.println\(""")
        for ((index, line) in text.lines().withIndex()) {
          val trimmed = line.trim()
          if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) continue
          if (bannedPattern.containsMatchIn(trimmed)) {
            throw AssertionError(
              "Line ${index + 1}: Use Console.log() instead of println(). " +
                "See: import xyz.block.trailblaze.util.Console"
            )
          }
        }
        text
      }
    }
  }
}
