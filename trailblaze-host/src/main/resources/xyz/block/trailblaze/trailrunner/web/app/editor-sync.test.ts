// Behavior tests for the trail editor's on-disk-change rule (app/editor-sync.js). The editor polls
// the file it has open so an edit made in another IDE (or by an agent) shows up on its own; these
// pin the part that must never regress - unsaved work is not silently replaced.
//
// Run: `bun test app/editor-sync.test.ts` from the web/ directory.
import { describe, expect, test } from 'bun:test';
// editor-sync.js dual-exports via module.exports; bun interops the CJS default import.
import Sync from './editor-sync.js';

const action = (args: Record<string, unknown>) => Sync.diskChangeAction(args);

describe('diskChangeAction', () => {
  test('waits until there is file content to show', () => {
    expect(action({ disk: null, text: null, baseline: null })).toBe('wait');
  });

  test('seeds an empty buffer with the file', () => {
    expect(action({ disk: 'trail: a', text: null, baseline: null })).toBe('seed');
  });

  test('does nothing while the file still says what the buffer was loaded from', () => {
    // The steady poll returns identical content almost every time - that must be inert, including
    // while the user is typing.
    expect(action({ disk: 'trail: a', text: 'trail: a', baseline: 'trail: a' })).toBe('keep');
    expect(action({ disk: 'trail: a', text: 'trail: a + my edits', baseline: 'trail: a' })).toBe('keep');
  });

  test('follows the file when the buffer has no unsaved edits', () => {
    expect(action({ disk: 'trail: b', text: 'trail: a', baseline: 'trail: a' })).toBe('adopt');
  });

  test('reports a conflict instead of overwriting unsaved edits', () => {
    // The whole point of the rule: an agent rewriting the file must not eat what the user typed.
    expect(action({ disk: 'trail: b', text: 'trail: mine', baseline: 'trail: a' })).toBe('conflict');
  });

  test('a buffer edited to exactly match the new file on disk is not a conflict', () => {
    expect(action({ disk: 'trail: b', text: 'trail: b', baseline: 'trail: a' })).toBe('adopt');
  });

  test('empty content is never offered as a replacement for a buffer that has text', () => {
    // The daemon answers a file it could not read with an empty body and a success status, so an
    // unreadable file must not read as "the file was emptied" and put a blank up for adoption.
    expect(action({ disk: '', text: 'trail: a', baseline: 'trail: a' })).toBe('wait');
    expect(action({ disk: '', text: 'trail: mine', baseline: 'trail: a' })).toBe('wait');
    // A genuinely empty file still seeds an editor that holds nothing.
    expect(action({ disk: '', text: null, baseline: null })).toBe('seed');
  });
});

describe('polledFileContent', () => {
  const OPEN = 'folder-1/android.trail.yaml';
  // `extra` is the file the read's `data` came from; the second argument is the file the editor is
  // showing. The default here is the steady state where they agree.
  const content = (args: Record<string, unknown>, key: string = OPEN) =>
    Sync.polledFileContent({ extra: OPEN, ...args }, key);

  test('hands the editor the file once it has been read', () => {
    expect(content({ data: 'trail: a', loading: false })).toBe('trail: a');
  });

  test('shows nothing while the first read is still out', () => {
    // Not the placeholder: the file is probably fine, we just haven't heard back yet, and flashing
    // "could not read file" into the editor on every open would be a lie most of the time.
    expect(content({ data: null, loading: true })).toBe(null);
  });

  test('shows nothing on the frame that names a file before its read has started', () => {
    // The read hook only turns `loading` on from an effect, so the render that first names a file
    // still reports the previous one's settled empty state - no data, no error, not loading. That is
    // "we haven't asked yet", not "we asked and got nothing", and the editor seeds its buffer from
    // this frame, so calling it unreadable is how the placeholder ends up in the editor on open.
    expect(content({ data: null, loading: false })).toBe(null);
  });

  test('shows nothing on the frame still carrying the file the editor just left', () => {
    // Same one-render lag, but with the previous file's text in hand rather than nothing. The editor
    // resets to the incoming content on this frame, so handing it the old file's text seeds the new
    // editor with the wrong file - and typing then saves the old file over the new one.
    expect(content({ data: 'trail: previous', loading: false }, 'folder-1/ios.trail.yaml')).toBe(null);
  });

  test('says so when the read of THIS file failed', () => {
    const failed = Object.assign(new Error('gone'), { fileKey: OPEN });
    expect(content({ data: null, loading: false, error: failed })).toBe(Sync.UNREADABLE);
    // An unstamped error is taken at face value: nothing else identifies it, and reading it as
    // another file's problem would hide a genuine one.
    expect(content({ data: null, loading: false, error: new Error('gone') })).toBe(Sync.UNREADABLE);
  });

  test('does not call a file unreadable because the file before it was', () => {
    // A failed read clears `data` AND `extra`, so on the frame that first names the next file the
    // only thing identifying that failure is the key stamped on the error. Reading it as this file's
    // failure seeds the placeholder into an editor whose file has not been asked for yet.
    const failed = Object.assign(new Error('gone'), { fileKey: OPEN });
    expect(content({ data: null, loading: false, error: failed }, 'folder-1/ios.trail.yaml')).toBe(null);
  });

  test('a failed poll does not replace a file that was already read', () => {
    // The rule that matters, and the reason this isn't just `error ? placeholder : data`. A failed
    // tick sets `error` but keeps the last good `data`, so reading the error would put a message
    // where the user's file - and any unsaved edits reconciling against it - had been.
    expect(content({ data: 'trail: a', loading: false, error: new Error('daemon hiccup') })).toBe('trail: a');
  });

  test('an empty file is content, not a missing read', () => {
    expect(content({ data: '', loading: false })).toBe('');
  });
});

