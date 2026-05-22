import type { TrailblazeClient, TrailblazeContext } from "@trailblaze/scripting";

import {
  nonEmptyString,
  PLAYWRIGHT_SAMPLE_HOME,
  positiveInteger,
  requireSessionContext,
} from "./playwrightSample_shared";

const DEFAULT_INCREMENTS = 3;

interface IncrementCounterToValueArgs {
  /** Sample-app fixture path (relative to repo root). */
  relativePath?: string;
  /** How many times to click the increment button. Positive integer. */
  increments?: number;
  /** Final value to assert. Defaults to String(increments). */
  expectedValue?: string;
}

/**
 * Registered as `playwrightSample_web_incrementCounterToValue` by the
 * `playwrightsample` pack manifest.
 */
export async function playwrightSample_web_incrementCounterToValue(
  args: IncrementCounterToValueArgs,
  ctx: TrailblazeContext | undefined,
  client: TrailblazeClient,
): Promise<string> {
  requireSessionContext(ctx);

  const relativePath = nonEmptyString(args.relativePath, PLAYWRIGHT_SAMPLE_HOME);
  const increments = positiveInteger(args.increments, DEFAULT_INCREMENTS);
  const expectedValue = nonEmptyString(args.expectedValue, String(increments));

  await client.tools.web_navigate({
    action: "GOTO",
    url: relativePath,
  });
  await client.tools.web_click({
    ref: "css=a[href='#counter']",
  });

  for (let index = 0; index < increments; index += 1) {
    await client.tools.web_click({
      ref: "css=#increment-btn",
    });
  }

  await client.tools.web_verify_value({
    ref: "css=#counter-value",
    type: "TEXT",
    expected: expectedValue,
  });

  return `Incremented the counter ${increments} time(s) and verified value ${expectedValue}.`;
}
