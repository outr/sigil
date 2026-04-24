# Sigil LongMemEval Benchmark Results

**Date:** 2026-04-24T08:20
**Pipeline:** Sigil (VectorIndex + OpenAI-compatible embeddings)
**Score:** 0/1 (0.0% R@5)
**NDCG@5:** 0.0%
**Time:** 10s (0.1 q/s)

## Results by Question Type

| Type | Correct | Total | Accuracy |
|------|---------|-------|----------|
| multi-session | 0 | 1 | 0.0% |

## Failures (1)

| # | Type | Question | Expected | Rank |
|---|------|----------|----------|------|
| Q72 | multi-session | How many projects have I led or am currently leading? | 2 | #6 |

## All Questions

### Q72 — ❌ FAIL

- **Type:** multi-session
- **Question:** How many projects have I led or am currently leading?
- **Expected answer:** 2
- **Answer session(s):** answer_ec904b3c_1, answer_ec904b3c_4, answer_ec904b3c_3, answer_ec904b3c_2
- **Answer found at rank:** #6 (outside top 5)
- **Turns embedded:** 552
- **Top 5 distinct sessions:**
  1.   `2e4430d8_2` (score=0.4361)
     > I'm planning to launch a new product feature in June and I need to create a project timeline. Can you help me create a Gantt chart or recommend a tool to do so? By the way, I've be
  2.   `sharegpt_zciCXP1_12` (score=0.3401)
     > Answer the following question in 2 different ways, propose different answers: What are your plans after the grant is completed?\* How do you aim to be sustainable after the grant? 
  3.   `sharegpt_J7ZAFLd_0` (score=0.2967)
     > I am planning a project in Nigeria that connects 100 rural villages to running water. Who can be the stakeholders that will benefit or be harmed by the project? give the response i
  4.   `sharegpt_oXgiN7q_53` (score=0.2876)
     > How many words was that last section you wrote
  5.   `sharegpt_OLkkH3L_41` (score=0.2743)
     > Can I get it showing multiple applications as well

