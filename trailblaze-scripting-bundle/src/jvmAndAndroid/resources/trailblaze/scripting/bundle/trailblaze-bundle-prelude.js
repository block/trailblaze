// Kotlin-authored JavaScript prelude for the PR A5 on-device bundle runtime. Evaluated
// inside QuickJS BEFORE the author's bundle so `globalThis.__trailblazeInProcessTransport`
// is visible when the bundle runs. See `BundleRuntimePrelude.kt` for the full contract
// documentation; this file is the JS payload only.
//
// Two JS-side placeholders are substituted at load time by `BundleRuntimePrelude.loadSource`:
//   __DELIVER_TO_KOTLIN__     → the JS→Kotlin async binding name
//   __IN_PROCESS_TRANSPORT__  → the JS global object name
//
// Keep them as identifier-shaped placeholders so the file still lints as valid JS source
// in an editor without the substitution (identifier references resolve to `undefined`,
// which is a no-op until runtime and makes the placeholder visible in diffs).

(function() {
  var onMessageHandler = null;
  var onCloseHandler = null;
  var onErrorHandler = null;

  var transport = {
    // Required by the MCP JS SDK's Transport interface. No-op because the bridge is live
    // as soon as QuickJS evaluates this prelude — there's no stdio pipe to open, no
    // socket to dial.
    start: function() { return Promise.resolve(); },

    // Ship a JSON-RPC message Kotlin-ward. The Kotlin-side [InProcessMcpTransport]
    // binding accepts a single string argument and delivers it to the MCP Client's
    // onMessage callback (which Protocol dispatches to the waiting request's
    // Continuation). Serialization here is `JSON.stringify` because the Kotlin binding
    // is string-valued; we pay one round trip per message, acceptable given LLM
    // tool-dispatch cadence.
    send: function(message) {
      return __DELIVER_TO_KOTLIN__(JSON.stringify(message));
    },

    // Close: flush the close handler if the author installed one. The Kotlin side
    // observes close via AbstractClientTransport.closeResources(); this callback is
    // JS-local only and exists so authors who install cleanup via the SDK's onclose get
    // notified on explicit disconnect.
    close: function() {
      if (onCloseHandler) {
        try { onCloseHandler(); } catch (e) { /* swallow; close is best-effort */ }
      }
      return Promise.resolve();
    },

    // The SDK installs handlers via these setters. Kept as setters (not direct fields)
    // so a handler registered before `connect()` finishes still gets wired — the SDK's
    // Server constructor sets `onmessage` synchronously during `connect()`, so timing
    // is already predictable, but the indirection is cheap and makes future change
    // ergonomic.
    set onmessage(handler) { onMessageHandler = handler; },
    get onmessage() { return onMessageHandler; },
    set onclose(handler) { onCloseHandler = handler; },
    get onclose() { return onCloseHandler; },
    set onerror(handler) { onErrorHandler = handler; },
    get onerror() { return onErrorHandler; },

    // Kotlin→JS delivery. Kotlin's InProcessMcpTransport.performSend parses the JSON-RPC
    // message into a JS object and calls this. Returns a Promise so the Kotlin-side
    // quickJs.evaluate waits for the SDK's Server to finish handling before resolving —
    // which, for request messages, is when the handler has produced a response and
    // called transport.send(response). By the time this Promise resolves, the matching
    // response has already fired Kotlin's _onMessage.
    __deliverFromKotlin: function(message) {
      if (!onMessageHandler) {
        // Author didn't wire the transport. Fail loudly instead of silently hanging.
        return Promise.reject(new Error(
          "Trailblaze bundle: no MCP onmessage handler installed. The bundle must call " +
          "server.connect(globalThis.__IN_PROCESS_TRANSPORT__) before Kotlin dispatches a message."
        ));
      }
      try {
        var ret = onMessageHandler(message);
        // The SDK's handler returns a Promise (async function) — await it so errors
        // inside the handler propagate back up the Kotlin evaluate(). Pre-ES2017 code
        // that returns undefined works too.
        return Promise.resolve(ret);
      } catch (e) {
        if (onErrorHandler) {
          try { onErrorHandler(e); } catch (_) { /* swallow */ }
        }
        return Promise.reject(e);
      }
    }
  };

  globalThis.__IN_PROCESS_TRANSPORT__ = transport;

  // ---- console shim -----------------------------------------------------------------------
  // QuickJS ships without a `console` global. Install one that routes every method through
  // the Kotlin `__CONSOLE_BINDING__` sink (logcat via Trailblaze.Console.log). Matches the
  // MCP stdio convention on host — `console.error` lands somewhere visible, author code
  // doesn't need to know the runtime. Variadic args are stringified (String for primitives,
  // JSON.stringify for objects with a best-effort fallback) and joined by spaces, mirroring
  // Node's console formatting closely enough for debug use.
  function formatArg(arg) {
    if (typeof arg === "string") return arg;
    if (arg instanceof Error) {
      // Errors need explicit handling; JSON.stringify(error) returns "{}". Including the
      // stack when available matches Node's default console.error behavior. Wrapped in
      // try/catch because a custom Error subclass with non-string / non-coercible `name`
      // or `message` fields (an object, a throwing getter) could blow up concatenation —
      // and the whole point of formatArg is that author code can never crash a log line.
      try {
        return arg.stack || (String(arg.name) + ": " + String(arg.message));
      } catch (_) {
        return "[Error with unserializable fields]";
      }
    }
    try {
      return JSON.stringify(arg);
    } catch (_) {
      // Circular references, BigInts, etc. Fall back to the default coercion. Also
      // wrapped: a broken toString() on a plain object could throw from String(arg).
      try {
        return String(arg);
      } catch (_) {
        return "[unserializable value]";
      }
    }
  }
  function emit(level, args) {
    var parts = [];
    for (var i = 0; i < args.length; i++) parts.push(formatArg(args[i]));
    __CONSOLE_BINDING__(level, parts.join(" "));
  }
  globalThis.console = {
    log:   function() { emit("log",   arguments); },
    info:  function() { emit("info",  arguments); },
    warn:  function() { emit("warn",  arguments); },
    error: function() { emit("error", arguments); },
    debug: function() { emit("debug", arguments); },
  };

  // ---- AbortController / AbortSignal shim ------------------------------------------------
  // QuickJS is spec-minimal — `AbortController` ships with browsers and Node, not with the
  // core ES language. The bundled MCP SDK uses it for request-timeout cancellation, so
  // without this shim the on-device bundle's FIRST `server.connect(transport)` call throws
  // `ReferenceError: 'AbortController' is not defined` during `_onrequest` wiring.
  //
  // Minimal contract the MCP SDK relies on: `new AbortController()` yields `{ signal,
  // abort() }`; the signal exposes `aborted`, `reason`, and `addEventListener("abort", ...)`
  // for the fetch-timeout integration. The host path uses real AbortController via fetch;
  // on-device `client.callTool` takes the `__trailblazeCallback` binding (no fetch, no real
  // abort), so signal handlers here never actually fire — the shim only has to keep the
  // constructor and `signal.aborted` accessor alive so SDK-internal code compiles cleanly.
  if (typeof globalThis.AbortController === "undefined") {
    globalThis.AbortSignal = function AbortSignal() {
      this.aborted = false;
      this.reason = undefined;
      this._listeners = [];
    };
    globalThis.AbortSignal.prototype.addEventListener = function(type, listener) {
      if (type === "abort") this._listeners.push(listener);
    };
    globalThis.AbortSignal.prototype.removeEventListener = function(type, listener) {
      if (type !== "abort") return;
      var i = this._listeners.indexOf(listener);
      if (i >= 0) this._listeners.splice(i, 1);
    };
    globalThis.AbortSignal.prototype.dispatchEvent = function(ev) {
      for (var i = 0; i < this._listeners.length; i++) {
        try { this._listeners[i].call(this, ev); } catch (_) { /* listener errors don't block abort */ }
      }
      return true;
    };
    globalThis.AbortSignal.prototype.throwIfAborted = function() {
      if (this.aborted) {
        var err = new Error(this.reason != null ? String(this.reason) : "AbortError");
        err.name = "AbortError";
        throw err;
      }
    };
    globalThis.AbortController = function AbortController() {
      this.signal = new globalThis.AbortSignal();
    };
    globalThis.AbortController.prototype.abort = function(reason) {
      if (this.signal.aborted) return;
      this.signal.aborted = true;
      this.signal.reason = reason;
      this.signal.dispatchEvent({ type: "abort", target: this.signal });
    };
  }
})();
