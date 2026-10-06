package xyz.block.trailblaze.scripting.host

import kotlin.reflect.KClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext

/**
 * Kotlin code a scripted tool calls as `ctx.host.<name>(args)` — internal plumbing that is not a
 * tool.
 *
 * A scripted tool could previously reach Kotlin only through `ctx.tools`, so helpers it needed
 * (credential lookups, provisioning calls) were registered as hidden tools. That made every call
 * a tool step: logged into the session and the report, and — like any tool that isn't read-only —
 * it threw away the cached screen. A host function has none of that. It never enters the tool
 * registry, so the LLM can't see it, recordings can't capture it, and the report doesn't list it.
 * Make something a tool only when a trail or the LLM must be able to call it, or its calls must be
 * logged as steps.
 *
 * The implementing class is the call's arguments, the way a class-backed tool is: a
 * `@Serializable` data class whose properties are decoded from the script's `args`. It runs on
 * the JVM that runs the calling script — the host daemon, or the device for a trail running
 * entirely inside a test APK.
 *
 * Register one by adding `trails/config/trailmaps/<trailmap>/host/<name>.host.yaml` with `id:` and
 * `class:`. Scripts in that trailmap and in every trailmap depending on it get a typed binding.
 *
 * Throw [TrailblazeHostFunctionException] to fail the call with a message the script sees. Any
 * other exception fails the call too, with its class name and message — so never put a secret in
 * an exception message.
 */
interface TrailblazeHostFunction<out R : Any> {
  suspend fun invoke(context: TrailblazeToolExecutionContext): R
}

/**
 * Names a [TrailblazeHostFunction] and declares its result type.
 *
 * @property name The id scripts call it by (`ctx.host.<name>`). Must match the `id:` of its
 *   `.host.yaml` descriptor.
 * @property result The `@Serializable` class [TrailblazeHostFunction.invoke] returns. The value is
 *   encoded with this class's serializer, and the generated TypeScript binding types the result
 *   from it.
 * @property description Shown on the generated TypeScript binding.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class TrailblazeHostFunctionClass(
  val name: String,
  val result: KClass<*>,
  val description: String = "",
)

/** Fails a host function call; [message] reaches the calling script, so it must not carry secrets. */
class TrailblazeHostFunctionException(message: String) : Exception(message)
