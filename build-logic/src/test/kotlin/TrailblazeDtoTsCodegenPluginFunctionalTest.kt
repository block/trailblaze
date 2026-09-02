import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner

/**
 * Plugin-level contract of `trailblaze.dto-ts-codegen`: the shape that makes `check` a real
 * TypeScript freshness gate, and that a module can export MORE THAN ONE binding without the two
 * interfering.
 *
 * Both failure modes these cover are silent rather than loud, which is why they need a fixture:
 *  - An EMPTY container leaves `verifyDtoTs` with no dependencies, so `check` passes having verified
 *    nothing — the exact outcome of a `bindings.register` block dropped or mis-nested in an edit.
 *  - A SHARED fresh-codegen file makes each verify diff against the other binding's output, so a
 *    stale committed `.ts` can pass while a fresh one fails.
 *
 * Nothing here runs a real generator. `JavaExec` needs compiled `@Serializable` classes on a
 * classpath, so the codegen itself is verified where those exist — the consuming modules'
 * `verifyDtoTs<Name>` tasks, which byte-diff a fresh codegen against the committed `.ts` on every
 * `check`.
 */
class TrailblazeDtoTsCodegenPluginFunctionalTest {

  private val tempDirs = mutableListOf<File>()

  @AfterTest
  fun cleanupTempDirs() {
    tempDirs.forEach { it.deleteRecursively() }
    tempDirs.clear()
  }

  @Test
  fun `applying the plugin without registering a binding fails configuration`() {
    val projectDir = newFixtureProject(
      buildScript = """
        plugins {
          base
          id("trailblaze.dto-ts-codegen")
        }
      """.trimIndent(),
    )

    val result = runner(projectDir, "tasks", "--stacktrace").buildAndFail()

    assertTrue(
      result.output.contains("registers no bindings"),
      "the failure must say what is wrong, not just that configuration failed: ${result.output}",
    )
    assertTrue(
      result.output.contains("bindings.register"),
      "and must name the fix, since the likely cause is a mis-nested register block: ${result.output}",
    )
  }

  @Test
  fun `each binding gets its own generate and verify task`() {
    val result = runner(newTwoBindingProject(), "tasks", "--all").build()
    listOf(
      "generateDtoTsAlpha",
      "verifyDtoTsAlpha",
      "generateDtoTsBeta",
      "verifyDtoTsBeta",
    ).forEach { task ->
      assertTrue("expected $task in the task list:\n${result.output}") {
        result.output.contains(task)
      }
    }
  }

  @Test
  fun `the aggregate tasks cover every binding`() {
    // `generateDtoTs` / `verifyDtoTs` are the names a developer types and every generated file
    // header names. They became aggregates when a module could own more than one binding, so
    // regenerating a module has to still mean regenerating everything it exports — an aggregate that
    // reached only the first binding would leave the second silently stale.
    val projectDir = newTwoBindingProject()
    val result = runner(projectDir, "generateDtoTs", "--dry-run").build()
    assertTrue("generateDtoTs must reach both bindings:\n${result.output}") {
      result.output.contains(":generateDtoTsAlpha") && result.output.contains(":generateDtoTsBeta")
    }
  }

  @Test
  fun `check runs every binding's verify`() {
    // The freshness gate is only a gate if `check` reaches it. A binding missing from this graph
    // would let its committed `.ts` drift from the models with the build still green.
    val result = runner(newTwoBindingProject(), "check", "--dry-run").build()
    assertTrue("check must reach both verifies:\n${result.output}") {
      result.output.contains(":verifyDtoTsAlpha") && result.output.contains(":verifyDtoTsBeta")
    }
  }

  @Test
  fun `two bindings in one module do not share a fresh-codegen file`() {
    val projectDir = newTwoBindingProject(
      extraBuildScript = """
        // `outputs.files` is the task's declared output — the fresh-codegen file the verify task
        // writes before diffing.
        val alpha = tasks.named("verifyDtoTsAlpha")
        val beta = tasks.named("verifyDtoTsBeta")
        tasks.register("printFreshFiles") {
          doLast {
            println("FRESH_ALPHA=" + alpha.get().outputs.files.singleFile.name)
            println("FRESH_BETA=" + beta.get().outputs.files.singleFile.name)
          }
        }
      """.trimIndent(),
    )
    val output = runner(projectDir, "printFreshFiles").build().output
    val alpha = output.lineSequence().first { it.startsWith("FRESH_ALPHA=") }.substringAfter('=')
    val beta = output.lineSequence().first { it.startsWith("FRESH_BETA=") }.substringAfter('=')
    assertTrue("both bindings wrote their fresh codegen to '$alpha':\n$output") { alpha != beta }
  }

  // ---- Fixtures ----

  /**
   * Two bindings whose `mainClass` and `codegenClasspath` are never invoked — every test here reads
   * task wiring, and a real generator would need compiled `@Serializable` classes. `generatedTsFile`
   * points inside the fixture so nothing touches the repo.
   */
  private fun newTwoBindingProject(extraBuildScript: String = ""): File =
    newFixtureProject(
      buildScript = """
        plugins {
          base
          id("trailblaze.dto-ts-codegen")
        }
        trailblazeDtoTsCodegen {
          bindings {
            register("alpha") {
              mainClass.set("com.example.AlphaKt")
              generatedTsFile.set(layout.projectDirectory.file("out/alpha.ts"))
            }
            register("beta") {
              mainClass.set("com.example.BetaKt")
              generatedTsFile.set(layout.projectDirectory.file("out/beta.ts"))
            }
          }
        }
        $extraBuildScript
      """.trimIndent(),
    )

  private fun newFixtureProject(buildScript: String): File {
    val dir = createTempDirectory("trailblaze-dto-ts-codegen-functional").toFile().also(tempDirs::add)
    File(dir, "settings.gradle.kts").writeText("""rootProject.name = "fixture"""")
    File(dir, "build.gradle.kts").writeText(buildScript)
    return dir
  }

  private fun runner(projectDir: File, vararg args: String): GradleRunner =
    GradleRunner.create()
      .withProjectDir(projectDir)
      .withArguments(*args)
      .withPluginClasspath()
      .forwardOutput()
}
