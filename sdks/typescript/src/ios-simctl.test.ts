import { expect, test } from "bun:test";
import type { IosSimctlArgs } from "./built-in-tools.js";
import type { IosSimctlResult } from "./generated/built-in-tool-results.js";
import { simctlOrThrow } from "./ios-simctl.js";
import { ToolError } from "./tool-error.js";

function tools(result: IosSimctlResult) {
  const calls: IosSimctlArgs[] = [];
  return {
    calls,
    async ios_simctl(args: IosSimctlArgs) {
      calls.push(args);
      return result;
    },
  };
}

test("returns the result when simctl exits 0, passing argv and options through", async () => {
  const fake = tools({ exitCode: 0, stdout: "/data/app\n", stderr: "", timedOut: false });
  const result = await simctlOrThrow(fake, ["push", "booted", "com.example.app"], { stdin: '{"aps":{}}', timeoutSeconds: 5 });
  expect(result.stdout).toBe("/data/app\n");
  expect(fake.calls).toEqual([{ argv: ["push", "booted", "com.example.app"], stdin: '{"aps":{}}', timeoutSeconds: 5 }]);
});

test("throws a ToolError with simctl's stderr and exit code on a non-zero exit", async () => {
  const fake = tools({ exitCode: 149, stdout: "", stderr: "No such process\n", timedOut: false });
  const error = await simctlOrThrow(fake, ["openurl", "booted", "myapp://a"]).catch((e) => e);
  expect(error).toBeInstanceOf(ToolError);
  expect(error.message).toBe("simctl openurl exited 149: No such process");
  expect(error.data).toEqual({
    code: "simctl_failed",
    argv: ["openurl", "booted", "myapp://a"],
    exitCode: 149,
    stderr: "No such process\n",
    timedOut: false,
  });
});

test("throws on a timeout even when the exit code looks clean", async () => {
  const fake = tools({ exitCode: 0, stdout: "", stderr: "", timedOut: true });
  await expect(simctlOrThrow(fake, ["spawn", "booted", "log", "show"])).rejects.toThrow("simctl spawn timed out.");
});
