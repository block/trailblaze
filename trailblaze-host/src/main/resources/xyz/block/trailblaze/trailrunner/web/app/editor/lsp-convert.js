// Pure LSP ⇄ Monaco conversions, shared by the TypeScript and YAML editor clients in monaco-lsp.js.
//
// These are the coordinate/enum mappings most prone to a silent off-by-one (LSP positions are 0-based;
// Monaco's are 1-based) — and now that BOTH language clients route completions/hover/diagnostics
// through them, a bug here misplaces results for every language. Extracted into this standalone module
// (rather than living inside monaco-lsp.js's IIFE) precisely so they can be unit-tested in CI without
// a browser — see the sibling `lsp-convert.test.ts`.
//
// Dual-published: as `window.TBLspConvert` for the browser (loaded as a classic <script> before
// monaco-lsp.js) and as `module.exports` for the bun test runner. The `monaco`-typed helpers take the
// monaco namespace as a parameter so they stay pure (no global dependency) and testable with a stub.
(function () {
  'use strict';

  // Monaco Position (1-based lineNumber/column) -> LSP Position (0-based line/character). Both count
  // `character` in UTF-16 code units (matching JS strings + Monaco), so no surrogate re-encoding.
  function toLspPosition(p) {
    return { line: p.lineNumber - 1, character: p.column - 1 };
  }

  // LSP Range (0-based) -> Monaco Range (1-based).
  function lspRangeToMonaco(monaco, r) {
    return new monaco.Range(r.start.line + 1, r.start.character + 1, r.end.line + 1, r.end.character + 1);
  }

  // LSP CompletionItemKind (1..25) -> Monaco CompletionItemKind. Unknown kinds fall back to Property.
  function completionKind(monaco, lspKind) {
    var K = monaco.languages.CompletionItemKind;
    var map = {
      1: K.Text, 2: K.Method, 3: K.Function, 4: K.Constructor, 5: K.Field, 6: K.Variable, 7: K.Class,
      8: K.Interface, 9: K.Module, 10: K.Property, 11: K.Unit, 12: K.Value, 13: K.Enum, 14: K.Keyword,
      15: K.Snippet, 16: K.Color, 17: K.File, 18: K.Reference, 19: K.Folder, 20: K.EnumMember,
      21: K.Constant, 22: K.Struct, 23: K.Event, 24: K.Operator, 25: K.TypeParameter,
    };
    return map[lspKind] != null ? map[lspKind] : K.Property;
  }

  // LSP DiagnosticSeverity (1..4) -> Monaco MarkerSeverity. Unknown/absent severities are treated as Error.
  function markerSeverity(monaco, lspSeverity) {
    var S = monaco.MarkerSeverity;
    return { 1: S.Error, 2: S.Warning, 3: S.Info, 4: S.Hint }[lspSeverity] || S.Error;
  }

  // LSP documentation (a string or a MarkupContent { kind, value }) -> Monaco IMarkdownString | undefined.
  function toMarkdown(doc) {
    if (doc == null) return undefined;
    if (typeof doc === 'string') return { value: doc };
    return { value: doc.value || '' };
  }

  var api = {
    toLspPosition: toLspPosition,
    lspRangeToMonaco: lspRangeToMonaco,
    completionKind: completionKind,
    markerSeverity: markerSeverity,
    toMarkdown: toMarkdown,
  };

  if (typeof module !== 'undefined' && module.exports) module.exports = api; // bun test / CommonJS
  if (typeof window !== 'undefined') window.TBLspConvert = api;              // browser classic script
})();
