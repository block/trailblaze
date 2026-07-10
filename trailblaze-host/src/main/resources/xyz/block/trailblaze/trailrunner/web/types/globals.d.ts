// Ambient declarations for the Trail Runner web UI.
//
// The UI has no bundler and no imports: every dependency is a UMD global loaded from a CDN
// (see index.html), and the app's own files talk to each other through the shared global scope and
// `window.*`. This file gives `tsc --noEmit` types for both:
//   1. the CDN globals that have no defining .tsx (React/ReactDOM come from @types automatically via
//      their `export as namespace` UMD declarations, so they are NOT redeclared here);
//   2. the cross-file `window.*` surface published by module/plain-JS files that TS can't see as
//      script-scope globals — `TbRpc` (from the bundled ES-module app/rpc/daemon.ts), `TBMonaco`
//      (from the plain-JS app/editor/monaco-lsp.js), and the run-report-core.js helpers.
//
// App symbols defined as top-level `const`/`function` in a .tsx file (Btn, formatDuration, …) are
// NOT declared here: because the .tsx files are global-scope scripts, TS already sees them as
// globals from their defining file. Only symbols published via `window.X = …` without a matching
// top-level binding (notably `TB`) need an ambient here.
//
// Types start deliberately loose (`any` in the places the app is still untyped). Tighten as the
// migration progresses — replace an `any` with a real shape and fix the fallout.

import type { TbRpcApi } from "../app/rpc/daemon";

declare global {
  // ─── CDN UMD globals (loaded via <script> in index.html) ──────────────────────────────────────
  // React / ReactDOM are provided by @types/react + @types/react-dom (`export as namespace`), so
  // they are already global — do not redeclare them here.

  /** codemirror@5 — the global is the module's export (factory fn + namespace). @types/codemirror. */
  const CodeMirror: typeof import("codemirror");

  /** js-yaml@4 — parsed/loaded YAML. @types/js-yaml. */
  const jsyaml: typeof import("js-yaml");

  /** lucide@0.460 icon library. Typed minimally — the UI uses a small slice (createIcons + icons). */
  const lucide: {
    createIcons(options?: {
      icons?: Record<string, unknown>;
      nameAttr?: string;
      attrs?: Record<string, string | number>;
    }): void;
    icons?: Record<string, unknown>;
    [key: string]: unknown;
  };

  /** highlight.js@11 — the `hljs` global. Minimal surface; not installed via @types. */
  const hljs: {
    highlight(code: string, options: { language: string; ignoreIllegals?: boolean }): { value: string };
    highlightAuto(code: string): { value: string; language?: string };
    highlightElement(el: HTMLElement): void;
    getLanguage(name: string): unknown;
    [key: string]: unknown;
  };

  // ─── App-internal globals published onto window (not visible to TS as script-scope consts) ─────

  /**
   * The Monaco editor handle published by app/editor/monaco-lsp.js (a plain classic <script>, not
   * type-checked). Feature-detected by the .tsx files, so it may be undefined.
   */
  interface TBMonacoHandle {
    getValue(): string;
    setValue(value: string): void;
    setTheme?(): void;
    layout(): void;
    dispose(): void;
  }
  interface TBMonacoApi {
    mountTypescript(opts: {
      host: HTMLElement;
      value: string;
      sourcePath?: string;
      languageId?: "typescript" | "javascript";
      onChange?: (text: string) => void;
      onSave?: () => void;
      readOnly?: boolean;
    }): Promise<TBMonacoHandle>;
    loadMonaco(): Promise<unknown>;
    MONACO_VERSION: string;
  }
  const TBMonaco: TBMonacoApi | undefined;

  // The consolidated data/UI namespace published by app/data-extract.jsx via `window.TB = {…}`.
  // There is no top-level `const TB`, so TS needs this ambient. Left as `any` for now — it aggregates
  // ~200 hooks/helpers whose shapes will firm up as the data layer is typed. Prefer narrowing this to
  // a real interface (mirroring the TbRpc pattern) as a follow-up.
  const TB: any;

  // home.tsx publishes `Empty` under the ALIAS `EmptyState` (`Object.assign(window, { ..., EmptyState:
  // Empty })`) — there's no top-level `const EmptyState`, only a `window.EmptyState` property, so
  // other screens calling bare `EmptyState(...)` need this ambient (browsers expose `window`'s own
  // properties as bare globals at runtime; TS's script-scope model doesn't infer that automatically).
  const EmptyState: any;

  // ─── run-report-core.js (plain classic <script>, also require()'d by the bun CLI) ──────────────
  // Referenced bare by timeline.tsx / share-export.tsx. Loose signatures for now.
  function extractTrace(logs: unknown): any[];
  function extractLlmLogs(logs: unknown): any[];
  function buildRunReportHtml(input: any): string;
  function slimTraceForShare(trace: any): any;
  function slimLlmForShare(llmLogs: any): any;
  function truncate(text: string, max: number): string;

  // ─── trail-model.js (pure step-matrix model; classic <script>, also require()'d by bun test) ────
  // Parses a UNIFIED single-file trail into an editable StepMatrix and serializes it back, round-trip
  // stable. Referenced via `window.TM` from trail-detail.tsx.
  interface TMTool { name: string; body: unknown }
  interface TMStep { kind: "trailhead" | "step" | "verify"; text: string; recording: Record<string, TMTool[]>; extra: Record<string, unknown> }
  interface TMStepMatrix { config: Record<string, unknown>; platforms: string[]; trailhead: TMStep | null; steps: TMStep[] }
  interface TMApi {
    recToTools(rec: unknown): TMTool[];
    toolsToRec(tools: TMTool[], trailheadForm?: boolean): unknown;
    /** null when `doc` isn't the unified `config`/`trail` mapping shape. */
    unifiedDocToMatrix(doc: unknown): TMStepMatrix | null;
    matrixToUnifiedDoc(model: TMStepMatrix): Record<string, unknown>;
  }
  const TM: TMApi;

  interface Window {
    TbRpc: TbRpcApi;
    TB: typeof TB;
    TBMonaco?: TBMonacoApi;
    /** Optional native bridge for the desktop file picker (absent in a plain browser). */
    trailblazePickDirectory?: (initial?: string) => Promise<string | null>;
    // The CDN globals above are also referenced via `window.X` in several files (not just bare) —
    // `const X` alone only makes `X` resolve as a bare identifier, not as a `window` member.
    jsyaml: typeof jsyaml;
    TM: typeof TM;
    CodeMirror: typeof CodeMirror;
    lucide: typeof lucide;
    hljs: typeof hljs;
    EmptyState: typeof EmptyState;
  }
}

export {};
