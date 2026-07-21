// Test-classpath event formatter: proves the classpath-discovery → staging → driver `require`
// path end-to-end (RunReportGeneratorTest), including the import of a staged support file from
// a subdirectory. Only test sessions that write a `com.example.tests.network` events stream are
// affected.
import { requestLabel } from "./lib/test-sample-helpers";

export default {
  id: "test-sample",
  streams: ["com.example.tests.network"],
  format(entries) {
    return entries
      .filter((e) => e.data && e.data.request)
      .map((e) => ({
        t: e.t,
        label: requestLabel(e.data.request),
        badges: [{ text: "formatted-by-test-sample", tone: "ok" }],
        raw: [e.data],
      }));
  },
};
