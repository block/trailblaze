// Entry point for the viewer shell's loader bundle: the bun bundler builds this file (plus what it
// imports) into the self-executing classic script buildViewerShellHtml embeds. Deliberately tiny —
// the derivation and payload-shaping code it needs is already inside the viewer bundle embedded in
// the same document, reached through window.__TB_REPORT_DERIVE rather than bundled a second time.
import { RUN_REPORT_SHELL } from './run-report-shell';

RUN_REPORT_SHELL();
