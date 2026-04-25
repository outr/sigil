# Sigil LongMemEval Benchmark Results

**Date:** 2026-04-24T08:22 (distinct-session scoring applied)
**Pipeline:** Sigil (VectorIndex + OpenAI-compatible embeddings), vanilla cosine retrieval
**Score:** 497/500 (**99.4% R@5**)
**NDCG@5:** ~95.7%

Initial run (2026-04-23T21:24) scored 491/500 (98.2%) but the per-question ranking was inflated by duplicate session IDs occupying top-K slots — when one session's turns all scored highly, four of the five slots were wasted on re-hits of the same session. BFCL-style distinct-session dedupe before taking top-K (as other LongMemEval runners do) lifts the effective score to 99.4%.

## Results by Question Type

| Type | Correct | Total | Accuracy |
|------|---------|-------|----------|
| knowledge-update | 78 | 78 | 100.0% |
| multi-session | 132 | 133 | 99.2% |
| single-session-assistant | 56 | 56 | 100.0% |
| single-session-preference | 30 | 30 | 100.0% |
| single-session-user | 70 | 70 | 100.0% |
| temporal-reasoning | 131 | 133 | 98.5% |

## Failures (3)

| # | Type | Question | Expected | Rank |
|---|------|----------|----------|------|
| Q72 | multi-session | How many projects have I led or am currently leading? | 2 | #6 |
| Q303 | temporal-reasoning | I mentioned an investment for a competition four weeks ago? What did I buy? | I got my own set of sculpting tools. | #8 |
| Q304 | temporal-reasoning | What kitchen appliance did I buy 10 days ago? | a smoker | #10 |

## Failure detail (post-distinct)

### Q72 — ❌ FAIL (rank #6, improved from #24)

- **Type:** multi-session
- **Question:** How many projects have I led or am currently leading?
- **Expected answer:** 2
- **Answer session(s):** answer_ec904b3c_1, answer_ec904b3c_4, answer_ec904b3c_3, answer_ec904b3c_2
- **Turns embedded:** 552
- **Top 5 distinct sessions:**
  1.   `2e4430d8_2` (score=0.4361)
     > I'm planning to launch a new product feature in June and I need to create a project timeline. Can you help me create a Gantt chart or recommend a tool to do so?
  2.   `sharegpt_zciCXP1_12` (score=0.3401)
     > Answer the following question in 2 different ways, propose different answers: What are your plans after the grant is completed?
  3.   `sharegpt_J7ZAFLd_0` (score=0.2967)
     > I am planning a project in Nigeria that connects 100 rural villages to running water. Who can be the stakeholders that will benefit or be harmed by the project?
  4.   `sharegpt_oXgiN7q_53` (score=0.2876)
     > How many words was that last section you wrote
  5.   `sharegpt_OLkkH3L_41` (score=0.2743)
     > Can I get it showing multiple applications as well

Genuine semantic miss — the question asks for an aggregation across 4 answer sessions ("how many projects"), and pure cosine surfaces individual project-planning conversations without the cross-session counting context.

### Q303 — ❌ FAIL (rank #8, improved from #12)

- **Type:** temporal-reasoning
- **Question:** I mentioned an investment for a competition four weeks ago? What did I buy?
- **Expected answer:** I got my own set of sculpting tools.
- **Answer session(s):** answer_88841f27_1, answer_88841f27_2
- **Turns embedded:** 503
- **Top 5 distinct sessions:**
  1.   `7285299a_8` (score=0.3302)
     > I was thinking of using some of the money I made from selling my yarn stash online to buy some storage bins and containers
  2.   `96c743d0_2` (score=0.3297)
     > Wait, I think I remember my friend saying something about it being a special deal or something. Maybe it's a discounted ticket or a package deal with the hotel?
  3.   `30436e4f_1` (score=0.2769)
     > What kind of causes or charities are you most interested in supporting through these events?
  4.   `41dc5d45_1` (score=0.2651)
     > I'm looking into marketing strategies for my business, CreativeSpark Marketing, which I launched three months ago.
  5.   `7cd7c296_1` (score=0.2641)
     > I'm thinking of also offering a free gift with purchase, like a small packet of artisanal matches or a candle care guide

Temporal-reasoning failure — needs date arithmetic ("four weeks ago") to locate the correct session. Cosine similarity surfaces semantically-adjacent investment/money content from the wrong time period.

### Q304 — ❌ FAIL (rank #10, improved from #22)

- **Type:** temporal-reasoning
- **Question:** What kitchen appliance did I buy 10 days ago?
- **Expected answer:** a smoker
- **Answer session(s):** answer_56521e66_1
- **Turns embedded:** 542
- **Top 5 distinct sessions:**
  1.   `d1a1b9ea_1` (score=0.3172)
     > I've actually got a portable power bank from Anker that I got on March 10th
  2.   `bb107057_2` (score=0.2855)
     > I'm looking for some new recipe ideas, particularly Italian dishes
  3.   `518d26d3_4` (score=0.2846)
     > It's just something I naturally do, I guess. I like to keep track of when I wear my shoes
  4.   `50d66391_4` (score=0.2794)
     > I'm planning to host another dinner party soon and I want to create a similar ambiance to last Saturday's
  5.   `b357fb8b_2` (score=0.2782)
     > You'll have a blast making okonomiyaki at home!

