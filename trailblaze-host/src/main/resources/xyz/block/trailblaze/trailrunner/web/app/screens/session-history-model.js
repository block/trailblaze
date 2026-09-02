// Pure grouping model for the History rail. Sessions remain the selectable/actionable rows; this
// layer only supplies stable local-day, primary-axis and repeat-run sections plus truthful status
// summaries. Every level has the same shape - `rows` newest-first, `counts` over all of them - so a
// caller can read a day, a leg or a group the same way.
(function () {
  function partsFor(ms, timeZone) {
    var opts = { year: 'numeric', month: '2-digit', day: '2-digit' };
    if (timeZone) opts.timeZone = timeZone;
    var parts = new Intl.DateTimeFormat('en-CA', opts).formatToParts(new Date(ms || 0));
    var out = {};
    parts.forEach(function (p) { if (p.type !== 'literal') out[p.type] = p.value; });
    return out.year + '-' + out.month + '-' + out.day;
  }

  function deviceOf(session) {
    var device = String((session || {}).device || '').split('·').map(function (p) { return p.trim(); }).filter(Boolean).join('-');
    return device || String((session || {}).platform || '').trim() || 'Unknown device';
  }

  // What makes two runs "the same thing, run again": the same trail dispatched at the same target.
  // A run with no `trailId` falls back to its own id, which groups it with nothing - deliberately.
  // The tempting fallback is `title`, but that is `SessionInfo.displayName`, whose own docs say it
  // "may collide between unrelated tests", and only browser dispatches record a `trailId` at all.
  // Keying on it would file two unrelated CLI trails that happen to share a name under one
  // disclosure, hiding the older one. Not collapsing is merely today's behaviour; collapsing the
  // wrong runs loses a run. `SessionInfo.stableTestKey` is the identity built for this and would
  // let CLI retries group properly, but it isn't on `SessionSummary` yet.
  function trailKeyOf(session) {
    var s = session || {};
    return [String(s.target || ''), String(s.trailId || s.id || '')].join('\u0000');
  }

  // A label has to name everything its key separates on, or two sections claim to be the same thing.
  // `trailKeyOf` separates on target as well as trail, so one trail run at two targets is two legs -
  // and on the title alone their headers would read identically, which is worse than verbose. The
  // last resort is `trailId` rather than the session id: a path still tells the reader which trail
  // ran, where a raw session id names nothing they have ever seen.
  function trailLabelOf(session) {
    var s = session || {};
    var name = String(s.title || s.trailId || s.id || '');
    var target = String(s.target || '');
    return target ? name + ' · ' + target : name;
  }

  // The two grouping axes, defined once as each other's mirror image: whichever one the reader picks
  // becomes the section header, and the other becomes the repeat-run group inside it. Keeping them
  // in one table is what stops "group by trail" growing its own parallel copy of the walk below.
  var AXES = {
    device: { keyOf: deviceOf, labelOf: deviceOf, other: 'trail' },
    trail: { keyOf: trailKeyOf, labelOf: trailLabelOf, other: 'device' },
  };

  // Own-property only: the axis arrives from localStorage, so a hand-edited or stale `'constructor'`
  // would otherwise pass a plain `AXES[groupBy]` test off the prototype chain and take the walk
  // below into a node with no `keyOf`.
  function axisOf(groupBy) {
    return Object.prototype.hasOwnProperty.call(AXES, groupBy) ? groupBy : 'device';
  }

  function countsOf(rows) {
    var c = { total: rows.length, passed: 0, failed: 0, cancelled: 0, imported: 0 };
    rows.forEach(function (s) {
      var status = String(s.status || '').toLowerCase();
      if (status === 'passed' || status === 'healed') c.passed++;
      else if (status === 'failed' || status === 'error' || status === 'timeout') c.failed++;
      else if (status === 'cancelled' || status === 'canceled' || status === 'stopped') c.cancelled++;
      if (s.imported) c.imported++;
    });
    return c;
  }

  // A group's outcome summary, as glyphs and as words. Both list the same four buckets and skip
  // whatever is zero, so a caller can never show one and forget the other - `imported` is the easy
  // one to drop, and a group whose earlier runs are all archives would then summarise as blank.
  function countsGlyphs(counts) {
    var c = counts || {};
    return [
      c.passed ? c.passed + '✓' : null,
      c.failed ? c.failed + '×' : null,
      c.cancelled ? c.cancelled + '■' : null,
      c.imported ? c.imported + '↥' : null,
    ].filter(Boolean).join(' · ');
  }

  function countsWords(counts) {
    var c = counts || {};
    return [
      c.passed ? c.passed + ' passed' : null,
      c.failed ? c.failed + ' failed' : null,
      c.cancelled ? c.cancelled + ' cancelled' : null,
      c.imported ? c.imported + ' imported' : null,
    ].filter(Boolean).join(', ');
  }

  // Whether a trail's file stem is just its title again, slugified - "Checkout with a card" beside
  // `checkout-with-a-card`. That is the common case, and in a rail this narrow the repeat is what
  // pushed the row onto a second line. A stem that genuinely differs still earns its place, because
  // then it is the only thing separating two trails that share a title.
  function isSlugOfTitle(title, name) {
    var slug = function (t) { return String(t || '').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, ''); };
    var a = slug(title);
    return !!a && a === slug(name);
  }

  function labelForDay(key, nowMs, timeZone) {
    var today = partsFor(nowMs == null ? Date.now() : nowMs, timeZone);
    var yesterday = partsFor((nowMs == null ? Date.now() : nowMs) - 86400000, timeZone);
    if (key === today) return 'Today';
    if (key === yesterday) return 'Yesterday';
    var bits = key.split('-').map(Number);
    return new Intl.DateTimeFormat(undefined, { month: 'short', day: 'numeric', year: 'numeric', ...(timeZone ? { timeZone: timeZone } : {}) })
      .format(new Date(Date.UTC(bits[0], bits[1] - 1, bits[2], 12)));
  }

  function groupSessions(sessions, options) {
    var opts = options || {};
    var groupBy = axisOf(opts.groupBy);
    var leg = AXES[groupBy];
    var group = AXES[leg.other];
    var sorted = (sessions || []).slice().sort(function (a, b) { return (b.timestampMs || 0) - (a.timestampMs || 0); });
    var days = [];
    var byDay = {};
    sorted.forEach(function (s) {
      var dayKey = partsFor(s.timestampMs || 0, opts.timeZone);
      var day = byDay[dayKey];
      if (!day) {
        day = byDay[dayKey] = { key: dayKey, label: labelForDay(dayKey, opts.nowMs, opts.timeZone), rows: [], legs: [], _legs: {} };
        days.push(day);
      }
      day.rows.push(s);
      var legKey = leg.keyOf(s);
      var legNode = day._legs[legKey];
      if (!legNode) {
        legNode = day._legs[legKey] = { key: legKey, label: leg.labelOf(s), rows: [], groups: [], _groups: {} };
        day.legs.push(legNode);
      }
      legNode.rows.push(s);
      // `sorted` is newest-first, so the run that opens a group IS that group's latest and the
      // groups themselves come out ordered by how recently each last ran.
      var groupKey = group.keyOf(s);
      var groupNode = legNode._groups[groupKey];
      // A group is the same group on either axis - one device's runs of one trail on one day - so
      // its disclosure key is built in a fixed order rather than in traversal order. Traversal
      // order is not injective: read by device, `(device A, target B, trail C)` concatenates to
      // exactly what `(device C, target A, trail B)` gives when read by trail, and those are two
      // unrelated runs whose disclosures would then toggle each other. Fixing the order also means
      // a group the reader closed stays closed when they switch axes, which is what "the same
      // group" ought to mean. The day is in there because the same pair recurs daily.
      if (!groupNode) {
        groupNode = legNode._groups[groupKey] = {
          key: [dayKey, AXES.device.keyOf(s), AXES.trail.keyOf(s)].join('\u0000'),
          label: group.labelOf(s),
          rows: [],
        };
        legNode.groups.push(groupNode);
      }
      groupNode.rows.push(s);
    });
    days.forEach(function (day) {
      day.counts = countsOf(day.rows);
      day.legs.forEach(function (legNode) {
        legNode.counts = countsOf(legNode.rows);
        legNode.groups.forEach(function (groupNode) { groupNode.counts = countsOf(groupNode.rows); });
        delete legNode._groups;
      });
      delete day._legs;
    });
    return days;
  }

  // Whether a group renders expanded. `open` holds explicit reader toggles only, so absence means
  // "use the default", and the default is open exactly while a run the disclosure would hide holds
  // the selection - the rail must never point at a row it is covering. Selecting the visible latest
  // is NOT enough: that would force every group open the moment you clicked its top row.
  function groupOpen(group, selectedId, open) {
    var rows = (group || {}).rows || [];
    var key = (group || {}).key;
    if (open && Object.prototype.hasOwnProperty.call(open, key)) return !!open[key];
    return rows.some(function (r, i) { return i > 0 && r.id === selectedId; });
  }

  // Hand a group back to that default when the selection lands on a run it is hiding - a deep
  // link, a retry, or the Home screen's recent list can all select a run the reader had closed
  // away. Only ever CLEARS a stored close, so it cannot reopen a group whose selection sits
  // elsewhere, and it is driven by WHICH run is selected rather than by whether some hidden run
  // is: moving between two hidden runs of one closed group would otherwise strand the second.
  function revealSelectedGroup(open, days, selectedId) {
    if (!open || !selectedId) return open || {};
    var next = open;
    eachGroup(days, function (group) {
      if (next[group.key] !== false) return;
      if (!group.rows.some(function (r, i) { return i > 0 && r.id === selectedId; })) return;
      if (next === open) next = Object.assign({}, open);
      delete next[group.key];
    });
    return next;
  }

  function eachGroup(days, fn) {
    (days || []).forEach(function (day) {
      (day.legs || []).forEach(function (leg) {
        (leg.groups || []).forEach(fn);
      });
    });
  }

  // The runs the rail actually renders, in the order it renders them. Arrow-key navigation picks its
  // target by POSITION among the rendered rows, so it has to walk this list rather than the full set
  // of sessions: with a closed disclosure the two lengths differ and every index past that group
  // points at the wrong row.
  function visibleRuns(days, open, selectedId) {
    var out = [];
    eachGroup(days, function (group) {
      var rows = group.rows || [];
      if (!rows.length) return;
      out.push(rows[0]);
      if (rows.length > 1 && groupOpen(group, selectedId, open)) out.push.apply(out, rows.slice(1));
    });
    return out;
  }

  var api = {
    partsFor: partsFor,
    deviceOf: deviceOf,
    trailKeyOf: trailKeyOf,
    trailLabelOf: trailLabelOf,
    axisOf: axisOf,
    countsOf: countsOf,
    isSlugOfTitle: isSlugOfTitle,
    countsGlyphs: countsGlyphs,
    countsWords: countsWords,
    groupSessions: groupSessions,
    groupOpen: groupOpen,
    revealSelectedGroup: revealSelectedGroup,
    visibleRuns: visibleRuns,
  };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (typeof window !== 'undefined') window.SessionHistoryModel = api;
})();