describe('polledTrailDetail', () => {
  const OPEN = '0/sample/login/android-phone';
  const detail = (args: Record<string, unknown>, id: string = OPEN) =>
    Sync.polledTrailDetail({ extra: OPEN, loading: false, error: null, ...args }, id);

  test('hands over the detail once the trail has been read', () => {
    expect(detail({ data: { yaml: 'trail: a' } }).data).toEqual({ yaml: 'trail: a' });
  });

  test('withholds the detail of the trail the user just left', () => {
    // The bug this exists for: the read only turns `loading` on from an effect, so the render that
    // first names a new trail still reports the previous one's settled detail - and the editor seeds
    // its buffer from that render, showing the wrong trail's YAML under the selected trail.
    const read = detail({ data: { yaml: 'trail: previous' } }, '0/sample/login/ios-iphone');
    expect(read.data).toBe(null);
    // Restored, because fetching the newly-named trail is exactly what happens next: consumers that
    // gate on `loading` show their loading state instead of the previous trail's content.
    expect(read.loading).toBe(true);
  });

  test('a failed read of THIS trail stays a failure, not a skeleton', () => {
    // A deps-change failure clears `data` and `extra` together, so there is nothing to narrow. Left
    // alone it reports "couldn't load"; forced back to loading it would spin forever.
    const read = detail({ data: null, extra: undefined, error: new Error('gone') });
    expect(read.data).toBe(null);
    expect(read.loading).toBe(false);
    expect(read.error).toBeInstanceOf(Error);
  });

  test('no trail asked for is not a mismatch', () => {
    expect(Sync.polledTrailDetail({ data: null, extra: '', loading: false }, null).loading).toBe(false);
  });

  test('carries the rest of the read through untouched', () => {
    // Consumers read `reload` / `inFlight` off the same object, so narrowing must not drop them.
    const reload = () => {};
    const read = Sync.polledTrailDetail({ data: { yaml: 'x' }, extra: OPEN, loading: false, reload }, 'other');
    expect(read.reload).toBe(reload);
  });
});

describe('canDiffTrail', () => {
  const can = (baseline: unknown, current: unknown = 'trail: b') => Sync.canDiffTrail(baseline, current);

  test('a committed text is something to diff against', () => {
    expect(can({ state: 'modified', committed: 'trail: a' })).toBe(true);
    expect(can({ state: 'clean', committed: 'trail: a' })).toBe(true);
    // A file committed empty has a real baseline: every line in the buffer is genuinely new.
    expect(can({ state: 'modified', committed: '' })).toBe(true);
  });

  test('states that carry no committed text offer no diff', () => {
    // The rule that matters. Both of these arrive without a baseline and mean opposite things - "all
    // new" vs "git could not answer" - and diffing either against nothing would render the whole
    // trail as deleted.
    expect(can({ state: 'untracked', committed: null })).toBe(false);
    expect(can({ state: 'unavailable', committed: null })).toBe(false);
    expect(can({ state: 'untracked' })).toBe(false);
  });

  test('no answer yet is not a baseline', () => {
    expect(can(null)).toBe(false);
    expect(can(undefined)).toBe(false);
  });

  test('a missing working text offers no diff either', () => {
    // No buffer and no polled file: the trail was deleted or renamed while open. Diffing against an
    // empty right-hand side renders the whole trail as deleted, the left-hand misread in mirror.
    const baseline = { state: 'modified', committed: 'trail: a' };
    expect(can(baseline, null)).toBe(false);
    // Direct, not through `can`: an explicit undefined would take the helper's default.
    expect(Sync.canDiffTrail(baseline, undefined)).toBe(false);
    // A trail the user emptied in the buffer is a real text: those deletions are the diff.
    expect(can(baseline, '')).toBe(true);
  });
});
