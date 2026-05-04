import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Wires up the per-pack TypeScript bindings generator (Tier 2 of the typesafe-tools work).
 *
 * The plugin registers one task — [BundleTrailblazePackTask] — that walks a
 * pack root, reads each runnable pack's `target.tools:` list, and emits a single
 * `<toolsDir>/.trailblaze/tools.d.ts` file that augments `TrailblazeToolMap` (from
 * `@trailblaze/scripting`) with one entry per scripted tool. Authors get autocomplete on
 * `client.tools.<toolName>(...)` call sites and on the args object the IDE shows at hover.
 *
 * **Where the file lands:** under the per-pack tools directory configured in the extension,
 * specifically `[toolsDir]/.trailblaze/tools.d.ts`. The per-pack `tsconfig.json` (set up by
 * the Tier-1 IDE-setup PR) needs to opt the dotfile-prefixed dir back into its include
 * glob — TypeScript's default recursive expansion treats `.trailblaze/` as hidden and
 * skips it. Adopting packs add a literal `.trailblaze` recursive include alongside the
 * existing `.ts` / `.js` glob entries; with that addition, both `tsc` and the IDE pick up
 * the generated bindings automatically. Keeping the dot prefix (rather than renaming to a
 * non-hidden dir) preserves the "sorted to top, signals tooling output" property.
 *
 * **Gitignored output.** The bindings file is regenerated from the pack manifest on every
 * build; it is not source-of-truth and shouldn't drift in source control. The plugin does
 * not verify the file (unlike [TrailblazeBundledConfigPlugin]) — there's nothing to verify
 * because nothing is checked in.
 *
 * **Build wiring.** The generator is wired as a dependency of the project's `build` task
 * so bindings stay in sync with the manifest on every build. Authors who want to iterate
 * faster can run `./gradlew :<module>:bundleTrailblazePack` directly.
 */
class TrailblazeBundlePlugin : Plugin<Project> {
  override fun apply(project: Project) {
    val extension = project.extensions.create(
      "trailblazeBundle",
      TrailblazeBundleExtension::class.java,
    )

    val generate = project.tasks.register(
      GENERATE_TASK_NAME,
      BundleTrailblazePackTask::class.java,
    ) { task ->
      task.group = "trailblaze"
      task.description = "Generates per-pack TypeScript bindings (.d.ts) augmenting " +
        "TrailblazeToolMap with scripted tools declared in each runnable pack's target manifest."
      task.packsDir.set(extension.packsDir)
      // Output lives under `<toolsDir>/.trailblaze/` so the per-pack `tsconfig.json`
      // (`"include": ["**/*.ts"]`) picks the generated `.d.ts` up automatically. The
      // dot-prefixed dir keeps it sorted to the top and signals "tooling output" without
      // colliding with any author-managed file.
      task.outputDir.set(
        extension.toolsDir.dir(BundleTrailblazePackTask.GENERATED_DIR_NAME),
      )
    }

    // Run before `build` so a fresh checkout's first `./gradlew build` writes the .d.ts
    // before anything that might consume it. The generator is incremental (Gradle
    // up-to-date checks via @InputDirectory / @OutputDirectory), so wiring as a build
    // dependency is cheap on no-change builds.
    project.tasks.matching { it.name == "build" }.configureEach { it.dependsOn(generate) }
  }

  private companion object {
    const val GENERATE_TASK_NAME = "bundleTrailblazePack"
  }
}
