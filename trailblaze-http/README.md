# HTTP transport

The shared HTTP client setup, timeouts and OS CA bundle policy live here. There are no session,
logging, LLM or execution dependencies. `TransportHttpClientFactory` creates an ordinary client;
the runtime factory explicitly adds its dispatcher, tracing, network logging and token-capture
interceptor through the configuration callback. Planning uses the uninstrumented client.

The existing runtime HTTPS integration tests continue to cover certificate behavior through the
instrumented factory. Both factories therefore use one certificate and timeout implementation.
