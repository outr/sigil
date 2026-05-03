# Phase 0 — Memory-Heavy (100 critical pinned + up to 50 retrieved per turn)

_30 request(s) profiled._

## Overall token totals

| Metric | Tokens |
|---|---|
| min  | 4932 |
| p50  | 6882 |
| p95  | 7012 |
| max  | 7022 |
| mean | 6577 |

## Growth per turn

| Metric | Tokens |
|---|---|
| min Δ  | 10 |
| max Δ  | 210 |
| mean Δ | 72 |

## Per-section contribution

| Section | Avg | p95 | Max | Share % |
|---|---|---|---|---|
| CriticalMemories | 3905 | 3905 | 3905 | 59.4 |
| Memories | 1704 | 2004 | 2004 | 25.9 |
| ToolRoster | 395 | 395 | 395 | 6.0 |
| Instructions | 367 | 367 | 367 | 5.6 |
| Frames | 155 | 290 | 300 | 2.4 |
| ModeBlock | 29 | 29 | 29 | 0.4 |
| ToolFramingPrefix | 22 | 22 | 22 | 0.3 |

## Per-frame-kind contribution

| Kind | Avg tokens | p95 | Max |
|---|---|---|---|
| Text | 5 | 5 | 5 |

