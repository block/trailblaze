import { describe, expect, test } from "bun:test";
import { createMockContext } from "@trailblaze/scripting/testing";
import { android_ensureAppCompiled, parseDexoptState } from "./android_ensureAppCompiled";

const APP = "com.example.app";

/** A `dumpsys package` dexopt section carrying [status] for [appId], plus an unrelated package. */
function dump(status: string, appId: string = APP): string {
  return [
    "Dexopt state:",
    `  [${appId}]`,
    "    path: /data/app/~~a==/base.apk",
    `      arm64-v8a: [status=${status}] [reason=install] [primary-abi]`,
    "  [com.other.app]",
    "    path: /data/app/~~b==/base.apk",
    "      arm64-v8a: [status=speed-profile] [reason=bg-dexopt] [primary-abi]",
    "Compiler stats:",
    "  com.example.app",
  ].join("\n");
}

/**
 * A client whose `android_adbShell` answers per command, because this tool issues up to three
 * different ones in a single run (read, compile, re-read) and the whole point of the re-read is
 * that it can answer differently from the first.
 */
function shellClient(respond: (command: string[]) => string) {
  const commands: string[][] = [];
  const tools = new Proxy(
    {},
    {
      get: (_target, name: string) => async (args: { command: string[] }) => {
        if (name !== "android_adbShell") {
          throw new Error(`unexpected tool call: ${name}`);
        }
        commands.push(args.command);
        return respond(args.command);
      },
    },
  );
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  return { client: { tools } as any, commands };
}

const ctx = () => createMockContext({ platform: "android" });

const isCompile = (command: string[]) => command[0] === "pm";

describe("parseDexoptState", () => {
  test("reads the status and reason of the target package only", () => {
    const entries = parseDexoptState(dump("run-from-apk"), APP);
    expect(entries).toEqual([
      { abi: "arm64-v8a", status: "run-from-apk", reason: "install", primaryAbi: true },
    ]);
  });

  test("a package with no block in the dump is undefined, not empty", () => {
    // `dumpsys package <unknown>` dumps the whole database, so a missing block is the only
    // signal that the app is absent — reading it as "no entries" would look like a broken install.
    expect(parseDexoptState(dump("verify"), "com.not.installed")).toBeUndefined();
  });

  test("secondary dex files do not count against the app", () => {
    // `pm compile` without `--secondary-dex` never touches them, so an app that always ships one
    // uncompiled would otherwise read as permanently broken with no compile that could fix it.
    const withSecondary = [
      "Dexopt state:",
      `  [${APP}]`,
      "    path: /data/app/~~a==/base.apk",
      "      arm64-v8a: [status=verify] [reason=install] [primary-abi]",
      "    known secondary dex files:",
      "      /data/user/0/com.example.app/extra.apk",
      "        arm64-v8a: [status=run-from-apk] [reason=unknown]",
    ].join("\n");
    const entries = parseDexoptState(withSecondary, APP)!;
    expect(entries.map((e) => e.status)).toEqual(["verify"]);
  });

  test("a split listed after a secondary dex list is still read", () => {
    // Android 8.1 through 13 print `known secondary dex files:` inside EVERY `path:` block — it is
    // the package-level map, repeated per path. A flag that only cleared at the next package
    // header swallowed every split after the base, so an uncompiled split read as absent.
    const baseThenSplit = [
      "Dexopt state:",
      `  [${APP}]`,
      "    path: /data/app/~~a==/base.apk",
      "      arm64-v8a: [status=verify] [reason=install] [primary-abi]",
      "      known secondary dex files:",
      "        /data/user/0/com.example.app/extra.apk",
      "          arm64-v8a: [status=verify] [reason=unknown]",
      "    path: /data/app/~~a==/split_config.arm64_v8a.apk",
      "      arm64-v8a: [status=run-from-apk] [reason=unknown] [primary-abi]",
      "      known secondary dex files:",
      "        /data/user/0/com.example.app/extra.apk",
      "          arm64-v8a: [status=verify] [reason=unknown]",
    ].join("\n");
    expect(parseDexoptState(baseThenSplit, APP)!.map((e) => e.status)).toEqual([
      "verify",
      "run-from-apk",
    ]);
  });

  test("a secondary-ABI entry does not decide a package whose primary ABI is compiled", () => {
    const mixedAbis = [
      "Dexopt state:",
      `  [${APP}]`,
      "    path: /data/app/~~a==/base.apk",
      "      arm64-v8a: [status=verify] [reason=install] [primary-abi]",
      "      armeabi-v7a: [status=run-from-apk] [reason=unknown]",
    ].join("\n");
    expect(parseDexoptState(mixedAbis, APP)).toHaveLength(2);
  });
});

