package xyz.block.trailblaze.surveys

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import xyz.block.trailblaze.scripting.host.TrailblazeHostFunctionException
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext

/**
 * `trailblaze.surveys.load`, the host function behind `ctx.surveys`: hands a tool the bundle that
 * holds the named survey. Answered from the session's [SurveyBundles.sourceFor], so the same call
 * works on the host and on a device.
 */
object SurveyHostFunctions {

  const val LOAD: String = "trailblaze.surveys.load"

  fun handles(name: String): Boolean = name == LOAD

  suspend fun call(args: JsonObject, context: TrailblazeToolExecutionContext): JsonElement {
    val unknown = args.keys - "name"
    if (unknown.isNotEmpty()) {
      throw TrailblazeHostFunctionException("$LOAD does not accept ${unknown.joinToString { "'$it'" }}. Accepted: name.")
    }
    val name = (args["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
      ?: throw TrailblazeHostFunctionException("$LOAD needs the survey's export name.")
    val source = SurveyBundles.sourceFor(context.sessionProvider.invoke().sessionId)
      ?: throw TrailblazeHostFunctionException(
        "Surveys can't run here: this runtime packaged no surveys. Run the trail through the " +
          "Trailblaze daemon, or rebuild the test APK with the Trailblaze Gradle plugin.",
      )
    val bundle = try {
      // A first load may run esbuild; keep it off the caller's dispatcher.
      withContext(Dispatchers.IO) { source.load(name) }
    } catch (e: SurveyNotFoundException) {
      throw TrailblazeHostFunctionException(e.message.orEmpty())
    } catch (e: IllegalStateException) {
      throw TrailblazeHostFunctionException(e.message.orEmpty())
    }
    return buildJsonObject {
      put("source", bundle.source)
      put("file", bundle.file)
      putJsonObject("targets") {
        bundle.targets.forEach { (id, platforms) ->
          putJsonObject(id) { platforms.forEach { (platform, appIds) -> putJsonArray(platform) { appIds.forEach { add(it) } } } }
        }
      }
    }
  }
}
