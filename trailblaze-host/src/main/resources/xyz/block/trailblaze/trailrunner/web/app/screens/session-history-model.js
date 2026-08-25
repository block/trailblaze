// Pure grouping model for the History rail. Sessions remain the selectable/actionable rows; this
// layer only supplies stable local-day and device-leg sections plus truthful status summaries.
(function () {
  function partsFor(ms, timeZone) {
    var opts = { year: 'numeric', month: '2-digit', day: '2-digit' };
    if (timeZone) opts.timeZone = timeZone;
    var parts = new Intl.DateTimeFormat('en-CA', opts).formatToParts(new Date(ms || 0));
    var out = {};
    parts.forEach(function (p) { if (p.type !== 'literal') out[p.type] = p.value; });
    return out.year + '-' + out.month + '-' + out.day;
  }

  function legOf(session) {
    var device = String((session || {}).device || '').split('·').map(function (p) { return p.trim(); }).filter(Boolean).join('-');
    return device || String((session || {}).platform || '').trim() || 'Unknown device';
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
    var sorted = (sessions || []).slice().sort(function (a, b) { return (b.timestampMs || 0) - (a.timestampMs || 0); });
    var days = [];
    var byDay = {};
    sorted.forEach(function (s) {
      var key = partsFor(s.timestampMs || 0, opts.timeZone);
      var day = byDay[key];
      if (!day) {
        day = byDay[key] = { key: key, label: labelForDay(key, opts.nowMs, opts.timeZone), rows: [], legs: [], _legs: {} };
        days.push(day);
      }
      day.rows.push(s);
      var legName = legOf(s);
      var leg = day._legs[legName];
      if (!leg) { leg = day._legs[legName] = { key: legName, label: legName, rows: [] }; day.legs.push(leg); }
      leg.rows.push(s);
    });
    days.forEach(function (day) {
      day.counts = countsOf(day.rows);
      day.legs.forEach(function (leg) { leg.counts = countsOf(leg.rows); });
      delete day._legs;
    });
    return days;
  }

  var api = { partsFor: partsFor, legOf: legOf, countsOf: countsOf, groupSessions: groupSessions };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (typeof window !== 'undefined') window.SessionHistoryModel = api;
})();
