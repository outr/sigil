# Sigil LongMemEval Benchmark Results

**Date:** 2026-04-24T08:22
**Pipeline:** Sigil (VectorIndex + OpenAI-compatible embeddings)
**Score:** 6/9 (66.7% R@5)
**NDCG@5:** 28.8%
**Time:** 59s (0.2 q/s)

## Results by Question Type

| Type | Correct | Total | Accuracy |
|------|---------|-------|----------|
| multi-session | 0 | 1 | 0.0% |
| single-session-preference | 1 | 1 | 100.0% |
| temporal-reasoning | 5 | 7 | 71.4% |

## Failures (3)

| # | Type | Question | Expected | Rank |
|---|------|----------|----------|------|
| Q72 | multi-session | How many projects have I led or am currently leading? | 2 | #6 |
| Q303 | temporal-reasoning | I mentioned an investment for a competition four weeks ag... | I got my own set of sculpting tools. | #8 |
| Q304 | temporal-reasoning | What kitchen appliance did I buy 10 days ago? | a smoker | #10 |

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

### Q153 — ✅ PASS

- **Type:** single-session-preference
- **Question:** I've been feeling nostalgic lately. Do you think it would be a good idea to attend my high school reunion?
- **Expected answer:** The user would prefer responses that draw upon their personal experiences and memories, specifically their positive high school experiences such as being part of the debate team and taking advanced placement courses. They would prefer suggestions that highlight the potential benefits of attending the reunion, such as reconnecting with old friends and revisiting favorite subjects like history and economics. The user might not prefer generic or vague responses that do not take into account their individual experiences and interests.
- **Answer session(s):** answer_b0fac439
- **Answer found at rank:** #4 ✓
- **Turns embedded:** 481
- **Top 5 distinct sessions:**
  1.   `32f28c7b_1` (score=0.4063)
     > That sounds great! I think I'll make the Ratatouille ahead of time and reheat it before serving. By the way, speaking of friends, I was thinking of inviting Rachel and Emily to the
  2.   `f916c63a_2` (score=0.3205)
     > I'm planning a family gathering to celebrate my grandma's life. I want to make a photo album or scrapbook with pictures from her funeral and other fond memories. Can you suggest so
  3.   `94bc18df_3` (score=0.3148)
     > I'm thinking of checking out some concerts in the Bay Area soon, especially since I just got back from the Khalid concert at the Greek Theatre in Berkeley with my sister, Sophia - 
  4. ✓ `answer_b0fac439` (score=0.3146)
     > I'm not sure if I'm ready to commit to a full Master's program yet. I am asking because a lot of old high school friends plan to work after the graduate from university. Can you re
  5.   `c927ffbb` (score=0.2845)
     > I'm thinking of selling my old car, a 2012 Nissan Versa, which has been sitting in my driveway for months. Do you think I can get a good price for it?

### Q240 — ✅ PASS

- **Type:** temporal-reasoning
- **Question:** How many weeks ago did I attend the friends and family sale at Nordstrom?
- **Expected answer:** 2
- **Answer session(s):** answer_b51b6115_1
- **Answer found at rank:** #5 ✓
- **Turns embedded:** 538
- **Top 5 distinct sessions:**
  1.   `1fcb4134_2` (score=0.3862)
     > I'm looking for some fashion advice. I recently bought a pair of boots and a sweater from Macy's during their Black Friday sale, and I'm not sure what to wear them with. Can you gi
  2.   `e132259f` (score=0.3531)
     > I'm trying to organize my travel schedule for the next few months. Can you remind me of the dates for the E-commerce Expo in Los Angeles?
  3.   `1dfef591_2` (score=0.3437)
     > I'm looking for some new graphic t-shirts similar to the ones I got from The Gap outlet stores. Do you have any recommendations or deals on graphic tees? By the way, I got an amazi
  4.   `1040e24b_1` (score=0.3301)
     > I'm looking for some advice on how to style my new statement handbag from Zara. I just got it last week and want to make the most out of it. By the way, I had an amazing time at th
  5. ✓ `answer_b51b6115_1` (score=0.3140)
     > I'm actually going to be using the TV in my living room, and I think a full-motion mount would be perfect. I've been doing some shopping lately and scored some great deals, by the 

