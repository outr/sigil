# LongMemEval — end-to-end QA

model: `qwen3.5-9b-q4_k_m`  memories/turn: 5  questions: 500

| arm | QA accuracy | gold retrieved | judge failures | errors | mean tokens/question | mean ingest | mean turn |
|---|--:|--:|--:|--:|--:|--:|--:|
| sigil | 44.8% | 97.2% | 0 | 0 | 3694 | 43s | 16s |
| norag | 4.8% | — | 0 | 0 | 2483 | 0s | 8s |
