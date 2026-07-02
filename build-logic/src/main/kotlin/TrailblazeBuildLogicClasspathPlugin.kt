import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Deliberate no-op. Applying it is the supported way to put build-logic's classes (e.g.
 * [ShrinkUberJarWithProguardTask]) on a module's build-script classpath when the module doesn't
 * apply any other build-logic convention plugin — Gradle only adds an included build's plugin jar
 * to a script's classloader when a plugin from that build is applied in that script.
 */
class TrailblazeBuildLogicClasspathPlugin : Plugin<Project> {
  override fun apply(project: Project) = Unit
}
