# Phase 0 — Tool-Heavy (25 tools, find_capability + tool_call cycles)

_30 request(s) profiled._

## Overall token totals

| Metric | Tokens |
|---|---|
| min  | 1408 |
| p50  | 2962 |
| p95  | 4325 |
| max  | 4431 |
| mean | 2916 |

## Growth per turn

| Metric | Tokens |
|---|---|
| min Δ  | 102 |
| max Δ  | 108 |
| mean Δ | 104 |

## Per-section contribution

| Section | Avg | p95 | Max | Share % |
|---|---|---|---|---|
| Frames | 1613 | 3022 | 3128 | 55.3 |
| ToolRoster | 872 | 872 | 872 | 29.9 |
| Instructions | 367 | 367 | 367 | 12.6 |
| ModeBlock | 29 | 29 | 29 | 1.0 |
| ToolFramingPrefix | 22 | 22 | 22 | 0.8 |
| SuggestedTools | 13 | 13 | 13 | 0.4 |

## Per-frame-kind contribution

| Kind | Avg tokens | p95 | Max |
|---|---|---|---|
| ToolResult | 27 | 44 | 46 |
| Text | 16 | 17 | 17 |
| ToolCall | 8 | 9 | 9 |

