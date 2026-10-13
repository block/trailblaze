import { describe, expect, test } from 'bun:test';
import { externalComparisonUrl, hasExternalComparison, isExternalRunComparable } from './run-report-index-links';

const link = (archive: string, viewer = 'https://cdn.example/viewer/index.html') => {
  const url = new URL(viewer);
  url.searchParams.append('zip', archive);
  return url.href;
};

describe('index comparisons in the shared viewer', () => {
  test('opens only the selected archives, in selection order, directly in Compare', () => {
    const first = 'https://cdn.example/runs/first.zip?signature=a+b&expires=9';
    const second = 'https://cdn.example/runs/second.zip';
    const url = new URL(externalComparisonUrl([
      link(first, 'https://cdn.example/viewer/index.html?theme=light&view=detail&run=9#old'),
      link(second, 'https://cdn.example/viewer/index.html?theme=light'),
    ])!);
    expect(url.searchParams.getAll('zip')).toEqual([first, second]);
    expect(url.searchParams.get('view')).toBe('compare');
    expect(url.searchParams.get('pick')).toBe('0,1');
    expect(url.searchParams.get('theme')).toBe('light');
    expect(url.searchParams.has('run')).toBe(false);
    expect(url.hash).toBe('');
  });

  test('duplicate links never add a second copy of one session', () => {
    const a = link('https://cdn.example/a.zip');
    const b = link('https://cdn.example/b.zip');
    expect(externalComparisonUrl([a, a])).toBeNull();
    const url = new URL(externalComparisonUrl([a, a, b])!);
    expect(url.searchParams.getAll('zip')).toHaveLength(2);
    expect(url.searchParams.get('pick')).toBe('0,1');
  });

  test('runs from different viewer versions cannot be silently combined', () => {
    const a = link('https://cdn.example/a.zip', 'https://cdn.example/viewer/v1/index.html');
    const b = link('https://cdn.example/b.zip', 'https://cdn.example/viewer/v2/index.html');
    expect(externalComparisonUrl([a, b])).toBeNull();
    expect(hasExternalComparison([a, b])).toBe(false);
    expect(hasExternalComparison([a, b, link('https://cdn.example/c.zip', 'https://cdn.example/viewer/v1/index.html')])).toBe(true);
  });

  test('missing, unsafe, or multi-session destinations are not selectable', () => {
    for (const value of [undefined, '', 'javascript:alert(1)', 'https://cdn.example/viewer.html',
      link('javascript:alert(1)'), link('https://token@cdn.example/a.zip'),
      link('https://cdn.example/a.zip', 'https://token@cdn.example/viewer.html'),
      link('https://cdn.example/a.zip') + '&zip=https://cdn.example/b.zip']) {
      expect(isExternalRunComparable(value)).toBe(false);
      expect(externalComparisonUrl([link('https://cdn.example/a.zip'), value])).toBeNull();
    }
  });
});
