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
// `stream`, `organize`, `eventstep`, `place`, `eventq`, and `eventall` preserve the focused event
// navigator. Three keys are retired, and are listed only so a link written before their view went
// away is still canonicalized rather than ignored: `filter` (the Self-healed index filter), plus
// `mode` and `trail` (the standalone trail page's projection and scope). `dir`, `all` and `align`
// were that page's too but are NOT retired — they still carry the trail tabs' own layout.
// `basesession`/`vssession` name a compare side by session id rather than by index — how one
// report links into another, whose run order it cannot know. They are inbound only: the viewer
// resolves them to indices and writes `base`/`vs` back.
export const VIEWER_ROUTE_KEYS = [
  'view', 'runs', 'run', 'tab', 'step', 'kid', 'streams', 'types', 'llm', 'inspect',
  'stream', 'organize', 'eventstep', 'place', 'eventq', 'eventall', 'group', 'sort', 'search',
  'filter', 'mode', 'dir', 'all', 'align', 'trail', 'pick', 'base', 'vs', 'basesession', 'vssession', 'lane',
];