### Q289 — ✅ PASS

- **Type:** temporal-reasoning
- **Question:** I received a piece of jewelry last Saturday from whom?
- **Expected answer:** my aunt
- **Answer session(s):** answer_0b4a8adc_1
- **Answer found at rank:** #5 ✓
- **Turns embedded:** 542
- **Top 5 distinct sessions:**
  1.   `ultrachat_557308` (score=0.3777)
     > Wow, I had no idea that jewelry played such a significant role in Indian weddings! Do the different types of jewelry carry any specific meanings or symbolism?
  2.   `e5b86922_1` (score=0.3074)
     > I'm thinking of using some wire-wrapped jewelry-making tools I have to create some decorative accents for the coasters. Do you think that's a good idea, or should I stick to a more
  3.   `ad175dc4_5` (score=0.3014)
     > I'm going to ask, what's the best way to wrap and present the gift basket? Should I add a gift tag or a card with a personal message?
  4.   `f8de4e92_4` (score=0.2947)
     > I really like some of those suggestions. Speaking of shopping, I recently went to the local mall with my friends the following weekend after Black Friday, and we hit up the H&M sal
  5. ✓ `answer_0b4a8adc_1` (score=0.2871)
     > What a great idea! Researching the history of your great-grandmother's crystal chandelier can be a fascinating and rewarding experience. There are several online resources and data

### Q294 — ✅ PASS

- **Type:** temporal-reasoning
- **Question:** I mentioned that I participated in an art-related event two weeks ago. Where was that event held at?
- **Expected answer:** The Metropolitan Museum of Art.
- **Answer session(s):** answer_d00ba6d1_1, answer_d00ba6d1_2
- **Answer found at rank:** #4 ✓
- **Turns embedded:** 547
- **Top 5 distinct sessions:**
  1.   `23754665` (score=0.5204)
     > I'm thinking of attending another art museum event soon. Do you know if there are any upcoming exhibitions or events at the Modern Art Museum that I should check out?
  2.   `765ce8a7_2` (score=0.3583)
     > I'll definitely check out those resources to find out about upcoming concerts and festivals in my area. Thanks for the suggestions! By the way, I noticed that you didn't ask about 
  3.   `ultrachat_463998` (score=0.3573)
     > Wow, the March Fair also sounds amazing! I would love to see the dragon boat racing and horse racing. What other cultural events do you recommend in China?
  4. ✓ `answer_d00ba6d1_1` (score=0.3473)
     > I'm looking for some information on modern art movements. I just got back from a guided tour at the Museum of Modern Art focused on 20th-century modern art movements, and it really
  5. ✓ `answer_d00ba6d1_2` (score=0.3218)
     > That's fascinating! I had no idea that mummification was practiced in so many ancient cultures. I've always been interested in ancient civilizations, which is why I attended the "A

### Q299 — ✅ PASS

- **Type:** temporal-reasoning
- **Question:** I mentioned cooking something for my friend a couple of days ago. What was it?
- **Expected answer:** a chocolate cake
- **Answer session(s):** answer_dba89488_2, answer_dba89488_1
- **Answer found at rank:** #4 ✓
- **Turns embedded:** 583
- **Top 5 distinct sessions:**
  1.   `68d35085_1` (score=0.4321)
     > I was thinking of making something with leftover beef and pasta. Do you have any recipe ideas for that?
  2.   `7a4d00b3_2` (score=0.4098)
     > That sounds amazing! Congratulations on taking the initiative to explore your culinary skills and passion for vegetarian cuisine. A cooking class focused on vegetarian dishes is a 
  3.   `990f3ef9_2` (score=0.3865)
     > I'm trying to plan out my meals for the week and was wondering if you could give me some recipe suggestions that use fresh vegetables and fruits. By the way, I just got back from T
  4. ✓ `answer_dba89488_1` (score=0.3763)
     > I'm looking for some new recipe ideas for a dinner party I'm hosting next weekend. I've been really into baking lately. Do you have any suggestions for a unique dessert I could mak
  5. ✓ `answer_dba89488_2` (score=0.3643)
     > I'm looking for some inspiration for my next baking project. I've been really into baking lately.

