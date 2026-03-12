# Playwright-Native Benchmarks

Comparison of **AI mode** (LLM interprets natural language steps) vs **Recording mode** (pre-recorded tool calls replayed deterministically) for the Playwright-native test trails.

## Latest Results (2026-02-25)

| Trail | AI (sec) | Recording (sec) | Speedup |
|---|---:|---:|---:|
| test-counter | 48.7 | 0.4 | 135.1x |
| test-form-interaction | 41.2 | 0.3 | 139.6x |
| test-navigation | 54.6 | 0.3 | 165.6x |
| test-all-tools | 111.6 | 0.0 | 13954.0x |
| test-scroll-containers | 47.6 | 0.0 | 7929.3x |
| test-duplicate-list | 112.5 | 0.6 | 200.2x |
| test-search-duplicates | 164.9 | 0.8 | 217.0x |

## Trail Descriptions

- **test-counter** - Navigate to counter page, increment three times, verify value is 3, decrement once, verify value is 2, reset, verify value is 0
- **test-form-interaction** - Fill out a contact form (name, email, category dropdown, message textarea), submit, verify success message and submitted data
- **test-navigation** - Navigate between Home, Form, Counter, and About pages via links, verify each page heading/content is correct
- **test-all-tools** - Exercises every Playwright-native tool: navigate, snapshot, verify (text/element/value/list), click, type, hover, scroll (page + container), select option, press key, wait, browser back/forward
- **test-scroll-containers** - Scroll within independent sidebar and content panel containers, verify initially-hidden items (Category 15, Item 20) become visible after scrolling
- **test-duplicate-list** - Click specific View buttons in a list where multiple items share the same "Premium Cable" or "Standard Adapter" text across Electronics, Office Supplies, and Accessories sections, verifying each click selects the correct item by its unique ID
- **test-search-duplicates** - Search for products with duplicate names (Wireless Mouse, Keyboard, Monitor Stand), then click each individual result distinguished by subtitle/variant, verifying the correct item detail is shown

## How to Run

```bash
bash trails/playwright-native/benchmark.sh
```

Results are appended to [`playwright-native-benchmarks.csv`](./playwright-native-benchmarks.csv) for tracking over time.

## Notes

- AI mode timings include LLM inference latency and are expected to vary between runs.
- Recording mode replays pre-recorded tool calls without LLM inference, so timings reflect pure Playwright execution speed.
- The speedup ratio shows how much faster recording mode is compared to AI mode. Higher speedup on simpler trails (e.g., test-counter) reflects the fixed overhead of LLM calls dominating short tests.
