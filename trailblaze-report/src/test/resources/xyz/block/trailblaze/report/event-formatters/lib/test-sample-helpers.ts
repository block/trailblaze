// Support file for test-sample.formatter.ts: proves that staged formatter modules can import
// shared code from a subdirectory (the generator stages every path-safe file under
// event-formatters/ preserving relative paths).
export const requestLabel = (request: { method?: string; path?: string }): string =>
  `${request.method} ${request.path}`;
