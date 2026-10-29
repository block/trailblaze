import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.TaskProvider
import org.gradle.jvm.tasks.Jar

/**
 * Registers `run`: the CLI launched straight from the build, on the same classpath the uber JAR
 * flattens ([appJar] first, then [runtimeClasspath]).
 *
 * `./trailblaze --gradle`, and the launcher's fallback when the JAR build fails, invoke
 * `<module>:run --args=...`. The Compose Desktop plugin's `application {}` block used to register
 * this task; without it those paths fail with "task 'run' not found". The module's own
 * `tasks.withType<JavaExec>` block adds the working directory, stdin, heap and JVM flags.
 */
fun Project.registerCliRunTask(
  mainClass: String,
  appJar: TaskProvider<out Jar>,
  runtimeClasspath: FileCollection,
): TaskProvider<JavaExec> =
  tasks.register("run", JavaExec::class.java) { task ->
    task.description = "Runs the CLI from this build (what `./trailblaze --gradle` invokes)"
    task.group = "application"
    task.mainClass.set(mainClass)
    task.classpath = files(appJar, runtimeClasspath)
  }
