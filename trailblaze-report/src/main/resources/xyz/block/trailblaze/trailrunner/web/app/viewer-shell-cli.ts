// bun driver that writes the viewer shell — the data-less edition of the interactive report, which
// loads a session archive by URL, by paste, or by drop and renders it in the browser with no daemon.
//
//   bun run viewer-shell-cli.ts              # shell HTML to stdout
//   bun run viewer-shell-cli.ts out.html     # …or to a file
//
// One generated file IS the whole viewer, so a hosted copy can never drift from the renderer: it
// carries the same stylesheet, viewer bundle, and ZIP pipeline a report does, with no run baked in.
// Deploy it by serving that file from any static host.
import { writeFileSync } from 'fs';
import { buildViewerShellHtml } from './run-report-shell-html';

const html = buildViewerShellHtml();
const out = process.argv[2];
if (out) writeFileSync(out, html);
else process.stdout.write(html);