### Q300 — ✅ PASS

- **Type:** temporal-reasoning
- **Question:** What was the significant buisiness milestone I mentioned four weeks ago?
- **Expected answer:** I signed a contract with my first client.
- **Answer session(s):** answer_0d4d0348_1, answer_0d4d0348_2
- **Answer found at rank:** #5 ✓
- **Turns embedded:** 518
- **Top 5 distinct sessions:**
  1.   `ultrachat_133409` (score=0.4152)
     > That's interesting. Do you have any insights into the current challenges faced by businesses in Ibadan?
  2.   `369695b4_2` (score=0.3317)
     > I'd like to focus on my current projects, which include wrapping up the Johnson account, onboarding my new team, and mentoring Alex. The deadline for wrapping up the Johnson accoun
  3.   `ultrachat_75926` (score=0.3314)
     > Yeah, I'll keep that in mind. It's good to know that businesses may just need some time to implement changes.
  4.   `e58109ed_2` (score=0.3155)
     > It's great that you invested in a new laptop for work.  Here's the updated spreadsheet with the laptop purchase:  **Online Shopping Tracker**  **Columns:**  A. **Date** B. **Store/
  5. ✓ `answer_0d4d0348_1` (score=0.3025)
     > Congratulations on launching your website and creating a business plan outline! Developing a content calendar for your social media channels is a great next step to ensure consiste

### Q303 — ❌ FAIL

- **Type:** temporal-reasoning
- **Question:** I mentioned an investment for a competition four weeks ago? What did I buy?
- **Expected answer:** I got my own set of sculpting tools.
- **Answer session(s):** answer_88841f27_1, answer_88841f27_2
- **Answer found at rank:** #8 (outside top 5)
- **Turns embedded:** 503
- **Top 5 distinct sessions:**
  1.   `7285299a_8` (score=0.3302)
     > I was thinking of using some of the money I made from selling my yarn stash online to buy some storage bins and containers to help with the organization. Do you think that's a good
  2.   `96c743d0_2` (score=0.3297)
     > Wait, I think I remember my friend saying something about it being a special deal or something. Maybe it's a discounted ticket or a package deal with the hotel? Do you think that c
  3.   `30436e4f_1` (score=0.2769)
     > What kind of causes or charities are you most interested in supporting through these events?
  4.   `41dc5d45_1` (score=0.2651)
     > I'm looking into marketing strategies for my business, CreativeSpark Marketing, which I launched three months ago. Can you suggest some effective ways to reach out to potential cli
  5.   `7cd7c296_1` (score=0.2641)
     > I'm thinking of also offering a free gift with purchase, like a small packet of artisanal matches or a candle care guide, to customers who spend a certain amount. Do you think that

### Q304 — ❌ FAIL

- **Type:** temporal-reasoning
- **Question:** What kitchen appliance did I buy 10 days ago?
- **Expected answer:** a smoker
- **Answer session(s):** answer_56521e66_1
- **Answer found at rank:** #10 (outside top 5)
- **Turns embedded:** 542
- **Top 5 distinct sessions:**
  1.   `d1a1b9ea_1` (score=0.3172)
     > I've actually got a portable power bank from Anker that I got on March 10th, and it's been a lifesaver on long days. It cost around $40, but it's been worth it, as I can now charge
  2.   `bb107057_2` (score=0.2855)
     > I'm looking for some new recipe ideas, particularly Italian dishes. I've been cooking at home a lot lately, about 4 times a week for the past 3 months, and I'm getting more comfort
  3.   `518d26d3_4` (score=0.2846)
     > It's just something I naturally do, I guess. I like to keep track of when I wear my shoes and when I need to clean or maintain them. Speaking of which, I still need to clean my whi
  4.   `50d66391_4` (score=0.2794)
     > I'm planning to host another dinner party soon and I want to create a similar ambiance to last Saturday's. Can you recommend some dimming settings for the smart light switch I inst
  5.   `b357fb8b_2` (score=0.2782)
     > You'll have a blast making okonomiyaki at home!  Ah, yes! Tokyo has plenty of amazing places to buy ingredients for Japanese cooking. Here are some popular options:  1. **Tsukiji O

