import { describe, expect, test } from 'bun:test';
import { frameKindFor } from './run-report-device-frames';

describe('which enclosure a device gets', () => {
  const android = (w: number, h: number) => frameKindFor({ platform: 'android', screen: { w, h } });

  test('a modern phone is a phone, upright or turned on its side', () => {
    expect(android(1080, 2400)).toBe('phone');
    expect(android(2400, 1080)).toBe('phone');
    expect(frameKindFor({ platform: 'ios', screen: { w: 402, h: 874 } })).toBe('phone-notch');
  });

  test('a landscape 16:9 display is a tablet, while an upright 16:9 screen is an older phone', () => {
    expect(android(1920, 1080)).toBe('tablet');
    expect(android(1080, 1920)).toBe('phone');
  });

  test('a 16:10 or squarer screen is a tablet either way up', () => {
    expect(android(1280, 800)).toBe('tablet');
    expect(android(800, 1280)).toBe('tablet');
    expect(frameKindFor({ platform: 'ios', screen: { w: 820, h: 1180 } })).toBe('tablet');
  });

  test('what the run calls the device wins over its shape', () => {
    expect(frameKindFor({ platform: 'android', classifier: 'android-tablet', screen: { w: 1080, h: 2400 } })).toBe('tablet');
    expect(frameKindFor({ platform: 'web', screen: { w: 390, h: 844 } })).toBe('browser');
  });

  test('a device that never said its size is drawn as its platform\'s phone', () => {
    expect(frameKindFor({ platform: 'android' })).toBe('phone');
  });
});
