// Entry point for the viewer shell's loader bundle: the bun bundler builds this file (plus what it
// imports) into the self-executing classic script buildViewerShellHtml embeds. The derivation and
// payload-shaping functions it needs are bundled in HERE (see run-report-shell's REPORT_DERIVE),
// which keeps them out of the viewer bundle every exported report embeds.
import { RUN_REPORT_SHELL } from './run-report-shell';

RUN_REPORT_SHELL();
