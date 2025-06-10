package xyz.block.trailblaze.android.maestro

import maestro.js.JsEngine

/**
 * Maestro requires an implementation of a JS engine for a small feature
 * that we don't plan to use currently.  This allows us to set the value, satisfy
 * the interface, and not have to worry about the actual implementation.
 */
class FakeJsEngine : JsEngine {
  override fun enterScope() {
    println("enterScope")
  }

  override fun evaluateScript(
    script: String,
    env: Map<String, String>,
    sourceName: String,
    runInSubScope: Boolean,
  ): Any? {
    println("evaluateScript")
    return null
  }

  override fun leaveScope() {
    println("leaveScope")
  }

  override fun onLogMessage(callback: (String) -> Unit) {
    println("onLogMessage")
  }

  override fun putEnv(key: String, value: String) {
    println("putEnv")
  }

  override fun setCopiedText(text: String?) {
    println("setCopiedText")
  }

  override fun close() {
    println("close")
  }
}
