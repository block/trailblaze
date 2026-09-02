// The query parameters the report viewer owns.
//
// Report state lives in the query string so a copied URL communicates its selected run, view, and
// step. Two places must agree on the set: the viewer reads and rewrites these keys as the reader
// navigates (leaving everything else — a signed artifact's `jwt`, a host's `theme` — untouched), and
// the viewer shell strips them when a locally dropped archive replaces whatever the address
// described. A key one knows and the other doesn't is a stale instruction from the old report
// applied to the new one.
//
// Its own module because the shell and the viewer are bundled separately: importing either from the
// other would embed a whole report viewer in the loader script, or the loader in every exported
// report.
//
// 'stream' (the retired Events tab's selected-stream index) and 'filter' (the retired Self-healed
// index filter) are still listed so legacy URLs carrying them are canonicalized away, but nothing
// reads or writes them any more.
export const VIEWER_ROUTE_KEYS = [
  'view', 'runs', 'run', 'tab', 'step', 'kid', 'streams', 'types', 'llm', 'inspect',
  'stream', 'group', 'sort', 'search', 'filter', 'mode', 'dir', 'all', 'trail', 'pick', 'base', 'vs',
  'lane',
];
