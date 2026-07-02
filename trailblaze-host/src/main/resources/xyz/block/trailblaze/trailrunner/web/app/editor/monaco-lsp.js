// Monaco + Language Server Protocol client for the Trail Runner scripted-tool editor.
//
// This is the browser half of the VS Code-grade TypeScript experience for `.ts` scripted tools:
// autocomplete, IntelliSense (hover + signature help), go-to-definition, and live error squiggles
// that resolve `@trailblaze/scripting` and a tool's relative imports EXACTLY like VS Code — because
// the language features come from a real native `vtsls` TypeScript language server running on the
// daemon, not from Monaco's sandboxed in-browser worker (which can't see the workspace's tsconfig or
// node-resolution). The daemon side is `LspRoutes.kt` (a WebSocket↔stdio bridge to `vtsls`).
//
// WHY PLAIN JS + CDN, NOT A BUNDLED monaco-languageclient.  The Trail Runner web app deliberately has
// no JS bundler for its own code — React, CodeMirror, highlight.js, etc. all load from the unpkg CDN
// via classic <script> tags, and the app's .jsx is compiled in-browser by Babel-standalone. Monaco
// ships an AMD distribution designed for exactly this CDN-load pattern, so we load it the same way
// instead of dragging in a bundler + `@codingame/monaco-vscode-api`'s worker/service machinery. And
// because the daemon bridge already frames messages with `Content-Length`, the wire protocol over the
// WebSocket is just bare JSON-RPC 2.0 — one message per text frame — which is small enough to speak
// directly here without `vscode-ws-jsonrpc`. The result: zero new npm deps, zero new build steps.
//
// GRACEFUL DEGRADATION.  Every failure path (Monaco CDN unreachable, LSP socket refused, `vtsls`
// missing) is non-fatal: the editor still mounts and edits text; only the language features are
// absent. The React caller additionally falls back to the CodeMirror editor when `window.TBMonaco`
// never loads at all.
(function () {
  'use strict';

  // Pinned to match the CodeMirror/React CDN-pinning convention in index.html. Monaco's `min/vs` AMD
  // build self-resolves its workers from this same path (see the MonacoEnvironment shim below).
  var MONACO_VERSION = '0.52.2';
  var MONACO_VS = 'https://unpkg.com/monaco-editor@' + MONACO_VERSION + '/min/vs';

  // Max time to wait for the language server's `initialize` reply before giving up on the session (and
  // closing the socket, which tears down the vtsls child). Generous — a cold tsserver project load can
  // take a few seconds — but bounded so a wedged server can't leave the editor's LSP silently dead.
  var INIT_TIMEOUT_MS = 15000;

  var monacoLoadPromise = null;

  // ── Monaco bootstrap (CDN AMD loader, once) ────────────────────────────────────────────────────
  function loadMonaco() {
    if (window.monaco && window.monaco.editor) return Promise.resolve(window.monaco);
    if (monacoLoadPromise) return monacoLoadPromise;
    monacoLoadPromise = new Promise(function (resolve, reject) {
      // Monaco's editor workers must be same-origin. Point them at a tiny data: worker that re-exports
      // the cross-origin CDN worker via importScripts — the documented Monaco cross-origin recipe.
      window.MonacoEnvironment = {
        getWorkerUrl: function () {
          var shim =
            'self.MonacoEnvironment={baseUrl:"' + MONACO_VS + '/"};' +
            'importScripts("' + MONACO_VS + '/base/worker/workerMain.js");';
          return 'data:text/javascript;charset=utf-8,' + encodeURIComponent(shim);
        },
      };
      var script = document.createElement('script');
      script.src = MONACO_VS + '/loader.js';
      script.onload = function () {
        // `require` here is Monaco's AMD loader, not Node's.
        window.require.config({ paths: { vs: MONACO_VS } });
        window.require(['vs/editor/editor.main'], function () {
          try {
            disableBuiltinTsFeatures(window.monaco);
            defineThemes(window.monaco);
            resolve(window.monaco);
          } catch (e) {
            reject(e);
          }
        });
      };
      script.onerror = function () {
        monacoLoadPromise = null; // allow a later retry (e.g. transient CDN blip)
        reject(new Error('Monaco failed to load from ' + MONACO_VS));
      };
      document.head.appendChild(script);
    });
    return monacoLoadPromise;
  }

  // The native `vtsls` server owns ALL TypeScript language features. Turn Monaco's bundled TS web
  // worker fully off so it doesn't double-diagnose or offer a second, workspace-blind set of
  // completions/hovers that would compete with the LSP providers we register below. Monarch syntax
  // highlighting for `typescript`/`javascript` is independent of the worker, so colors stay.
  function disableBuiltinTsFeatures(monaco) {
    if (!monaco.languages.typescript) return;
    [monaco.languages.typescript.typescriptDefaults, monaco.languages.typescript.javascriptDefaults].forEach(function (d) {
      d.setDiagnosticsOptions({ noSemanticValidation: true, noSyntaxValidation: true, noSuggestionDiagnostics: true });
      d.setModeConfiguration({
        completionItems: false, hovers: false, documentSymbols: false, definitions: false,
        references: false, documentHighlights: false, rename: false, diagnostics: false,
        signatureHelp: false, onTypeFormatting: false, codeActions: false, inlayHints: false,
      });
    });
  }

  function defineThemes(monaco) {
    // Lean on Monaco's built-in vs / vs-dark; theme is picked per-mount from the app's data-theme.
    // (Kept as a hook so a future custom theme can register here without touching mount logic.)
    void monaco;
  }

  function currentMonacoTheme() {
    return document.documentElement.getAttribute('data-theme') === 'light' ? 'vs' : 'vs-dark';
  }

  // ── LSP ⇄ Monaco position / enum conversions ───────────────────────────────────────────────────
  // Pulled from the shared, unit-tested module (app/editor/lsp-convert.js, loaded as a classic script
  // before this one). Both language clients route through these, so the off-by-one math lives in one
  // CI-tested place rather than inline here. If the module failed to load, this IIFE throws and
  // `window.TBMonaco` is never published — tools.jsx then cleanly falls back to the CodeMirror editor.
  var _conv = window.TBLspConvert;
  var toLspPosition = _conv.toLspPosition;
  var lspRangeToMonaco = _conv.lspRangeToMonaco;
  var completionKind = _conv.completionKind;
  var markerSeverity = _conv.markerSeverity;
  var toMarkdown = _conv.toMarkdown;

  // ── LSP client over the WebSocket (bare JSON-RPC 2.0, one message per frame) ─────────────────────
  // The daemon bridge handles Content-Length framing, so this only does JSON-RPC: id correlation for
  // requests, method dispatch for notifications, and minimal replies to the handful of server→client
  // requests `vtsls` issues during a session.
  function LspClient(wsUrl, monaco) {
    this.wsUrl = wsUrl;
    this.monaco = monaco;
    this.ws = null;
    this.seq = 0;
    this.pending = new Map();        // request id -> { resolve, reject }
    this.docVersions = new Map();    // uri -> integer version
    this.ready = null;               // resolves once `initialize`/`initialized` complete
    this.ready_settled = false;      // true once the initialize handshake resolved
    this.closed = false;
    // When true, completion results are reported to Monaco as `incomplete` even if the server marked
    // them complete, forcing a fresh provider query on every keystroke instead of client-side re-filter
    // of a cached list. Needed for schema-driven YAML, where the valid completion set changes entirely
    // by cursor nesting (tool-name list at the `tools:` level vs. a single tool's param set one level
    // in) — yaml-language-server returns isIncomplete=false, so without this Monaco would re-show a
    // cached list from a prior position (e.g. a tool's `overrides` param surfacing on a new tool line).
    this.forceIncompleteCompletions = false;
  }

  // options (all optional): { initializationOptions, settingsBySection }.
  //   initializationOptions — sent in `initialize` (yaml-language-server reads its schema config here).
  //   settingsBySection      — { "<section>": {...} } answered to server `workspace/configuration`
  //                            requests (yaml-language-server pulls the "yaml" section this way).
  LspClient.prototype.connect = function (rootUri, options) {
    var self = this;
    options = options || {};
    this.settingsBySection = options.settingsBySection || {};
    var initializationOptions = options.initializationOptions || null;
    this.ready = new Promise(function (resolve, reject) {
      // Single-settle guard: whichever of {initialize resolved, socket error, socket closed, timeout}
      // happens first wins; the rest are no-ops. Without this a slow initialize racing a socket close
      // could resolve AND reject, or leave the timer firing after success.
      var settled = false;
      function settle(fn, arg) { if (settled) return; settled = true; clearTimeout(initTimer); fn(arg); }

      var ws;
      try { ws = new WebSocket(self.wsUrl); } catch (e) { reject(e); return; }
      self.ws = ws;

      // Bound the handshake: a wedged language server (e.g. stuck resolving tsconfig) would otherwise
      // leave `ready` pending forever — no completions, no error, and the vtsls child lingering. On
      // timeout we reject and close the socket (which tears down the server via the bridge's finally).
      var initTimer = setTimeout(function () {
        if (!self.ready_settled) {
          settle(reject, new Error('LSP initialize timed out after ' + INIT_TIMEOUT_MS + 'ms'));
          try { ws.close(); } catch (e) { /* ignore */ }
        }
      }, INIT_TIMEOUT_MS);

      ws.onmessage = function (ev) { self._onMessage(ev.data); };
      ws.onerror = function () { settle(reject, new Error('LSP socket error')); };
      ws.onclose = function () { self.closed = true; self._failPending('LSP socket closed'); settle(reject, new Error('LSP socket closed before initialize')); };
      ws.onopen = function () {
        self._request('initialize', {
          processId: null,
          rootUri: rootUri || null,
          workspaceFolders: rootUri ? [{ uri: rootUri, name: 'workspace' }] : null,
          capabilities: CLIENT_CAPABILITIES,
          initializationOptions: initializationOptions,
        }).then(function () {
          self._notify('initialized', {});
          // Push the settings too (not just answer the server's pull). yaml-language-server applies its
          // `yaml.schemas` mapping from this; harmless when there are no sections (vtsls).
          if (self.settingsBySection && Object.keys(self.settingsBySection).length) {
            self._notify('workspace/didChangeConfiguration', { settings: self.settingsBySection });
          }
          self.ready_settled = true;
          settle(resolve, self);
        }).catch(function (e) { settle(reject, e); });
      };
    });
    return this.ready;
  };

  LspClient.prototype.isReady = function () { return !!this.ready_settled && !this.closed; };

  LspClient.prototype._send = function (obj) {
    if (this.ws && this.ws.readyState === WebSocket.OPEN) this.ws.send(JSON.stringify(obj));
  };
  LspClient.prototype._request = function (method, params) {
    var self = this;
    var id = ++this.seq;
    return new Promise(function (resolve, reject) {
      self.pending.set(id, { resolve: resolve, reject: reject });
      self._send({ jsonrpc: '2.0', id: id, method: method, params: params });
    });
  };
  LspClient.prototype._notify = function (method, params) {
    this._send({ jsonrpc: '2.0', method: method, params: params });
  };
  LspClient.prototype._failPending = function (why) {
    this.pending.forEach(function (p) { p.reject(new Error(why)); });
    this.pending.clear();
  };

  LspClient.prototype._onMessage = function (data) {
    var msg;
    try { msg = JSON.parse(data); } catch (e) { return; }
    // Response to one of our requests.
    if (msg.id !== undefined && (msg.result !== undefined || msg.error !== undefined) && msg.method === undefined) {
      var p = this.pending.get(msg.id);
      if (p) {
        this.pending.delete(msg.id);
        if (msg.error) p.reject(msg.error); else p.resolve(msg.result);
      }
      return;
    }
    if (msg.method === undefined) return;
    // Server→client request (has id): must reply or the server stalls.
    if (msg.id !== undefined) { this._send({ jsonrpc: '2.0', id: msg.id, result: this._answerServerRequest(msg) }); return; }
    // Server→client notification.
    if (msg.method === 'textDocument/publishDiagnostics') this._applyDiagnostics(msg.params);
    // Other notifications (window/logMessage, $/progress, telemetry…) are intentionally ignored.
  };

  // Minimal-but-correct replies to the requests vtsls/tsserver makes mid-session. Wrong replies here
  // are the classic "completions never show up" footgun, so each is handled explicitly.
  LspClient.prototype._answerServerRequest = function (msg) {
    switch (msg.method) {
      case 'workspace/configuration': {
        // One config object per requested item, keyed by the item's `section`. yaml-language-server
        // pulls its schema config by requesting the "yaml" section here; vtsls requests sections we
        // don't configure, which fall through to {} (server defaults).
        var bySection = this.settingsBySection || {};
        return ((msg.params && msg.params.items) || []).map(function (item) {
          return (item && item.section && bySection[item.section]) ? bySection[item.section] : {};
        });
      }
      case 'client/registerCapability':
      case 'client/unregisterCapability':
      case 'window/workDoneProgress/create':
        return null;
      case 'workspace/applyEdit':
        return { applied: false };
      default:
        return null;
    }
  };

  LspClient.prototype._applyDiagnostics = function (params) {
    if (!params || !params.uri) return;
    var model = this.monaco.editor.getModel(this.monaco.Uri.parse(params.uri));
    if (!model) return;
    var monaco = this.monaco;
    var markers = (params.diagnostics || []).map(function (d) {
      var code = d.code && typeof d.code === 'object' ? d.code.value : d.code;
      return {
        severity: markerSeverity(monaco, d.severity),
        message: d.message,
        startLineNumber: d.range.start.line + 1,
        startColumn: d.range.start.character + 1,
        endLineNumber: d.range.end.line + 1,
        endColumn: d.range.end.character + 1,
        source: d.source || 'ts',
        code: code != null ? String(code) : undefined,
      };
    });
    monaco.editor.setModelMarkers(model, 'vtsls', markers);
  };

  // Document sync — FULL (whole text per change). Tool files are small, and full-sync sidesteps the
  // incremental-range bookkeeping that's the usual source of "the server's copy drifted" bugs.
  LspClient.prototype.openDocument = function (uri, languageId, text) {
    this.docVersions.set(uri, 1);
    this._notify('textDocument/didOpen', { textDocument: { uri: uri, languageId: languageId, version: 1, text: text } });
  };
  LspClient.prototype.changeDocument = function (uri, text) {
    var v = (this.docVersions.get(uri) || 1) + 1;
    this.docVersions.set(uri, v);
    this._notify('textDocument/didChange', { textDocument: { uri: uri, version: v }, contentChanges: [{ text: text }] });
  };
  LspClient.prototype.closeDocument = function (uri) {
    this._notify('textDocument/didClose', { textDocument: { uri: uri } });
    this.docVersions.delete(uri);
  };

  // ── Language-feature requests (called by the global Monaco providers) ───────────────────────────
  LspClient.prototype.completion = function (uri, monacoModel, position) {
    var self = this;
    return this._request('textDocument/completion', {
      textDocument: { uri: uri }, position: toLspPosition(position),
    }).then(function (res) {
      var items = Array.isArray(res) ? res : ((res && res.items) || []);
      var monaco = self.monaco;
      var word = monacoModel.getWordUntilPosition(position);
      var defaultRange = new monaco.Range(position.lineNumber, word.startColumn, position.lineNumber, word.endColumn);
      var suggestions = items.map(function (it) {
        var edit = it.textEdit;
        var range = edit && edit.range ? lspRangeToMonaco(monaco, edit.range)
          : (edit && edit.insert ? lspRangeToMonaco(monaco, edit.insert) : defaultRange);
        var insertText = (edit && edit.newText != null) ? edit.newText : (it.insertText != null ? it.insertText : it.label);
        var suggestion = {
          label: it.label,
          kind: completionKind(monaco, it.kind),
          insertText: insertText,
          range: range,
          detail: it.detail,
          documentation: toMarkdown(it.documentation),
          sortText: it.sortText,
          filterText: it.filterText,
          preselect: it.preselect,
          commitCharacters: it.commitCharacters,
        };
        if (it.insertTextFormat === 2) suggestion.insertTextRules = monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet;
        suggestion.__lsp = it;        // the raw LSP item, carried for completionItem/resolve
        suggestion.__ownerUri = uri;  // route resolve back to THIS document's client (not the focused one)
        return suggestion;
      });
      var incomplete = self.forceIncompleteCompletions || !!(res && res.isIncomplete);
      return { suggestions: suggestions, incomplete: incomplete };
    }).catch(function () { return { suggestions: [] }; });
  };

  LspClient.prototype.resolveCompletion = function (item) {
    if (!item.__lsp) return Promise.resolve(item);
    var self = this;
    return this._request('completionItem/resolve', item.__lsp).then(function (resolved) {
      if (!resolved) return item;
      if (resolved.detail) item.detail = resolved.detail;
      if (resolved.documentation) item.documentation = toMarkdown(resolved.documentation);
      if (resolved.additionalTextEdits) {
        item.additionalTextEdits = resolved.additionalTextEdits.map(function (e) {
          return { range: lspRangeToMonaco(self.monaco, e.range), text: e.newText };
        });
      }
      return item;
    }).catch(function () { return item; });
  };

  LspClient.prototype.hover = function (uri, position) {
    var self = this;
    return this._request('textDocument/hover', { textDocument: { uri: uri }, position: toLspPosition(position) })
      .then(function (res) {
        if (!res || !res.contents) return null;
        var contents = Array.isArray(res.contents) ? res.contents : [res.contents];
        var parts = contents.map(function (c) {
          if (typeof c === 'string') return { value: c };
          if (c.language) return { value: '```' + c.language + '\n' + c.value + '\n```' }; // legacy MarkedString
          return { value: c.value || '' };
        });
        var hover = { contents: parts };
        if (res.range) hover.range = lspRangeToMonaco(self.monaco, res.range);
        return hover;
      }).catch(function () { return null; });
  };

  LspClient.prototype.signatureHelp = function (uri, position) {
    return this._request('textDocument/signatureHelp', { textDocument: { uri: uri }, position: toLspPosition(position) })
      .then(function (res) {
        if (!res || !res.signatures || !res.signatures.length) return null;
        var value = {
          signatures: res.signatures.map(function (s) {
            return {
              label: s.label,
              documentation: toMarkdown(s.documentation),
              parameters: (s.parameters || []).map(function (p) { return { label: p.label, documentation: toMarkdown(p.documentation) }; }),
            };
          }),
          activeSignature: res.activeSignature || 0,
          activeParameter: res.activeParameter || 0,
        };
        return { value: value, dispose: function () {} };
      }).catch(function () { return null; });
  };

  LspClient.prototype.definition = function (uri, position) {
    var self = this;
    return this._request('textDocument/definition', { textDocument: { uri: uri }, position: toLspPosition(position) })
      .then(function (res) {
        if (!res) return null;
        var locs = Array.isArray(res) ? res : [res];
        return locs.map(function (loc) {
          var targetUri = loc.uri || loc.targetUri;
          var targetRange = loc.range || loc.targetSelectionRange || loc.targetRange;
          return { uri: self.monaco.Uri.parse(targetUri), range: lspRangeToMonaco(self.monaco, targetRange) };
        });
      }).catch(function () { return null; });
  };

  LspClient.prototype.dispose = function () {
    this.closed = true;
    this._failPending('editor disposed');
    try { if (this.ws) this.ws.close(); } catch (e) { /* ignore */ }
  };

  // What we tell the server we can do. Kept lean — only the features this editor surfaces.
  var CLIENT_CAPABILITIES = {
    textDocument: {
      synchronization: { dynamicRegistration: false, didSave: false },
      completion: {
        dynamicRegistration: false,
        completionItem: { snippetSupport: true, documentationFormat: ['markdown', 'plaintext'], resolveSupport: { properties: ['documentation', 'detail', 'additionalTextEdits'] } },
        contextSupport: true,
      },
      hover: { dynamicRegistration: false, contentFormat: ['markdown', 'plaintext'] },
      signatureHelp: { dynamicRegistration: false, signatureInformation: { documentationFormat: ['markdown', 'plaintext'] } },
      definition: { dynamicRegistration: false, linkSupport: true },
      publishDiagnostics: { relatedInformation: true },
    },
    workspace: { configuration: true, workspaceFolders: true },
  };

  // ── Global providers (registered once; routed to the active doc's client by model URI) ──────────
  // Monaco providers are per-language, not per-editor. Register them once and dispatch to whichever
  // LspClient owns the requested model, so opening/closing tool editors over a session just works.
  var clientsByUri = new Map();   // model URI string -> LspClient
  var providersRegistered = false;

  function ensureProviders(monaco) {
    if (providersRegistered) return;
    providersRegistered = true;
    // typescript/javascript → vtsls; yaml → yaml-language-server. Providers route by model URI, so the
    // same registration serves whichever client owns the document (definition/signatureHelp simply
    // no-op for YAML, which the server doesn't provide).
    ['typescript', 'javascript', 'yaml'].forEach(function (lang) {
      monaco.languages.registerCompletionItemProvider(lang, {
        triggerCharacters: ['.', '"', '\'', '/', '@', '<', ' ', '('],
        provideCompletionItems: function (model, position) {
          var c = clientsByUri.get(model.uri.toString());
          return c ? c.completion(model.uri.toString(), model, position) : { suggestions: [] };
        },
        resolveCompletionItem: function (item) {
          // resolveCompletionItem has no model arg, so route by the owning URI we stamped on the
          // suggestion at provide-time — NOT activeClient, which can be the wrong session if focus
          // moved between completion and resolve. activeClient is only a last-resort fallback.
          var c = (item.__ownerUri && clientsByUri.get(item.__ownerUri)) || activeClient;
          return c ? c.resolveCompletion(item) : item;
        },
      });
      monaco.languages.registerHoverProvider(lang, {
        provideHover: function (model, position) {
          var c = clientsByUri.get(model.uri.toString());
          return c ? c.hover(model.uri.toString(), position) : null;
        },
      });
      monaco.languages.registerSignatureHelpProvider(lang, {
        signatureHelpTriggerCharacters: ['(', ','],
        signatureHelpRetriggerCharacters: [')'],
        provideSignatureHelp: function (model, position) {
          var c = clientsByUri.get(model.uri.toString());
          return c ? c.signatureHelp(model.uri.toString(), position) : null;
        },
      });
      monaco.languages.registerDefinitionProvider(lang, {
        provideDefinition: function (model, position) {
          var c = clientsByUri.get(model.uri.toString());
          return c ? c.definition(model.uri.toString(), position) : null;
        },
      });
    });
  }

  var activeClient = null;

  // ── Mount core (shared by mountTypescript / mountYaml) ──────────────────────────────────────────
  // Mounts a Monaco editor for a tool source file and wires it to a language server over the WS bridge.
  //
  // opts:
  //   host              DOM element to mount into (required)
  //   value             initial source text (required)
  //   sourcePath        workspace-relative tool source path (catalog DTO `sourcePath`); used to fetch
  //                     the file's real file:// URI so the LSP opens the document at its on-disk path
  //   languageId        Monaco language id ('typescript' | 'yaml' | …)
  //   wsPath            language-server WS route, relative to the page base ('lsp/typescript' | 'lsp/yaml')
  //   connectOptionsFor (info) => connect options (initializationOptions / settingsBySection); optional
  //   onChange          (text) => void   — fires on every edit
  //   onSave            () => void        — fires on Cmd/Ctrl+S
  //   readOnly          boolean
  //
  // Returns a handle { getValue, setValue, layout, dispose }. LSP wiring is best-effort: if the file
  // URI can't be resolved or the socket fails, the editor still works without language features.
  function mountLsp(opts) {
    var host = opts.host;
    var languageId = opts.languageId || 'typescript';
    var wsPath = opts.wsPath || 'lsp/typescript';
    return loadMonaco().then(function (monaco) {
      ensureProviders(monaco);

      // Fetch the real on-disk file URI + workspace root + daemon-local schema base (best-effort). A
      // path-less request still returns the schema base URLs (with a null fileUri) — that's how the
      // trail editor gets a proxy-safe schema URL for its in-memory doc.
      var fileUriUrl = new URL('api/lsp/file-uri?path=' + encodeURIComponent(opts.sourcePath || ''), document.baseURI).toString();
      return fetch(fileUriUrl, { cache: 'no-store' })
        .then(function (r) { return r.ok ? r.json() : null; })
        .catch(function () { return null; })
        .then(function (info) {
          // A caller can hand us a synthetic file URI (the trail editor validates an in-memory
          // `.trail.yaml` off the schema URL, not an on-disk path). Keep the daemon-local base URLs from
          // the fetch (proxy-safe schema fetch) but open the model at the caller's explicit URI.
          if (opts.explicitFileUri) {
            info = {
              fileUri: opts.explicitFileUri,
              workspaceUri: (info && info.workspaceUri) || null,
              toolSchemaUrl: (info && info.toolSchemaUrl) || null,
            };
          }
          return build(monaco, info);
        });
    });

    function build(monaco, info) {
      // Use the daemon-resolved file URI when available so diagnostics map back and tsconfig resolves;
      // otherwise fall back to an in-memory model (still edits + highlights, just no language server).
      var uriStr = info && info.fileUri ? info.fileUri : null;
      var modelUri = uriStr ? monaco.Uri.parse(uriStr) : monaco.Uri.parse('inmemory://tool/' + (opts.sourcePath || 'tool.ts'));
      var existing = monaco.editor.getModel(modelUri);
      if (existing) existing.dispose(); // a prior mount of the same file left a stale model
      var model = monaco.editor.createModel(opts.value != null ? opts.value : '', languageId, modelUri);

      var editor = monaco.editor.create(host, {
        model: model,
        theme: currentMonacoTheme(),
        readOnly: !!opts.readOnly,
        automaticLayout: true,
        minimap: { enabled: false },
        fontSize: 12.5,
        lineNumbers: 'on',
        scrollBeyondLastLine: false,
        tabSize: 2,
        insertSpaces: true,
        fixedOverflowWidgets: true,
        renderWhitespace: 'selection',
        smoothScrolling: true,
        wordWrap: opts.wrap ? 'on' : 'off',
        // The language server (vtsls / yaml-language-server) is the sole completion source. Disable
        // Monaco's built-in word-scraper, which otherwise mixes prose words from the buffer (e.g. the
        // text of a `description:` field) into the schema/LSP suggestions and buries the real ones.
        wordBasedSuggestions: 'off',
      });

      var client = null;
      if (uriStr) {
        var wsUrl = new URL(wsPath, document.baseURI);
        wsUrl.protocol = (wsUrl.protocol === 'https:') ? 'wss:' : 'ws:';
        client = new LspClient(wsUrl.toString(), monaco);
        client.forceIncompleteCompletions = !!opts.forceIncompleteCompletions;
        clientsByUri.set(uriStr, client);
        activeClient = client;
        var connectOptions = opts.connectOptionsFor ? opts.connectOptionsFor(info) : undefined;
        client.connect(info && info.workspaceUri ? info.workspaceUri : null, connectOptions).then(function () {
          client.openDocument(uriStr, languageId, model.getValue());
        }).catch(function (e) {
          // Non-fatal: language server unavailable. Editing still works.
          if (window.console) console.warn('[TBMonaco] language server unavailable:', (e && e.message) || e);
        });
      }

      var changeSub = model.onDidChangeContent(function () {
        var text = model.getValue();
        if (opts.onChange) opts.onChange(text);
        if (client && client.isReady()) client.changeDocument(uriStr, text);
      });
      var focusSub = editor.onDidFocusEditorText(function () { if (client) activeClient = client; });

      if (opts.onSave) {
        editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyS, function () { opts.onSave(); });
      }

      var disposed = false;
      return {
        getValue: function () { return model.getValue(); },
        setValue: function (v) { if (model.getValue() !== v) model.setValue(v != null ? v : ''); },
        setTheme: function () { monaco.editor.setTheme(currentMonacoTheme()); },
        layout: function () { editor.layout(); },
        // 0-based cursor line, matching the CodeMirror insert API the tools palette drives.
        getCursorLine: function () { var p = editor.getPosition(); return p ? p.lineNumber - 1 : 0; },
        // Toggle soft-wrap — carries over the trail editor's wrap toggle to the Monaco path.
        setWrap: function (on) { editor.updateOptions({ wordWrap: on ? 'on' : 'off' }); },
        focus: function () { editor.focus(); },
        // Plain insert at the caret (fallback when there's no step to attach a tool to).
        insertAtCursor: function (text) {
          editor.executeEdits('tb-insert', [{ range: editor.getSelection(), text: text, forceMoveMarkers: true }]);
          editor.focus();
        },
        // Structural replace: the palette computes the full new text + target caret via
        // insertToolIntoTrailYaml, then hands it here to apply + reveal (0-based line/col in).
        applyFullTextWithCursor: function (text, line0, ch0) {
          model.setValue(text != null ? text : '');
          var pos = { lineNumber: (line0 || 0) + 1, column: (ch0 || 0) + 1 };
          editor.setPosition(pos);
          editor.revealPositionInCenter(pos);
          editor.focus();
        },
        // Additional (non-LSP) diagnostics under a distinct owner so they coexist with the language
        // server's schema markers. Carries over the trail editor's server-side SEMANTIC lint (things a
        // JSON Schema can't express). Each entry: { line0, message, severity: 'error'|'warning' }.
        setMarkers: function (list) {
          var markers = (list || []).map(function (m) {
            return {
              startLineNumber: (m.line0 || 0) + 1, startColumn: 1,
              endLineNumber: (m.line0 || 0) + 1, endColumn: 500,
              message: m.message || '', source: 'trailblaze',
              severity: m.severity === 'warning' ? monaco.MarkerSeverity.Warning : monaco.MarkerSeverity.Error,
            };
          });
          monaco.editor.setModelMarkers(model, 'trail-semantic', markers);
        },
        // Reveal a step's block and briefly select it (built-in selection highlight — no extra CSS) to
        // carry over the board-cell → YAML "flash". 0-based inclusive line range.
        revealAndFlashLines: function (start0, end0) {
          var s = (start0 || 0) + 1, e = (end0 || start0 || 0) + 1;
          editor.revealLinesInCenter(s, e);
          editor.setSelection({ startLineNumber: s, startColumn: 1, endLineNumber: e, endColumn: model.getLineMaxColumn(e) });
          setTimeout(function () { if (!disposed) editor.setPosition({ lineNumber: s, column: 1 }); }, 1500);
        },
        dispose: function () {
          if (disposed) return;
          disposed = true;
          changeSub.dispose();
          focusSub.dispose();
          if (client) {
            if (uriStr) { client.closeDocument(uriStr); clientsByUri.delete(uriStr); }
            if (activeClient === client) activeClient = null;
            client.dispose();
          }
          editor.dispose();
          model.dispose();
        },
      };
    }
  }

  // ── Public mount API ────────────────────────────────────────────────────────────────────────────
  // Mount a Monaco TypeScript editor for a scripted `.ts` tool, wired to vtsls.
  function mountTypescript(opts) {
    var o = {};
    for (var k in opts) o[k] = opts[k];
    o.wsPath = 'lsp/typescript';
    o.languageId = opts.languageId || 'typescript';
    return mountLsp(o);
  }

  // Mount a Monaco YAML editor for a `.tool.yaml` definition, wired to yaml-language-server with the
  // tool-definition JSON Schema associated (so you get validation + key/enum completion + hover).
  function mountYaml(opts) {
    var o = {};
    for (var k in opts) o[k] = opts[k];
    o.wsPath = 'lsp/yaml';
    o.languageId = 'yaml';
    // YAML schema completions are highly position-sensitive (tool-name list vs. a tool's param set),
    // and yaml-language-server reports them complete — force re-query per keystroke to avoid Monaco
    // re-showing a cached list from a prior cursor position. See LspClient.forceIncompleteCompletions.
    o.forceIncompleteCompletions = true;
    o.connectOptionsFor = function (info) {
      // Scope the generated schema's tool-name set to this tool's trailmap (+ framework tools).
      var query = opts.trailmap ? ('?trailmap=' + encodeURIComponent(opts.trailmap)) : '';
      // Prefer the daemon-LOCAL schema URL when the daemon supplies one: yaml-language-server runs as
      // a daemon child and fetches this URL itself, so it must be reachable on the daemon host — not
      // `document.baseURI`, which is a proxy origin (a cloud-workstation preview proxy) the child can't reach. Fall back to
      // document.baseURI for direct localhost access (and when the daemon didn't supply a URL).
      // `toolSchemaUrl` from the daemon is a bare path (no query string), so appending `?trailmap=…` is
      // safe — keep it query-free server-side or this concatenation would double the `?`.
      var schemaUrl = (info && info.toolSchemaUrl)
        ? (info.toolSchemaUrl + query)
        : new URL('api/lsp/tool-schema.json' + query, document.baseURI).toString();
      // Associate the schema both to this exact file URI (most reliable) and to the *.tool.yaml glob.
      var globs = [];
      if (info && info.fileUri) globs.push(info.fileUri);
      globs.push('**/*.tool.yaml');
      var schemas = {};
      schemas[schemaUrl] = globs;
      var yamlSettings = { schemas: schemas, validate: true, hover: true, completion: true, format: { enable: false } };
      // initializationOptions for servers that read it directly; settingsBySection answers the
      // server's `workspace/configuration` pull AND is pushed via didChangeConfiguration (see connect).
      return { initializationOptions: { yaml: yamlSettings }, settingsBySection: { yaml: yamlSettings } };
    };
    return mountLsp(o);
  }

  // Mount a Monaco YAML editor for a `.trail.yaml` file, wired to yaml-language-server with the
  // TRAIL schema associated — so `recording:` → `tools:` blocks autocomplete/validate against the
  // tools registered for the trail's target. Uses a synthetic in-memory `.trail.yaml` URI (validation
  // runs off the didOpen text + the schema URL; no on-disk path needed), so it never depends on the
  // trail-detail plumbing. opts.target / opts.platform scope the schema (read from the trail's config).
  function mountTrailYaml(opts) {
    var o = {};
    for (var k in opts) o[k] = opts[k];
    o.wsPath = 'lsp/yaml';
    o.languageId = 'yaml';
    o.forceIncompleteCompletions = true;
    // Synthetic file URI: matches the `**/*.trail.yaml` glob so the schema binds; the text comes from
    // the editor via didOpen, not disk.
    o.explicitFileUri = 'file:///virtual-trail/preview.trail.yaml';
    o.connectOptionsFor = function (info) {
      var q = [];
      if (opts.target) q.push('target=' + encodeURIComponent(opts.target));
      if (opts.platform) q.push('platform=' + encodeURIComponent(opts.platform));
      // `config.driver` is more specific than platform and the target's tool set can vary by driver
      // (buildRunToolsResponse prefers driver), so scope by it when the trail declares one.
      if (opts.driver) q.push('driver=' + encodeURIComponent(opts.driver));
      var query = q.length ? ('?' + q.join('&')) : '';
      // Daemon-local trail-schema URL derived from the tool-schema base when the daemon supplied one
      // (proxy-safe); else document.baseURI, which is fine for direct localhost.
      var base = (info && info.toolSchemaUrl) ? info.toolSchemaUrl.replace('tool-schema.json', 'trail-schema.json') : null;
      var schemaUrl = base ? (base + query) : new URL('api/lsp/trail-schema.json' + query, document.baseURI).toString();
      var globs = [];
      if (info && info.fileUri) globs.push(info.fileUri);
      globs.push('**/*.trail.yaml');
      var schemas = {};
      schemas[schemaUrl] = globs;
      var yamlSettings = { schemas: schemas, validate: true, hover: true, completion: true, format: { enable: false } };
      return { initializationOptions: { yaml: yamlSettings }, settingsBySection: { yaml: yamlSettings } };
    };
    return mountLsp(o);
  }

  window.TBMonaco = {
    mountTypescript: mountTypescript,
    mountYaml: mountYaml,
    mountTrailYaml: mountTrailYaml,
    loadMonaco: loadMonaco,
    MONACO_VERSION: MONACO_VERSION,
  };
})();
