// Pure grouping logic for the "Not installed — lives in another repo" section: turns the flat
// per-target NotInstalledTargetEntry list from TrailmapsResponse into one row per repo, because the
// unit a person acts on is the clone, not the target. Display strings (shortName, cloneCommand) are
// server-computed on each entry so this file never re-derives — and drifts from — the CLI's wording.
//
// Dual-exported like target-picker-model.js: `window.NotInstalledTargetsModel` for the
// classic-script app and `module.exports` for Bun tests.
(function (root, factory) {
  var api = factory();
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.NotInstalledTargetsModel = api;
})(typeof window !== 'undefined' ? window : null, function () {
  // Only http(s) URLs are safe to hand to an href: the url field is read verbatim from
  // known-target-workspaces YAML that a workspace can contribute, and React still renders
  // `javascript:` hrefs (warning only), which would execute on the daemon's origin.
  function safeHttpUrl(url) {
    return typeof url === 'string' && /^https?:\/\//i.test(url) ? url : null;
  }

  // Entries sharing a home repo collapse into one group carrying every target id, first-seen
  // order, keeping the first entry's url/description/cloneCommand. Grouped on the server-computed
  // shortName rather than the raw repo string, so the same repo spelled via SSH in one registry
  // file and HTTPS in another still renders as one row; the raw repo rides along as a stable key.
  function groupByRepo(entries) {
    var byKey = {};
    var order = [];
    (entries || []).forEach(function (e) {
      if (!e || !e.repo || !e.id) return;
      var key = e.shortName || e.repo;
      var g = byKey[key];
      if (!g) {
        g = byKey[key] = {
          repo: e.repo,
          shortName: e.shortName || e.repo,
          cloneCommand: e.cloneCommand || '',
          url: safeHttpUrl(e.url),
          description: e.description || null,
          targets: [],
        };
        order.push(g);
      }
      if (g.targets.indexOf(e.id) < 0) g.targets.push(e.id);
    });
    return order;
  }

  return { groupByRepo: groupByRepo, safeHttpUrl: safeHttpUrl };
});
