import { describe, expect, test } from 'bun:test';

const navigation = require('./workspace-navigation.js');

describe('extension workspace navigation', () => {
  test('only promotes integrations with workspace hrefs', () => {
    const items = navigation.items([
      { id: 'cases', name: 'Cases', action: { href: '/trailrunner/cases', navigationLabel: 'Test cases', navigationIcon: 'list-checks' } },
      { id: 'token', name: 'Token only', connected: true },
      { id: 'runs', name: 'Provider', action: { href: '/trailrunner/runs', navigationLabel: 'Cloud runs' } },
      { id: 'root', name: 'Trail Runner', action: { href: '/trailrunner' } },
      { id: 'traversal', name: 'Traversal', action: { href: '/trailrunner/../admin' } },
      { id: 'external', name: 'External', action: { href: 'https://example.com/trailrunner/workspace' } },
    ], { origin: 'http://localhost:52525' });

    expect(items).toEqual([
      { id: 'cases', route: 'workspace:cases', label: 'Test cases', icon: 'list-checks', href: '/trailrunner/cases' },
      { id: 'runs', route: 'workspace:runs', label: 'Cloud runs', icon: 'panels-top-left', href: '/trailrunner/runs' },
      { id: 'root', route: 'workspace:root', label: 'Trail Runner', icon: 'panels-top-left', href: '/trailrunner' },
    ]);
  });

  test('navigates through the supplied browser location', () => {
    let assigned = '';
    const locationLike = { origin: 'http://localhost:52525', assign: (href: string) => { assigned = href; } };

    expect(navigation.navigate({ href: '/trailrunner/workspace' }, locationLike)).toBe(true);
    expect(assigned).toBe('http://localhost:52525/trailrunner/workspace');
    expect(navigation.navigate({ href: '/trailrunner' }, locationLike)).toBe(true);
    expect(assigned).toBe('http://localhost:52525/trailrunner');
    expect(navigation.navigate({}, locationLike)).toBe(false);
  });

  test('rejects destinations outside the current Trail Runner origin', () => {
    let assigned = '';
    const locationLike = { origin: 'http://localhost:52525', assign: (href: string) => { assigned = href; } };

    expect(navigation.navigate({ href: 'https://example.com/trailrunner/workspace' }, locationLike)).toBe(false);
    expect(navigation.navigate({ href: '/settings' }, locationLike)).toBe(false);
    expect(navigation.navigate({ href: '/trailrunner/../admin' }, locationLike)).toBe(false);
    expect(navigation.navigate({ href: 'javascript:alert(1)' }, locationLike)).toBe(false);
    expect(assigned).toBe('');
  });

  test('routes explicit embedded links through the parent shell', () => {
    const anchor = (attributes: Record<string, string>) => ({
      getAttribute: (name: string) => attributes[name] || null,
    });
    const locationLike = { origin: 'http://localhost:52525' };

    expect(navigation.embeddedRequest(anchor({
      href: '/trailrunner/',
      'data-trailrunner-route': 'runs',
      'data-trailrunner-session': 'session-42',
    }), locationLike)).toEqual({ route: 'runs', params: { sel: 'session-42' } });
    expect(navigation.embeddedRequest(anchor({
      href: '/trailrunner/',
      'data-trailrunner-route': 'settings',
    }), locationLike)).toBeNull();
    expect(navigation.embeddedRequest(anchor({
      href: 'https://example.com/trailrunner/',
      'data-trailrunner-route': 'runs',
    }), locationLike)).toBeNull();
  });
});
