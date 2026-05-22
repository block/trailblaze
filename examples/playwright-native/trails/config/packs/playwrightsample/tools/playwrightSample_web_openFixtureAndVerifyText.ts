import type { TrailblazeClient, TrailblazeContext } from "@trailblaze/scripting";

import {
  nonEmptyString,
  PLAYWRIGHT_SAMPLE_HOME,
  requireSessionContext,
} from "./playwrightSample_shared";

const DEFAULT_TEXT = "Trailblaze Test Fixture";

interface OpenFixtureAndVerifyTextArgs {
  /** Sample-app fixture path (relative to repo root). */
  relativePath?: string;
  /** Visible text to assert on the loaded page. */
  text?: string;
}

/**
 * Registered as `playwrightSample_web_openFixtureAndVerifyText` by the
 * `playwrightsample` pack manifest.
 */
export async function playwrightSample_web_openFixtureAndVerifyText(
  args: OpenFixtureAndVerifyTextArgs,
  ctx: TrailblazeContext | undefined,
  client: TrailblazeClient,
): Promise<string> {
  requireSessionContext(ctx);

  const relativePath = nonEmptyString(args.relativePath, PLAYWRIGHT_SAMPLE_HOME);
  const text = nonEmptyString(args.text, DEFAULT_TEXT);

  await client.tools.web_navigate({
    action: "GOTO",
    url: relativePath,
  });
  await client.tools.web_verify_text_visible({ text });

  return `Opened ${relativePath} and verified "${text}" on ${ctx.device.platform}.`;
}