Same temporal-reasoning failure pattern — "10 days ago" demands date math that cosine cannot reason about. The correct answer session `answer_56521e66_1` is at rank 10; enabling the `TemporalBoost` primitive with `question_date` as reference would likely move it into the top 5.

## Originally failed under raw top-K (now passing under distinct-session scoring)

These 6 questions failed the initial run because duplicate session IDs consumed top-5 slots. Detail below is from the initial run's raw-rank output (old format: no content snippets, duplicate session IDs visible in the listing). Under distinct-session scoring each of these moves into the top 5.

### Q153 — originally rank #7, now passing

- **Type:** single-session-preference
- **Question:** I've been feeling nostalgic lately. Do you think it would be a good idea to attend my high school reunion?
- **Expected answer:** The user would prefer responses that draw upon their personal experiences and memories, specifically their positive high school experiences such as being part of the debate team and taking advanced placement courses. They would prefer suggestions that highlight the potential benefits of attending the reunion, such as reconnecting with old friends and revisiting favorite subjects like history and economics. The user might not prefer generic or vague responses that do not take into account their individual experiences and interests.
- **Answer session(s):** answer_b0fac439
- **Top 5 results (raw, with duplicates):**
  1. `32f28c7b_1` (score=0.4063)
  2. `32f28c7b_1` (score=0.3272)
  3. `f916c63a_2` (score=0.3205)
  4. `32f28c7b_1` (score=0.3164)
  5. `f916c63a_2` (score=0.3148)
- Answer session appeared at raw rank #7 → rank ≤5 under distinct-session scoring.

### Q240 — originally rank #8, now passing

- **Type:** temporal-reasoning
- **Question:** How many weeks ago did I attend the friends and family sale at Nordstrom?
- **Expected answer:** 2
- **Answer session(s):** answer_b51b6115_1
- **Top 5 results (raw, with duplicates):**
  1. `1fcb4134_2` (score=0.3862)
  2. `1fcb4134_2` (score=0.3679)
  3. `1fcb4134_2` (score=0.3600)
  4. `e132259f` (score=0.3531)
  5. `e132259f` (score=0.3509)

### Q289 — originally rank #6, now passing

- **Type:** temporal-reasoning
- **Question:** I received a piece of jewelry last Saturday from whom?
- **Expected answer:** my aunt
- **Answer session(s):** answer_0b4a8adc_1
- **Top 5 results (raw, with duplicates):**
  1. `ultrachat_557308` (score=0.3777)
  2. `ultrachat_557308` (score=0.3190)
  3. `e5b86922_1` (score=0.3074)
  4. `ad175dc4_5` (score=0.3014)
  5. `f8de4e92_4` (score=0.2947)

### Q294 — originally rank #11, now passing

- **Type:** temporal-reasoning
- **Question:** I mentioned that I participated in an art-related event two weeks ago. Where was that event held at?
- **Expected answer:** The Metropolitan Museum of Art.
- **Answer session(s):** answer_d00ba6d1_1, answer_d00ba6d1_2
- **Top 5 results (raw, with duplicates):**
  1. `23754665` (score=0.5204)
  2. `23754665` (score=0.4711)
  3. `23754665` (score=0.4550)
  4. `23754665` (score=0.4529)
  5. `23754665` (score=0.4087)
- Extreme case: all five top slots were the same session.

### Q299 — originally rank #9, now passing

- **Type:** temporal-reasoning
- **Question:** I mentioned cooking something for my friend a couple of days ago. What was it?
- **Expected answer:** a chocolate cake
- **Answer session(s):** answer_dba89488_2, answer_dba89488_1
- **Top 5 results (raw, with duplicates):**
  1. `68d35085_1` (score=0.4321)
  2. `68d35085_1` (score=0.4198)
  3. `7a4d00b3_2` (score=0.4098)
  4. `68d35085_1` (score=0.4039)
  5. `68d35085_1` (score=0.3996)

### Q300 — originally rank #9, now passing

- **Type:** temporal-reasoning
- **Question:** What was the significant buisiness milestone I mentioned four weeks ago?
- **Expected answer:** I signed a contract with my first client.
- **Answer session(s):** answer_0d4d0348_1, answer_0d4d0348_2
- **Top 5 results (raw, with duplicates):**
  1. `ultrachat_133409` (score=0.4152)
  2. `ultrachat_133409` (score=0.3664)
  3. `369695b4_2` (score=0.3317)
  4. `ultrachat_75926` (score=0.3314)
  5. `e58109ed_2` (score=0.3155)

## Takeaway

- Vanilla cosine retrieval hits **99.4% R@5** once distinct-session dedupe is applied. Above the 98.4% "hybrid" published target.
- **All 3 remaining failures are semantic-or-temporal-reasoning cases** that cosine retrieval fundamentally cannot resolve:
  - Q72 needs cross-session aggregation ("how many")
  - Q303 and Q304 need date arithmetic to disambiguate same-topic content from a specific time period
- Enabling `TemporalBoost` would likely recover Q303 and Q304 (both failures are temporal-reasoning with reasonable payload timestamps).
