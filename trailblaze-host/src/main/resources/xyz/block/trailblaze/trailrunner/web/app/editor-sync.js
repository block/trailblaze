// What the trail editor does when the file it has open changes on disk.
//
// Trail files are edited outside Trail Runner constantly - another IDE, a git checkout, an agent
// writing a whole suite - so the editor follows the file rather than showing whatever it said when
// it was opened. The one thing it must never do is throw away work: a buffer with unsaved edits is
// not replaced silently, it reports the conflict and lets the user pick a side.
//
// Plain JS (classic <script>, no transpile step) with a CommonJS tail so app/editor-sync.test.ts can
// require it.
(function () {
  'use strict';

  // How the editor should react to `disk` given the buffer it holds:
  //   'wait'     - no file content to show yet
  //   'seed'     - first content for this trail: put it in the buffer
  //   'adopt'    - the file changed and the buffer has nothing to lose: follow it
  //   'conflict' - the file changed under unsaved edits: surface it, change nothing
  //   'keep'     - the buffer already matches the file
  //
  // `text` is the buffer (null before the first load), `baseline` is what the buffer was last known
  // to share with disk - the save, the seed, and the adopt all move it, so `text !== baseline` is
  // exactly "has unsaved edits".
  function diskChangeAction(args) {
    var a = args || {};
    if (a.disk == null) return 'wait';
    if (a.text == null) return 'seed';
    // Empty content against a buffer that has something in it is treated as no news, not as a file
    // that was truncated. The daemon reports a file it could not READ as a successful detail with an
    // empty body, so following it would let a transient read error offer to replace real work with
    // nothing. A genuinely emptied file shows up the next time the trail is opened.
    if (a.disk === '' && a.text !== '') return 'wait';
    if (a.disk === a.baseline) return 'keep';
    // `text === disk` is adopted rather than flagged: the buffer already IS the file (the user typed
    // the same change, or the writer applied theirs), so there is nothing to reconcile - only the
    // stale baseline, which adopting moves.
    return (a.text === a.baseline || a.text === a.disk) ? 'adopt' : 'conflict';
  }

  // Shown in place of the file when the very first read of it failed. Deliberately not YAML: it is a
  // message, and a buffer holding it is one the user should not save over the file.
  var UNREADABLE = '(could not read file)';

  // What content to hand the editor for a POLLED file read: `read` is that read's
  // `{data, error, extra}`, and `key` names the file the caller is asking about. The read stamps
  // `extra` with the file its `data` came from.
  //
  // Every rule here exists because the read only reports `loading` from an effect, so the render
  // that first names a new file still carries the PREVIOUS file's settled state - and the editor
  // seeds its buffer from whatever that frame says.
  //
  //  - Data wins over an error, because a failed poll keeps the last good data. Reading the error
  //    instead would replace the user's file, and any unsaved edits reconciling against it, with a
  //    message every time a tick caught the daemon mid-hiccup.
  //  - But only data stamped with THIS file. Otherwise the new editor seeds with the old file's
  //    text, and typing in that window means saving the old file over the new one.
  //  - No data at all is not a failure: it is "we haven't asked yet". Reporting it as unreadable put
  //    the placeholder in the editor on every open, and it stuck, because it then WAS the buffer.
  //  - An error only speaks for the file it was raised against. A failed read clears `extra` along
  //    with the data, so the failure carries the file on the error itself (`error.fileKey`); without
  //    that, the frame naming a new file right after a failed read of the old one would call the new
  //    file unreadable before anything had asked for it. An unstamped error is taken at face value.
  function polledFileContent(read, key) {
    var a = read || {};
    if (a.data != null) return a.extra === key ? a.data : null;
    if (!a.error) return null;
    return a.error.fileKey == null || a.error.fileKey === key ? UNREADABLE : null;
  }

  // The same one-render lag as polledFileContent, on the Edit tab's trail-detail read instead of the
  // pushed editor's file read. `useTrailDetail` only turns `loading` on from an effect, so the render
  // that first names a new trail still reports the PREVIOUS trail's settled detail: data present,
  // loading false. The editor resets its buffer to the incoming content on exactly that render, so
  // that frame is what seeds it - with the trail the user just left.
  //
  // Answers with the read narrowed to the trail it was asked about: another trail's detail is removed
  // and `loading` restored, because a fetch for THIS trail is what happens next. Only `data` is
  // judged - an error is left alone, so a genuine failure to read this trail still reports itself
  // rather than turning into a skeleton that never resolves.
  function polledTrailDetail(read, id) {
    var a = read || {};
    if (a.data == null || a.extra === id) return a;
    var narrowed = {};
    for (var k in a) narrowed[k] = a[k];
    narrowed.data = null;
    narrowed.loading = true;
    return narrowed;
  }

  // Whether there is a real TEXT on both sides to diff. On the left, only a committed text counts:
  // an untracked trail and a workspace git can't answer for both arrive without one, and the two
  // mean opposite things ("all of this is new" vs "we don't know"), so neither may be shown as a
  // diff against nothing - an empty left-hand side reads as having deleted the whole file. The right
  // side is missing when there is no buffer and the polled file is gone (deleted or renamed while
  // open), which renders as having deleted the whole trail: the same misread, mirrored. An empty
  // string on either side is a real text - a file committed empty, or a trail emptied in the buffer.
  function canDiffTrail(baseline, current) {
    return !!baseline && typeof baseline.committed === 'string' && typeof current === 'string';
  }

  var api = {
    diskChangeAction: diskChangeAction,
    polledFileContent: polledFileContent,
    polledTrailDetail: polledTrailDetail,
    canDiffTrail: canDiffTrail,
    UNREADABLE: UNREADABLE,
  };

  if (typeof module !== 'undefined' && module.exports) module.exports = api; // bun test / CommonJS
  if (typeof window !== 'undefined') window.TbEditorSync = api;              // browser classic script
})();
