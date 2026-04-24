package xyz.block.trailblaze.scripting.callback

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * [CoroutineContext] element that propagates the reentrance depth of a
 * `/scripting/callback`-originated dispatch down to any [SubprocessTrailblazeTool] that runs
 * inside it. When a subprocess tool re-registers an invocation (because the callback-dispatched
 * tool is itself a subprocess tool), it reads this element to stamp the new [
 * JsScriptingInvocationRegistry.Entry.depth].
 *
 * Absent (`null`) when the current dispatch didn't originate from a callback — in which case the
 * SubprocessTrailblazeTool treats itself as the outer invocation (`depth = 0`).
 *
 * Lives on [CoroutineContext] rather than a ThreadLocal so it rides the same structured-
 * concurrency boundaries that tool execution already relies on (cancellation propagation,
 * context inheritance on `withContext` / `async`). A thread-local would leak into unrelated
 * coroutines scheduled on the same thread.
 */
class JsScriptingCallbackDispatchDepth(val depth: Int) : AbstractCoroutineContextElement(JsScriptingCallbackDispatchDepth) {

  companion object Key : CoroutineContext.Key<JsScriptingCallbackDispatchDepth>
}
