(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  if (root) root.TbWorkspaceNavigation = api;
})(typeof window !== 'undefined' ? window : globalThis, function () {
  function workspaceTarget(href, locationLike) {
    if (typeof href !== 'string') return null;
    const location = locationLike || (typeof window !== 'undefined' ? window.location : { origin: 'http://trailrunner.local' });
    try {
      const target = new URL(href, location.origin);
      const inTrailRunner = target.pathname === '/trailrunner' || target.pathname.startsWith('/trailrunner/');
      return target.origin === location.origin && inTrailRunner ? target : null;
    } catch (_) {
      return null;
    }
  }

  function isWorkspaceHref(href, locationLike) {
    return workspaceTarget(href, locationLike) !== null;
  }

  function items(integrations, locationLike) {
    return (integrations || [])
      .filter((integration) => integration && integration.action && isWorkspaceHref(integration.action.href, locationLike))
      .map((integration) => ({
        id: integration.id,
        route: 'workspace:' + integration.id,
        label: integration.action.navigationLabel || integration.name,
        icon: integration.action.navigationIcon || 'panels-top-left',
        href: integration.action.href,
      }));
  }

  function navigate(item, locationLike) {
    if (!item || !item.href) return false;
    const location = locationLike || window.location;
    const target = workspaceTarget(item.href, location);
    if (!target) return false;
    location.assign(target.href);
    return true;
  }

  const SHELL_ROUTES = new Set(['active', 'completed', 'runs', 'trails']);

  // Embedded workspaces stay inside Trail Runner's frame, so links back to a native screen
  // must ask the parent shell to navigate instead of loading another shell inside the iframe.
  // The explicit data attribute keeps ordinary links (including external source links) alone.
  function embeddedRequest(anchor, locationLike) {
    if (!anchor || typeof anchor.getAttribute !== 'function') return null;
    const route = anchor.getAttribute('data-trailrunner-route');
    if (!SHELL_ROUTES.has(route)) return null;
    const href = anchor.getAttribute('href');
    const location = locationLike || window.location;
    if (!href) return null;
    if (!workspaceTarget(href, location)) return null;
    const sessionId = anchor.getAttribute('data-trailrunner-session');
    return { route, params: sessionId ? { sel: sessionId } : {} };
  }

  return { items, navigate, embeddedRequest };
});
