// CI-gated unit tests for the shared LSP ⇄ Monaco conversions. These pin the 0-based↔1-based
// off-by-one math (the silent-failure class that would misplace completions/hover/squiggles for BOTH
// the TypeScript and YAML editors, since both route through these) without needing a browser.
import { test, expect } from "bun:test";
// lsp-convert.js dual-exports via module.exports; bun interops the CJS default import.
import convert from "./lsp-convert.js";

// A minimal stub of the Monaco namespace the conversions touch — just enough to assert the mapping.
const KIND_NAMES = [
  "Text", "Method", "Function", "Constructor", "Field", "Variable", "Class", "Interface", "Module",
  "Property", "Unit", "Value", "Enum", "Keyword", "Snippet", "Color", "File", "Reference", "Folder",
  "EnumMember", "Constant", "Struct", "Event", "Operator", "TypeParameter",
];
const CompletionItemKind: Record<string, string> = {};
KIND_NAMES.forEach((n) => { CompletionItemKind[n] = n; });
const monaco = {
  // `new monaco.Range(...)` — returning an object from the constructor makes `new` yield it.
  Range: function (a: number, b: number, c: number, d: number) {
    return { startLineNumber: a, startColumn: b, endLineNumber: c, endColumn: d };
  },
  languages: { CompletionItemKind },
  MarkerSeverity: { Error: "Error", Warning: "Warning", Info: "Info", Hint: "Hint" },
};

test("toLspPosition converts Monaco 1-based to LSP 0-based", () => {
  expect(convert.toLspPosition({ lineNumber: 1, column: 1 })).toEqual({ line: 0, character: 0 });
  expect(convert.toLspPosition({ lineNumber: 12, column: 5 })).toEqual({ line: 11, character: 4 });
});

test("lspRangeToMonaco converts LSP 0-based range to Monaco 1-based range", () => {
  const r = convert.lspRangeToMonaco(monaco, { start: { line: 0, character: 0 }, end: { line: 2, character: 5 } });
  expect(r).toEqual({ startLineNumber: 1, startColumn: 1, endLineNumber: 3, endColumn: 6 });
});

test("completionKind maps known LSP kinds and falls back to Property", () => {
  expect(convert.completionKind(monaco, 3)).toBe("Function");
  expect(convert.completionKind(monaco, 15)).toBe("Snippet");
  expect(convert.completionKind(monaco, 999)).toBe("Property"); // unknown → Property
  expect(convert.completionKind(monaco, undefined)).toBe("Property");
});

test("markerSeverity maps LSP severities and treats unknown/absent as Error", () => {
  expect(convert.markerSeverity(monaco, 1)).toBe("Error");
  expect(convert.markerSeverity(monaco, 2)).toBe("Warning");
  expect(convert.markerSeverity(monaco, 3)).toBe("Info");
  expect(convert.markerSeverity(monaco, 4)).toBe("Hint");
  expect(convert.markerSeverity(monaco, undefined)).toBe("Error");
});

test("toMarkdown handles strings, MarkupContent, and null", () => {
  expect(convert.toMarkdown("hello")).toEqual({ value: "hello" });
  expect(convert.toMarkdown({ kind: "markdown", value: "**hi**" })).toEqual({ value: "**hi**" });
  expect(convert.toMarkdown(null)).toBeUndefined();
  expect(convert.toMarkdown(undefined)).toBeUndefined();
});