/** The same package on Android 8.1, whose dump predates `[status=…]`. */
const oreoDump = [
  "Dexopt state:",
  `  [${APP}]`,
  "    path: /data/app/com.example.app-1/base.apk",
  "      arm64-v8a: kOatUpToDate",
].join("\n");

describe("android_ensureAppCompiled", () => {
  test("a dump whose status lines cannot be read compiles nothing, even when forced", async () => {
    // Android 8.x prints the status in an older shape. Reading the empty result as "no artifacts"
    // would compile a healthy app on every session, and the re-read afterwards would be just as
    // unreadable — so it would report failure every time too. `force` does not change that.
    const { client, commands } = shellClient(() => oreoDump);
    const result = await android_ensureAppCompiled({ appId: APP, force: true }, ctx(), client);
    expect(commands).toEqual([["dumpsys", "package", APP]]);
    expect(result).toContain("no dexopt status in a shape this tool reads");
  });

  test("a compiled app is read once and never compiled", async () => {
    const { client, commands } = shellClient(() => dump("verify"));
    const result = await android_ensureAppCompiled({ appId: APP }, ctx(), client);
    expect(commands).toEqual([["dumpsys", "package", APP]]);
    expect(result).toContain("already has compiled artifacts");
  });

  test("an uncompiled app is compiled and then re-read from the device", async () => {
    let compiled = false;
    const { client, commands } = shellClient((command) => {
      if (isCompile(command)) {
        compiled = true;
        return "Success";
      }
      return dump(compiled ? "verify" : "run-from-apk");
    });
    const result = await android_ensureAppCompiled({ appId: APP }, ctx(), client);
    expect(commands).toEqual([
      ["dumpsys", "package", APP],
      ["pm", "compile", "-m", "verify", "-f", APP],
      ["dumpsys", "package", APP],
    ]);
    expect(result).toContain("Compiled 'com.example.app' with filter verify");
  });

  test("a compile the device did not actually apply fails instead of reporting success", async () => {
    // The re-read, not `pm compile`'s exit line, is what decides — the one broken state seen in
    // the wild is one ART considers up to date.
    const { client } = shellClient((command) => (isCompile(command) ? "Success" : dump("run-from-apk")));
    await expect(android_ensureAppCompiled({ appId: APP }, ctx(), client)).rejects.toThrow(
      /still has no compiled artifacts/,
    );
  });

  test("checkOnly reports an uncompiled app without compiling it", async () => {
    const { client, commands } = shellClient(() => dump("run-from-apk"));
    const result = await android_ensureAppCompiled({ appId: APP, checkOnly: true }, ctx(), client);
    expect(commands.some(isCompile)).toBe(false);
    expect(result).toContain("NO compiled artifacts");
  });

  test("a forced recompile the device refused is not reported as compiled", async () => {
    // force + already-compiled is the one case the re-read cannot judge: the status is identical
    // whether the recompile ran or was refused, so `pm compile`'s own word is the only signal.
    const { client } = shellClient((command) => (isCompile(command) ? "Failure" : dump("verify")));
    await expect(
      android_ensureAppCompiled({ appId: APP, force: true }, ctx(), client),
    ).rejects.toThrow(/pm compile said: Failure/);
  });

  test("a forced recompile the device accepted succeeds", async () => {
    const { client, commands } = shellClient((command) => (isCompile(command) ? "Success" : dump("speed")));
    const result = await android_ensureAppCompiled(
      { appId: APP, force: true, compilerFilter: "speed" },
      ctx(),
      client,
    );
    expect(commands).toContainEqual(["pm", "compile", "-m", "speed", "-f", APP]);
    expect(result).toContain("Compiled");
  });

  test("an app with no dexopt block reads as not installed", async () => {
    const { client } = shellClient(() => dump("verify", "com.something.else"));
    await expect(android_ensureAppCompiled({ appId: APP }, ctx(), client)).rejects.toThrow(
      /is the app installed on this device/,
    );
  });

  test("an unknown compiler filter is rejected before the device is touched", async () => {
    const { client, commands } = shellClient(() => dump("verify"));
    await expect(
      android_ensureAppCompiled({ appId: APP, compilerFilter: "turbo" }, ctx(), client),
    ).rejects.toThrow(/unknown compilerFilter 'turbo'/);
    expect(commands).toEqual([]);
  });

  test("an appId that could never name a package is rejected before the device is touched", async () => {
    const { client, commands } = shellClient(() => dump("verify"));
    await expect(
      android_ensureAppCompiled({ appId: "com.example; id" }, ctx(), client),
    ).rejects.toThrow(/not a valid Android package name/);
    expect(commands).toEqual([]);
  });
});
