// `trailblaze.deviceClassifier()`: a target's device classifier. The target names it in its
// trailmap (`target: { device_classifier: <export name> }`), and the framework calls it to get the
// device key that picks which recording replays and where a new one is saved.

/** What a device classifier is told about one device. Facts only: no tools, app, network or memory. */
export interface DeviceClassifierInput {
  /** `android`, `ios`, `web` or `desktop`. */
  platform: string;
  /**
   * The framework's own key for the device, broadest first, such as `["android", "phone"]` or
   * `["ios", "ipad"]`. A classifier usually returns it unchanged for devices it doesn't know.
   */
  key: string[];
  /** The device maker as the device reports it, such as Android's `ro.product.manufacturer`. */
  manufacturer?: string;
  /** The device model as the device reports it, such as Android's `ro.product.model`. */
  model?: string;
}

/** A device classifier: the whole key for one device, broadest first. */
export type DeviceClassifier = (device: DeviceClassifierInput) => string[];

const SEGMENT = /^[a-z0-9_]+$/;

/**
 * Declares a device classifier: a pure, synchronous function from device facts to the device key.
 *
 * ```ts
 * export const acme_deviceClassifier = trailblaze.deviceClassifier((device) =>
 *   device.manufacturer === "Acme" ? ["acme", "k2"] : device.key,
 * );
 * ```
 *
 * A recording falls back by dropping the last value first, so put broader values before narrower
 * ones. The key must either start with `device.key` and add no platform or form factor, or name a
 * device family with no platform or form factor at all, such as `["acme", "k2"]`. Every value is
 * lowercase letters, digits and `_`.
 */
export function deviceClassifier(classify: (device: DeviceClassifierInput) => readonly string[]): DeviceClassifier {
  return (device) => {
    const key = classify(device);
    if (!Array.isArray(key)) {
      throw new Error(`trailblaze.deviceClassifier: returned ${JSON.stringify(key)}; return the key as an array of values.`);
    }
    for (const value of key) {
      if (typeof value !== "string" || !SEGMENT.test(value)) {
        throw new Error(
          `trailblaze.deviceClassifier: returned ${JSON.stringify(value)}; a value must be lowercase letters, digits and _.`,
        );
      }
    }
    return [...key];
  };
}
