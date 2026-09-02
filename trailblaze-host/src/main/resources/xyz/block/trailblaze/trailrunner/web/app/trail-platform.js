// Which platform a trail belongs to, and whether a platform filter should keep it. Pure (trail
// objects in, strings/booleans out), so `app/trail-platform.test.ts` can exercise it with bun.
// Dual-exported like app/trail-yaml.js: `window.TrailPlatform` for the classic-script browser load,
// `module.exports` for bun test.
//
// A trail names its platform in one of two ways, and they do NOT agree on case. `config.platform`
// is freeform YAML the author typed, so it can read "Android"; the filename fallback and every
// device the daemon reports are lowercase. Comparisons here normalize both sides — the run path
// (resolveRunDevice) already did, and the filter path not doing it is what made a trail declaring
// "Android" invisible under the android filter.
(function () {
  function derivePlatformFromTrail(trail) {
    var name = ((trail.path || trail.id || '').toLowerCase().replace(/\.trail\.yaml$/, '').split('/').pop()) || '';
    if (name === 'web' || name.includes('web')) return 'web';
    if (name.startsWith('ios') || name.includes('iphone') || name.includes('ipad')) return 'ios';
    if (name.startsWith('android') || name.includes('phone') || name.includes('tablet')) return 'android';
    return null;
  }

  /** A trail's platform, normalized, or null when it declares none and none can be derived. */
  function trailPlatform(t) {
    if (!t) return null;
    var p = t.platform || derivePlatformFromTrail(t);
    return p ? String(p).toLowerCase() : null;
  }

  /**
   * Whether a trail satisfies a platform filter. Blazes and unified trails are cross-platform
   * (they carry per-classifier recordings inline), so they pass any filter. Other trails match
   * via their explicit platform field or the filename-derived fallback.
   */
  function trailMatchesPlatform(t, platform) {
    if (!platform) return true;
    if (t.kind === 'blaze' || t.format === 'unified') return true;
    return trailPlatform(t) === String(platform).toLowerCase();
  }

  /**
   * The platform filter to apply when the active target's devices imply one, or 'all' when no
   * trail in scope carries it.
   *
   * The guard is the point. This platform comes from the devices the reader picked on another
   * screen, not from the filter they are looking at, so applying it blind can empty the tree over
   * a choice they never made - and an empty tree reads as an empty workspace. The target filter
   * beside it has always been guarded this way; the platform beside it never was.
   *
   * Returns the platform normalized, because what comes back is a filter VALUE - it is stored, it
   * is compared against the picker's options, and it is what the reader sees selected. Handing back
   * the caller's casing would let "Android" sit in the filter looking chosen while matching the
   * picker's `android` option not at all.
   */
  function platformScopeFor(trails, target, platform) {
    if (!platform) return 'all';
    var want = String(platform).toLowerCase();
    var scoped = (trails || []).filter(function (t) { return !target || target === 'all' || t.target === target; });
    return scoped.some(function (t) { return trailMatchesPlatform(t, want); }) ? want : 'all';
  }

  var api = { derivePlatformFromTrail: derivePlatformFromTrail, trailPlatform: trailPlatform, trailMatchesPlatform: trailMatchesPlatform, platformScopeFor: platformScopeFor };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (typeof window !== 'undefined') window.TrailPlatform = api;
})();
