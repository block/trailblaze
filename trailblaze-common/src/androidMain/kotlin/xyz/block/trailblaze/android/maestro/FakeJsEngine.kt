package xyz.block.trailblaze.android.maestro

import maestro.js.JsEngine
import xyz.block.trailblaze.util.Console

/**
 * Maestro requires an implementation of a JS engine for a small feature
 * that we don't plan to use currently.  This allows us to set the value, satisfy
 * the interface, and not have to worry about the actual implementation.
 */
class FakeJsEngine : JsEngine {

  override fun enterEnvScope() {
    Console.log("enterEnvScope")
  }
  override fun leaveEnvScope() {
    Console.log("leaveEnvScope")
  }
  override fun enterScope() {
    Console.log("enterScope")
  }

  override fun evaluateScript(
    script: String,
    env: Map<String, String>,
    sourceName: String,
    runInSubScope: Boolean,
  ): Any? {
    Console.log("evaluateScript")
    return null
  }

  override fun leaveScope() {
    Console.log("leaveScope")
  }

  override fun onLogMessage(callback: (String) -> Unit) {
    Console.log("onLogMessage")
  }

  override fun putEnv(key: String, value: String) {
    Console.log("putEnv")
  }

  override fun setCopiedText(text: String?) {
    Console.log("setCopiedText")
  }

  override fun close() {
    Console.log("close")
  }
}
