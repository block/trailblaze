// Pure target-picker grouping logic. Browser devices do not have an "installed app" inventory,
// so they join every declared target that supports the web platform. Keeping this separate from
// the React screen makes the platform/target contract unit-testable without a browser.
//
// Dual-exported like trail-model.js: `window.TargetPickerModel` for the classic-script app and
// `module.exports` for Bun tests.
(function (root, factory) {
  var api = factory();
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.TargetPickerModel = api;
})(typeof window !== 'undefined' ? window : null, function () {
  function buildTargetGroups(input) {
    var deviceList = input.deviceList || [];
    var appsByDevice = input.appsByDevice || {};
    var trailmapList = input.trailmapList || [];
    var restartIds = new Set(input.restartIds || []);
    var groups = {};
    var webDevices = deviceList.filter(function (d) { return d.platform === 'web'; });

    // Mobile target membership comes from the installed-app scan: a target is selectable on a
    // phone only when its declared app is actually present there.
    deviceList.forEach(function (d) {
      if (d.platform === 'web') return;
      (appsByDevice[d.id] || []).forEach(function (a) {
        var g = groups[a.id] || (groups[a.id] = {
          id: a.id,
          label: a.displayName || a.id,
          items: [],
          platforms: [],
        });
        g.items.push({ device: d, app: a });
      });
    });

    var hasDeclaredWebTargets = false;
    trailmapList.forEach(function (t) {
      if (!t.displayName || t.workspaceListed === false) return;
      var platforms = t.platforms || [];
      if (platforms.length === 0) return;
      var supportsWeb = platforms.indexOf('web') >= 0;
      if (supportsWeb) hasDeclaredWebTargets = true;

      // Preserve declared-but-undetected mobile targets as disabled cards. Web targets are
      // immediately device-backed because a browser has no installability gate.
      var g = groups[t.id] || (groups[t.id] = { id: t.id, label: t.displayName, items: [], platforms: [] });
      // The declared platform list lets the card explain an empty group correctly — "no browser
      // connected" for a web target vs "app not installed" for a mobile one.
      g.platforms = platforms;
      if (supportsWeb && !restartIds.has(t.id)) {
        webDevices.forEach(function (d) {
          if (!g.items.some(function (it) { return it.device.id === d.id; })) {
            g.items.push({ device: d, app: null });
          }
        });
      }
    });

    var groupList = Object.values(groups).sort(function (x, y) {
      return (y.items.length > 0 ? 1 : 0) - (x.items.length > 0 ? 1 : 0)
        || x.label.localeCompare(y.label);
    });
    return { groupList: groupList, webDevices: webDevices, hasDeclaredWebTargets: hasDeclaredWebTargets };
  }

  function hasTargetSelection(selection) {
    return !!(selection && (selection.target || selection.label));
  }

  return {
    buildTargetGroups: buildTargetGroups,
    hasTargetSelection: hasTargetSelection,
  };
});
