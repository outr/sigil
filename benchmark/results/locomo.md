# Sigil LoCoMo Benchmark Results

**Date:** 2026-04-25T03:04:05.542632351Z
**Pipeline:** Sigil (VectorIndex + OpenAI-compatible embeddings)
**Retrieval:** vanilla cosine
**Score:** 688/1049 (65.6% R@10)

## Per-category accuracy

| Category | Correct | Total | Accuracy |
|---|---|---|---|
| basic_facts | 190 | 282 | 67.4% |
| temporal | 171 | 321 | 53.3% |
| abstention | 327 | 446 | 73.3% |

## Failures (361)

### [basic_facts] dataset_0.json — `What did Caroline research?`

- expected conv indices: 1
- top-10 ranked conv indices: 0, 5, 13, 8, 3, 9, 11, 16, 14, 4
- top-K snippets:
  1. Hey Mel! Good to see you! How have you been?
  2. Hey Mel! Long time no talk. Lots has been going on since then!
  3. Hey, Mel! How's it going? There's something I want to tell you. I went hiking last week and got into a bad spot with some people. It really 
  4. Hey Caroline, hope all's good! I had a quiet weekend after we went camping with my fam two weekends ago. It was great to unplug and hang wit
  5. Hey Melanie! Long time no talk! A lot's been going on in my life! Take a look at this.
  6. Hey Melanie! Just wanted to say hi!
  7. Hey Mel! Good to see you! How have you been?
  8. Hey Mel! Good to see you! How have you been?
  9. Hey Mel! How're ya doin'? Recently, I had a not-so-great experience on a hike. I ran into a group of religious conservatives who said someth
  10. Hey Mel, what's up? Long time no see! I just contacted my mentor for adoption advice. I'm ready to be a mom and share my love and family. It

### [basic_facts] dataset_0.json — `What are some changes Caroline has faced during her transition journey?`

- expected conv indices: 10, 15
- top-10 ranked conv indices: 13, 5, 2, 18, 4, 1, 16, 8, 12, 3
- top-K snippets:
  1. Hey, Mel! How's it going? There's something I want to tell you. I went hiking last week and got into a bad spot with some people. It really 
  2. Hey Mel! Long time no talk. Lots has been going on since then!
  3. Hey Melanie! How's it going? I wanted to tell you about my school event last week. It was awesome! I talked about my transgender journey and
  4. Woohoo Melanie! I passed the adoption agency interviews last Friday! I'm so excited and thankful. This is a big move towards my goal of havi
  5. Since we last spoke, some big things have happened. Last week I went to an LGBTQ+ pride parade. Everyone was so happy and it made me feel li
  6. Hey Caroline, since we last chatted, I've had a lot of things happening to me. I ran a charity race for mental health last Saturday – it was
  7. Hey Mel, what's up? Long time no see! I just contacted my mentor for adoption advice. I'm ready to be a mom and share my love and family. It
  8. Hey Caroline, hope all's good! I had a quiet weekend after we went camping with my fam two weekends ago. It was great to unplug and hang wit
  9. Hey Melanie! How's it going? I wanted to tell you about my school event last week. It was awesome! I talked about my transgender journey and
  10. Hi Melanie! Hope you're doing good. Guess what I did this week? I took the first step towards becoming a mom - I applied to adoption agencie

### [basic_facts] dataset_0.json — `When did Melanie go on a hike after the roadtrip?`

- expected conv indices: 17
- top-10 ranked conv indices: 

### [basic_facts] dataset_0.json — `What items has Melanie bought?`

- expected conv indices: 6, 18
- top-10 ranked conv indices: 

### [basic_facts] dataset_1.json — `What do Jon and Gina both have in common?`

- expected conv indices: 0, 1
- top-10 ranked conv indices: 18, 6, 8, 5, 17, 3, 9, 16, 7, 13
- top-K snippets:
  1. Hey Gina! We haven't talked in a few days. Been rehearsing hard and working on business plans. It's been stressful, but dancing has kept me 
  2. Hey Gina! We haven't talked in a few days. Been rehearsing hard and working on business plans. It's been stressful, but dancing has kept me 
  3. Hey Gina, how's it going?
  4. Hey Gina! I'm turning my loves of dance into a business. I'm sunk tons of time into the studio lately, and look at my students - they're alr
  5. Hi Gina! Been hectic for me lately. Started hitting the gym last week to stay on track with the venture. Gotta figure out how to balance it 
  6. Hey Jon! Long time no talk! Last week, I built a new website for customers to make orders. It's been a wild ride but I'm loving it. What's u
  7. Hey Gina! What's up? How's the store going? I gotta tell you about this thing with my biz.
  8. Hi Gina! I just wanted to fill you in on my business. Yesterday, I went to a fair to show off my studio, it was both stressful and great! I 
  9. Hey Jon! Long time no chat! How's the dance studio? Last week was wild, I got noticed by fashion editors and it's been amazing but kinda sca
  10. Hey Gina, I had to shut down my bank account. It was tough, but I needed to do it for my biz.

### [basic_facts] dataset_1.json — `Which cities has Jon visited?`

- expected conv indices: 1, 14
- top-10 ranked conv indices: 3, 15, 7, 16, 12, 17, 18, 11, 5, 2
- top-K snippets:
  1. Hey Gina! What's up? How's the store going? I gotta tell you about this thing with my biz.
  2. Hey Jon, what's been up? Some pretty cool stuff happened since we talked. I have acquired some new unique pieces for my store.
  3. Hey Jon, what's been up? Some pretty cool stuff happened since we talked. I have acquired some new unique pieces for my store.
  4. Hey Gina, I had to shut down my bank account. It was tough, but I needed to do it for my biz.
  5. Hey Jon! Long time no chat! How's the dance studio? Last week was wild, I got noticed by fashion editors and it's been amazing but kinda sca
  6. Hey Gina, thanks for being there for me and believing in me. It means a lot.
  7. Hey Jon! Long time no talk! Last week, I built a new website for customers to make orders. It's been a wild ride but I'm loving it. What's u
  8. Hey Gina, thanks for being there for me and believing in me. It means a lot.
  9. Hey Gina! We haven't talked in a few days. Been rehearsing hard and working on business plans. It's been stressful, but dancing has kept me 
  10. Hey Jon! Long time no talk! A lot's happened - I just got accepted for a fashion internship!

### [basic_facts] dataset_2.json — `What type of volunteering have John and Maria both done?`

- expected conv indices: 1, 2
- top-10 ranked conv indices: 30, 27, 26, 31, 28, 29, 13, 6, 8
- top-K snippets:
  1. Hi Maria, since we last chatted, I'm volunteering as a mentor for a local school. It's really rewarding to see how much I can help these stu
  2. Hey Maria, great chatting with you again! Crazy thing happened since we last talked. I lost my job at the mechanical engineering company. Th
  3. Hey Maria, hope you're doing OK. I had to share something cool with you - I asked family and friends to join the virtual support group I am 
  4. Hey Maria! Guess what? I'm now part of the fire-fighting brigade. I'm super excited to be involved and help out my community!
  5. Hey John, what's been going on? I just wanted to check in. Last week was wild - I volunteered at the homeless shelter and they gave me a med
  6. Hey Maria, great chatting with you again! Crazy thing happened since we last talked. I lost my job at the mechanical engineering company. Th
  7. Hey Maria! Guess what? I'm now part of the fire-fighting brigade. I'm super excited to be involved and help out my community!
  8. Hey John, what's been going on? I just wanted to check in. Last week was wild - I volunteered at the homeless shelter and they gave me a med
  9. Hey John, what's been going on? I just wanted to check in. Last week was wild - I volunteered at the homeless shelter and they gave me a med
  10. Hey Maria! Guess what? I'm now part of the fire-fighting brigade. I'm super excited to be involved and help out my community!

### [basic_facts] dataset_2.json — `What items des John mention having as a child?`

- expected conv indices: 2, 4
- top-10 ranked conv indices: 5, 29, 28, 31, 30, 27, 26, 3, 9, 7
- top-K snippets:
  1. Hey John! Long time no talk. I just wanted to let you know I challenged myself last Friday and did a charity event. It was great! I truly fe
  2. Hey John! Long time no talk! Guess what - I got a puppy two weeks ago! Her name's Coco and she's adorable.
  3. Hey John, what's been going on? I just wanted to check in. Last week was wild - I volunteered at the homeless shelter and they gave me a med
  4. Hey Maria! Guess what? I'm now part of the fire-fighting brigade. I'm super excited to be involved and help out my community!
  5. Hi Maria, since we last chatted, I'm volunteering as a mentor for a local school. It's really rewarding to see how much I can help these stu
  6. Hi Maria, since we last chatted, I'm volunteering as a mentor for a local school. It's really rewarding to see how much I can help these stu
  7. Hi Maria, since we last chatted, I'm volunteering as a mentor for a local school. It's really rewarding to see how much I can help these stu
  8. Hey John, what's been going on? I just wanted to check in. Last week was wild - I volunteered at the homeless shelter and they gave me a med
  9. Hey John! Long time no talk! Guess what - I got a puppy two weeks ago! Her name's Coco and she's adorable.
  10. Hey Maria, great chatting with you again! Crazy thing happened since we last talked. I lost my job at the mechanical engineering company. Th

### [basic_facts] dataset_2.json — `Who gave Maria's family money when she was younger and her family was going through tough times?`

- expected conv indices: 4, 5
- top-10 ranked conv indices: 28, 26, 27, 31, 30, 29, 1, 2
- top-K snippets:
  1. Hey John, what's been going on? I just wanted to check in. Last week was wild - I volunteered at the homeless shelter and they gave me a med
  2. Hey Maria, hope you're doing OK. I had to share something cool with you - I asked family and friends to join the virtual support group I am 
  3. Hey Maria, great chatting with you again! Crazy thing happened since we last talked. I lost my job at the mechanical engineering company. Th
  4. Hey Maria! Guess what? I'm now part of the fire-fighting brigade. I'm super excited to be involved and help out my community!
  5. Hey Maria, great chatting with you again! Crazy thing happened since we last talked. I lost my job at the mechanical engineering company. Th
  6. Hey Maria, great chatting with you again! Crazy thing happened since we last talked. I lost my job at the mechanical engineering company. Th
  7. Hey John, what's been going on? I just wanted to check in. Last week was wild - I volunteered at the homeless shelter and they gave me a med
  8. Hey John, what's been going on? I just wanted to check in. Last week was wild - I volunteered at the homeless shelter and they gave me a med
  9. Hey Maria, great chatting with you again! Crazy thing happened since we last talked. I lost my job at the mechanical engineering company. Th
  10. Hey Maria, great chatting with you again! Crazy thing happened since we last talked. I lost my job at the mechanical engineering company. Th

### [basic_facts] dataset_2.json — `What test has John taken multiple times?`

- expected conv indices: 2, 7
- top-10 ranked conv indices: 28, 27, 29, 30, 31, 26, 6, 12, 1, 0
- top-K snippets:
  1. Hey John, what's been going on? I just wanted to check in. Last week was wild - I volunteered at the homeless shelter and they gave me a med
  2. Hey John, what's been going on? I just wanted to check in. Last week was wild - I volunteered at the homeless shelter and they gave me a med
  3. Hey Maria, great chatting with you again! Crazy thing happened since we last talked. I lost my job at the mechanical engineering company. Th
  4. Hey John! Long time no talk! Guess what - I got a puppy two weeks ago! Her name's Coco and she's adorable.
  5. Hey Maria, great chatting with you again! Crazy thing happened since we last talked. I lost my job at the mechanical engineering company. Th
  6. Hey Maria, great chatting with you again! Crazy thing happened since we last talked. I lost my job at the mechanical engineering company. Th
  7. Hi Maria, since we last chatted, I'm volunteering as a mentor for a local school. It's really rewarding to see how much I can help these stu
  8. Hey John, what's been going on? I just wanted to check in. Last week was wild - I volunteered at the homeless shelter and they gave me a med
  9. Hey Maria! Guess what? I'm now part of the fire-fighting brigade. I'm super excited to be involved and help out my community!
  10. Hey John! Long time no talk! Guess what - I got a puppy two weeks ago! Her name's Coco and she's adorable.

### [basic_facts] dataset_2.json — `What writing classes has Maria taken?`

- expected conv indices: 6, 8
- top-10 ranked conv indices: 30, 26, 27, 31, 29, 28, 4, 15, 7, 0
- top-K snippets:
  1. Hi Maria, since we last chatted, I'm volunteering as a mentor for a local school. It's really rewarding to see how much I can help these stu
  2. Hi Maria, since we last chatted, I'm volunteering as a mentor for a local school. It's really rewarding to see how much I can help these stu
  3. Hey Maria, hope you're doing OK. I had to share something cool with you - I asked family and friends to join the virtual support group I am 
  4. Hey Maria, great chatting with you again! Crazy thing happened since we last talked. I lost my job at the mechanical engineering company. Th
  5. Hi Maria, since we last chatted, I'm volunteering as a mentor for a local school. It's really rewarding to see how much I can help these stu
  6. Hey Maria, great chatting with you again! Crazy thing happened since we last talked. I lost my job at the mechanical engineering company. Th
  7. Hey Maria, hope you're doing OK. I had to share something cool with you - I asked family and friends to join the virtual support group I am 
  8. Hi Maria, since we last chatted, I'm volunteering as a mentor for a local school. It's really rewarding to see how much I can help these stu
  9. Hey Maria, great chatting with you again! Crazy thing happened since we last talked. I lost my job at the mechanical engineering company. Th
  10. Hey Maria! Guess what? I'm now part of the fire-fighting brigade. I'm super excited to be involved and help out my community!

### [basic_facts] dataset_2.json — `What damages have happened to John's car?`

- expected conv indices: 3, 10
- top-10 ranked conv indices: 29, 17, 27, 28, 31, 30, 15, 26, 0, 13
- top-K snippets:
  1. Hey John! Long time no talk! Guess what - I got a puppy two weeks ago! Her name's Coco and she's adorable.
  2. Hey John, how're you doing? I'm sorry about Max. Losing a pet is tough. Some friends from church and I went camping last weekend - it was a 
  3. Hey Maria, great chatting with you again! Crazy thing happened since we last talked. I lost my job at the mechanical engineering company. Th
  4. Hey John, what's been going on? I just wanted to check in. Last week was wild - I volunteered at the homeless shelter and they gave me a med
  5. Hey Maria, great chatting with you again! Crazy thing happened since we last talked. I lost my job at the mechanical engineering company. Th
  6. Hey John, what's been going on? I just wanted to check in. Last week was wild - I volunteered at the homeless shelter and they gave me a med
  7. Hey Maria, great chatting with you again! Crazy thing happened since we last talked. I lost my job at the mechanical engineering company. Th
  8. Hey Maria! Guess what? I'm now part of the fire-fighting brigade. I'm super excited to be involved and help out my community!
  9. Hey John, how're you doing? I'm sorry about Max. Losing a pet is tough. Some friends from church and I went camping last weekend - it was a 
  10. Hi Maria, since we last chatted, I'm volunteering as a mentor for a local school. It's really rewarding to see how much I can help these stu

### [basic_facts] dataset_2.json — `What areas of the U.S. has John been to or is planning to go to?`

- expected conv indices: 10, 11
- top-10 ranked conv indices: 27, 29, 28, 31, 30, 26, 0, 14, 6, 17
- top-K snippets:
  1. Hey Maria, great chatting with you again! Crazy thing happened since we last talked. I lost my job at the mechanical engineering company. Th
  2. Hey John! Long time no talk! Guess what - I got a puppy two weeks ago! Her name's Coco and she's adorable.
  3. Hey John, what's been going on? I just wanted to check in. Last week was wild - I volunteered at the homeless shelter and they gave me a med
  4. Hey Maria, great chatting with you again! Crazy thing happened since we last talked. I lost my job at the mechanical engineering company. Th
  5. Hey Maria, great chatting with you again! Crazy thing happened since we last talked. I lost my job at the mechanical engineering company. Th
  6. Hey John! Long time no talk! Guess what - I got a puppy two weeks ago! Her name's Coco and she's adorable.
  7. Hey Maria! Guess what? I'm now part of the fire-fighting brigade. I'm super excited to be involved and help out my community!
  8. Hi Maria, since we last chatted, I'm volunteering as a mentor for a local school. It's really rewarding to see how much I can help these stu
  9. Hey Maria! Guess what? I'm now part of the fire-fighting brigade. I'm super excited to be involved and help out my community!
  10. Hey John, what's been going on? I just wanted to check in. Last week was wild - I volunteered at the homeless shelter and they gave me a med

### [basic_facts] dataset_2.json — `What desserts has Maria made?`

- expected conv indices: 1, 12
- top-10 ranked conv indices: 30, 29, 27, 26, 31, 28, 13, 4, 5, 11
- top-K snippets:
  1. Hi Maria, since we last chatted, I'm volunteering as a mentor for a local school. It's really rewarding to see how much I can help these stu
  2. Hey John! Long time no talk! Guess what - I got a puppy two weeks ago! Her name's Coco and she's adorable.
  3. Hey Maria, great chatting with you again! Crazy thing happened since we last talked. I lost my job at the mechanical engineering company. Th
  4. Hey Maria, hope you're doing OK. I had to share something cool with you - I asked family and friends to join the virtual support group I am 
  5. Hey Maria, hope you're doing OK. I had to share something cool with you - I asked family and friends to join the virtual support group I am 
  6. Hi Maria, since we last chatted, I'm volunteering as a mentor for a local school. It's really rewarding to see how much I can help these stu
  7. Hey Maria, great chatting with you again! Crazy thing happened since we last talked. I lost my job at the mechanical engineering company. Th
  8. Hey Maria, great chatting with you again! Crazy thing happened since we last talked. I lost my job at the mechanical engineering company. Th
  9. Hey Maria! Guess what? I'm now part of the fire-fighting brigade. I'm super excited to be involved and help out my community!
  10. Hey John, what's been going on? I just wanted to check in. Last week was wild - I volunteered at the homeless shelter and they gave me a med

### [basic_facts] dataset_2.json — `What European countries has Maria been to?`

- expected conv indices: 7, 12
- top-10 ranked conv indices: 30, 26, 29, 27, 28, 31, 14, 0, 4, 5
- top-K snippets:
  1. Hi Maria, since we last chatted, I'm volunteering as a mentor for a local school. It's really rewarding to see how much I can help these stu
  2. Hey Maria, hope you're doing OK. I had to share something cool with you - I asked family and friends to join the virtual support group I am 
  3. Hey Maria, hope you're doing OK. I had to share something cool with you - I asked family and friends to join the virtual support group I am 
  4. Hi Maria, since we last chatted, I'm volunteering as a mentor for a local school. It's really rewarding to see how much I can help these stu
  5. Hi Maria, since we last chatted, I'm volunteering as a mentor for a local school. It's really rewarding to see how much I can help these stu
  6. Hey John! Long time no talk! Guess what - I got a puppy two weeks ago! Her name's Coco and she's adorable.
  7. Hi Maria, since we last chatted, I'm volunteering as a mentor for a local school. It's really rewarding to see how much I can help these stu
  8. Hey Maria, great chatting with you again! Crazy thing happened since we last talked. I lost my job at the mechanical engineering company. Th
  9. Hey John, what's been going on? I just wanted to check in. Last week was wild - I volunteered at the homeless shelter and they gave me a med
  10. Hey Maria, great chatting with you again! Crazy thing happened since we last talked. I lost my job at the mechanical engineering company. Th

### [basic_facts] dataset_2.json — `What types of yoga has Maria practiced?`

- expected conv indices: 0, 17, 18
- top-10 ranked conv indices: 30, 27, 26, 31, 29, 28, 4, 15, 14, 10
- top-K snippets:
  1. Hi Maria, since we last chatted, I'm volunteering as a mentor for a local school. It's really rewarding to see how much I can help these stu
  2. Hi Maria, since we last chatted, I'm volunteering as a mentor for a local school. It's really rewarding to see how much I can help these stu
  3. Hey Maria, great chatting with you again! Crazy thing happened since we last talked. I lost my job at the mechanical engineering company. Th
  4. Hey Maria, hope you're doing OK. I had to share something cool with you - I asked family and friends to join the virtual support group I am 
  5. Hey Maria, great chatting with you again! Crazy thing happened since we last talked. I lost my job at the mechanical engineering company. Th
  6. Hi Maria, since we last chatted, I'm volunteering as a mentor for a local school. It's really rewarding to see how much I can help these stu
  7. Hi Maria, since we last chatted, I'm volunteering as a mentor for a local school. It's really rewarding to see how much I can help these stu
  8. Hey Maria! Guess what? I'm now part of the fire-fighting brigade. I'm super excited to be involved and help out my community!
  9. Hey Maria, great chatting with you again! Crazy thing happened since we last talked. I lost my job at the mechanical engineering company. Th
  10. Hi Maria, since we last chatted, I'm volunteering as a mentor for a local school. It's really rewarding to see how much I can help these stu

### [basic_facts] dataset_2.json — `What music events has John attended?`

- expected conv indices: 7, 19
- top-10 ranked conv indices: 28, 29, 31, 27, 30, 10, 14
- top-K snippets:
  1. Hey John, what's been going on? I just wanted to check in. Last week was wild - I volunteered at the homeless shelter and they gave me a med
  2. Hey John, what's been going on? I just wanted to check in. Last week was wild - I volunteered at the homeless shelter and they gave me a med
  3. Hey John! Long time no talk! Guess what - I got a puppy two weeks ago! Her name's Coco and she's adorable.
  4. Hey John, what's been going on? I just wanted to check in. Last week was wild - I volunteered at the homeless shelter and they gave me a med
  5. Hey Maria! Guess what? I'm now part of the fire-fighting brigade. I'm super excited to be involved and help out my community!
  6. Hey Maria, great chatting with you again! Crazy thing happened since we last talked. I lost my job at the mechanical engineering company. Th
  7. Hey John! Long time no talk! Guess what - I got a puppy two weeks ago! Her name's Coco and she's adorable.
  8. Hi Maria, since we last chatted, I'm volunteering as a mentor for a local school. It's really rewarding to see how much I can help these stu
  9. Hey Maria, great chatting with you again! Crazy thing happened since we last talked. I lost my job at the mechanical engineering company. Th
  10. Hey Maria! Guess what? I'm now part of the fire-fighting brigade. I'm super excited to be involved and help out my community!

### [basic_facts] dataset_2.json — `What area was hit by a flood?`

- expected conv indices: 13, 22
- top-10 ranked conv indices: 17, 28, 9, 15, 8, 31, 26, 14, 27, 7
- top-K snippets:
  1. Hey John, how're you doing? I'm sorry about Max. Losing a pet is tough. Some friends from church and I went camping last weekend - it was a 
  2. Hey John, what's been going on? I just wanted to check in. Last week was wild - I volunteered at the homeless shelter and they gave me a med
  3. Hey John, how're you doing? I'm sorry about Max. Losing a pet is tough. Some friends from church and I went camping last weekend - it was a 
  4. Hey Maria, I'm so excited to tell you I started a weekend yoga class with a colleague - it's awesome! I feel great, both mentally and physic
  5. Hey Maria, I've been busy doing the petition I started - it's tricky but it's been cool getting back in touch with my buddies and gaining su
  6. Hey John, long time no see! I've been taking a poetry class lately to help me put my feelings into words. It's been a rough ride, but it's b
  7. Hey Maria! Guess what? I'm now part of the fire-fighting brigade. I'm super excited to be involved and help out my community!
  8. Hey Maria, hope you're doing OK. I had to share something cool with you - I asked family and friends to join the virtual support group I am 
  9. Hey Maria, I'm so excited to tell you I started a weekend yoga class with a colleague - it's awesome! I feel great, both mentally and physic
  10. Hey Maria, how's it going? Been real busy tackling a project to support military veterans. Trying to get a petition going, it's pretty rewar

### [basic_facts] dataset_2.json — `What food item did Maria drop off at the homeless shelter?`

- expected conv indices: 24, 25
- top-10 ranked conv indices: 28, 31, 26, 30, 29, 18, 27, 14, 12, 5
- top-K snippets:
  1. Hey John, what's been going on? I just wanted to check in. Last week was wild - I volunteered at the homeless shelter and they gave me a med
  2. Hey Maria! Guess what? I'm now part of the fire-fighting brigade. I'm super excited to be involved and help out my community!
  3. Hey John, what's been going on? I just wanted to check in. Last week was wild - I volunteered at the homeless shelter and they gave me a med
  4. Hey Maria, hope you're doing OK. I had to share something cool with you - I asked family and friends to join the virtual support group I am 
  5. Hey John, what's been going on? I just wanted to check in. Last week was wild - I volunteered at the homeless shelter and they gave me a med
  6. Hey Maria! Guess what? I'm now part of the fire-fighting brigade. I'm super excited to be involved and help out my community!
  7. Hi Maria, since we last chatted, I'm volunteering as a mentor for a local school. It's really rewarding to see how much I can help these stu
  8. Hi Maria, since we last chatted, I'm volunteering as a mentor for a local school. It's really rewarding to see how much I can help these stu
  9. Hey Maria, hope you're doing OK. I had to share something cool with you - I asked family and friends to join the virtual support group I am 
  10. Hey Maria, hope you're doing OK. I had to share something cool with you - I asked family and friends to join the virtual support group I am 

### [basic_facts] dataset_3.json — `What are Joanna's hobbies?`

- expected conv indices: 0, 1
- top-10 ranked conv indices: 24, 27, 26, 23, 18, 19, 17, 20, 21, 16
- top-K snippets:
  1. Hey Joanna, what's been up since we last chatted? How's it going?
  2. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  3. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  4. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  5. Hey Joanna, what's been up since we last chatted? How's it going?
  6. Hey Joanna, what's been up? Haven't seen you since we last talked.
  7. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  8. Hey Joanna, what's been up since we last chatted? How's it going?
  9. Woah Joanna, I won an international tournament yesterday! It was wild. Gaming has brought me so much success and now I'm able to make a livi
  10. Hey Joanna! Long time no talk. So much has happened. Look how cute they are! Hanging with them has been a big help, especially recently. Spe

### [basic_facts] dataset_3.json — `What emotions is Joanna feeling about  the screenplay she submitted?`

- expected conv indices: 1, 2
- top-10 ranked conv indices: 28, 23, 24, 25, 26, 27, 19, 20, 22, 21
- top-K snippets:
  1. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  2. Hey Joanna, what's been up? Haven't seen you since we last talked.
  3. Hey Joanna, what's been up since we last chatted? How's it going?
  4. Wow, Nate, I'm on fire! I just set up meetings with movie producers — my dreams are comin' true!
  5. Wow, Nate, I'm on fire! I just set up meetings with movie producers — my dreams are comin' true!
  6. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  7. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  8. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  9. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  10. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w

### [basic_facts] dataset_3.json — `What is Joanna allergic to?`

- expected conv indices: 1, 3, 4
- top-10 ranked conv indices: 27, 19, 20, 24, 23, 28, 25, 26, 21, 13
- top-K snippets:
  1. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  2. Hey Joanna! Long time no talk. So much has happened. Look how cute they are! Hanging with them has been a big help, especially recently. Spe
  3. Hey Nate, long time no see! My laptop crashed last week and I lost all my work - super frustrating! As a writer, my laptop is like half of m
  4. Hey Joanna, what's been up since we last chatted? How's it going?
  5. Hey Joanna, what's been up? Haven't seen you since we last talked.
  6. Hey Joanna, what's been up since we last chatted? How's it going?
  7. Hey Nate, long time no see! My laptop crashed last week and I lost all my work - super frustrating! As a writer, my laptop is like half of m
  8. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  9. Hey Joanna! Long time no talk. So much has happened. Look how cute they are! Hanging with them has been a big help, especially recently. Spe
  10. Wow, Nate, I'm on fire! I just set up meetings with movie producers — my dreams are comin' true!

### [basic_facts] dataset_3.json — `How many times has Joanna found new hiking trails?`

- expected conv indices: 7, 10
- top-10 ranked conv indices: 24, 20, 23, 28, 25, 22, 26, 27, 19, 21
- top-K snippets:
  1. Hey Joanna, what's been up since we last chatted? How's it going?
  2. Hey Nate, long time no see! My laptop crashed last week and I lost all my work - super frustrating! As a writer, my laptop is like half of m
  3. Hey Joanna, what's been up? Haven't seen you since we last talked.
  4. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  5. Wow, Nate, I'm on fire! I just set up meetings with movie producers — my dreams are comin' true!
  6. Hey Joanna, it's been a couple days since we last talked. Something exciting happened last Friday. I went to a game convention and met new p
  7. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  8. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  9. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  10. Hey Joanna! Long time no talk. So much has happened. Look how cute they are! Hanging with them has been a big help, especially recently. Spe

### [basic_facts] dataset_3.json — `What places has Joanna submitted her work to?`

- expected conv indices: 1, 15
- top-10 ranked conv indices: 23, 26, 24, 20, 25, 19, 27, 28, 21, 22
- top-K snippets:
  1. Hey Joanna, what's been up? Haven't seen you since we last talked.
  2. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  3. Hey Joanna, what's been up since we last chatted? How's it going?
  4. Hey Nate, long time no see! My laptop crashed last week and I lost all my work - super frustrating! As a writer, my laptop is like half of m
  5. Wow, Nate, I'm on fire! I just set up meetings with movie producers — my dreams are comin' true!
  6. Hey Joanna! Long time no talk. So much has happened. Look how cute they are! Hanging with them has been a big help, especially recently. Spe
  7. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  8. Hey Joanna, what's been up since we last chatted? How's it going?
  9. Wow, Nate, I'm on fire! I just set up meetings with movie producers — my dreams are comin' true!
  10. Hey Joanna, what's been up since we last chatted? How's it going?

### [basic_facts] dataset_3.json — `What kind of writings does Joanna do?`

- expected conv indices: 1, 16, 17
- top-10 ranked conv indices: 24, 26, 20, 25, 27, 23, 19, 28, 21, 22
- top-K snippets:
  1. Hey Joanna, what's been up since we last chatted? How's it going?
  2. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  3. Hey Joanna, what's been up since we last chatted? How's it going?
  4. Hey Nate, long time no see! My laptop crashed last week and I lost all my work - super frustrating! As a writer, my laptop is like half of m
  5. Hey Joanna, what's been up since we last chatted? How's it going?
  6. Wow, Nate, I'm on fire! I just set up meetings with movie producers — my dreams are comin' true!
  7. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  8. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  9. Hey Nate, long time no see! My laptop crashed last week and I lost all my work - super frustrating! As a writer, my laptop is like half of m
  10. Hey Joanna, what's been up? Haven't seen you since we last talked.

### [basic_facts] dataset_3.json — `What book recommendations has Joanna given to Nate?`

- expected conv indices: 2, 18
- top-10 ranked conv indices: 21, 28, 19, 23, 20, 24, 27, 25, 22, 26
- top-K snippets:
  1. Hey Nate, hi! Yesterday, I tried my newest dairy-free recipe and it was a winner with my family! Mixing and matching flavors is fun and I'm 
  2. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  3. Hey Joanna! Long time no talk. So much has happened. Look how cute they are! Hanging with them has been a big help, especially recently. Spe
  4. Hey Joanna, what's been up? Haven't seen you since we last talked.
  5. Hey Nate, long time no see! My laptop crashed last week and I lost all my work - super frustrating! As a writer, my laptop is like half of m
  6. Hey Joanna, what's been up since we last chatted? How's it going?
  7. Hey Nate, long time no see! My laptop crashed last week and I lost all my work - super frustrating! As a writer, my laptop is like half of m
  8. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  9. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  10. Wow, Nate, I'm on fire! I just set up meetings with movie producers — my dreams are comin' true!

### [basic_facts] dataset_3.json — `How many letters has Joanna recieved?`

- expected conv indices: 13, 17
- top-10 ranked conv indices: 24, 27, 19, 23, 20, 25, 26, 21, 28, 22
- top-K snippets:
  1. Hey Joanna, what's been up since we last chatted? How's it going?
  2. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  3. Hey Joanna! Long time no talk. So much has happened. Look how cute they are! Hanging with them has been a big help, especially recently. Spe
  4. Hey Joanna, what's been up? Haven't seen you since we last talked.
  5. Hey Joanna, what's been up since we last chatted? How's it going?
  6. Hey Joanna, what's been up? Haven't seen you since we last talked.
  7. Hey Nate, long time no see! My laptop crashed last week and I lost all my work - super frustrating! As a writer, my laptop is like half of m
  8. Wow, Nate, I'm on fire! I just set up meetings with movie producers — my dreams are comin' true!
  9. Hey Nate, long time no see! My laptop crashed last week and I lost all my work - super frustrating! As a writer, my laptop is like half of m
  10. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w

### [basic_facts] dataset_3.json — `What pets does Nate have?`

- expected conv indices: 7, 11, 27
- top-10 ranked conv indices: 

### [basic_facts] dataset_3.json — `How many turtles does Nate have?`

- expected conv indices: 7, 27
- top-10 ranked conv indices: 

### [basic_facts] dataset_3.json — `What activities does Nate do with his turtles?`

- expected conv indices: 24, 27
- top-10 ranked conv indices: 

### [basic_facts] dataset_3.json — `What do both Joanna and Nate appreciate the beauty of?`

- expected conv indices: 10, 27
- top-10 ranked conv indices: 2, 1, 0
- top-K snippets:
  1. Hey Nate, long time no see! The screenplay I sent in to the film festival has been on my mind all day everyday. I keep bouncing between craz
  2. Hey Nate! Haven't talked in a few days. Crazy things happened to me!
  3. Hey Nate, long time no see! The screenplay I sent in to the film festival has been on my mind all day everyday. I keep bouncing between craz
  4. Hey Nate, long time no see! The screenplay I sent in to the film festival has been on my mind all day everyday. I keep bouncing between craz
  5. Hey Nate! Haven't talked in a few days. Crazy things happened to me!
  6. Hey Nate! Haven't talked in a few days. Crazy things happened to me!
  7. Hey Nate! Haven't talked in a few days. Crazy things happened to me!
  8. Hey Nate! Haven't talked in a few days. Crazy things happened to me!
  9. Hey Nate, long time no see! The screenplay I sent in to the film festival has been on my mind all day everyday. I keep bouncing between craz
  10. Hey Joanna! Long time no see! What's up? Anything fun going on?

### [basic_facts] dataset_3.json — `How has Nate tried to disburse his vegan ice-cream recipes?`

- expected conv indices: 17, 20
- top-10 ranked conv indices: 1, 0, 2
- top-K snippets:
  1. Hey Nate! Haven't talked in a few days. Crazy things happened to me!
  2. Hey Joanna! Long time no see! What's up? Anything fun going on?
  3. Hey Nate! Haven't talked in a few days. Crazy things happened to me!
  4. Hey Nate! Haven't talked in a few days. Crazy things happened to me!
  5. Hey Nate, long time no see! The screenplay I sent in to the film festival has been on my mind all day everyday. I keep bouncing between craz
  6. Hey Nate! Haven't talked in a few days. Crazy things happened to me!
  7. Hey Nate, long time no see! The screenplay I sent in to the film festival has been on my mind all day everyday. I keep bouncing between craz
  8. Hey Nate, long time no see! The screenplay I sent in to the film festival has been on my mind all day everyday. I keep bouncing between craz
  9. Hey Nate, long time no see! The screenplay I sent in to the film festival has been on my mind all day everyday. I keep bouncing between craz
  10. Hey Nate! Haven't talked in a few days. Crazy things happened to me!

### [basic_facts] dataset_3.json — `What recipes has Joanna made?`

- expected conv indices: 9, 18, 19, 20, 21
- top-10 ranked conv indices: 1, 5, 7, 6, 3, 2, 4, 0, 8
- top-K snippets:
  1. Hey Nate! Haven't talked in a few days. Crazy things happened to me!
  2. Hey Nate! Haven't talked in a few days. Crazy things happened to me!
  3. Hey Nate! Haven't talked in a few days. Crazy things happened to me!
  4. Hey Nate! Haven't talked in a few days. Crazy things happened to me!
  5. Hey Nate! Haven't talked in a few days. Crazy things happened to me!
  6. Hey Joanna! Long time no talk, how's it going? Crazy stuff's been happening since we last chatted.
  7. Hey Joanna! Haven't talked with you in a while - how's it going?
  8. Hey Nate! Haven't talked in a few days. Crazy things happened to me!
  9. Hey Jo, guess what I did? Dyed my hair last week - come see!
  10. Hey Nate! Haven't talked in a few days. Crazy things happened to me!

### [basic_facts] dataset_3.json — `What are the skills that Nate has helped others learn?`

- expected conv indices: 13, 17, 25
- top-10 ranked conv indices: 2, 7, 5, 1, 3, 0, 8, 6, 4
- top-K snippets:
  1. Hey Nate, long time no see! The screenplay I sent in to the film festival has been on my mind all day everyday. I keep bouncing between craz
  2. Hey Nate, long time no see! The screenplay I sent in to the film festival has been on my mind all day everyday. I keep bouncing between craz
  3. Hey Joanna! Haven't talked with you in a while - how's it going?
  4. Hey Joanna! Long time no talk, how's it going? Crazy stuff's been happening since we last chatted.
  5. Hey Nate! Haven't talked in a few days. Crazy things happened to me!
  6. Hey Joanna! Sorry I haven't been around. I made my friend some ice cream and they loved it!
  7. Hey Joanna! Sorry I haven't been around. I made my friend some ice cream and they loved it!
  8. Hey Joanna! Long time no see! What's up? Anything fun going on?
  9. Hey Joanna! Sorry I haven't been around. I made my friend some ice cream and they loved it!

### [basic_facts] dataset_4.json — `What sports does John like besides basketball?`

- expected conv indices: 0, 1, 2
- top-10 ranked conv indices: 23, 22, 11, 5, 10, 7, 28, 24, 18, 16
- top-K snippets:
  1. Hey John, catch up time! What've you been up to? Any good b-ball games lately?
  2. Hey Tim, great to see you! Any new success stories?
  3. Hey John! Awesome catchin' up with you! A lot's changed since last time.
  4. Hey Tim, sorry I missed you. Been a crazy few days. Took a trip to a new place - it's been amazing. Love the energy there.
  5. Hey Tim, been a while! How ya been?
  6. Hey Tim, been a while! How ya been?
  7. Hey Tim! Long time no talk. Hope you're doing great. Crazy things have been going on in my life. Just the other day, I found a new gym to st
  8. Hey John! How's it going? Hope all is good.
  9. Hey John, been a while since we chatted. How's it going?
  10. Hey John! Haven't talked in a bit, how ya been? Hope your injury is feeling better.

### [basic_facts] dataset_4.json — `What similar sports collectible do Tim and John own?`

- expected conv indices: 6, 15
- top-10 ranked conv indices: 

### [basic_facts] dataset_4.json — `Which TV series does Tim mention watching?`

- expected conv indices: 16, 25
- top-10 ranked conv indices: 

### [basic_facts] dataset_4.json — `What schools did John play basketball in and how many years was he with his team during high school?`

- expected conv indices: 5, 8
- top-10 ranked conv indices: 

### [basic_facts] dataset_4.json — `Which cities has John been to?`

- expected conv indices: 2, 5, 8, 26
- top-10 ranked conv indices: 

### [basic_facts] dataset_4.json — `What outdoor activities does John enjoy?`

- expected conv indices: 2, 11
- top-10 ranked conv indices: 

### [basic_facts] dataset_4.json — `Who is Tim and John's favorite basketball player?`

- expected conv indices: 11, 15
- top-10 ranked conv indices: 

### [basic_facts] dataset_4.json — `which country has Tim visited most frequently in his travels?`

- expected conv indices: 0, 12, 17
- top-10 ranked conv indices: 

### [basic_facts] dataset_4.json — `What kind of fiction stories does Tim write?`

- expected conv indices: 14, 15
- top-10 ranked conv indices: 

### [basic_facts] dataset_4.json — `What has John cooked?`

- expected conv indices: 9, 14
- top-10 ranked conv indices: 

### [basic_facts] dataset_4.json — `What does John like about Lebron James?`

- expected conv indices: 11, 15
- top-10 ranked conv indices: 0, 2, 1
- top-K snippets:
  1. Hey Tim, nice to meet you! What's up? Anything new happening?
  2. Hey Tim, nice to meet you! What's up? Anything new happening?
  3. Hey Tim! Good to see you again. So much has happened in the last month - on and off the court. Last week I scored 40 points, my highest ever
  4. Last night I joined a fantasy literature forum and had a great talk about my fave books. It was so enriching!
  5. Hey Tim, nice to meet you! What's up? Anything new happening?
  6. Hey Tim, nice to meet you! What's up? Anything new happening?
  7. Hey Tim, nice to meet you! What's up? Anything new happening?
  8. Hey Tim, nice to meet you! What's up? Anything new happening?
  9. Last night I joined a fantasy literature forum and had a great talk about my fave books. It was so enriching!

### [basic_facts] dataset_4.json — `When did John get an ankle injury in 2023?`

- expected conv indices: 17
- top-10 ranked conv indices: 1, 0, 2
- top-K snippets:
  1. Last night I joined a fantasy literature forum and had a great talk about my fave books. It was so enriching!
  2. Hey Tim, nice to meet you! What's up? Anything new happening?
  3. Last night I joined a fantasy literature forum and had a great talk about my fave books. It was so enriching!
  4. Last night I joined a fantasy literature forum and had a great talk about my fave books. It was so enriching!
  5. Hey Tim! Good to see you again. So much has happened in the last month - on and off the court. Last week I scored 40 points, my highest ever
  6. Last night I joined a fantasy literature forum and had a great talk about my fave books. It was so enriching!
  7. Hey Tim, nice to meet you! What's up? Anything new happening?
  8. Hey Tim, nice to meet you! What's up? Anything new happening?
  9. Hey Tim, nice to meet you! What's up? Anything new happening?

### [basic_facts] dataset_4.json — `How many times has John injured his ankle?`

- expected conv indices: 17, 18
- top-10 ranked conv indices: 1, 0, 2
- top-K snippets:
  1. Last night I joined a fantasy literature forum and had a great talk about my fave books. It was so enriching!
  2. Hey Tim, nice to meet you! What's up? Anything new happening?
  3. Last night I joined a fantasy literature forum and had a great talk about my fave books. It was so enriching!
  4. Hey Tim, nice to meet you! What's up? Anything new happening?
  5. Hey Tim, nice to meet you! What's up? Anything new happening?
  6. Last night I joined a fantasy literature forum and had a great talk about my fave books. It was so enriching!
  7. Hey Tim! Good to see you again. So much has happened in the last month - on and off the court. Last week I scored 40 points, my highest ever
  8. Last night I joined a fantasy literature forum and had a great talk about my fave books. It was so enriching!

### [basic_facts] dataset_4.json — `Which book was John reading during his recovery from an ankle injury?`

- expected conv indices: 17, 18
- top-10 ranked conv indices: 0, 1, 2
- top-K snippets:
  1. Hey Tim, nice to meet you! What's up? Anything new happening?
  2. Hey Tim, nice to meet you! What's up? Anything new happening?
  3. Hey Tim, nice to meet you! What's up? Anything new happening?
  4. Last night I joined a fantasy literature forum and had a great talk about my fave books. It was so enriching!
  5. Last night I joined a fantasy literature forum and had a great talk about my fave books. It was so enriching!
  6. Last night I joined a fantasy literature forum and had a great talk about my fave books. It was so enriching!
  7. Last night I joined a fantasy literature forum and had a great talk about my fave books. It was so enriching!
  8. Last night I joined a fantasy literature forum and had a great talk about my fave books. It was so enriching!
  9. Hey Tim, nice to meet you! What's up? Anything new happening?

### [basic_facts] dataset_4.json — `What does John do to supplement his basketball training?`

- expected conv indices: 7, 19
- top-10 ranked conv indices: 0, 2, 1
- top-K snippets:
  1. Hey Tim, nice to meet you! What's up? Anything new happening?
  2. Hey Tim, nice to meet you! What's up? Anything new happening?
  3. Hey Tim! Good to see you again. So much has happened in the last month - on and off the court. Last week I scored 40 points, my highest ever
  4. Hey Tim, nice to meet you! What's up? Anything new happening?
  5. Hey Tim, nice to meet you! What's up? Anything new happening?
  6. Last night I joined a fantasy literature forum and had a great talk about my fave books. It was so enriching!
  7. Hey Tim, nice to meet you! What's up? Anything new happening?
  8. Last night I joined a fantasy literature forum and had a great talk about my fave books. It was so enriching!
  9. Last night I joined a fantasy literature forum and had a great talk about my fave books. It was so enriching!

### [basic_facts] dataset_4.json — `What instruments does Tim play?`

- expected conv indices: 7, 20
- top-10 ranked conv indices: 0, 1, 2
- top-K snippets:
  1. Hey Tim, nice to meet you! What's up? Anything new happening?
  2. Hey Tim, nice to meet you! What's up? Anything new happening?
  3. Hey Tim, nice to meet you! What's up? Anything new happening?
  4. Hey Tim, nice to meet you! What's up? Anything new happening?
  5. Hey Tim, nice to meet you! What's up? Anything new happening?
  6. Last night I joined a fantasy literature forum and had a great talk about my fave books. It was so enriching!
  7. Hey Tim, nice to meet you! What's up? Anything new happening?
  8. Hey Tim, nice to meet you! What's up? Anything new happening?
  9. Last night I joined a fantasy literature forum and had a great talk about my fave books. It was so enriching!

### [basic_facts] dataset_4.json — `What does John do to share his knowledge?`

- expected conv indices: 13, 25
- top-10 ranked conv indices: 3, 1, 0, 2, 5, 4
- top-K snippets:
  1. Hey John! How've you been? Something awesome happened - I'm writing articles about fantasy novels for an online mag. It's so rewarding!
  2. Last night I joined a fantasy literature forum and had a great talk about my fave books. It was so enriching!
  3. Last night I joined a fantasy literature forum and had a great talk about my fave books. It was so enriching!
  4. Hey Tim, nice to meet you! What's up? Anything new happening?
  5. Hey Tim! Good to see you again. So much has happened in the last month - on and off the court. Last week I scored 40 points, my highest ever
  6. Hey John! How've you been? Something awesome happened - I'm writing articles about fantasy novels for an online mag. It's so rewarding!

### [basic_facts] dataset_4.json — `What fantasy movies does Tim like?`

- expected conv indices: 7, 25, 26
- top-10 ranked conv indices: 0, 2, 1, 4, 3
- top-K snippets:
  1. Hey Tim, nice to meet you! What's up? Anything new happening?
  2. Hey Tim, nice to meet you! What's up? Anything new happening?
  3. Hey Tim, nice to meet you! What's up? Anything new happening?
  4. Hey Tim, nice to meet you! What's up? Anything new happening?
  5. Hey Tim! Good to see you again. So much has happened in the last month - on and off the court. Last week I scored 40 points, my highest ever
  6. Hey Tim, nice to meet you! What's up? Anything new happening?
  7. Hey Tim, nice to meet you! What's up? Anything new happening?
  8. Hey Tim! Good to see you again. So much has happened in the last month - on and off the court. Last week I scored 40 points, my highest ever
  9. Hey Tim! Good to see you again. So much has happened in the last month - on and off the court. Last week I scored 40 points, my highest ever
  10. Hey Tim, nice to meet you! What's up? Anything new happening?

### [basic_facts] dataset_5.json — `What is a shared frustration regarding dog ownership for Audrey and Andrew?`

- expected conv indices: 6, 9
- top-10 ranked conv indices: 25, 26, 27, 17, 18, 19, 23, 12, 13, 20
- top-K snippets:
  1. Hey Andrew, I wanted to let you know about something going on with my dogs. I noticed they weren't acting normally, so I made an appointment
  2. Hey Audrey, had a great weekend! My girlfriend and I went on a bike ride and stumbled upon a cool park outside of town. It was awesome to ge
  3. Hey Andrew! Long time no talk! Last Friday I took my fur kids to the pet salon - they were so psyched and their tails were wagging like craz
  4. Hey Audrey, how's it going? Since we last talked, a few new things have come up in my life. Work's been tough and stressful, so my outdoor a
  5. Hey Andrew! Long time no talk! Last Friday I took my fur kids to the pet salon - they were so psyched and their tails were wagging like craz
  6. Hey Audrey! Long time no talk! How have you been?
  7. Hey wassup? Got some great news - the gf and I are hitting the beach next month with Toby!
  8. Hey Andrew, hope you're doing ok. I recently had a good week - I went to a pet store last Monday to buy toys for my dogs and it was great se
  9. Hey Audrey! How are you? My GF and I just had a great experience volunteering at a pet shelter on Monday - it was so rewarding! We loved spe
  10. Hey Andrew! Long time no talk! Last Friday I took my fur kids to the pet salon - they were so psyched and their tails were wagging like craz

### [basic_facts] dataset_5.json — `What does Audrey view her pets as?`

- expected conv indices: 14, 22
- top-10 ranked conv indices: 12, 18, 23, 27, 0, 11, 16, 20, 8, 26
- top-K snippets:
  1. Hey Audrey! How are you? My GF and I just had a great experience volunteering at a pet shelter on Monday - it was so rewarding! We loved spe
  2. Hey Audrey! Long time no talk! How have you been?
  3. Hey Andrew, hope you're doing ok. I recently had a good week - I went to a pet store last Monday to buy toys for my dogs and it was great se
  4. Hey Andrew! Long time no talk! Last Friday I took my fur kids to the pet salon - they were so psyched and their tails were wagging like craz
  5. Hey Audrey! Long time no talk! How have you been?
  6. Hey Audrey! How are you? My GF and I just had a great experience volunteering at a pet shelter on Monday - it was so rewarding! We loved spe
  7. Hey Audrey! Long time no talk! How have you been?
  8. Hey Andrew! Good to see ya! What's been up since we last talked?
  9. Hey Audrey! How are you? My GF and I just had a great experience volunteering at a pet shelter on Monday - it was so rewarding! We loved spe
  10. Hey Audrey! How are you? My GF and I just had a great experience volunteering at a pet shelter on Monday - it was so rewarding! We loved spe

### [basic_facts] dataset_6.json — `Which books has John recommended to James?`

- expected conv indices: 7, 13
- top-10 ranked conv indices: 29, 1, 16, 21, 0, 18, 14, 27, 4, 3
- top-K snippets:
  1. Hey John, hope you're doing well. Yesterday, we started on a road trip. It was fun spending time with the family and my dogs. Exploring new 
  2. Hey John, something awesome happened since we talked. I made a game avatar and joined a new platform. It's so fun exploring and chatting wit
  3. Hi James! I just started playing chess to get better at strategy. I'm loving it! Have you ever tried it out?
  4. Hey John! Been a while, but hope you're doing well. My Unity strategy game is finally finished—it took loads of time and effort, but I'm rea
  5. Hey! Glad to finally talk to you. I want to ask you, what motivates you?
  6. Hey James, been a few days. The convo got me thinking about my passions and goals. Thanks for encouraging me to try new game genres.
  7. Hey James, been a few days. The convo got me thinking about my passions and goals. Thanks for encouraging me to try new game genres.
  8. Hey John, since our last chat, something awesome happened. Last Friday, I started introducing Max, Daisy and the new pup Ned. It was hard at

### [basic_facts] dataset_6.json — `What kind of classes has James joined?`

- expected conv indices: 12, 22
- top-10 ranked conv indices: 0, 29, 2, 27, 16, 10, 30, 20, 28, 7
- top-K snippets:
  1. Hey! Glad to finally talk to you. I want to ask you, what motivates you?
  2. Hey John, hope you're doing well. Yesterday, we started on a road trip. It was fun spending time with the family and my dogs. Exploring new 
  3. Hey! Glad to finally talk to you. I want to ask you, what motivates you?
  4. Hey James, long time no see! I had a big win in my game last week - finally advanced to the next level! It was a huge confidence booster and
  5. Hey John, long time no talk! So much has happened!
  6. Hi James! I just started playing chess to get better at strategy. I'm loving it! Have you ever tried it out?
  7. Hey James, it's been a bit since we last talked. Something cool happened recently - I volunteered my programming skills for a social cause. 
  8. Hey John! Guess what? Me and my family are currently on the road trip! We`ve already visited my friends Josh and Mark and had such a great t
  9. Hey! Glad to finally talk to you. I want to ask you, what motivates you?
  10. Hey John, long time no talk! So much has happened!

### [basic_facts] dataset_6.json — `What happened to John's job situation in 2022?`

- expected conv indices: 3, 17
- top-10 ranked conv indices: 9, 10, 15, 24, 13, 23, 27, 16, 11, 25
- top-K snippets:
  1. Hey John! Been a while since we chatted. Sorry 'bout not getting back sooner. How's it going? Any new games you're into?
  2. Hey James, it's been a bit since we last talked. Something cool happened recently - I volunteered my programming skills for a social cause. 
  3. Hey John! Long time no talk - hope you're doing well. Guess what? Last week I actually won an online gaming tournament! It was such an excit
  4. Hey James, been a few days since we chatted. Lots of stuff goin' on in my life!
  5. Hey John, how's it going? A lot has happened for me lately, some good and some not so great. I`m lucky to have at least two people who alway
  6. Hey James! Long time no see! A lot's changed since we talked. I started getting into board games. I tried one last week and it turned out to
  7. Hey John, long time no talk! So much has happened!
  8. Hi James! I just started playing chess to get better at strategy. I'm loving it! Have you ever tried it out?
  9. Hey John, last weekend I had an awesome time at the amusement park with my friends. It was a great break from the virtual world. I went on s
  10. Hey James! Busy few weeks for sure, but I'm pushing through. Got an email about a volunteer gig at a game dev non-profit. It's something I'v

### [basic_facts] dataset_6.json — `What kind of beer does McGee's bar serve?`

- expected conv indices: 20, 22
- top-10 ranked conv indices: 28, 12, 27, 9, 1, 8, 10, 23, 30, 19
- top-K snippets:
  1. Hey James! Hope you're doing great. I've some amazing news - I held a gaming tourney with my buddies last night. We played Fortnite and a fe
  2. Hey James, long time no talk! A lot has happened during this time. Let me fill you in.
  3. Hey James! Hope you're doing great. I've some amazing news - I held a gaming tourney with my buddies last night. We played Fortnite and a fe
  4. Hey James! Hope you're doing great. I've some amazing news - I held a gaming tourney with my buddies last night. We played Fortnite and a fe
  5. Hey John, long time no talk! So much has happened!
  6. Hey John, long time no talk! So much has happened!
  7. Hey John! Been a while since we chatted. Sorry 'bout not getting back sooner. How's it going? Any new games you're into?
  8. Hey John, something awesome happened since we talked. I made a game avatar and joined a new platform. It's so fun exploring and chatting wit

### [basic_facts] dataset_6.json — `What gaming equipments did John buy or refurbish?`

- expected conv indices: 19, 22
- top-10 ranked conv indices: 2, 28, 29, 5, 27, 10, 3, 0, 25, 23
- top-K snippets:
  1. Hey James, long time no see! I had a big win in my game last week - finally advanced to the next level! It was a huge confidence booster and
  2. Hey James! Hope you're doing great. I've some amazing news - I held a gaming tourney with my buddies last night. We played Fortnite and a fe
  3. Hey James, long time no see! I had a big win in my game last week - finally advanced to the next level! It was a huge confidence booster and
  4. Hey John, hope you're doing well. Yesterday, we started on a road trip. It was fun spending time with the family and my dogs. Exploring new 
  5. Hey James! Long time no see! I have great news! Last Tuesday I met three cool new friends in my programming course, they share the same pass
  6. Hey John, long time no talk! So much has happened!
  7. Hey James, long time no see! I had a big win in my game last week - finally advanced to the next level! It was a huge confidence booster and
  8. Hey John, hope you're doing well. Yesterday, we started on a road trip. It was fun spending time with the family and my dogs. Exploring new 
  9. Hey James, it's been a bit since we last talked. Something cool happened recently - I volunteered my programming skills for a social cause. 
  10. Hey John, long time no talk! So much has happened!

### [basic_facts] dataset_7.json — `What symbolic gifts do Deborah and Jolene have from their mothers?`

- expected conv indices: 0
- top-10 ranked conv indices: 28, 3, 11, 12, 29, 22, 26, 27, 7, 14
- top-K snippets:
  1. Hey Jolene, I'm so excited to tell you! Yesterday, me and my neighbor ran a free gardening class for the community, it was awesome! People o
  2. Hey Deborah! Good to hear from you. How've you been? I've been on an emotional rollercoaster lately, but I'm coping.
  3. Hey Jolene! Great to see you! Had a blast biking nearby with my neighbor last week - was so freeing and beautiful. Checked out an art show w
  4. Hey Deborah! Good to hear from you. How've you been? I've been on an emotional rollercoaster lately, but I'm coping.
  5. Hey Jolene, I'm so excited to tell you! Yesterday, me and my neighbor ran a free gardening class for the community, it was awesome! People o
  6. Hey Jolene! Great to see you! Had a blast biking nearby with my neighbor last week - was so freeing and beautiful. Checked out an art show w
  7. Hey Jolene, I'm so excited to tell you! Yesterday, me and my neighbor ran a free gardening class for the community, it was awesome! People o
  8. Hey Deborah! Long time no talk - I had lots of stuff going on. Remember the tough engineering project? I finally wrapped that up last month.
  9. I had a great time at the music festival with my pals! The vibes were unreal and the music was magical. It was so freeing to dance and bop a
  10. Hey Deborah, how's it going? Guess what? Yesterday my partner and I got back from an awesome trip to Rio de Janeiro- we checked out some coo

### [basic_facts] dataset_7.json — `How many times has Jolene been to France?`

- expected conv indices: 0, 1
- top-10 ranked conv indices: 12, 11, 22, 28, 7, 3, 14, 4, 16, 29
- top-K snippets:
  1. Hey Deborah! Long time no talk - I had lots of stuff going on. Remember the tough engineering project? I finally wrapped that up last month.
  2. Hey Jolene! Great to see you! Had a blast biking nearby with my neighbor last week - was so freeing and beautiful. Checked out an art show w
  3. Hey Deborah, how's it going? Guess what? Yesterday my partner and I got back from an awesome trip to Rio de Janeiro- we checked out some coo
  4. Hey Jolene, I'm so excited to tell you! Yesterday, me and my neighbor ran a free gardening class for the community, it was awesome! People o
  5. Hey Jolene, I'm so excited to tell you! Yesterday, me and my neighbor ran a free gardening class for the community, it was awesome! People o
  6. Hey Jolene, Anna got me a vegan stir-fry the other day - tofu and veg with ginger and soy sauce. It was really tasty! Food is such a wonderf
  7. Hey Deborah! Good to hear from you. How've you been? I've been on an emotional rollercoaster lately, but I'm coping.
  8. Hey Deborah! Good to hear from you. How've you been? I've been on an emotional rollercoaster lately, but I'm coping.
  9. Hey Jolene, I'm so excited to tell you! Yesterday, me and my neighbor ran a free gardening class for the community, it was awesome! People o
  10. Hey Jolene, I'm so excited to tell you! Yesterday, me and my neighbor ran a free gardening class for the community, it was awesome! People o

### [basic_facts] dataset_7.json — `What new yoga poses did Deborah try?`

- expected conv indices: 3, 13
- top-10 ranked conv indices: 10, 12, 21, 11, 29, 28, 6, 14, 7, 22
- top-K snippets:
  1. Hey Deb, long time no talk. A lot's happened! On Friday I had a breakthrough with my engineering project. Finally found a solution to a prob
  2. Hey Deborah! Long time no talk - I had lots of stuff going on. Remember the tough engineering project? I finally wrapped that up last month.
  3. Hey Jolene, since we talked I've been thinking about my mom's influence. Remembering those we love is important.
  4. Hey Jolene! Great to see you! Had a blast biking nearby with my neighbor last week - was so freeing and beautiful. Checked out an art show w
  5. Hey Deborah! Long time no talk - I had lots of stuff going on. Remember the tough engineering project? I finally wrapped that up last month.
  6. Hey Deborah! Long time no talk - I had lots of stuff going on. Remember the tough engineering project? I finally wrapped that up last month.
  7. I had a great time at the music festival with my pals! The vibes were unreal and the music was magical. It was so freeing to dance and bop a
  8. Hey Jolene, since we talked I've been thinking about my mom's influence. Remembering those we love is important.
  9. Hey Jolene, I'm so excited to tell you! Yesterday, me and my neighbor ran a free gardening class for the community, it was awesome! People o
  10. Hi Deborah, it's been a while! Since we last talked, so much has happened. Balancing engineering school with my partner's video games is qui

### [basic_facts] dataset_7.json — `What time management techniques do Deborah and Jolene use?`

- expected conv indices: 9, 17
- top-10 ranked conv indices: 12, 22, 10, 11, 6, 28, 7, 29, 21, 24
- top-K snippets:
  1. Hey Deborah! Long time no talk - I had lots of stuff going on. Remember the tough engineering project? I finally wrapped that up last month.
  2. Hey Deborah, how's it going? Guess what? Yesterday my partner and I got back from an awesome trip to Rio de Janeiro- we checked out some coo
  3. Hey Deborah! Long time no talk - I had lots of stuff going on. Remember the tough engineering project? I finally wrapped that up last month.
  4. Hey Deb, long time no talk. A lot's happened! On Friday I had a breakthrough with my engineering project. Finally found a solution to a prob
  5. Hey Jolene! Great to see you! Had a blast biking nearby with my neighbor last week - was so freeing and beautiful. Checked out an art show w
  6. Hi Deborah, it's been a while! Since we last talked, so much has happened. Balancing engineering school with my partner's video games is qui
  7. Hey Jolene, I'm so excited to tell you! Yesterday, me and my neighbor ran a free gardening class for the community, it was awesome! People o
  8. Hey Jolene, I'm so excited to tell you! Yesterday, me and my neighbor ran a free gardening class for the community, it was awesome! People o
  9. Hey Jolene, Anna got me a vegan stir-fry the other day - tofu and veg with ginger and soy sauce. It was really tasty! Food is such a wonderf
  10. Hey Deborah, how's it going? Guess what? Yesterday my partner and I got back from an awesome trip to Rio de Janeiro- we checked out some coo

### [basic_facts] dataset_7.json — `Which locations does Deborah practice her yoga at?`

- expected conv indices: 1, 2, 3, 5
- top-10 ranked conv indices: 10, 12, 28, 6, 14, 21, 11, 29, 7, 24
- top-K snippets:
  1. Hey Deb, long time no talk. A lot's happened! On Friday I had a breakthrough with my engineering project. Finally found a solution to a prob
  2. Hey Deborah! Long time no talk - I had lots of stuff going on. Remember the tough engineering project? I finally wrapped that up last month.
  3. Hey Jolene, I'm so excited to tell you! Yesterday, me and my neighbor ran a free gardening class for the community, it was awesome! People o
  4. Hi Deborah, it's been a while! Since we last talked, so much has happened. Balancing engineering school with my partner's video games is qui
  5. Hey Jolene! I started a running group with Anna - it's awesome connecting with people who care about fitness!
  6. Hey Jolene, I'm so excited to tell you! Yesterday, me and my neighbor ran a free gardening class for the community, it was awesome! People o
  7. Hey Jolene, since we talked I've been thinking about my mom's influence. Remembering those we love is important.
  8. Hey Deborah! Long time no talk - I had lots of stuff going on. Remember the tough engineering project? I finally wrapped that up last month.
  9. Hey Jolene! Great to see you! Had a blast biking nearby with my neighbor last week - was so freeing and beautiful. Checked out an art show w
  10. Hey Deborah! Long time no talk - I had lots of stuff going on. Remember the tough engineering project? I finally wrapped that up last month.

### [basic_facts] dataset_8.json — `Where has Evan been on roadtrips with his family?`

- expected conv indices: 0, 1
- top-10 ranked conv indices: 22, 24, 20, 18, 19, 21, 16, 15, 23, 17
- top-K snippets:
  1. Hey Sam, guess what? My partner and I told our extended fam about our marriage yesterday – it was so special! We've been totally overwhelmed
  2. Hey Evan, been a few days since we last chatted. Hope you're doing OK. A lot's happened since then. Got issues with my health, it's been rou
  3. Hey Sam, guess what? My partner and I told our extended fam about our marriage yesterday – it was so special! We've been totally overwhelmed
  4. Hey Evan! Long time no see, how's it going?
  5. Hey Sam, hope you're doing good. Wanted to share some amazing news - my partner is pregnant! We're so excited! It's been a while since we ha
  6. Hey Sam, hope you're doing good. Wanted to share some amazing news - my partner is pregnant! We're so excited! It's been a while since we ha
  7. Hey Sam, what's up? Long time no see, huh? Lots has happened.
  8. Hey Sam, guess what? My partner and I told our extended fam about our marriage yesterday – it was so special! We've been totally overwhelmed
  9. Hey Sam, hope you're doing good. Wanted to share some amazing news - my partner is pregnant! We're so excited! It's been a while since we ha
  10. Hey Evan! I’m really getting into this healthier lifestyle—just took my friends on an epic hiking trip last Friday!

### [basic_facts] dataset_8.json — `How many Prius has Evan owned?`

- expected conv indices: 0
- top-10 ranked conv indices: 17, 13, 20, 11, 19, 16, 23, 15, 22, 14
- top-K snippets:
  1. Hey Sam, good to hear from you. I've hit a bit of a snag - my new Prius, the one I just bought, broke down. It's a bit of a stressor since I
  2. Hey Evan! I've been missing our chats. I had quite the health scare last weekend - ended up in the ER with a severe stomachache. Turns out, 
  3. Hey Evan! Long time no see, how's it going?
  4. Hey Sam, good to hear from you. I've hit a bit of a snag - my new Prius, the one I just bought, broke down. It's a bit of a stressor since I
  5. Hey Sam, good to hear from you. I've hit a bit of a snag - my new Prius, the one I just bought, broke down. It's a bit of a stressor since I
  6. Hey Evan! Long time no see, how's it going?
  7. Hey Evan, hope you're doing okay. I wanted to chat about something that's been bothering me lately... I went for a check-up Monday and my do
  8. Hey Evan! Long time no see, how's it going?
  9. Hey Evan, hope you're doing okay. I wanted to chat about something that's been bothering me lately... I went for a check-up Monday and my do
  10. Hey Sam, what's up? Long time no see, huh? Lots has happened.

### [basic_facts] dataset_8.json — `How many roadtrips did Evan take in May 2023?`

- expected conv indices: 0, 1
- top-10 ranked conv indices: 22, 24, 21, 20, 15, 18, 17, 23, 19, 11
- top-K snippets:
  1. Hey Sam, guess what? My partner and I told our extended fam about our marriage yesterday – it was so special! We've been totally overwhelmed
  2. Hey Evan, been a few days since we last chatted. Hope you're doing OK. A lot's happened since then. Got issues with my health, it's been rou
  3. Hey Evan! I’m really getting into this healthier lifestyle—just took my friends on an epic hiking trip last Friday!
  4. Hey Evan! Long time no see, how's it going?
  5. Hey Evan! Hope you're doing good. Got some good news to share - I'm a Weight Watchers coach in my group now! It's a pretty big accomplishmen
  6. Hey Evan! Hope you're doing good. Got some good news to share - I'm a Weight Watchers coach in my group now! It's a pretty big accomplishmen
  7. Hey Sam, hope you're doing good. Wanted to share some amazing news - my partner is pregnant! We're so excited! It's been a while since we ha
  8. Hey Sam, good to hear from you. I've hit a bit of a snag - my new Prius, the one I just bought, broke down. It's a bit of a stressor since I
  9. Hey Sam, hope you're doing good. Something funny happened last night.
  10. Hey Evan! Long time no see, how's it going?

### [basic_facts] dataset_8.json — `What kind of unhealthy snacks does Sam enjoy eating?`

- expected conv indices: 2
- top-10 ranked conv indices: 22, 21, 23, 8, 19, 24, 16, 17, 18, 20
- top-K snippets:
  1. Hey Sam, guess what? My partner and I told our extended fam about our marriage yesterday – it was so special! We've been totally overwhelmed
  2. Hey Evan! I’m really getting into this healthier lifestyle—just took my friends on an epic hiking trip last Friday!
  3. Hey Evan! I’m really getting into this healthier lifestyle—just took my friends on an epic hiking trip last Friday!
  4. Hey Evan! I’m really getting into this healthier lifestyle—just took my friends on an epic hiking trip last Friday!
  5. Hey Sam, hope you're doing good. Something funny happened last night.
  6. Hey Evan! I’m really getting into this healthier lifestyle—just took my friends on an epic hiking trip last Friday!
  7. Hey Evan! I’m really getting into this healthier lifestyle—just took my friends on an epic hiking trip last Friday!
  8. Hey Sam, hope you're doing good. Something funny happened last night.
  9. Hey Evan! Exciting news: I started a new diet and exercise routine last Monday and it's made a huge difference. I feel great! What about you
  10. Hey Sam, guess what? My partner and I told our extended fam about our marriage yesterday – it was so special! We've been totally overwhelmed

### [basic_facts] dataset_8.json — `What motivates Evan to take care of his health?`

- expected conv indices: 4
- top-10 ranked conv indices: 23, 24, 21, 5, 11, 2, 22, 13, 0, 20
- top-K snippets:
  1. Hey Sam, hope you're doing good. Something funny happened last night.
  2. Hey Evan, been a few days since we last chatted. Hope you're doing OK. A lot's happened since then. Got issues with my health, it's been rou
  3. Hey Evan, been a few days since we last chatted. Hope you're doing OK. A lot's happened since then. Got issues with my health, it's been rou
  4. Hey Evan, been a few days since we last chatted. Hope you're doing OK. A lot's happened since then. Got issues with my health, it's been rou
  5. Hey Sam, hope you're doing good. Something funny happened last night.
  6. Hey Sam, hope you're doing good. Something funny happened last night.
  7. Hey Evan, been a few days since we last chatted. Hope you're doing OK. A lot's happened since then. Got issues with my health, it's been rou
  8. Hey Sam, hope you're doing good. Something funny happened last night.
  9. Hey Sam, hope you're doing good. Something funny happened last night.
  10. Hey Evan! I’m really getting into this healthier lifestyle—just took my friends on an epic hiking trip last Friday!

### [basic_facts] dataset_8.json — `What kind of writing does Sam do to relax and cope with his health issues?`

- expected conv indices: 5, 10
- top-10 ranked conv indices: 24, 15, 23, 13, 11, 21, 22, 1, 20, 6
- top-K snippets:
  1. Hey Evan, been a few days since we last chatted. Hope you're doing OK. A lot's happened since then. Got issues with my health, it's been rou
  2. Hey Evan! Hope you're doing good. Got some good news to share - I'm a Weight Watchers coach in my group now! It's a pretty big accomplishmen
  3. Hey Evan, been a few days since we last chatted. Hope you're doing OK. A lot's happened since then. Got issues with my health, it's been rou
  4. Hey Sam, hope you're doing good. Something funny happened last night.
  5. Hey Evan, been a few days since we last chatted. Hope you're doing OK. A lot's happened since then. Got issues with my health, it's been rou
  6. Hey Evan, been a few days since we last chatted. Hope you're doing OK. A lot's happened since then. Got issues with my health, it's been rou
  7. Hey Sam, hope you're doing good. Something funny happened last night.
  8. Hey Evan! I've been missing our chats. I had quite the health scare last weekend - ended up in the ER with a severe stomachache. Turns out, 
  9. Hey Sam, hope you're doing good. Something funny happened last night.

### [basic_facts] dataset_8.json — `What accidents has Evan's son faced lately?`

- expected conv indices: 6, 19
- top-10 ranked conv indices: 22, 11, 20, 23, 17, 15, 24, 13, 7, 14
- top-K snippets:
  1. Hey Sam, guess what? My partner and I told our extended fam about our marriage yesterday – it was so special! We've been totally overwhelmed
  2. Hey Evan, hope you're doing okay. I wanted to chat about something that's been bothering me lately... I went for a check-up Monday and my do
  3. Hey Sam, guess what? My partner and I told our extended fam about our marriage yesterday – it was so special! We've been totally overwhelmed
  4. Hey Evan, hope you're doing okay. I wanted to chat about something that's been bothering me lately... I went for a check-up Monday and my do
  5. Hey Evan! Long time no see, how's it going?
  6. Hey Sam, hope you're doing good. Something funny happened last night.
  7. Hey Sam, good to hear from you. I've hit a bit of a snag - my new Prius, the one I just bought, broke down. It's a bit of a stressor since I
  8. Hey Evan! Hope you're doing good. Got some good news to share - I'm a Weight Watchers coach in my group now! It's a pretty big accomplishmen
  9. Hey Evan, been a few days since we last chatted. Hope you're doing OK. A lot's happened since then. Got issues with my health, it's been rou
  10. Hey Evan! I've been missing our chats. I had quite the health scare last weekend - ended up in the ER with a severe stomachache. Turns out, 

### [basic_facts] dataset_8.json — `What personal health incidents does Evan face in 2023?`

- expected conv indices: 2, 8, 10
- top-10 ranked conv indices: 23, 22, 11, 20, 15, 7, 14, 24, 6, 17
- top-K snippets:
  1. Hey Sam, hope you're doing good. Something funny happened last night.
  2. Hey Sam, guess what? My partner and I told our extended fam about our marriage yesterday – it was so special! We've been totally overwhelmed
  3. Hey Sam, guess what? My partner and I told our extended fam about our marriage yesterday – it was so special! We've been totally overwhelmed
  4. Hey Evan, hope you're doing okay. I wanted to chat about something that's been bothering me lately... I went for a check-up Monday and my do
  5. Hey Evan, hope you're doing okay. I wanted to chat about something that's been bothering me lately... I went for a check-up Monday and my do
  6. Hey Evan! Long time no see, how's it going?
  7. Hey Evan! Hope you're doing good. Got some good news to share - I'm a Weight Watchers coach in my group now! It's a pretty big accomplishmen
  8. Hey Evan, some big news: I'm on a diet and living healthier! Been tough, but I'm determined.
  9. Morning, Evan. I've been trying to keep up with my new health routine, but it's tough. My family's really pushing for it, and I feel so pres
  10. Hey Evan! Hope you're doing good. Got some good news to share - I'm a Weight Watchers coach in my group now! It's a pretty big accomplishmen

### [basic_facts] dataset_8.json — `What recurring adventure does Evan have with strangers?`

- expected conv indices: 10, 13
- top-10 ranked conv indices: 22, 15, 11, 7, 20, 6, 23, 19, 18, 1
- top-K snippets:
  1. Hey Sam, guess what? My partner and I told our extended fam about our marriage yesterday – it was so special! We've been totally overwhelmed
  2. Hey Sam, guess what? My partner and I told our extended fam about our marriage yesterday – it was so special! We've been totally overwhelmed
  3. Hey Evan! Hope you're doing good. Got some good news to share - I'm a Weight Watchers coach in my group now! It's a pretty big accomplishmen
  4. Hey Evan, hope you're doing okay. I wanted to chat about something that's been bothering me lately... I went for a check-up Monday and my do
  5. Hey Evan, some big news: I'm on a diet and living healthier! Been tough, but I'm determined.
  6. Hey Evan, some big news: I'm on a diet and living healthier! Been tough, but I'm determined.
  7. Hey Evan, hope you're doing okay. I wanted to chat about something that's been bothering me lately... I went for a check-up Monday and my do
  8. Hey Evan! Long time no see, how's it going?
  9. Hey Sam, what's up? It's been a few days since we talked. How have you been? Life's been tough lately - my son had a soccer accident last Sa
  10. Hey Sam, hope you're doing good. Something funny happened last night.

### [basic_facts] dataset_8.json — `What health scares did Sam and Evan experience?`

- expected conv indices: 2, 13, 16
- top-10 ranked conv indices: 22, 11, 20, 23, 15, 7, 6, 17, 4, 1
- top-K snippets:
  1. Hey Sam, guess what? My partner and I told our extended fam about our marriage yesterday – it was so special! We've been totally overwhelmed
  2. Hey Sam, guess what? My partner and I told our extended fam about our marriage yesterday – it was so special! We've been totally overwhelmed
  3. Hey Sam, guess what? My partner and I told our extended fam about our marriage yesterday – it was so special! We've been totally overwhelmed
  4. Hey Sam, guess what? My partner and I told our extended fam about our marriage yesterday – it was so special! We've been totally overwhelmed
  5. Hey Evan, hope you're doing okay. I wanted to chat about something that's been bothering me lately... I went for a check-up Monday and my do
  6. Hey Sam, guess what? My partner and I told our extended fam about our marriage yesterday – it was so special! We've been totally overwhelmed
  7. Hey Sam, guess what? My partner and I told our extended fam about our marriage yesterday – it was so special! We've been totally overwhelmed
  8. Hey Sam, guess what? My partner and I told our extended fam about our marriage yesterday – it was so special! We've been totally overwhelmed

### [basic_facts] dataset_8.json — `Which activity did Sam resume in December 2023 after a long time?`

- expected conv indices: 19, 21
- top-10 ranked conv indices: 22, 15, 12, 24, 17, 20, 23, 3, 9, 1
- top-K snippets:
  1. Hey Sam, guess what? My partner and I told our extended fam about our marriage yesterday – it was so special! We've been totally overwhelmed
  2. Hey Evan! Hope you're doing good. Got some good news to share - I'm a Weight Watchers coach in my group now! It's a pretty big accomplishmen
  3. Hey Sam, how's it going? Been a while since we talked. Hope all is good.
  4. Hey Sam, guess what? My partner and I told our extended fam about our marriage yesterday – it was so special! We've been totally overwhelmed
  5. Hey Sam, guess what? My partner and I told our extended fam about our marriage yesterday – it was so special! We've been totally overwhelmed
  6. Hey Evan, been a few days since we last chatted. Hope you're doing OK. A lot's happened since then. Got issues with my health, it's been rou
  7. Hey Sam, guess what? My partner and I told our extended fam about our marriage yesterday – it was so special! We've been totally overwhelmed
  8. Hey Sam, good to hear from you. I've hit a bit of a snag - my new Prius, the one I just bought, broke down. It's a bit of a stressor since I

### [basic_facts] dataset_9.json — `What items did Calvin buy in March 2023?`

- expected conv indices: 0, 1
- top-10 ranked conv indices: 24, 26, 28, 29, 27, 25
- top-K snippets:
  1. Hey Calvin, how's the tour with Frank Ocean? I was pondering our chat the other day about fame and its impact on relationships. It must be a
  2. Hey Dave! Since we last talked, I went to a networking event to meet more artists. So cool! The people I met will help me build up my fan ba
  3. Hey Dave, I invited my old high school buddy to see me perform in Boston! It was insane. It got me thinking about how far I've come and remi
  4. Hey Calvin, long time no talk! A lot has happened. I've taken up photography and it's been great - been taking pics of the scenery around he
  5. Hey Calvin, long time no talk! A lot has happened. I've taken up photography and it's been great - been taking pics of the scenery around he
  6. Hey Calvin, long time no talk! A lot has happened. I've taken up photography and it's been great - been taking pics of the scenery around he
  7. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  8. Hey Calvin, how's the tour with Frank Ocean? I was pondering our chat the other day about fame and its impact on relationships. It must be a
  9. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  10. Hey Calvin, long time no talk! A lot has happened. I've taken up photography and it's been great - been taking pics of the scenery around he

### [basic_facts] dataset_9.json — `Which bands has Dave enjoyed listening to?`

- expected conv indices: 1, 22
- top-10 ranked conv indices: 27, 25, 29, 26, 28, 24
- top-K snippets:
  1. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  2. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  3. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  4. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  5. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  6. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  7. Hey Dave! Long time no talk! I had a great time yesterday, and visited some sights in Boston with a high school friend. It was really fun an
  8. Hey Dave! Long time no talk! I had a great time yesterday, and visited some sights in Boston with a high school friend. It was really fun an
  9. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  10. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a

### [basic_facts] dataset_9.json — `Which types of cars does Dave like the most?`

- expected conv indices: 0, 2, 3
- top-10 ranked conv indices: 25, 26, 27, 24, 29, 28
- top-K snippets:
  1. Hey Dave! Long time no talk! I had a great time yesterday, and visited some sights in Boston with a high school friend. It was really fun an
  2. Hey Dave! Since we last talked, I went to a networking event to meet more artists. So cool! The people I met will help me build up my fan ba
  3. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  4. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  5. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  6. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  7. Hey Dave! Long time no talk! I had a great time yesterday, and visited some sights in Boston with a high school friend. It was really fun an
  8. Hey Dave! Since we last talked, I went to a networking event to meet more artists. So cool! The people I met will help me build up my fan ba
  9. Hey Calvin, how's the tour with Frank Ocean? I was pondering our chat the other day about fame and its impact on relationships. It must be a
  10. Hey Dave! Long time no talk! I had a great time yesterday, and visited some sights in Boston with a high school friend. It was really fun an

### [basic_facts] dataset_9.json — `What mishaps has Calvin run into?`

- expected conv indices: 5, 8
- top-10 ranked conv indices: 29, 28, 27, 24, 26, 25
- top-K snippets:
  1. Hey Calvin, long time no talk! A lot has happened. I've taken up photography and it's been great - been taking pics of the scenery around he
  2. Hey Dave, I invited my old high school buddy to see me perform in Boston! It was insane. It got me thinking about how far I've come and remi
  3. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  4. Hey Dave, I invited my old high school buddy to see me perform in Boston! It was insane. It got me thinking about how far I've come and remi
  5. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  6. Hey Calvin, long time no talk! A lot has happened. I've taken up photography and it's been great - been taking pics of the scenery around he
  7. Hey Calvin, how's the tour with Frank Ocean? I was pondering our chat the other day about fame and its impact on relationships. It must be a
  8. Hey Dave! Since we last talked, I went to a networking event to meet more artists. So cool! The people I met will help me build up my fan ba
  9. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  10. Hey Calvin, how's the tour with Frank Ocean? I was pondering our chat the other day about fame and its impact on relationships. It must be a

### [basic_facts] dataset_9.json — `How many times has Calvin had to deal with insurance paperwork?`

- expected conv indices: 5, 8
- top-10 ranked conv indices: 29, 27, 26, 28, 24, 25
- top-K snippets:
  1. Hey Calvin, long time no talk! A lot has happened. I've taken up photography and it's been great - been taking pics of the scenery around he
  2. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  3. Hey Dave! Since we last talked, I went to a networking event to meet more artists. So cool! The people I met will help me build up my fan ba
  4. Hey Dave, I invited my old high school buddy to see me perform in Boston! It was insane. It got me thinking about how far I've come and remi
  5. Hey Calvin, how's the tour with Frank Ocean? I was pondering our chat the other day about fame and its impact on relationships. It must be a
  6. Hey Dave, I invited my old high school buddy to see me perform in Boston! It was insane. It got me thinking about how far I've come and remi
  7. Hey Calvin, how's the tour with Frank Ocean? I was pondering our chat the other day about fame and its impact on relationships. It must be a
  8. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  9. Hey Calvin, how's the tour with Frank Ocean? I was pondering our chat the other day about fame and its impact on relationships. It must be a
  10. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a

### [basic_facts] dataset_9.json — `Which places or events has Calvin visited in Tokyo?`

- expected conv indices: 2, 11, 23
- top-10 ranked conv indices: 25, 26, 29, 24, 28, 27, 5
- top-K snippets:
  1. Hey Dave! Long time no talk! I had a great time yesterday, and visited some sights in Boston with a high school friend. It was really fun an
  2. Hey Dave! Since we last talked, I went to a networking event to meet more artists. So cool! The people I met will help me build up my fan ba
  3. Hey Calvin, long time no talk! A lot has happened. I've taken up photography and it's been great - been taking pics of the scenery around he
  4. Hey Calvin, long time no talk! A lot has happened. I've taken up photography and it's been great - been taking pics of the scenery around he
  5. Hey Calvin, how's the tour with Frank Ocean? I was pondering our chat the other day about fame and its impact on relationships. It must be a
  6. Hey Calvin, long time no talk! A lot has happened. I've taken up photography and it's been great - been taking pics of the scenery around he
  7. Hey Calvin, long time no talk! A lot has happened. I've taken up photography and it's been great - been taking pics of the scenery around he
  8. Hey Dave, I invited my old high school buddy to see me perform in Boston! It was insane. It got me thinking about how far I've come and remi
  9. Hey Dave! Since we last talked, I went to a networking event to meet more artists. So cool! The people I met will help me build up my fan ba
  10. Hey Calvin, long time no talk! A lot has happened. I've taken up photography and it's been great - been taking pics of the scenery around he

### [basic_facts] dataset_9.json — `Does Calvin want to expand his brand?`

- expected conv indices: 11, 17
- top-10 ranked conv indices: 28, 27, 24, 29, 26, 25
- top-K snippets:
  1. Hey Dave, I invited my old high school buddy to see me perform in Boston! It was insane. It got me thinking about how far I've come and remi
  2. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  3. Hey Dave, I invited my old high school buddy to see me perform in Boston! It was insane. It got me thinking about how far I've come and remi
  4. Hey Calvin, how's the tour with Frank Ocean? I was pondering our chat the other day about fame and its impact on relationships. It must be a
  5. Hey Dave, I invited my old high school buddy to see me perform in Boston! It was insane. It got me thinking about how far I've come and remi
  6. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  7. Hey Calvin, how's the tour with Frank Ocean? I was pondering our chat the other day about fame and its impact on relationships. It must be a
  8. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  9. Hey Calvin, long time no talk! A lot has happened. I've taken up photography and it's been great - been taking pics of the scenery around he
  10. Hey Dave, I invited my old high school buddy to see me perform in Boston! It was insane. It got me thinking about how far I've come and remi

### [basic_facts] dataset_9.json — `Can Dave work with engines?`

- expected conv indices: 12, 19, 21
- top-10 ranked conv indices: 25, 27, 26, 29, 24, 6, 4, 8, 28, 3
- top-K snippets:
  1. Hey Dave! Long time no talk! I had a great time yesterday, and visited some sights in Boston with a high school friend. It was really fun an
  2. Hey Dave! Long time no talk! I had a great time yesterday, and visited some sights in Boston with a high school friend. It was really fun an
  3. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  4. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  5. Hey Dave! Since we last talked, I went to a networking event to meet more artists. So cool! The people I met will help me build up my fan ba
  6. Hey Calvin, long time no talk! A lot has happened. I've taken up photography and it's been great - been taking pics of the scenery around he
  7. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  8. Hey Calvin, how's the tour with Frank Ocean? I was pondering our chat the other day about fame and its impact on relationships. It must be a
  9. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  10. Hey Dave! Since we last talked, I went to a networking event to meet more artists. So cool! The people I met will help me build up my fan ba

### [basic_facts] dataset_9.json — `What does Calvin do to relax?`

- expected conv indices: 4, 6
- top-10 ranked conv indices: 27, 29, 26, 25, 12, 24, 28, 14, 10, 13
- top-K snippets:
  1. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  2. Hey Calvin, long time no talk! A lot has happened. I've taken up photography and it's been great - been taking pics of the scenery around he
  3. Hey Calvin, long time no talk! A lot has happened. I've taken up photography and it's been great - been taking pics of the scenery around he
  4. Hey Dave! Since we last talked, I went to a networking event to meet more artists. So cool! The people I met will help me build up my fan ba
  5. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  6. Hey Calvin, long time no talk! A lot has happened. I've taken up photography and it's been great - been taking pics of the scenery around he
  7. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  8. Hey Dave! Long time no talk! I had a great time yesterday, and visited some sights in Boston with a high school friend. It was really fun an
  9. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  10. Hey Calvin, long time no talk! A lot has happened. I've taken up photography and it's been great - been taking pics of the scenery around he

### [basic_facts] dataset_9.json — `Where did Dave return from with new knowledge of different techniques of car restoration?`

- expected conv indices: 13, 16
- top-10 ranked conv indices: 27, 25, 26, 24, 29, 28
- top-K snippets:
  1. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  2. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  3. Hey Dave! Long time no talk! I had a great time yesterday, and visited some sights in Boston with a high school friend. It was really fun an
  4. Hey Dave! Since we last talked, I went to a networking event to meet more artists. So cool! The people I met will help me build up my fan ba
  5. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  6. Hey Calvin, how's the tour with Frank Ocean? I was pondering our chat the other day about fame and its impact on relationships. It must be a
  7. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  8. Hey Dave! Long time no talk! I had a great time yesterday, and visited some sights in Boston with a high school friend. It was really fun an
  9. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  10. Hey Calvin, how's the tour with Frank Ocean? I was pondering our chat the other day about fame and its impact on relationships. It must be a

### [basic_facts] dataset_9.json — `What was Dave doing in San Francisco?`

- expected conv indices: 13, 16
- top-10 ranked conv indices: 27, 29, 26, 25, 28, 24
- top-K snippets:
  1. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  2. Hey Calvin, long time no talk! A lot has happened. I've taken up photography and it's been great - been taking pics of the scenery around he
  3. Hey Dave! Since we last talked, I went to a networking event to meet more artists. So cool! The people I met will help me build up my fan ba
  4. Hey Calvin, long time no talk! A lot has happened. I've taken up photography and it's been great - been taking pics of the scenery around he
  5. Hey Dave! Long time no talk! I had a great time yesterday, and visited some sights in Boston with a high school friend. It was really fun an
  6. Hey Dave, I invited my old high school buddy to see me perform in Boston! It was insane. It got me thinking about how far I've come and remi
  7. Hey Dave! Since we last talked, I went to a networking event to meet more artists. So cool! The people I met will help me build up my fan ba
  8. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  9. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  10. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a

### [basic_facts] dataset_9.json — `What was the artists Calvin used to listen to when he was a kid?`

- expected conv indices: 19
- top-10 ranked conv indices: 27, 24, 28, 26, 29, 25, 10
- top-K snippets:
  1. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  2. Hey Calvin, how's the tour with Frank Ocean? I was pondering our chat the other day about fame and its impact on relationships. It must be a
  3. Hey Dave, I invited my old high school buddy to see me perform in Boston! It was insane. It got me thinking about how far I've come and remi
  4. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  5. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  6. Hey Dave, I invited my old high school buddy to see me perform in Boston! It was insane. It got me thinking about how far I've come and remi
  7. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  8. Hey Dave! Since we last talked, I went to a networking event to meet more artists. So cool! The people I met will help me build up my fan ba
  9. Hey Dave, I invited my old high school buddy to see me perform in Boston! It was insane. It got me thinking about how far I've come and remi
  10. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a

### [basic_facts] dataset_9.json — `Which of their family member do Calvin and Dave have nostalgic memories about?`

- expected conv indices: 11, 19
- top-10 ranked conv indices: 28, 25, 27, 26, 29, 24, 1, 5
- top-K snippets:
  1. Hey Dave, I invited my old high school buddy to see me perform in Boston! It was insane. It got me thinking about how far I've come and remi
  2. Hey Dave, I invited my old high school buddy to see me perform in Boston! It was insane. It got me thinking about how far I've come and remi
  3. Hey Dave, I invited my old high school buddy to see me perform in Boston! It was insane. It got me thinking about how far I've come and remi
  4. Hey Dave! Long time no talk! I had a great time yesterday, and visited some sights in Boston with a high school friend. It was really fun an
  5. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  6. Hey Dave! Since we last talked, I went to a networking event to meet more artists. So cool! The people I met will help me build up my fan ba
  7. Hey Calvin, long time no talk! A lot has happened. I've taken up photography and it's been great - been taking pics of the scenery around he
  8. Hey Calvin, long time no talk! A lot has happened. I've taken up photography and it's been great - been taking pics of the scenery around he
  9. Hey Dave, I invited my old high school buddy to see me perform in Boston! It was insane. It got me thinking about how far I've come and remi
  10. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a

### [basic_facts] dataset_9.json — `What shared activities do Dave and Calvin have?`

- expected conv indices: 20
- top-10 ranked conv indices: 25, 26, 27, 28, 29, 14, 24
- top-K snippets:
  1. Hey Dave! Long time no talk! I had a great time yesterday, and visited some sights in Boston with a high school friend. It was really fun an
  2. Hey Dave! Since we last talked, I went to a networking event to meet more artists. So cool! The people I met will help me build up my fan ba
  3. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  4. Hey Dave, I invited my old high school buddy to see me perform in Boston! It was insane. It got me thinking about how far I've come and remi
  5. Hey Dave! Long time no talk! I had a great time yesterday, and visited some sights in Boston with a high school friend. It was really fun an
  6. Hey Dave, I invited my old high school buddy to see me perform in Boston! It was insane. It got me thinking about how far I've come and remi
  7. Hey Calvin, long time no talk! A lot has happened. I've taken up photography and it's been great - been taking pics of the scenery around he
  8. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  9. Hey Calvin, long time no talk! A lot has happened. I've taken up photography and it's been great - been taking pics of the scenery around he

### [basic_facts] dataset_9.json — `Which cities did Dave travel to in 2023?`

- expected conv indices: 13, 25
- top-10 ranked conv indices: 29, 27, 24, 5, 21, 12, 23, 1, 4, 22
- top-K snippets:
  1. Hey Calvin, long time no talk! A lot has happened. I've taken up photography and it's been great - been taking pics of the scenery around he
  2. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  3. Hey Calvin, long time no talk! A lot has happened. I've taken up photography and it's been great - been taking pics of the scenery around he
  4. Hey Calvin, long time no talk! A lot has happened. I've taken up photography and it's been great - been taking pics of the scenery around he
  5. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  6. Hey Calvin, long time no talk! A lot has happened. I've taken up photography and it's been great - been taking pics of the scenery around he
  7. Hey Calvin, how's the tour with Frank Ocean? I was pondering our chat the other day about fame and its impact on relationships. It must be a
  8. Hey Calvin, long time no talk! A lot has happened. I've taken up photography and it's been great - been taking pics of the scenery around he
  9. Hey Dave! Long time no chat! Lots has gone down since we last caught up.
  10. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a

### [basic_facts] dataset_9.json — `How many Ferraris does Calvin own?`

- expected conv indices: 1, 22
- top-10 ranked conv indices: 27, 24, 29, 18, 3, 28, 0, 13, 2, 7
- top-K snippets:
  1. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  2. Hey Calvin, how's the tour with Frank Ocean? I was pondering our chat the other day about fame and its impact on relationships. It must be a
  3. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  4. Hey Calvin, how's the tour with Frank Ocean? I was pondering our chat the other day about fame and its impact on relationships. It must be a
  5. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  6. Hey Calvin, long time no talk! A lot has happened. I've taken up photography and it's been great - been taking pics of the scenery around he
  7. Hey Calvin, long time no talk! A lot has happened. I've taken up photography and it's been great - been taking pics of the scenery around he
  8. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  9. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  10. Hey Calvin, how's the tour with Frank Ocean? I was pondering our chat the other day about fame and its impact on relationships. It must be a

### [basic_facts] dataset_9.json — `What style of guitars does Calvin own?`

- expected conv indices: 15
- top-10 ranked conv indices: 27, 24, 29, 10, 18, 22, 2, 1, 14, 6
- top-K snippets:
  1. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  2. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  3. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  4. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  5. Hey Calvin, how's the tour with Frank Ocean? I was pondering our chat the other day about fame and its impact on relationships. It must be a
  6. Hey Calvin, how's the tour with Frank Ocean? I was pondering our chat the other day about fame and its impact on relationships. It must be a
  7. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  8. Hey Calvin, long time no talk! A lot has happened. I've taken up photography and it's been great - been taking pics of the scenery around he
  9. Hey Calvin, how's the tour with Frank Ocean? I was pondering our chat the other day about fame and its impact on relationships. It must be a
  10. Hey Calvin, long time no talk! A lot has happened. I've taken up photography and it's been great - been taking pics of the scenery around he

### [temporal] dataset_0.json — `When did Caroline meet up with her friends, family, and mentors?`

- expected conv indices: 2
- top-10 ranked conv indices: 8, 6, 7, 0, 9, 3, 13, 5, 14, 16
- top-K snippets:
  1. Hey Caroline, hope all's good! I had a quiet weekend after we went camping with my fam two weekends ago. It was great to unplug and hang wit
  2. Hey Mel, great to chat with you again! So much has happened since we last spoke - I went to an LGBTQ conference two days ago and it was real
  3. Hey Mel, what's up? Been a busy week since we talked.
  4. Hey Mel! Good to see you! How have you been?
  5. Hey Melanie! Just wanted to say hi!
  6. Hey Melanie! Long time no talk! A lot's been going on in my life! Take a look at this.
  7. Hey, Mel! How's it going? There's something I want to tell you. I went hiking last week and got into a bad spot with some people. It really 
  8. Hey Mel! Long time no talk. Lots has been going on since then!
  9. Hey Caroline, hope all's good! I had a quiet weekend after we went camping with my fam two weekends ago. It was great to unplug and hang wit
  10. Hey Mel, what's up? Been a busy week since we talked.

### [temporal] dataset_0.json — `When did Caroline have a picnic?`

- expected conv indices: 5
- top-10 ranked conv indices: 14, 11, 13, 10, 7, 15, 3, 9, 8, 17
- top-K snippets:
  1. Hey Melanie, great to hear from you. What's been up since we talked?
  2. Hey Mel! How're ya doin'? Recently, I had a not-so-great experience on a hike. I ran into a group of religious conservatives who said someth
  3. Hey, Mel! How's it going? There's something I want to tell you. I went hiking last week and got into a bad spot with some people. It really 
  4. Hey Mel! How're ya doin'? Recently, I had a not-so-great experience on a hike. I ran into a group of religious conservatives who said someth
  5. Hey Caroline! Last night was amazing! We celebrated my daughter's birthday with a concert surrounded by music, joy and the warm summer breez
  6. Hey Mel, what's up? Been a busy week since we talked.
  7. Hey Mel, what's up? Been a busy week since we talked.
  8. Hey Mel, long time no chat! I had a wicked day out with the gang last weekend - we went biking and saw some pretty cool stuff. It was so ref
  9. Hey Melanie! Long time no talk! A lot's been going on in my life! Take a look at this.
  10. Hey Melanie! Just wanted to say hi!

### [temporal] dataset_0.json — `When did Melanie go camping in July?`

- expected conv indices: 8
- top-10 ranked conv indices: 9, 5, 7, 14, 3, 11, 1, 15, 13, 17
- top-K snippets:
  1. Hey Melanie! Just wanted to say hi!
  2. Hey Mel! Long time no talk. Lots has been going on since then!
  3. Hey Mel, what's up? Been a busy week since we talked.
  4. Hey Melanie, great to hear from you. What's been up since we talked?
  5. Hey Melanie! Long time no talk! A lot's been going on in my life! Take a look at this.
  6. Hey Melanie! Long time no talk! A lot's been going on in my life! Take a look at this.
  7. Hey Mel! How're ya doin'? Recently, I had a not-so-great experience on a hike. I ran into a group of religious conservatives who said someth
  8. Hey Melanie! Just wanted to say hi!
  9. Hey Melanie! Long time no talk! A lot's been going on in my life! Take a look at this.
  10. Hey Melanie! Just wanted to say hi!

### [temporal] dataset_0.json — `When is Melanie's daughter's birthday?`

- expected conv indices: 10
- top-10 ranked conv indices: 2, 9, 3, 5, 0, 13, 14, 12, 1, 18
- top-K snippets:
  1. Hey Melanie! How's it going? I wanted to tell you about my school event last week. It was awesome! I talked about my transgender journey and
  2. Hey Melanie! Just wanted to say hi!
  3. Hey Melanie! Just wanted to say hi!
  4. Hey Melanie! Long time no talk! A lot's been going on in my life! Take a look at this.
  5. Hey Melanie! Long time no talk! A lot's been going on in my life! Take a look at this.
  6. Hey Mel! Long time no talk. Lots has been going on since then!
  7. Hey Mel! Good to see you! How have you been?
  8. Hey, Mel! How's it going? There's something I want to tell you. I went hiking last week and got into a bad spot with some people. It really 
  9. Hey Melanie, great to hear from you. What's been up since we talked?
  10. Hey Melanie! Long time no talk! A lot's been going on in my life! Take a look at this.

### [temporal] dataset_0.json — `When did Caroline and Melanie go to a pride fesetival together?`

- expected conv indices: 11
- top-10 ranked conv indices: 

### [temporal] dataset_0.json — `When did Caroline apply to adoption agencies?`

- expected conv indices: 12
- top-10 ranked conv indices: 

### [temporal] dataset_0.json — `When did Caroline draw a self-portrait?`

- expected conv indices: 12
- top-10 ranked conv indices: 

### [temporal] dataset_0.json — `When did Caroline encounter people on a hike and have a negative experience?`

- expected conv indices: 13
- top-10 ranked conv indices: 0, 2, 1, 3
- top-K snippets:
  1. Hey Mel! Good to see you! How have you been?
  2. Hey Mel! Good to see you! How have you been?
  3. Hey Mel! Good to see you! How have you been?
  4. Hey Mel! Good to see you! How have you been?
  5. Hey Melanie! How's it going? I wanted to tell you about my school event last week. It was awesome! I talked about my transgender journey and
  6. Hey Caroline, since we last chatted, I've had a lot of things happening to me. I ran a charity race for mental health last Saturday – it was
  7. Hey Caroline, since we last chatted, I've had a lot of things happening to me. I ran a charity race for mental health last Saturday – it was
  8. Hey Caroline, since we last chatted, I've had a lot of things happening to me. I ran a charity race for mental health last Saturday – it was
  9. Hey Caroline, since we last chatted, I've had a lot of things happening to me. I ran a charity race for mental health last Saturday – it was
  10. Hey Mel! Good to see you! How have you been?

### [temporal] dataset_0.json — `When did Melanie make a plate in pottery class?`

- expected conv indices: 13
- top-10 ranked conv indices: 0, 1, 2, 3
- top-K snippets:
  1. Hey Mel! Good to see you! How have you been?
  2. Hey Mel! Good to see you! How have you been?
  3. Hey Mel! Good to see you! How have you been?
  4. Hey Mel! Good to see you! How have you been?
  5. Hey Mel! Good to see you! How have you been?
  6. Hey Caroline, since we last chatted, I've had a lot of things happening to me. I ran a charity race for mental health last Saturday – it was
  7. Hey Melanie! How's it going? I wanted to tell you about my school event last week. It was awesome! I talked about my transgender journey and
  8. Hey Mel! Good to see you! How have you been?
  9. Hey Mel! Good to see you! How have you been?
  10. Hey Caroline, since we last chatted, I've had a lot of things happening to me. I ran a charity race for mental health last Saturday – it was

### [temporal] dataset_0.json — `When did Melanie go to the park?`

- expected conv indices: 14
- top-10 ranked conv indices: 0, 1, 2, 3
- top-K snippets:
  1. Hey Mel! Good to see you! How have you been?
  2. Hey Mel! Good to see you! How have you been?
  3. Hey Mel! Good to see you! How have you been?
  4. Hey Mel! Good to see you! How have you been?
  5. Hey Caroline, since we last chatted, I've had a lot of things happening to me. I ran a charity race for mental health last Saturday – it was
  6. Hey Mel! Good to see you! How have you been?
  7. Hey Mel! Good to see you! How have you been?
  8. Hey Mel! Good to see you! How have you been?
  9. Hey Mel! Good to see you! How have you been?
  10. Hey Mel! Good to see you! How have you been?

### [temporal] dataset_0.json — `When is Caroline's youth center putting on a talent show?`

- expected conv indices: 14
- top-10 ranked conv indices: 0, 1, 2, 3
- top-K snippets:
  1. Hey Mel! Good to see you! How have you been?
  2. Hey Caroline, since we last chatted, I've had a lot of things happening to me. I ran a charity race for mental health last Saturday – it was
  3. Hey Mel! Good to see you! How have you been?
  4. Hey Melanie! How's it going? I wanted to tell you about my school event last week. It was awesome! I talked about my transgender journey and
  5. Hey Mel! Good to see you! How have you been?
  6. Hey Melanie! How's it going? I wanted to tell you about my school event last week. It was awesome! I talked about my transgender journey and
  7. Hey Mel! Good to see you! How have you been?
  8. Hey Caroline, since we last chatted, I've had a lot of things happening to me. I ran a charity race for mental health last Saturday – it was
  9. Hey Mel! Good to see you! How have you been?

### [temporal] dataset_0.json — `When did Caroline go biking with friends?`

- expected conv indices: 15
- top-10 ranked conv indices: 5, 0, 1, 4, 3, 2
- top-K snippets:
  1. Hey Mel! Long time no talk. Lots has been going on since then!
  2. Hey Mel! Good to see you! How have you been?
  3. Hey Mel! Good to see you! How have you been?
  4. Hey Mel! Good to see you! How have you been?
  5. Hey Caroline, since we last chatted, I've had a lot of things happening to me. I ran a charity race for mental health last Saturday – it was
  6. Hey Mel! Good to see you! How have you been?
  7. Hey Caroline, since we last chatted, I've had a lot of things happening to me. I ran a charity race for mental health last Saturday – it was
  8. Hey Mel! Good to see you! How have you been?
  9. Since we last spoke, some big things have happened. Last week I went to an LGBTQ+ pride parade. Everyone was so happy and it made me feel li
  10. Hey Mel! Good to see you! How have you been?

### [temporal] dataset_0.json — `How long has Melanie been practicing art?`

- expected conv indices: 15
- top-10 ranked conv indices: 0, 1, 4, 2, 5, 3
- top-K snippets:
  1. Hey Mel! Good to see you! How have you been?
  2. Hey Mel! Good to see you! How have you been?
  3. Hey Mel! Good to see you! How have you been?
  4. Hey Mel! Good to see you! How have you been?
  5. Hey Mel! Good to see you! How have you been?
  6. Hey Caroline, since we last chatted, I've had a lot of things happening to me. I ran a charity race for mental health last Saturday – it was
  7. Hey Caroline, since we last chatted, I've had a lot of things happening to me. I ran a charity race for mental health last Saturday – it was
  8. Since we last spoke, some big things have happened. Last week I went to an LGBTQ+ pride parade. Everyone was so happy and it made me feel li
  9. Hey Mel! Good to see you! How have you been?
  10. Hey Mel! Good to see you! How have you been?

### [temporal] dataset_0.json — `When did Melanie's friend adopt a child?`

- expected conv indices: 16
- top-10 ranked conv indices: 4, 0, 6, 3, 2, 1, 5
- top-K snippets:
  1. Since we last spoke, some big things have happened. Last week I went to an LGBTQ+ pride parade. Everyone was so happy and it made me feel li
  2. Since we last spoke, some big things have happened. Last week I went to an LGBTQ+ pride parade. Everyone was so happy and it made me feel li
  3. Hey Mel! Good to see you! How have you been?
  4. Hey Mel, great to chat with you again! So much has happened since we last spoke - I went to an LGBTQ conference two days ago and it was real
  5. Hey Mel! Good to see you! How have you been?
  6. Since we last spoke, some big things have happened. Last week I went to an LGBTQ+ pride parade. Everyone was so happy and it made me feel li
  7. Since we last spoke, some big things have happened. Last week I went to an LGBTQ+ pride parade. Everyone was so happy and it made me feel li
  8. Hey Melanie! Long time no talk! A lot's been going on in my life! Take a look at this.
  9. Hey Mel! Good to see you! How have you been?
  10. Hey Mel! Good to see you! How have you been?

### [temporal] dataset_0.json — `When did Melanie get hurt?`

- expected conv indices: 16
- top-10 ranked conv indices: 6, 2, 4, 1, 3, 5, 0
- top-K snippets:
  1. Hey Mel, great to chat with you again! So much has happened since we last spoke - I went to an LGBTQ conference two days ago and it was real
  2. Hey Melanie! How's it going? I wanted to tell you about my school event last week. It was awesome! I talked about my transgender journey and
  3. Hey Melanie! How's it going? I wanted to tell you about my school event last week. It was awesome! I talked about my transgender journey and
  4. Since we last spoke, some big things have happened. Last week I went to an LGBTQ+ pride parade. Everyone was so happy and it made me feel li
  5. Since we last spoke, some big things have happened. Last week I went to an LGBTQ+ pride parade. Everyone was so happy and it made me feel li
  6. Hey Caroline, since we last chatted, I've had a lot of things happening to me. I ran a charity race for mental health last Saturday – it was
  7. Since we last spoke, some big things have happened. Last week I went to an LGBTQ+ pride parade. Everyone was so happy and it made me feel li
  8. Hey Melanie! Long time no talk! A lot's been going on in my life! Take a look at this.
  9. Hey Mel! Long time no talk. Lots has been going on since then!
  10. Since we last spoke, some big things have happened. Last week I went to an LGBTQ+ pride parade. Everyone was so happy and it made me feel li

### [temporal] dataset_0.json — `When did Melanie's family go on a roadtrip?`

- expected conv indices: 17
- top-10 ranked conv indices: 7, 8, 1, 0, 4, 5, 2, 6
- top-K snippets:
  1. Hey Mel, what's up? Been a busy week since we talked.
  2. Hey Caroline, hope all's good! I had a quiet weekend after we went camping with my fam two weekends ago. It was great to unplug and hang wit
  3. Hey Caroline, hope all's good! I had a quiet weekend after we went camping with my fam two weekends ago. It was great to unplug and hang wit
  4. Hey Caroline, since we last chatted, I've had a lot of things happening to me. I ran a charity race for mental health last Saturday – it was
  5. Hey Caroline, since we last chatted, I've had a lot of things happening to me. I ran a charity race for mental health last Saturday – it was
  6. Hey Caroline, hope all's good! I had a quiet weekend after we went camping with my fam two weekends ago. It was great to unplug and hang wit
  7. Hey Mel! Good to see you! How have you been?
  8. Hey Mel, what's up? Been a busy week since we talked.
  9. Hey Caroline, hope all's good! I had a quiet weekend after we went camping with my fam two weekends ago. It was great to unplug and hang wit
  10. Since we last spoke, some big things have happened. Last week I went to an LGBTQ+ pride parade. Everyone was so happy and it made me feel li

### [temporal] dataset_0.json — `When did Caroline pass the adoption interview?`

- expected conv indices: 18
- top-10 ranked conv indices: 0, 7, 4, 1, 6, 8, 2, 5, 3
- top-K snippets:
  1. Hey Mel! Good to see you! How have you been?
  2. Hey Mel! Good to see you! How have you been?
  3. Hey Mel, what's up? Been a busy week since we talked.
  4. Hey Mel! Good to see you! How have you been?
  5. Since we last spoke, some big things have happened. Last week I went to an LGBTQ+ pride parade. Everyone was so happy and it made me feel li
  6. Since we last spoke, some big things have happened. Last week I went to an LGBTQ+ pride parade. Everyone was so happy and it made me feel li
  7. Since we last spoke, some big things have happened. Last week I went to an LGBTQ+ pride parade. Everyone was so happy and it made me feel li
  8. Hey Mel! Good to see you! How have you been?
  9. Hey Mel! Good to see you! How have you been?
  10. Hey Mel, what's up? Been a busy week since we talked.

### [temporal] dataset_0.json — `When did Melanie buy the figurines?`

- expected conv indices: 18
- top-10 ranked conv indices: 4, 7, 0, 2, 5, 3, 8
- top-K snippets:
  1. Since we last spoke, some big things have happened. Last week I went to an LGBTQ+ pride parade. Everyone was so happy and it made me feel li
  2. Since we last spoke, some big things have happened. Last week I went to an LGBTQ+ pride parade. Everyone was so happy and it made me feel li
  3. Hey Mel, what's up? Been a busy week since we talked.
  4. Hey Mel! Good to see you! How have you been?
  5. Hey Mel, what's up? Been a busy week since we talked.
  6. Hey Mel, what's up? Been a busy week since we talked.
  7. Hey Mel! Good to see you! How have you been?
  8. Hey Mel, what's up? Been a busy week since we talked.
  9. Since we last spoke, some big things have happened. Last week I went to an LGBTQ+ pride parade. Everyone was so happy and it made me feel li
  10. Hey Melanie! How's it going? I wanted to tell you about my school event last week. It was awesome! I talked about my transgender journey and

### [temporal] dataset_1.json — `When did Jon start to go to the gym?`

- expected conv indices: 5
- top-10 ranked conv indices: 16, 13, 8, 2, 10, 18, 3, 0, 15, 12
- top-K snippets:
  1. Hey Jon! Long time no chat! How's the dance studio? Last week was wild, I got noticed by fashion editors and it's been amazing but kinda sca
  2. Gina, you won't believe it - I got mentored by this amazing business dude yesterday! It was really inspiring and now I'm even more pumped to
  3. Hey Gina! I'm turning my loves of dance into a business. I'm sunk tons of time into the studio lately, and look at my students - they're alr
  4. Hey Gina, hope you're doing ok! Still following my passion for dance. It's been bumpy, but I'm determined to make it work. I'm still searchi
  5. Hi! Since we last spoke I am still working on the dance studio and things are looking up!
  6. Hey Gina! We haven't talked in a few days. Been rehearsing hard and working on business plans. It's been stressful, but dancing has kept me 
  7. Hey Gina! What's up? How's the store going? I gotta tell you about this thing with my biz.
  8. Hey Jon! Good to see you. What's up? Anything new?
  9. Hey Jon, what's been up? Some pretty cool stuff happened since we talked. I have acquired some new unique pieces for my store.
  10. Hey Gina, thanks for being there for me and believing in me. It means a lot.

### [temporal] dataset_1.json — `When was Jon in Rome?`

- expected conv indices: 14
- top-10 ranked conv indices: 1, 18, 16, 7, 12, 0, 3, 11, 5, 13
- top-K snippets:
  1. Hey Jon! Long time no see! Things have been hectic lately. I just launched an ad campaign for my clothing store in hopes of growing the busi
  2. Hey Gina! We haven't talked in a few days. Been rehearsing hard and working on business plans. It's been stressful, but dancing has kept me 
  3. Hey Jon! Long time no chat! How's the dance studio? Last week was wild, I got noticed by fashion editors and it's been amazing but kinda sca
  4. Hey Gina, I had to shut down my bank account. It was tough, but I needed to do it for my biz.
  5. Hey Gina, thanks for being there for me and believing in me. It means a lot.
  6. Hey Jon! Good to see you. What's up? Anything new?
  7. Hey Gina! What's up? How's the store going? I gotta tell you about this thing with my biz.
  8. Hey Gina, I had to shut down my bank account. It was tough, but I needed to do it for my biz.
  9. Hey Jon! Long time no talk! A lot's happened - I just got accepted for a fashion internship!
  10. Hey Gina, thanks for being there for me and believing in me. It means a lot.

### [temporal] dataset_2.json — `Who did Maria have dinner with on May 3, 2023?`

- expected conv indices: 12
- top-10 ranked conv indices: 30, 28, 21, 29, 10, 18, 31, 27, 11, 16
- top-K snippets:
  1. Hi Maria, since we last chatted, I'm volunteering as a mentor for a local school. It's really rewarding to see how much I can help these stu
  2. Hey John, what's been going on? I just wanted to check in. Last week was wild - I volunteered at the homeless shelter and they gave me a med
  3. Hi Maria, since we last chatted, I'm volunteering as a mentor for a local school. It's really rewarding to see how much I can help these stu
  4. Since the last chat, I've been thinking about how education and infrastructure shape communities. It's so sad how they can stunt growth in n
  5. Hi Maria, since we last chatted, I'm volunteering as a mentor for a local school. It's really rewarding to see how much I can help these stu
  6. Hey John! Long time no talk! Guess what - I got a puppy two weeks ago! Her name's Coco and she's adorable.
  7. Hey Maria, haven't talked for a few days. Had a wild week, my car broke down last Fri on my way to work. Trying to get it fixed but it's tou
  8. Hey Maria, haven't talked for a few days. Had a wild week, my car broke down last Fri on my way to work. Trying to get it fixed but it's tou
  9. Since the last chat, I've been thinking about how education and infrastructure shape communities. It's so sad how they can stunt growth in n
  10. Hey John, been good since we talked? I got some great news to share - I joined a gym last week! It's been super positive - I'm sticking to m

### [temporal] dataset_2.json — `When did Maria donate her car?`

- expected conv indices: 1
- top-10 ranked conv indices: 28, 31, 16, 27, 26, 11, 25, 30, 19, 10
- top-K snippets:
  1. Hey John, what's been going on? I just wanted to check in. Last week was wild - I volunteered at the homeless shelter and they gave me a med
  2. Hey Maria! Guess what? I'm now part of the fire-fighting brigade. I'm super excited to be involved and help out my community!
  3. Hey Maria, long time no talk! Life's been pretty wild lately. The toughest thing to deal with is that we had to say goodbye to Max. He was s
  4. Hey Maria, great chatting with you again! Crazy thing happened since we last talked. I lost my job at the mechanical engineering company. Th
  5. Hey John, what's been going on? I just wanted to check in. Last week was wild - I volunteered at the homeless shelter and they gave me a med
  6. Hey Maria, hope you're doing OK. I had to share something cool with you - I asked family and friends to join the virtual support group I am 
  7. Hey Maria, hope you're doing okay. Since we chatted last, I've been blogging about politics and the government. It's been a really satisfyin
  8. Hey John, I'm doing ok - hope you are too. Some interesting stuff has been going on; last week I dropped off that stuff I baked at the homel
  9. Hey Maria, great chatting with you again! Crazy thing happened since we last talked. I lost my job at the mechanical engineering company. Th
  10. Hey Maria! Guess what? I'm now part of the fire-fighting brigade. I'm super excited to be involved and help out my community!

### [temporal] dataset_2.json — `When did John join the online support group?`

- expected conv indices: 2
- top-10 ranked conv indices: 20, 27, 31, 13, 25, 14, 10, 21, 16, 5
- top-K snippets:
  1. Hey John, long time no see! Sorry I didn't get back to you sooner... So much has happened! Check out these kids I met at the shelter!
  2. Hey Maria, great chatting with you again! Crazy thing happened since we last talked. I lost my job at the mechanical engineering company. Th
  3. Hey Maria! Guess what? I'm now part of the fire-fighting brigade. I'm super excited to be involved and help out my community!
  4. Hey Maria, great to chat again! A lot has happened since we last spoke. Last week, I decided to run for office again - even though I haven't
  5. Hey John, I'm doing ok - hope you are too. Some interesting stuff has been going on; last week I dropped off that stuff I baked at the homel
  6. Hey Maria! Guess what? I'm now part of the fire-fighting brigade. I'm super excited to be involved and help out my community!
  7. Hey Maria, how's it going? Been real busy tackling a project to support military veterans. Trying to get a petition going, it's pretty rewar
  8. Hey Maria, haven't talked for a few days. Had a wild week, my car broke down last Fri on my way to work. Trying to get it fixed but it's tou
  9. Hey Maria, great chatting with you again! Crazy thing happened since we last talked. I lost my job at the mechanical engineering company. Th
  10. Since the last chat, I've been thinking about how education and infrastructure shape communities. It's so sad how they can stunt growth in n

### [temporal] dataset_2.json — `When did Maria go to the beach?`

- expected conv indices: 2
- top-10 ranked conv indices: 18, 21, 30, 12, 10, 11, 25, 31, 19, 16
- top-K snippets:
  1. Hey John, been good since we talked? I got some great news to share - I joined a gym last week! It's been super positive - I'm sticking to m
  2. Since the last chat, I've been thinking about how education and infrastructure shape communities. It's so sad how they can stunt growth in n
  3. Hi Maria, since we last chatted, I'm volunteering as a mentor for a local school. It's really rewarding to see how much I can help these stu
  4. Hey John, been good since we talked? I got some great news to share - I joined a gym last week! It's been super positive - I'm sticking to m
  5. Hi Maria, since we last chatted, I'm volunteering as a mentor for a local school. It's really rewarding to see how much I can help these stu
  6. Hey Maria! Long time no see! Tons has gone down since then!
  7. Hi Maria, since we last chatted, I'm volunteering as a mentor for a local school. It's really rewarding to see how much I can help these stu
  8. Hi Maria, since we last chatted, I'm volunteering as a mentor for a local school. It's really rewarding to see how much I can help these stu
  9. Since the last chat, I've been thinking about how education and infrastructure shape communities. It's so sad how they can stunt growth in n
  10. Hey Maria, haven't talked for a few days. Had a wild week, my car broke down last Fri on my way to work. Trying to get it fixed but it's tou

### [temporal] dataset_2.json — `When did Maria meet Jean?`

- expected conv indices: 6
- top-10 ranked conv indices: 30, 21, 31, 11, 12, 7, 10, 17, 26, 16
- top-K snippets:
  1. Hi Maria, since we last chatted, I'm volunteering as a mentor for a local school. It's really rewarding to see how much I can help these stu
  2. Since the last chat, I've been thinking about how education and infrastructure shape communities. It's so sad how they can stunt growth in n
  3. Hi Maria, since we last chatted, I'm volunteering as a mentor for a local school. It's really rewarding to see how much I can help these stu
  4. Hey Maria! Guess what? I'm now part of the fire-fighting brigade. I'm super excited to be involved and help out my community!
  5. Hey Maria, hope you're doing okay. Since we chatted last, I've been blogging about politics and the government. It's been a really satisfyin
  6. Hey Maria! Long time no see! Tons has gone down since then!
  7. Hi Maria, since we last chatted, I'm volunteering as a mentor for a local school. It's really rewarding to see how much I can help these stu
  8. Hi Maria, since we last chatted, I'm volunteering as a mentor for a local school. It's really rewarding to see how much I can help these stu
  9. Hey John, I haven't talked to you in a while. Last week, my grandma passed away and it's been really hard. I'm trying to stay positive, but 
  10. Hey Maria, haven't talked for a few days. Had a wild week, my car broke down last Fri on my way to work. Trying to get it fixed but it's tou

### [temporal] dataset_2.json — `When did Maria's grandmother pass away?`

- expected conv indices: 7
- top-10 ranked conv indices: 21, 30, 10, 16, 11, 18, 12, 25, 31, 19
- top-K snippets:
  1. Since the last chat, I've been thinking about how education and infrastructure shape communities. It's so sad how they can stunt growth in n
  2. Hi Maria, since we last chatted, I'm volunteering as a mentor for a local school. It's really rewarding to see how much I can help these stu
  3. Hi Maria, since we last chatted, I'm volunteering as a mentor for a local school. It's really rewarding to see how much I can help these stu
  4. Hey Maria, haven't talked for a few days. Had a wild week, my car broke down last Fri on my way to work. Trying to get it fixed but it's tou
  5. Hey Maria, long time no talk! Life's been pretty wild lately. The toughest thing to deal with is that we had to say goodbye to Max. He was s
  6. Hi Maria, since we last chatted, I'm volunteering as a mentor for a local school. It's really rewarding to see how much I can help these stu
  7. Hi Maria, since we last chatted, I'm volunteering as a mentor for a local school. It's really rewarding to see how much I can help these stu
  8. Since the last chat, I've been thinking about how education and infrastructure shape communities. It's so sad how they can stunt growth in n
  9. Hey Maria, hope you're doing okay. Since we chatted last, I've been blogging about politics and the government. It's been a really satisfyin
  10. Hey Maria, long time no talk! Life's been pretty wild lately. The toughest thing to deal with is that we had to say goodbye to Max. He was s

### [temporal] dataset_2.json — `When did John get his degree?`

- expected conv indices: 8
- top-10 ranked conv indices: 12, 20, 16, 18, 31, 5, 30, 25, 21, 10
- top-K snippets:
  1. Hey Maria! Long time no see! Tons has gone down since then!
  2. Hey John, long time no see! Sorry I didn't get back to you sooner... So much has happened! Check out these kids I met at the shelter!
  3. Hey Maria, long time no talk! Life's been pretty wild lately. The toughest thing to deal with is that we had to say goodbye to Max. He was s
  4. Hey John, been good since we talked? I got some great news to share - I joined a gym last week! It's been super positive - I'm sticking to m
  5. Hey Maria! Guess what? I'm now part of the fire-fighting brigade. I'm super excited to be involved and help out my community!
  6. Hey Maria! Guess what? I'm now part of the fire-fighting brigade. I'm super excited to be involved and help out my community!
  7. Hey John, long time no see! Sorry I didn't get back to you sooner... So much has happened! Check out these kids I met at the shelter!
  8. Hey John! Long time no talk. I just wanted to let you know I challenged myself last Friday and did a charity event. It was great! I truly fe
  9. Hi Maria, since we last chatted, I'm volunteering as a mentor for a local school. It's really rewarding to see how much I can help these stu
  10. Hi Maria, since we last chatted, I'm volunteering as a mentor for a local school. It's really rewarding to see how much I can help these stu

### [temporal] dataset_2.json — `When did John go to a convention with colleagues?`

- expected conv indices: 11
- top-10 ranked conv indices: 20, 31, 12, 13, 30, 21, 18, 10, 16, 5
- top-K snippets:
  1. Hey John, long time no see! Sorry I didn't get back to you sooner... So much has happened! Check out these kids I met at the shelter!
  2. Hey John, long time no see! Sorry I didn't get back to you sooner... So much has happened! Check out these kids I met at the shelter!
  3. Hey John, long time no see! Sorry I didn't get back to you sooner... So much has happened! Check out these kids I met at the shelter!
  4. Hey Maria! Guess what? I'm now part of the fire-fighting brigade. I'm super excited to be involved and help out my community!
  5. Hey Maria! Long time no see! Tons has gone down since then!
  6. Hey Maria, great to chat again! A lot has happened since we last spoke. Last week, I decided to run for office again - even though I haven't
  7. Hey Maria! Guess what? I'm now part of the fire-fighting brigade. I'm super excited to be involved and help out my community!
  8. Hi Maria, since we last chatted, I'm volunteering as a mentor for a local school. It's really rewarding to see how much I can help these stu
  9. Hey Maria, great to chat again! A lot has happened since we last spoke. Last week, I decided to run for office again - even though I haven't
  10. Hey Maria! Guess what? I'm now part of the fire-fighting brigade. I'm super excited to be involved and help out my community!

### [temporal] dataset_2.json — `When did Maria get in a car accident?`

- expected conv indices: 20
- top-10 ranked conv indices: 30, 21, 10, 11, 31, 25, 16, 12, 19, 26
- top-K snippets:
  1. Hi Maria, since we last chatted, I'm volunteering as a mentor for a local school. It's really rewarding to see how much I can help these stu
  2. Since the last chat, I've been thinking about how education and infrastructure shape communities. It's so sad how they can stunt growth in n
  3. Hey Maria, haven't talked for a few days. Had a wild week, my car broke down last Fri on my way to work. Trying to get it fixed but it's tou
  4. Hey Maria, hope you're doing okay. Since we chatted last, I've been blogging about politics and the government. It's been a really satisfyin
  5. Hi Maria, since we last chatted, I'm volunteering as a mentor for a local school. It's really rewarding to see how much I can help these stu
  6. Since the last chat, I've been thinking about how education and infrastructure shape communities. It's so sad how they can stunt growth in n
  7. Hey Maria! Guess what? I'm now part of the fire-fighting brigade. I'm super excited to be involved and help out my community!
  8. Hey John, I'm doing ok - hope you are too. Some interesting stuff has been going on; last week I dropped off that stuff I baked at the homel
  9. Hey Maria, long time no talk! Life's been pretty wild lately. The toughest thing to deal with is that we had to say goodbye to Max. He was s
  10. Hey Maria! Long time no see! Tons has gone down since then!

### [temporal] dataset_2.json — `When was John's old area hit with a flood?`

- expected conv indices: 22
- top-10 ranked conv indices: 12, 21, 31, 18, 5, 20, 30, 13, 16, 10
- top-K snippets:
  1. Hey Maria! Long time no see! Tons has gone down since then!
  2. Since the last chat, I've been thinking about how education and infrastructure shape communities. It's so sad how they can stunt growth in n
  3. Hey Maria! Guess what? I'm now part of the fire-fighting brigade. I'm super excited to be involved and help out my community!
  4. Hey John, been good since we talked? I got some great news to share - I joined a gym last week! It's been super positive - I'm sticking to m
  5. Hey John! Long time no talk. I just wanted to let you know I challenged myself last Friday and did a charity event. It was great! I truly fe
  6. Hey John, long time no see! Sorry I didn't get back to you sooner... So much has happened! Check out these kids I met at the shelter!
  7. Hi Maria, since we last chatted, I'm volunteering as a mentor for a local school. It's really rewarding to see how much I can help these stu
  8. Hey John, long time no see! Sorry I didn't get back to you sooner... So much has happened! Check out these kids I met at the shelter!
  9. Hey Maria, great to chat again! A lot has happened since we last spoke. Last week, I decided to run for office again - even though I haven't
  10. Hey Maria, long time no talk! Life's been pretty wild lately. The toughest thing to deal with is that we had to say goodbye to Max. He was s

### [temporal] dataset_2.json — `When did Maria go hiking with her church friends?`

- expected conv indices: 24
- top-10 ranked conv indices: 30, 18, 12, 21, 11, 17, 31, 20, 16, 10
- top-K snippets:
  1. Hi Maria, since we last chatted, I'm volunteering as a mentor for a local school. It's really rewarding to see how much I can help these stu
  2. Hey John, been good since we talked? I got some great news to share - I joined a gym last week! It's been super positive - I'm sticking to m
  3. Hey John, been good since we talked? I got some great news to share - I joined a gym last week! It's been super positive - I'm sticking to m
  4. Hey Maria! Long time no see! Tons has gone down since then!
  5. Hi Maria, since we last chatted, I'm volunteering as a mentor for a local school. It's really rewarding to see how much I can help these stu
  6. Since the last chat, I've been thinking about how education and infrastructure shape communities. It's so sad how they can stunt growth in n
  7. Hey Maria, hope you're doing okay. Since we chatted last, I've been blogging about politics and the government. It's been a really satisfyin
  8. Hey Maria! Long time no see! Tons has gone down since then!
  9. Hey Maria! Long time no see! Tons has gone down since then!
  10. Since the last chat, I've been thinking about how education and infrastructure shape communities. It's so sad how they can stunt growth in n

### [temporal] dataset_2.json — `When did John have his first firefighter call-out?`

- expected conv indices: 25
- top-10 ranked conv indices: 20, 31, 30, 12, 21, 16, 26, 5, 14, 7
- top-K snippets:
  1. Hey John, long time no see! Sorry I didn't get back to you sooner... So much has happened! Check out these kids I met at the shelter!
  2. Hey Maria! Guess what? I'm now part of the fire-fighting brigade. I'm super excited to be involved and help out my community!
  3. Hey Maria! Guess what? I'm now part of the fire-fighting brigade. I'm super excited to be involved and help out my community!
  4. Hi Maria, since we last chatted, I'm volunteering as a mentor for a local school. It's really rewarding to see how much I can help these stu
  5. Hey Maria! Long time no see! Tons has gone down since then!
  6. Since the last chat, I've been thinking about how education and infrastructure shape communities. It's so sad how they can stunt growth in n
  7. Hey Maria! Guess what? I'm now part of the fire-fighting brigade. I'm super excited to be involved and help out my community!
  8. Hey Maria! Guess what? I'm now part of the fire-fighting brigade. I'm super excited to be involved and help out my community!
  9. Hey Maria! Guess what? I'm now part of the fire-fighting brigade. I'm super excited to be involved and help out my community!
  10. Hey John, long time no see! Sorry I didn't get back to you sooner... So much has happened! Check out these kids I met at the shelter!

### [temporal] dataset_2.json — `When did Maria start volunteering at the homeless shelter?`

- expected conv indices: 26
- top-10 ranked conv indices: 7, 30, 11, 31, 10, 13, 18, 25, 14, 16
- top-K snippets:
  1. Hey John, I haven't talked to you in a while. Last week, my grandma passed away and it's been really hard. I'm trying to stay positive, but 
  2. Hi Maria, since we last chatted, I'm volunteering as a mentor for a local school. It's really rewarding to see how much I can help these stu
  3. Hey Maria, hope you're doing okay. Since we chatted last, I've been blogging about politics and the government. It's been a really satisfyin
  4. Hey Maria, hope you're doing okay. Since we chatted last, I've been blogging about politics and the government. It's been a really satisfyin
  5. Hey Maria! Guess what? I'm now part of the fire-fighting brigade. I'm super excited to be involved and help out my community!
  6. Hey Maria! Guess what? I'm now part of the fire-fighting brigade. I'm super excited to be involved and help out my community!
  7. Hey Maria, haven't talked for a few days. Had a wild week, my car broke down last Fri on my way to work. Trying to get it fixed but it's tou
  8. Hey Maria, great to chat again! A lot has happened since we last spoke. Last week, I decided to run for office again - even though I haven't
  9. Hey John, been good since we talked? I got some great news to share - I joined a gym last week! It's been super positive - I'm sticking to m
  10. Hey Maria! Guess what? I'm now part of the fire-fighting brigade. I'm super excited to be involved and help out my community!

### [temporal] dataset_2.json — `When did John help renovate his hometown community center?`

- expected conv indices: 27
- top-10 ranked conv indices: 13, 31, 14, 16, 20, 10, 7, 5, 11, 9
- top-K snippets:
  1. Hey Maria, great to chat again! A lot has happened since we last spoke. Last week, I decided to run for office again - even though I haven't
  2. Hey Maria! Guess what? I'm now part of the fire-fighting brigade. I'm super excited to be involved and help out my community!
  3. Hey Maria, how's it going? Been real busy tackling a project to support military veterans. Trying to get a petition going, it's pretty rewar
  4. Hey Maria, great to chat again! A lot has happened since we last spoke. Last week, I decided to run for office again - even though I haven't
  5. Hey Maria! Guess what? I'm now part of the fire-fighting brigade. I'm super excited to be involved and help out my community!
  6. Hey Maria, how's it going? Been real busy tackling a project to support military veterans. Trying to get a petition going, it's pretty rewar
  7. Hey Maria, long time no talk! Life's been pretty wild lately. The toughest thing to deal with is that we had to say goodbye to Max. He was s
  8. Hey John, long time no see! Sorry I didn't get back to you sooner... So much has happened! Check out these kids I met at the shelter!
  9. Hey Maria, great to chat again! A lot has happened since we last spoke. Last week, I decided to run for office again - even though I haven't
  10. Hey Maria! Guess what? I'm now part of the fire-fighting brigade. I'm super excited to be involved and help out my community!

### [temporal] dataset_2.json — `When did Maria take up community work with her church friends?`

- expected conv indices: 27
- top-10 ranked conv indices: 30, 7, 31, 13, 11, 10, 20, 26, 17, 18
- top-K snippets:
  1. Hi Maria, since we last chatted, I'm volunteering as a mentor for a local school. It's really rewarding to see how much I can help these stu
  2. Hey John, I haven't talked to you in a while. Last week, my grandma passed away and it's been really hard. I'm trying to stay positive, but 
  3. Hey Maria! Guess what? I'm now part of the fire-fighting brigade. I'm super excited to be involved and help out my community!
  4. Hey Maria, great to chat again! A lot has happened since we last spoke. Last week, I decided to run for office again - even though I haven't
  5. Hey Maria, great to chat again! A lot has happened since we last spoke. Last week, I decided to run for office again - even though I haven't
  6. Hey Maria, hope you're doing okay. Since we chatted last, I've been blogging about politics and the government. It's been a really satisfyin
  7. Hey Maria! Guess what? I'm now part of the fire-fighting brigade. I'm super excited to be involved and help out my community!
  8. Hey Maria, great to chat again! A lot has happened since we last spoke. Last week, I decided to run for office again - even though I haven't
  9. Hey Maria, haven't talked for a few days. Had a wild week, my car broke down last Fri on my way to work. Trying to get it fixed but it's tou
  10. Hey John, long time no see! Sorry I didn't get back to you sooner... So much has happened! Check out these kids I met at the shelter!

### [temporal] dataset_2.json — `When did Maria receive a medal from the homeless shelter?`

- expected conv indices: 28
- top-10 ranked conv indices: 7, 20, 14, 31, 30, 10, 26, 11, 25, 19
- top-K snippets:
  1. Hey John, I haven't talked to you in a while. Last week, my grandma passed away and it's been really hard. I'm trying to stay positive, but 
  2. Hey John, long time no see! Sorry I didn't get back to you sooner... So much has happened! Check out these kids I met at the shelter!
  3. Hey Maria, how's it going? Been real busy tackling a project to support military veterans. Trying to get a petition going, it's pretty rewar
  4. Hey Maria! Guess what? I'm now part of the fire-fighting brigade. I'm super excited to be involved and help out my community!
  5. Hi Maria, since we last chatted, I'm volunteering as a mentor for a local school. It's really rewarding to see how much I can help these stu
  6. Hey Maria, haven't talked for a few days. Had a wild week, my car broke down last Fri on my way to work. Trying to get it fixed but it's tou
  7. Hey Maria, hope you're doing OK. I had to share something cool with you - I asked family and friends to join the virtual support group I am 
  8. Hey Maria, hope you're doing okay. Since we chatted last, I've been blogging about politics and the government. It's been a really satisfyin
  9. Hey Maria! Guess what? I'm now part of the fire-fighting brigade. I'm super excited to be involved and help out my community!
  10. Hey John, I'm doing ok - hope you are too. Some interesting stuff has been going on; last week I dropped off that stuff I baked at the homel

### [temporal] dataset_2.json — `When did John participate in a 5K charity run?`

- expected conv indices: 28
- top-10 ranked conv indices: 31, 20, 14, 13, 9, 12, 11, 30, 25, 18
- top-K snippets:
  1. Hey Maria! Guess what? I'm now part of the fire-fighting brigade. I'm super excited to be involved and help out my community!
  2. Hey Maria! Guess what? I'm now part of the fire-fighting brigade. I'm super excited to be involved and help out my community!
  3. Hey John, long time no see! Sorry I didn't get back to you sooner... So much has happened! Check out these kids I met at the shelter!
  4. Hey Maria, how's it going? Been real busy tackling a project to support military veterans. Trying to get a petition going, it's pretty rewar
  5. Hey Maria, great to chat again! A lot has happened since we last spoke. Last week, I decided to run for office again - even though I haven't
  6. Hey Maria! Guess what? I'm now part of the fire-fighting brigade. I'm super excited to be involved and help out my community!
  7. Hey Maria, great to chat again! A lot has happened since we last spoke. Last week, I decided to run for office again - even though I haven't
  8. Hey John, long time no see! Sorry I didn't get back to you sooner... So much has happened! Check out these kids I met at the shelter!
  9. Hey Maria, I'm so excited to tell you I started a weekend yoga class with a colleague - it's awesome! I feel great, both mentally and physic
  10. Hey John, long time no see! Sorry I didn't get back to you sooner... So much has happened! Check out these kids I met at the shelter!

### [temporal] dataset_2.json — `When did Maria get Coco?`

- expected conv indices: 29
- top-10 ranked conv indices: 30, 21, 18, 10, 12, 31, 7, 11, 16, 17
- top-K snippets:
  1. Hi Maria, since we last chatted, I'm volunteering as a mentor for a local school. It's really rewarding to see how much I can help these stu
  2. Since the last chat, I've been thinking about how education and infrastructure shape communities. It's so sad how they can stunt growth in n
  3. Hi Maria, since we last chatted, I'm volunteering as a mentor for a local school. It's really rewarding to see how much I can help these stu
  4. Hi Maria, since we last chatted, I'm volunteering as a mentor for a local school. It's really rewarding to see how much I can help these stu
  5. Hey John, been good since we talked? I got some great news to share - I joined a gym last week! It's been super positive - I'm sticking to m
  6. Hi Maria, since we last chatted, I'm volunteering as a mentor for a local school. It's really rewarding to see how much I can help these stu
  7. Hi Maria, since we last chatted, I'm volunteering as a mentor for a local school. It's really rewarding to see how much I can help these stu
  8. Hey Maria, haven't talked for a few days. Had a wild week, my car broke down last Fri on my way to work. Trying to get it fixed but it's tou
  9. Hey Maria! Long time no see! Tons has gone down since then!
  10. Hey Maria! Guess what? I'm now part of the fire-fighting brigade. I'm super excited to be involved and help out my community!

### [temporal] dataset_2.json — `When did John go on a camping trip with Max?`

- expected conv indices: 29
- top-10 ranked conv indices: 30, 18, 17, 20, 21, 31, 12, 7, 5, 19
- top-K snippets:
  1. Hi Maria, since we last chatted, I'm volunteering as a mentor for a local school. It's really rewarding to see how much I can help these stu
  2. Hi Maria, since we last chatted, I'm volunteering as a mentor for a local school. It's really rewarding to see how much I can help these stu
  3. Hey John, been good since we talked? I got some great news to share - I joined a gym last week! It's been super positive - I'm sticking to m
  4. Hey John, how're you doing? I'm sorry about Max. Losing a pet is tough. Some friends from church and I went camping last weekend - it was a 
  5. Hey John, long time no see! Sorry I didn't get back to you sooner... So much has happened! Check out these kids I met at the shelter!
  6. Hey John, been good since we talked? I got some great news to share - I joined a gym last week! It's been super positive - I'm sticking to m
  7. Since the last chat, I've been thinking about how education and infrastructure shape communities. It's so sad how they can stunt growth in n
  8. Hey Maria! Guess what? I'm now part of the fire-fighting brigade. I'm super excited to be involved and help out my community!
  9. Hey Maria! Long time no see! Tons has gone down since then!
  10. Hey John, I haven't talked to you in a while. Last week, my grandma passed away and it's been really hard. I'm trying to stay positive, but 

### [temporal] dataset_2.json — `What did John attend with his colleagues in March 2023?`

- expected conv indices: 11
- top-10 ranked conv indices: 20, 13, 21, 31, 30, 14, 12, 16, 18, 10
- top-K snippets:
  1. Hey John, long time no see! Sorry I didn't get back to you sooner... So much has happened! Check out these kids I met at the shelter!
  2. Hey Maria, great to chat again! A lot has happened since we last spoke. Last week, I decided to run for office again - even though I haven't
  3. Hey Maria, great to chat again! A lot has happened since we last spoke. Last week, I decided to run for office again - even though I haven't
  4. Since the last chat, I've been thinking about how education and infrastructure shape communities. It's so sad how they can stunt growth in n
  5. Hey Maria! Guess what? I'm now part of the fire-fighting brigade. I'm super excited to be involved and help out my community!
  6. Hi Maria, since we last chatted, I'm volunteering as a mentor for a local school. It's really rewarding to see how much I can help these stu
  7. Hey John, long time no see! Sorry I didn't get back to you sooner... So much has happened! Check out these kids I met at the shelter!
  8. Hey Maria! Guess what? I'm now part of the fire-fighting brigade. I'm super excited to be involved and help out my community!
  9. Hey Maria, how's it going? Been real busy tackling a project to support military veterans. Trying to get a petition going, it's pretty rewar
  10. Hey Maria! Long time no see! Tons has gone down since then!

### [temporal] dataset_3.json — `When did Joanna first watch "Eternal Sunshine of the Spotless Mind?`

- expected conv indices: 0
- top-10 ranked conv indices: 28, 24, 12, 25, 26, 23, 27, 19, 22, 20
- top-K snippets:
  1. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  2. Hey Joanna, what's been up since we last chatted? How's it going?
  3. Hey Jo! Been ages since we last talked. Here's something cool that happened the other day - I took Max for a walk and ran into this super ni
  4. Wow, Nate, I'm on fire! I just set up meetings with movie producers — my dreams are comin' true!
  5. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  6. Hey Joanna, what's been up since we last chatted? How's it going?
  7. Hey Joanna, what's been up? Haven't seen you since we last talked.
  8. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  9. Hey Joanna, what's been up since we last chatted? How's it going?
  10. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 

### [temporal] dataset_3.json — `When did Nate win his first video game tournament?`

- expected conv indices: 0
- top-10 ranked conv indices: 21, 27, 26, 22, 20, 19, 23, 12, 25, 24
- top-K snippets:
  1. Hey Nate, hi! Yesterday, I tried my newest dairy-free recipe and it was a winner with my family! Mixing and matching flavors is fun and I'm 
  2. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  3. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  4. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  5. Hey Nate, hi! Yesterday, I tried my newest dairy-free recipe and it was a winner with my family! Mixing and matching flavors is fun and I'm 
  6. Hey Joanna, it's been a couple days since we last talked. Something exciting happened last Friday. I went to a game convention and met new p
  7. Hey Nate, long time no see! My laptop crashed last week and I lost all my work - super frustrating! As a writer, my laptop is like half of m
  8. Hey Joanna! Long time no talk. So much has happened. Look how cute they are! Hanging with them has been a big help, especially recently. Spe
  9. Hey Nate, hi! Yesterday, I tried my newest dairy-free recipe and it was a winner with my family! Mixing and matching flavors is fun and I'm 
  10. Hey Joanna, what's been up? Haven't seen you since we last talked.

### [temporal] dataset_3.json — `How long has Nate had his first two turtles?`

- expected conv indices: 1
- top-10 ranked conv indices: 26, 27, 25, 28, 24, 19, 22, 21, 23, 20
- top-K snippets:
  1. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  2. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  3. Wow, Nate, I'm on fire! I just set up meetings with movie producers — my dreams are comin' true!
  4. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  5. Hey Joanna, what's been up since we last chatted? How's it going?
  6. Hey Joanna! Long time no talk. So much has happened. Look how cute they are! Hanging with them has been a big help, especially recently. Spe
  7. Hey Joanna, what's been up since we last chatted? How's it going?
  8. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  9. Hey Joanna, what's been up since we last chatted? How's it going?
  10. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!

### [temporal] dataset_3.json — `When did Joanna finish her first screenplay?`

- expected conv indices: 1
- top-10 ranked conv indices: 28, 25, 26, 23, 24, 19, 27, 12, 20, 10
- top-K snippets:
  1. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  2. Wow, Nate, I'm on fire! I just set up meetings with movie producers — my dreams are comin' true!
  3. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  4. Hey Joanna, what's been up? Haven't seen you since we last talked.
  5. Hey Joanna, what's been up since we last chatted? How's it going?
  6. Hey Joanna! Long time no talk. So much has happened. Look how cute they are! Hanging with them has been a big help, especially recently. Spe
  7. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  8. Hey Joanna, what's been up since we last chatted? How's it going?
  9. Hey Joanna, what's been up since we last chatted? How's it going?
  10. Hey Jo! Been ages since we last talked. Here's something cool that happened the other day - I took Max for a walk and ran into this super ni

### [temporal] dataset_3.json — `When did Nate get his first two turtles?`

- expected conv indices: 1
- top-10 ranked conv indices: 26, 27, 19, 24, 25, 28, 21, 22, 20, 23
- top-K snippets:
  1. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  2. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  3. Hey Joanna! Long time no talk. So much has happened. Look how cute they are! Hanging with them has been a big help, especially recently. Spe
  4. Hey Joanna, what's been up since we last chatted? How's it going?
  5. Hey Joanna, what's been up since we last chatted? How's it going?
  6. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  7. Wow, Nate, I'm on fire! I just set up meetings with movie producers — my dreams are comin' true!
  8. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  9. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  10. Hey Joanna, what's been up since we last chatted? How's it going?

### [temporal] dataset_3.json — `What major achievement did Joanna accomplish in January 2022?`

- expected conv indices: 1
- top-10 ranked conv indices: 25, 26, 27, 24, 20, 21, 19, 23, 28, 22
- top-K snippets:
  1. Wow, Nate, I'm on fire! I just set up meetings with movie producers — my dreams are comin' true!
  2. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  3. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  4. Hey Joanna, what's been up since we last chatted? How's it going?
  5. Hey Nate, long time no see! My laptop crashed last week and I lost all my work - super frustrating! As a writer, my laptop is like half of m
  6. Hey Nate, hi! Yesterday, I tried my newest dairy-free recipe and it was a winner with my family! Mixing and matching flavors is fun and I'm 
  7. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  8. Wow, Nate, I'm on fire! I just set up meetings with movie producers — my dreams are comin' true!
  9. Hey Nate, hi! Yesterday, I tried my newest dairy-free recipe and it was a winner with my family! Mixing and matching flavors is fun and I'm 
  10. Hey Joanna! Long time no talk. So much has happened. Look how cute they are! Hanging with them has been a big help, especially recently. Spe

### [temporal] dataset_3.json — `When did Joanna have an audition for a writing gig?`

- expected conv indices: 5
- top-10 ranked conv indices: 26, 25, 28, 23, 24, 20, 19, 27, 10, 12
- top-K snippets:
  1. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  2. Wow, Nate, I'm on fire! I just set up meetings with movie producers — my dreams are comin' true!
  3. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  4. Hey Joanna, what's been up? Haven't seen you since we last talked.
  5. Hey Joanna, what's been up since we last chatted? How's it going?
  6. Hey Nate, long time no see! My laptop crashed last week and I lost all my work - super frustrating! As a writer, my laptop is like half of m
  7. Hey Joanna! Long time no talk. So much has happened. Look how cute they are! Hanging with them has been a big help, especially recently. Spe
  8. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  9. Hey Joanna, what's been up? Haven't seen you since we last talked.
  10. Hey Joanna, what's been up since we last chatted? How's it going?

### [temporal] dataset_3.json — `When did Nate get purple hair?`

- expected conv indices: 6
- top-10 ranked conv indices: 21, 22, 19, 28, 26, 20, 10, 27, 24, 23
- top-K snippets:
  1. Hey Nate, hi! Yesterday, I tried my newest dairy-free recipe and it was a winner with my family! Mixing and matching flavors is fun and I'm 
  2. Hey Joanna, it's been a couple days since we last talked. Something exciting happened last Friday. I went to a game convention and met new p
  3. Hey Joanna! Long time no talk. So much has happened. Look how cute they are! Hanging with them has been a big help, especially recently. Spe
  4. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  5. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  6. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  7. Hey Nate, long time no see! My laptop crashed last week and I lost all my work - super frustrating! As a writer, my laptop is like half of m
  8. Hey Nate! Great to hear from you! Quite a week since we last talked - something awesome happened to me!
  9. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  10. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!

### [temporal] dataset_3.json — `What physical transformation did Nate undergo in April 2022?`

- expected conv indices: 6
- top-10 ranked conv indices: 25, 21, 22, 19, 26, 23, 28, 20, 24, 10
- top-K snippets:
  1. Wow, Nate, I'm on fire! I just set up meetings with movie producers — my dreams are comin' true!
  2. Hey Nate, hi! Yesterday, I tried my newest dairy-free recipe and it was a winner with my family! Mixing and matching flavors is fun and I'm 
  3. Hey Joanna, it's been a couple days since we last talked. Something exciting happened last Friday. I went to a game convention and met new p
  4. Hey Joanna! Long time no talk. So much has happened. Look how cute they are! Hanging with them has been a big help, especially recently. Spe
  5. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  6. Hey Joanna, what's been up? Haven't seen you since we last talked.
  7. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  8. Hey Joanna, what's been up? Haven't seen you since we last talked.
  9. Hey Nate, long time no see! My laptop crashed last week and I lost all my work - super frustrating! As a writer, my laptop is like half of m
  10. Hey Joanna, it's been a couple days since we last talked. Something exciting happened last Friday. I went to a game convention and met new p

### [temporal] dataset_3.json — `What movie did Joanna watch on 1 May, 2022?`

- expected conv indices: 9
- top-10 ranked conv indices: 24, 28, 12, 25, 27, 23, 21, 19, 20, 22
- top-K snippets:
  1. Hey Joanna, what's been up since we last chatted? How's it going?
  2. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  3. Hey Jo! Been ages since we last talked. Here's something cool that happened the other day - I took Max for a walk and ran into this super ni
  4. Hey Joanna, what's been up since we last chatted? How's it going?
  5. Wow, Nate, I'm on fire! I just set up meetings with movie producers — my dreams are comin' true!
  6. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  7. Hey Joanna, what's been up since we last chatted? How's it going?
  8. Hey Joanna, what's been up? Haven't seen you since we last talked.
  9. Hey Nate, hi! Yesterday, I tried my newest dairy-free recipe and it was a winner with my family! Mixing and matching flavors is fun and I'm 
  10. Hey Joanna, what's been up since we last chatted? How's it going?

### [temporal] dataset_3.json — `When did Nate adopt Max?`

- expected conv indices: 11
- top-10 ranked conv indices: 22, 21, 19, 10, 28, 23, 26, 20, 16, 18
- top-K snippets:
  1. Hey Joanna, it's been a couple days since we last talked. Something exciting happened last Friday. I went to a game convention and met new p
  2. Hey Nate, hi! Yesterday, I tried my newest dairy-free recipe and it was a winner with my family! Mixing and matching flavors is fun and I'm 
  3. Hey Joanna! Long time no talk. So much has happened. Look how cute they are! Hanging with them has been a big help, especially recently. Spe
  4. Hey Nate! Great to hear from you! Quite a week since we last talked - something awesome happened to me!
  5. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  6. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  7. Hey Joanna, what's been up? Haven't seen you since we last talked.
  8. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  9. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  10. Hey Nate, hi! Yesterday, I tried my newest dairy-free recipe and it was a winner with my family! Mixing and matching flavors is fun and I'm 

### [temporal] dataset_3.json — `Who was the new addition to Nate's family in May 2022?`

- expected conv indices: 11
- top-10 ranked conv indices: 22, 19, 20, 23, 25, 26, 21, 28, 27, 10
- top-K snippets:
  1. Hey Joanna, it's been a couple days since we last talked. Something exciting happened last Friday. I went to a game convention and met new p
  2. Hey Joanna! Long time no talk. So much has happened. Look how cute they are! Hanging with them has been a big help, especially recently. Spe
  3. Hey Nate, long time no see! My laptop crashed last week and I lost all my work - super frustrating! As a writer, my laptop is like half of m
  4. Hey Joanna, what's been up? Haven't seen you since we last talked.
  5. Wow, Nate, I'm on fire! I just set up meetings with movie producers — my dreams are comin' true!
  6. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  7. Hey Joanna, it's been a couple days since we last talked. Something exciting happened last Friday. I went to a game convention and met new p
  8. Hey Nate, hi! Yesterday, I tried my newest dairy-free recipe and it was a winner with my family! Mixing and matching flavors is fun and I'm 
  9. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  10. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 

### [temporal] dataset_3.json — `When did Joanna start writing her third screenplay?`

- expected conv indices: 11
- top-10 ranked conv indices: 28, 26, 25, 24, 23, 12, 19, 27, 10, 20
- top-K snippets:
  1. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  2. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  3. Wow, Nate, I'm on fire! I just set up meetings with movie producers — my dreams are comin' true!
  4. Hey Joanna, what's been up since we last chatted? How's it going?
  5. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  6. Hey Joanna, what's been up? Haven't seen you since we last talked.
  7. Hey Jo! Been ages since we last talked. Here's something cool that happened the other day - I took Max for a walk and ran into this super ni
  8. Hey Joanna, what's been up since we last chatted? How's it going?
  9. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  10. Hey Joanna! Long time no talk. So much has happened. Look how cute they are! Hanging with them has been a big help, especially recently. Spe

### [temporal] dataset_3.json — `When is Nate hosting a gaming party?`

- expected conv indices: 13
- top-10 ranked conv indices: 27, 22, 26, 19, 20, 25, 10, 21, 12, 23
- top-K snippets:
  1. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  2. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  3. Hey Joanna, it's been a couple days since we last talked. Something exciting happened last Friday. I went to a game convention and met new p
  4. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  5. Hey Joanna! Long time no talk. So much has happened. Look how cute they are! Hanging with them has been a big help, especially recently. Spe
  6. Hey Nate, long time no see! My laptop crashed last week and I lost all my work - super frustrating! As a writer, my laptop is like half of m
  7. Wow, Nate, I'm on fire! I just set up meetings with movie producers — my dreams are comin' true!
  8. Wow, Nate, I'm on fire! I just set up meetings with movie producers — my dreams are comin' true!
  9. Hey Nate! Great to hear from you! Quite a week since we last talked - something awesome happened to me!
  10. Wow, Nate, I'm on fire! I just set up meetings with movie producers — my dreams are comin' true!

### [temporal] dataset_3.json — `When did Joanna hike with her buddies?`

- expected conv indices: 13
- top-10 ranked conv indices: 24, 23, 27, 19, 10, 22, 25, 20, 28, 12
- top-K snippets:
  1. Hey Joanna, what's been up since we last chatted? How's it going?
  2. Hey Joanna, what's been up? Haven't seen you since we last talked.
  3. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  4. Hey Joanna, what's been up since we last chatted? How's it going?
  5. Hey Joanna! Long time no talk. So much has happened. Look how cute they are! Hanging with them has been a big help, especially recently. Spe
  6. Hey Nate! Great to hear from you! Quite a week since we last talked - something awesome happened to me!
  7. Hey Joanna, it's been a couple days since we last talked. Something exciting happened last Friday. I went to a game convention and met new p
  8. Wow, Nate, I'm on fire! I just set up meetings with movie producers — my dreams are comin' true!
  9. Hey Joanna! Long time no talk. So much has happened. Look how cute they are! Hanging with them has been a big help, especially recently. Spe
  10. Hey Nate, long time no see! My laptop crashed last week and I lost all my work - super frustrating! As a writer, my laptop is like half of m

### [temporal] dataset_3.json — `When did Nate win his third tourney?`

- expected conv indices: 13
- top-10 ranked conv indices: 21, 22, 26, 27, 19, 20, 10, 12, 23, 28
- top-K snippets:
  1. Hey Nate, hi! Yesterday, I tried my newest dairy-free recipe and it was a winner with my family! Mixing and matching flavors is fun and I'm 
  2. Hey Nate, hi! Yesterday, I tried my newest dairy-free recipe and it was a winner with my family! Mixing and matching flavors is fun and I'm 
  3. Hey Joanna, it's been a couple days since we last talked. Something exciting happened last Friday. I went to a game convention and met new p
  4. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  5. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  6. Hey Joanna! Long time no talk. So much has happened. Look how cute they are! Hanging with them has been a big help, especially recently. Spe
  7. Hey Nate, long time no see! My laptop crashed last week and I lost all my work - super frustrating! As a writer, my laptop is like half of m
  8. Hey Nate, hi! Yesterday, I tried my newest dairy-free recipe and it was a winner with my family! Mixing and matching flavors is fun and I'm 
  9. Hey Nate! Great to hear from you! Quite a week since we last talked - something awesome happened to me!
  10. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 

### [temporal] dataset_3.json — `When did Nate make vegan icecream and share it with a vegan diet group?`

- expected conv indices: 15
- top-10 ranked conv indices: 20, 21, 19, 28, 23, 27, 25, 26, 22, 12
- top-K snippets:
  1. Hey Nate, long time no see! My laptop crashed last week and I lost all my work - super frustrating! As a writer, my laptop is like half of m
  2. Hey Nate, hi! Yesterday, I tried my newest dairy-free recipe and it was a winner with my family! Mixing and matching flavors is fun and I'm 
  3. Hey Nate, long time no see! My laptop crashed last week and I lost all my work - super frustrating! As a writer, my laptop is like half of m
  4. Hey Joanna! Long time no talk. So much has happened. Look how cute they are! Hanging with them has been a big help, especially recently. Spe
  5. Hey Nate, long time no see! My laptop crashed last week and I lost all my work - super frustrating! As a writer, my laptop is like half of m
  6. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  7. Hey Nate, long time no see! My laptop crashed last week and I lost all my work - super frustrating! As a writer, my laptop is like half of m
  8. Hey Nate, long time no see! My laptop crashed last week and I lost all my work - super frustrating! As a writer, my laptop is like half of m
  9. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  10. Hey Nate, long time no see! My laptop crashed last week and I lost all my work - super frustrating! As a writer, my laptop is like half of m

### [temporal] dataset_3.json — `When is Joanna going to make Nate's ice cream for her family?`

- expected conv indices: 15
- top-10 ranked conv indices: 19, 20, 28, 27, 21, 25, 23, 24, 10, 12
- top-K snippets:
  1. Hey Joanna! Long time no talk. So much has happened. Look how cute they are! Hanging with them has been a big help, especially recently. Spe
  2. Hey Nate, long time no see! My laptop crashed last week and I lost all my work - super frustrating! As a writer, my laptop is like half of m
  3. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  4. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  5. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  6. Hey Nate, hi! Yesterday, I tried my newest dairy-free recipe and it was a winner with my family! Mixing and matching flavors is fun and I'm 
  7. Hey Nate, long time no see! My laptop crashed last week and I lost all my work - super frustrating! As a writer, my laptop is like half of m
  8. Wow, Nate, I'm on fire! I just set up meetings with movie producers — my dreams are comin' true!
  9. Hey Nate, long time no see! My laptop crashed last week and I lost all my work - super frustrating! As a writer, my laptop is like half of m
  10. Hey Nate, long time no see! My laptop crashed last week and I lost all my work - super frustrating! As a writer, my laptop is like half of m

### [temporal] dataset_3.json — `When did Nate win his fourth video game tournament?`

- expected conv indices: 16
- top-10 ranked conv indices: 21, 27, 26, 22, 20, 19, 23, 12, 10, 25
- top-K snippets:
  1. Hey Nate, hi! Yesterday, I tried my newest dairy-free recipe and it was a winner with my family! Mixing and matching flavors is fun and I'm 
  2. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  3. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  4. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  5. Hey Nate, hi! Yesterday, I tried my newest dairy-free recipe and it was a winner with my family! Mixing and matching flavors is fun and I'm 
  6. Hey Joanna, it's been a couple days since we last talked. Something exciting happened last Friday. I went to a game convention and met new p
  7. Hey Nate, long time no see! My laptop crashed last week and I lost all my work - super frustrating! As a writer, my laptop is like half of m
  8. Hey Joanna! Long time no talk. So much has happened. Look how cute they are! Hanging with them has been a big help, especially recently. Spe
  9. Hey Nate, hi! Yesterday, I tried my newest dairy-free recipe and it was a winner with my family! Mixing and matching flavors is fun and I'm 
  10. Hey Joanna, what's been up? Haven't seen you since we last talked.

### [temporal] dataset_3.json — `Where did Joanna travel to in July 2022?`

- expected conv indices: 16
- top-10 ranked conv indices: 19, 27, 24, 25, 23, 10, 20, 22, 28, 26
- top-K snippets:
  1. Hey Joanna! Long time no talk. So much has happened. Look how cute they are! Hanging with them has been a big help, especially recently. Spe
  2. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  3. Hey Joanna, what's been up since we last chatted? How's it going?
  4. Hey Joanna, what's been up since we last chatted? How's it going?
  5. Wow, Nate, I'm on fire! I just set up meetings with movie producers — my dreams are comin' true!
  6. Hey Joanna, what's been up? Haven't seen you since we last talked.
  7. Hey Nate! Great to hear from you! Quite a week since we last talked - something awesome happened to me!
  8. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  9. Hey Nate, long time no see! My laptop crashed last week and I lost all my work - super frustrating! As a writer, my laptop is like half of m
  10. Hey Joanna, it's been a couple days since we last talked. Something exciting happened last Friday. I went to a game convention and met new p

### [temporal] dataset_3.json — `When did someone write Joanna a touching letter?`

- expected conv indices: 17
- top-10 ranked conv indices: 24, 27, 26, 23, 19, 20, 22, 28, 21, 25
- top-K snippets:
  1. Hey Joanna, what's been up since we last chatted? How's it going?
  2. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  3. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  4. Hey Joanna, what's been up? Haven't seen you since we last talked.
  5. Hey Joanna! Long time no talk. So much has happened. Look how cute they are! Hanging with them has been a big help, especially recently. Spe
  6. Hey Joanna, what's been up since we last chatted? How's it going?
  7. Hey Joanna! Long time no talk. So much has happened. Look how cute they are! Hanging with them has been a big help, especially recently. Spe
  8. Hey Nate, long time no see! My laptop crashed last week and I lost all my work - super frustrating! As a writer, my laptop is like half of m
  9. Hey Joanna, what's been up since we last chatted? How's it going?
  10. Hey Joanna, what's been up? Haven't seen you since we last talked.

### [temporal] dataset_3.json — `When did Joanna share her book with her writers group?`

- expected conv indices: 18
- top-10 ranked conv indices: 25, 10, 20, 26, 24, 23, 28, 12, 22, 27
- top-K snippets:
  1. Wow, Nate, I'm on fire! I just set up meetings with movie producers — my dreams are comin' true!
  2. Hey Nate! Great to hear from you! Quite a week since we last talked - something awesome happened to me!
  3. Hey Nate, long time no see! My laptop crashed last week and I lost all my work - super frustrating! As a writer, my laptop is like half of m
  4. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  5. Hey Joanna, what's been up since we last chatted? How's it going?
  6. Hey Nate, long time no see! My laptop crashed last week and I lost all my work - super frustrating! As a writer, my laptop is like half of m
  7. Hey Joanna, what's been up? Haven't seen you since we last talked.
  8. Hey Joanna, what's been up since we last chatted? How's it going?
  9. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  10. Hey Jo! Been ages since we last talked. Here's something cool that happened the other day - I took Max for a walk and ran into this super ni

### [temporal] dataset_3.json — `When did Nate win an international tournament?`

- expected conv indices: 18
- top-10 ranked conv indices: 21, 26, 27, 22, 19, 20, 23, 25, 12, 10
- top-K snippets:
  1. Hey Nate, hi! Yesterday, I tried my newest dairy-free recipe and it was a winner with my family! Mixing and matching flavors is fun and I'm 
  2. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  3. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  4. Hey Nate, hi! Yesterday, I tried my newest dairy-free recipe and it was a winner with my family! Mixing and matching flavors is fun and I'm 
  5. Hey Joanna, it's been a couple days since we last talked. Something exciting happened last Friday. I went to a game convention and met new p
  6. Hey Joanna! Long time no talk. So much has happened. Look how cute they are! Hanging with them has been a big help, especially recently. Spe
  7. Hey Nate, hi! Yesterday, I tried my newest dairy-free recipe and it was a winner with my family! Mixing and matching flavors is fun and I'm 
  8. Hey Nate, long time no see! My laptop crashed last week and I lost all my work - super frustrating! As a writer, my laptop is like half of m
  9. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  10. Hey Joanna, what's been up? Haven't seen you since we last talked.

### [temporal] dataset_3.json — `When did Nate win his second tournament?`

- expected conv indices: 9
- top-10 ranked conv indices: 21, 26, 27, 22, 19, 20, 23, 10, 12, 25
- top-K snippets:
  1. Hey Nate, hi! Yesterday, I tried my newest dairy-free recipe and it was a winner with my family! Mixing and matching flavors is fun and I'm 
  2. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  3. Hey Nate, hi! Yesterday, I tried my newest dairy-free recipe and it was a winner with my family! Mixing and matching flavors is fun and I'm 
  4. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  5. Hey Joanna, it's been a couple days since we last talked. Something exciting happened last Friday. I went to a game convention and met new p
  6. Hey Joanna! Long time no talk. So much has happened. Look how cute they are! Hanging with them has been a big help, especially recently. Spe
  7. Hey Nate, long time no see! My laptop crashed last week and I lost all my work - super frustrating! As a writer, my laptop is like half of m
  8. Hey Nate, hi! Yesterday, I tried my newest dairy-free recipe and it was a winner with my family! Mixing and matching flavors is fun and I'm 
  9. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  10. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 

### [temporal] dataset_4.json — `In which month's game did John achieve a career-high score in points?`

- expected conv indices: 2
- top-10 ranked conv indices: 23, 22, 25, 24, 19, 28, 27, 14, 8, 16
- top-K snippets:
  1. Hey John, catch up time! What've you been up to? Any good b-ball games lately?
  2. Hey John, catch up time! What've you been up to? Any good b-ball games lately?
  3. Hey Tim, great to see you! Any new success stories?
  4. Hey Tim! Great to hear from you. My week's been busy - I started doing seminars, helping people with their sports and marketing. It's been a
  5. Hey John, been a while since we chatted. How's it going?
  6. Hey Tim! Great to hear from you. My week's been busy - I started doing seminars, helping people with their sports and marketing. It's been a
  7. Hey John! It's been ages since we last chatted. I had a tough exam last week that had me doubting myself. But instead of giving up, I turned
  8. Hey Tim, great to see you! Any new success stories?
  9. Hey John, catch up time! What've you been up to? Any good b-ball games lately?
  10. Hey John! How's it going? Hope all is good.

### [temporal] dataset_4.json — `When was John in Seattle for a game?`

- expected conv indices: 2, 4
- top-10 ranked conv indices: 23, 24, 22, 28, 25, 26, 21, 7, 27, 20
- top-K snippets:
  1. Hey John, catch up time! What've you been up to? Any good b-ball games lately?
  2. Hey John, catch up time! What've you been up to? Any good b-ball games lately?
  3. Hey John, been a while since we chatted. How's it going?
  4. Hey Tim, great to see you! Any new success stories?
  5. Hey John! How's it going? Hope all is good.
  6. Hey Tim! Great to hear from you. My week's been busy - I started doing seminars, helping people with their sports and marketing. It's been a
  7. Hi John, how's it going? Interesting things have happened since we last talked - I joined a group of globetrotters who are into the same stu
  8. Hey John! Long time no see! I just got back from the coolest Harry Potter party. Met lots of awesome people who were into the same stuff as 
  9. Hi John, how's it going? Interesting things have happened since we last talked - I joined a group of globetrotters who are into the same stu
  10. Hey John, catch up time! What've you been up to? Any good b-ball games lately?

### [temporal] dataset_4.json — `What year did John start surfing?`

- expected conv indices: 2
- top-10 ranked conv indices: 25, 24, 23, 28, 26, 27, 0, 22, 19, 20
- top-K snippets:
  1. Hey Tim! Great to hear from you. My week's been busy - I started doing seminars, helping people with their sports and marketing. It's been a
  2. Hey Tim! Great to hear from you. My week's been busy - I started doing seminars, helping people with their sports and marketing. It's been a
  3. Hey John, been a while since we chatted. How's it going?
  4. Hey John, catch up time! What've you been up to? Any good b-ball games lately?
  5. Hey John! How's it going? Hope all is good.
  6. Hi John, how's it going? Interesting things have happened since we last talked - I joined a group of globetrotters who are into the same stu
  7. Hey John, long time no talk. On Friday, I got great news - I'm finally in the study abroad program I applied for! Next month, I'm off to Ire
  8. Hey Tim, nice to meet you! What's up? Anything new happening?
  9. Hey Tim, great to see you! Any new success stories?
  10. Hey John! It's been ages since we last chatted. I had a tough exam last week that had me doubting myself. But instead of giving up, I turned

### [temporal] dataset_4.json — `After how many weeks did Tim reconnect with the fellow Harry Potter fan from California?`

- expected conv indices: 2, 4
- top-10 ranked conv indices: 21, 26, 28, 22, 25, 27, 24, 23, 12, 20
- top-K snippets:
  1. Hey John! Long time no see! I just got back from the coolest Harry Potter party. Met lots of awesome people who were into the same stuff as 
  2. Hey John! Long time no see! I just got back from the coolest Harry Potter party. Met lots of awesome people who were into the same stuff as 
  3. Hi John, how's it going? Interesting things have happened since we last talked - I joined a group of globetrotters who are into the same stu
  4. Hey John! How's it going? Hope all is good.
  5. Hey Tim, great to see you! Any new success stories?
  6. Hey Tim! Great to hear from you. My week's been busy - I started doing seminars, helping people with their sports and marketing. It's been a
  7. Hey John! Long time no see! I just got back from the coolest Harry Potter party. Met lots of awesome people who were into the same stuff as 
  8. Hey John, long time no talk. On Friday, I got great news - I'm finally in the study abroad program I applied for! Next month, I'm off to Ire
  9. Hey John, long time no talk. On Friday, I got great news - I'm finally in the study abroad program I applied for! Next month, I'm off to Ire
  10. Hey Tim! Great to hear from you. My week's been busy - I started doing seminars, helping people with their sports and marketing. It's been a

### [temporal] dataset_4.json — `Which city was John in before traveling to Chicago?`

- expected conv indices: 2, 4, 5
- top-10 ranked conv indices: 26, 24, 23, 27, 28, 25, 20, 21, 1, 11
- top-K snippets:
  1. Hi John, how's it going? Interesting things have happened since we last talked - I joined a group of globetrotters who are into the same stu
  2. Hi John, how's it going? Interesting things have happened since we last talked - I joined a group of globetrotters who are into the same stu
  3. Hey John, been a while since we chatted. How's it going?
  4. Hey John, catch up time! What've you been up to? Any good b-ball games lately?
  5. Hey John, long time no talk. On Friday, I got great news - I'm finally in the study abroad program I applied for! Next month, I'm off to Ire
  6. Hey John! How's it going? Hope all is good.
  7. Hey Tim! Great to hear from you. My week's been busy - I started doing seminars, helping people with their sports and marketing. It's been a
  8. Hey John! Haven't talked in a few days, wanted to let you know I joined a travel club! Always been interested in different cultures and coun
  9. Hey Tim! Great to hear from you. My week's been busy - I started doing seminars, helping people with their sports and marketing. It's been a
  10. Hey John! Long time no see! I just got back from the coolest Harry Potter party. Met lots of awesome people who were into the same stuff as 

### [temporal] dataset_4.json — `When did John meet with his teammates after returning from Chicago?`

- expected conv indices: 6
- top-10 ranked conv indices: 23, 24, 26, 25, 28, 22, 21, 19, 20, 12
- top-K snippets:
  1. Hey John, catch up time! What've you been up to? Any good b-ball games lately?
  2. Hey John, been a while since we chatted. How's it going?
  3. Hi John, how's it going? Interesting things have happened since we last talked - I joined a group of globetrotters who are into the same stu
  4. Hey Tim! Great to hear from you. My week's been busy - I started doing seminars, helping people with their sports and marketing. It's been a
  5. Hey John, catch up time! What've you been up to? Any good b-ball games lately?
  6. Hey John! How's it going? Hope all is good.
  7. Hey Tim, great to see you! Any new success stories?
  8. Hey John, catch up time! What've you been up to? Any good b-ball games lately?
  9. Hey John, been a while since we chatted. How's it going?
  10. Hey John! Long time no see! I just got back from the coolest Harry Potter party. Met lots of awesome people who were into the same stuff as 

### [temporal] dataset_4.json — `When is Tim attending a book conference?`

- expected conv indices: 6
- top-10 ranked conv indices: 25, 27, 21, 28, 22, 26, 18, 24, 20, 23
- top-K snippets:
  1. Hey Tim! Great to hear from you. My week's been busy - I started doing seminars, helping people with their sports and marketing. It's been a
  2. Hey John, long time no talk. On Friday, I got great news - I'm finally in the study abroad program I applied for! Next month, I'm off to Ire
  3. Hey John, long time no talk. On Friday, I got great news - I'm finally in the study abroad program I applied for! Next month, I'm off to Ire
  4. Hey John! Long time no see! I just got back from the coolest Harry Potter party. Met lots of awesome people who were into the same stuff as 
  5. Hey John! How's it going? Hope all is good.
  6. Hey Tim, great to see you! Any new success stories?
  7. Hi John, how's it going? Interesting things have happened since we last talked - I joined a group of globetrotters who are into the same stu
  8. Hey John! Haven't talked in a bit, how ya been? Hope your injury is feeling better.
  9. Hey John, long time no talk. On Friday, I got great news - I'm finally in the study abroad program I applied for! Next month, I'm off to Ire
  10. Hey John, been a while since we chatted. How's it going?

### [temporal] dataset_4.json — `Where was John between August 11 and August 15 2023?`

- expected conv indices: 5, 6
- top-10 ranked conv indices: 24, 27, 23, 28, 26, 25, 20, 21, 12, 7
- top-K snippets:
  1. Hey John, been a while since we chatted. How's it going?
  2. Hey John, long time no talk. On Friday, I got great news - I'm finally in the study abroad program I applied for! Next month, I'm off to Ire
  3. Hey John, catch up time! What've you been up to? Any good b-ball games lately?
  4. Hey John! How's it going? Hope all is good.
  5. Hi John, how's it going? Interesting things have happened since we last talked - I joined a group of globetrotters who are into the same stu
  6. Hi John, how's it going? Interesting things have happened since we last talked - I joined a group of globetrotters who are into the same stu
  7. Hey Tim! Great to hear from you. My week's been busy - I started doing seminars, helping people with their sports and marketing. It's been a
  8. Hey Tim! Great to hear from you. My week's been busy - I started doing seminars, helping people with their sports and marketing. It's been a
  9. Hey John! Haven't talked in a few days, wanted to let you know I joined a travel club! Always been interested in different cultures and coun
  10. Hey John! Long time no see! I just got back from the coolest Harry Potter party. Met lots of awesome people who were into the same stuff as 

### [temporal] dataset_4.json — `What month did Tim plan on going to Universal Studios?`

- expected conv indices: 9
- top-10 ranked conv indices: 27, 26, 21, 28, 24, 25, 11, 14, 22, 7
- top-K snippets:
  1. Hey John, long time no talk. On Friday, I got great news - I'm finally in the study abroad program I applied for! Next month, I'm off to Ire
  2. Hi John, how's it going? Interesting things have happened since we last talked - I joined a group of globetrotters who are into the same stu
  3. Hey John! Long time no see! I just got back from the coolest Harry Potter party. Met lots of awesome people who were into the same stuff as 
  4. Hey John! How's it going? Hope all is good.
  5. Hey John, been a while since we chatted. How's it going?
  6. Hey Tim! Great to hear from you. My week's been busy - I started doing seminars, helping people with their sports and marketing. It's been a
  7. Hey John! Awesome catchin' up with you! A lot's changed since last time.
  8. Hey John! Haven't talked to you in a bit but wanted to let you know I read this awesome book about castles in the UK. It was so interesting 
  9. Hey Tim, great to see you! Any new success stories?
  10. Hey Tim! Great to hear from you. My week's been busy - I started doing seminars, helping people with their sports and marketing. It's been a

### [temporal] dataset_4.json — `When does John plan on traveling with his team on a team trip?`

- expected conv indices: 10
- top-10 ranked conv indices: 26, 20, 23, 27, 25, 28, 24, 11, 3, 21
- top-K snippets:
  1. Hi John, how's it going? Interesting things have happened since we last talked - I joined a group of globetrotters who are into the same stu
  2. Hi John, how's it going? Interesting things have happened since we last talked - I joined a group of globetrotters who are into the same stu
  3. Hey John! Haven't talked in a few days, wanted to let you know I joined a travel club! Always been interested in different cultures and coun
  4. Hey John, catch up time! What've you been up to? Any good b-ball games lately?
  5. Hey John, long time no talk. On Friday, I got great news - I'm finally in the study abroad program I applied for! Next month, I'm off to Ire
  6. Hey Tim! Great to hear from you. My week's been busy - I started doing seminars, helping people with their sports and marketing. It's been a
  7. Hey John! How's it going? Hope all is good.
  8. Hey John, been a while since we chatted. How's it going?
  9. Hey John! Awesome catchin' up with you! A lot's changed since last time.
  10. Hey Tim! Great to hear from you. My week's been busy - I started doing seminars, helping people with their sports and marketing. It's been a

### [temporal] dataset_4.json — `Which week did Tim visit the UK for the Harry Potter Conference?`

- expected conv indices: 12
- top-10 ranked conv indices: 21, 25, 26, 27, 22, 28, 24, 23, 20, 18
- top-K snippets:
  1. Hey John! Long time no see! I just got back from the coolest Harry Potter party. Met lots of awesome people who were into the same stuff as 
  2. Hey Tim! Great to hear from you. My week's been busy - I started doing seminars, helping people with their sports and marketing. It's been a
  3. Hi John, how's it going? Interesting things have happened since we last talked - I joined a group of globetrotters who are into the same stu
  4. Hey John, long time no talk. On Friday, I got great news - I'm finally in the study abroad program I applied for! Next month, I'm off to Ire
  5. Hey John! Long time no see! I just got back from the coolest Harry Potter party. Met lots of awesome people who were into the same stuff as 
  6. Hey Tim! Great to hear from you. My week's been busy - I started doing seminars, helping people with their sports and marketing. It's been a
  7. Hey Tim! Great to hear from you. My week's been busy - I started doing seminars, helping people with their sports and marketing. It's been a
  8. Hey Tim, great to see you! Any new success stories?
  9. Hey John! How's it going? Hope all is good.
  10. Hey Tim! Great to hear from you. My week's been busy - I started doing seminars, helping people with their sports and marketing. It's been a

### [temporal] dataset_4.json — `What year did Tim go to the Smoky Mountains?`

- expected conv indices: 13
- top-10 ranked conv indices: 27, 28, 24, 26, 22, 21, 25, 19, 23, 20
- top-K snippets:
  1. Hey John, long time no talk. On Friday, I got great news - I'm finally in the study abroad program I applied for! Next month, I'm off to Ire
  2. Hey John! How's it going? Hope all is good.
  3. Hey John, been a while since we chatted. How's it going?
  4. Hi John, how's it going? Interesting things have happened since we last talked - I joined a group of globetrotters who are into the same stu
  5. Hey Tim, great to see you! Any new success stories?
  6. Hey John! Long time no see! I just got back from the coolest Harry Potter party. Met lots of awesome people who were into the same stuff as 
  7. Hey Tim! Great to hear from you. My week's been busy - I started doing seminars, helping people with their sports and marketing. It's been a
  8. Hey John! It's been ages since we last chatted. I had a tough exam last week that had me doubting myself. But instead of giving up, I turned
  9. Hey John, long time no talk. On Friday, I got great news - I'm finally in the study abroad program I applied for! Next month, I'm off to Ire
  10. Hey John, catch up time! What've you been up to? Any good b-ball games lately?

### [temporal] dataset_4.json — `Has Tim been to North Carolina and/or Tennesee states in the US?`

- expected conv indices: 13
- top-10 ranked conv indices: 26, 28, 22, 25, 27, 24, 20, 21, 18, 23
- top-K snippets:
  1. Hi John, how's it going? Interesting things have happened since we last talked - I joined a group of globetrotters who are into the same stu
  2. Hey John! How's it going? Hope all is good.
  3. Hey Tim, great to see you! Any new success stories?
  4. Hey Tim! Great to hear from you. My week's been busy - I started doing seminars, helping people with their sports and marketing. It's been a
  5. Hey John, long time no talk. On Friday, I got great news - I'm finally in the study abroad program I applied for! Next month, I'm off to Ire
  6. Hey John, been a while since we chatted. How's it going?
  7. Hey John! Haven't talked in a few days, wanted to let you know I joined a travel club! Always been interested in different cultures and coun
  8. Hey John! Long time no see! I just got back from the coolest Harry Potter party. Met lots of awesome people who were into the same stuff as 
  9. Hey John! Haven't talked in a bit, how ya been? Hope your injury is feeling better.
  10. Hey John, long time no talk. On Friday, I got great news - I'm finally in the study abroad program I applied for! Next month, I'm off to Ire

### [temporal] dataset_4.json — `When did John and his wife go on a European vacation?`

- expected conv indices: 15
- top-10 ranked conv indices: 26, 27, 20, 24, 25, 28, 23, 21, 1, 14
- top-K snippets:
  1. Hi John, how's it going? Interesting things have happened since we last talked - I joined a group of globetrotters who are into the same stu
  2. Hi John, how's it going? Interesting things have happened since we last talked - I joined a group of globetrotters who are into the same stu
  3. Hey John, long time no talk. On Friday, I got great news - I'm finally in the study abroad program I applied for! Next month, I'm off to Ire
  4. Hey John! Haven't talked in a few days, wanted to let you know I joined a travel club! Always been interested in different cultures and coun
  5. Hey John, been a while since we chatted. How's it going?
  6. Hey Tim! Great to hear from you. My week's been busy - I started doing seminars, helping people with their sports and marketing. It's been a
  7. Hey John! How's it going? Hope all is good.
  8. Hey John, catch up time! What've you been up to? Any good b-ball games lately?
  9. Hi John, how's it going? Interesting things have happened since we last talked - I joined a group of globetrotters who are into the same stu
  10. Hey John! Long time no see! I just got back from the coolest Harry Potter party. Met lots of awesome people who were into the same stuff as 

### [temporal] dataset_4.json — `Which country was Tim visiting in the second week of November?`

- expected conv indices: 17
- top-10 ranked conv indices: 26, 27, 22, 28, 25, 24, 20, 21, 23, 18
- top-K snippets:
  1. Hi John, how's it going? Interesting things have happened since we last talked - I joined a group of globetrotters who are into the same stu
  2. Hey John, long time no talk. On Friday, I got great news - I'm finally in the study abroad program I applied for! Next month, I'm off to Ire
  3. Hey Tim, great to see you! Any new success stories?
  4. Hey John! How's it going? Hope all is good.
  5. Hey Tim! Great to hear from you. My week's been busy - I started doing seminars, helping people with their sports and marketing. It's been a
  6. Hey John, been a while since we chatted. How's it going?
  7. Hi John, how's it going? Interesting things have happened since we last talked - I joined a group of globetrotters who are into the same stu
  8. Hey John! Haven't talked in a few days, wanted to let you know I joined a travel club! Always been interested in different cultures and coun
  9. Hey John! How's it going? Hope all is good.
  10. Hey John, long time no talk. On Friday, I got great news - I'm finally in the study abroad program I applied for! Next month, I'm off to Ire

### [temporal] dataset_4.json — `Where was Tim in the week before 16 November 2023?`

- expected conv indices: 17
- top-10 ranked conv indices: 27, 28, 22, 25, 24, 26, 21, 23, 11, 20
- top-K snippets:
  1. Hey John, long time no talk. On Friday, I got great news - I'm finally in the study abroad program I applied for! Next month, I'm off to Ire
  2. Hey John! How's it going? Hope all is good.
  3. Hey Tim, great to see you! Any new success stories?
  4. Hey Tim! Great to hear from you. My week's been busy - I started doing seminars, helping people with their sports and marketing. It's been a
  5. Hey John, been a while since we chatted. How's it going?
  6. Hi John, how's it going? Interesting things have happened since we last talked - I joined a group of globetrotters who are into the same stu
  7. Hey John! Long time no see! I just got back from the coolest Harry Potter party. Met lots of awesome people who were into the same stuff as 
  8. Hey John, catch up time! What've you been up to? Any good b-ball games lately?
  9. Hey John! Awesome catchin' up with you! A lot's changed since last time.
  10. Hey John! Haven't talked in a few days, wanted to let you know I joined a travel club! Always been interested in different cultures and coun

### [temporal] dataset_4.json — `When did John get married at a greenhouse?`

- expected conv indices: 11
- top-10 ranked conv indices: 25, 24, 28, 14, 27, 26, 23, 21, 22, 7
- top-K snippets:
  1. Hey Tim! Great to hear from you. My week's been busy - I started doing seminars, helping people with their sports and marketing. It's been a
  2. Hey John, been a while since we chatted. How's it going?
  3. Hey John! How's it going? Hope all is good.
  4. Hey John! Haven't talked to you in a bit but wanted to let you know I read this awesome book about castles in the UK. It was so interesting 
  5. Hey Tim! Great to hear from you. My week's been busy - I started doing seminars, helping people with their sports and marketing. It's been a
  6. Hey John, long time no talk. On Friday, I got great news - I'm finally in the study abroad program I applied for! Next month, I'm off to Ire
  7. Hi John, how's it going? Interesting things have happened since we last talked - I joined a group of globetrotters who are into the same stu
  8. Hey John, catch up time! What've you been up to? Any good b-ball games lately?
  9. Hi John, how's it going? Interesting things have happened since we last talked - I joined a group of globetrotters who are into the same stu
  10. Hey John! Long time no see! I just got back from the coolest Harry Potter party. Met lots of awesome people who were into the same stuff as 

### [temporal] dataset_4.json — `What day did Tim get into his study abroad program?`

- expected conv indices: 27
- top-10 ranked conv indices: 

### [temporal] dataset_4.json — `When will Tim leave for Ireland?`

- expected conv indices: 27
- top-10 ranked conv indices: 

### [temporal] dataset_5.json — `When did Audrey see a hummingbird?`

- expected conv indices: 3
- top-10 ranked conv indices: 27, 2, 18, 0, 16, 24, 5, 12, 1, 13
- top-K snippets:
  1. Hey Andrew! Long time no talk! Last Friday I took my fur kids to the pet salon - they were so psyched and their tails were wagging like craz
  2. Hey Audrey! What's up? Missed chatting with ya! Check it out, my girl & I tried out that new cafe scene in the city last weekend! Super fun 
  3. Hey Audrey! Long time no talk! How have you been?
  4. Hey Andrew! Good to see ya! What's been up since we last talked?
  5. Hey Audrey! What's up? Last weekend my girlfriend and I went fishing in one of the nearby lakes. It was so nice. We got a few fish and had a
  6. Hi Audrey! How have you been lately? My girlfriend and I went to this awesome wine tasting last weekend. It was great! We tried so many uniq
  7. Hi Audrey! I had a great hike last weekend with some friends and my girlfriend at the spot we found recently. Nature was so peaceful – it wa
  8. Hey Audrey! What's up? Last weekend my girlfriend and I went fishing in one of the nearby lakes. It was so nice. We got a few fish and had a
  9. Hey Andrew! Good to see ya! What's been up since we last talked?
  10. Hey Andrew! Good to see ya! What's been up since we last talked?

### [temporal] dataset_5.json — `When did Andrew go rock climbing?`

- expected conv indices: 7
- top-10 ranked conv indices: 0, 19, 5, 12, 2, 27, 17, 23, 25, 18
- top-K snippets:
  1. Hey Andrew! Good to see ya! What's been up since we last talked?
  2. Hey wassup? Got some great news - the gf and I are hitting the beach next month with Toby!
  3. Hi Audrey! I had a great hike last weekend with some friends and my girlfriend at the spot we found recently. Nature was so peaceful – it wa
  4. Hey wassup? Got some great news - the gf and I are hitting the beach next month with Toby!
  5. Hey Audrey! How are you? My GF and I just had a great experience volunteering at a pet shelter on Monday - it was so rewarding! We loved spe
  6. Hey Audrey! What's up? Missed chatting with ya! Check it out, my girl & I tried out that new cafe scene in the city last weekend! Super fun 
  7. Hey Andrew! Long time no talk! Last Friday I took my fur kids to the pet salon - they were so psyched and their tails were wagging like craz
  8. Hey Andrew! Good to see ya! What's been up since we last talked?
  9. Hey Audrey, how's it going? Since we last talked, a few new things have come up in my life. Work's been tough and stressful, so my outdoor a
  10. Hey Andrew, hope you're doing ok. I recently had a good week - I went to a pet store last Monday to buy toys for my dogs and it was great se

### [temporal] dataset_5.json — `When did Audrey move to a new place?`

- expected conv indices: 8
- top-10 ranked conv indices: 17, 0, 26, 2, 16, 13, 24, 20, 27, 5
- top-K snippets:
  1. Hey Audrey, how's it going? Since we last talked, a few new things have come up in my life. Work's been tough and stressful, so my outdoor a
  2. Hey Andrew! Good to see ya! What's been up since we last talked?
  3. Hey Audrey, had a great weekend! My girlfriend and I went on a bike ride and stumbled upon a cool park outside of town. It was awesome to ge
  4. Hey Audrey! What's up? Missed chatting with ya! Check it out, my girl & I tried out that new cafe scene in the city last weekend! Super fun 
  5. Hey Audrey! What's up? Last weekend my girlfriend and I went fishing in one of the nearby lakes. It was so nice. We got a few fish and had a
  6. Hey, Audrey! I can't wait for the weekend. My girlfriend, Toby and I are going camping. It's been forever since I've been in nature.
  7. Hi Audrey! How have you been lately? My girlfriend and I went to this awesome wine tasting last weekend. It was great! We tried so many uniq
  8. Hi Audrey! Been a while since I hear from you. How's it been?
  9. Hey Andrew! Long time no talk! Last Friday I took my fur kids to the pet salon - they were so psyched and their tails were wagging like craz
  10. Hey Audrey! What's up? Last weekend my girlfriend and I went fishing in one of the nearby lakes. It was so nice. We got a few fish and had a

### [temporal] dataset_6.json — `Which recreational activity was James pursuing on March 16, 2022?`

- expected conv indices: 0
- top-10 ranked conv indices: 29, 28, 27, 7, 13, 30, 16, 24, 10, 22
- top-K snippets:
  1. Hey John, hope you're doing well. Yesterday, we started on a road trip. It was fun spending time with the family and my dogs. Exploring new 
  2. Hey James! Hope you're doing great. I've some amazing news - I held a gaming tourney with my buddies last night. We played Fortnite and a fe
  3. Hey John, long time no talk! So much has happened!
  4. Hey John! What's up? Anything fun going on?
  5. Hey John, long time no talk! So much has happened!
  6. Hey John, how's it going? A lot has happened for me lately, some good and some not so great. I`m lucky to have at least two people who alway
  7. Hey John! Guess what? Me and my family are currently on the road trip! We`ve already visited my friends Josh and Mark and had such a great t
  8. Hey James! Hope you're doing great. I've some amazing news - I held a gaming tourney with my buddies last night. We played Fortnite and a fe
  9. Hey John, how's it going? A lot has happened for me lately, some good and some not so great. I`m lucky to have at least two people who alway
  10. Hi James! I just started playing chess to get better at strategy. I'm loving it! Have you ever tried it out?

### [temporal] dataset_6.json — `When did John resume playing drums in his adulthood?`

- expected conv indices: 2
- top-10 ranked conv indices: 27, 29, 26, 28, 19, 30, 17, 13, 8, 7
- top-K snippets:
  1. Hey John, long time no talk! So much has happened!
  2. Hey John, hope you're doing well. Yesterday, we started on a road trip. It was fun spending time with the family and my dogs. Exploring new 
  3. Hey John, hope you're doing well. Yesterday, we started on a road trip. It was fun spending time with the family and my dogs. Exploring new 
  4. Hey John, long time no talk! So much has happened!
  5. Hey James! How's it going? I had a blast last week when my programmer friends and I organized an online comp. It was awesome to see everyone
  6. Hey James! Hope you're doing great. I've some amazing news - I held a gaming tourney with my buddies last night. We played Fortnite and a fe
  7. Hey, James! Good to hear from you. I have some awesome news - I joined a programming group online last Friday and it's been incredible! It's
  8. Hey John! Guess what? Me and my family are currently on the road trip! We`ve already visited my friends Josh and Mark and had such a great t
  9. Hey John, long time no talk! So much has happened!
  10. Hey James, good catching up! Been a while huh? I made a huge call - recently left my IT job after 3 years. It was tough but I wanted somethi

### [temporal] dataset_6.json — `When did James adopt Ned?`

- expected conv indices: 4
- top-10 ranked conv indices: 16, 27, 28, 29, 26, 30, 19, 17, 12, 14
- top-K snippets:
  1. Hi James! I just started playing chess to get better at strategy. I'm loving it! Have you ever tried it out?
  2. Hey John, long time no talk! So much has happened!
  3. Hey James! Hope you're doing great. I've some amazing news - I held a gaming tourney with my buddies last night. We played Fortnite and a fe
  4. Hey John, hope you're doing well. Yesterday, we started on a road trip. It was fun spending time with the family and my dogs. Exploring new 
  5. Hey John, long time no talk! So much has happened!
  6. Hey James! How's it going? I had a blast last week when my programmer friends and I organized an online comp. It was awesome to see everyone
  7. Hey John! Guess what? Me and my family are currently on the road trip! We`ve already visited my friends Josh and Mark and had such a great t
  8. Hey John, long time no talk! So much has happened!
  9. Hey James! Hope you're doing great. I've some amazing news - I held a gaming tourney with my buddies last night. We played Fortnite and a fe
  10. Hey, James! Good to hear from you. I have some awesome news - I joined a programming group online last Friday and it's been incredible! It's

### [temporal] dataset_6.json — `When did James visit Italy?`

- expected conv indices: 5
- top-10 ranked conv indices: 16, 29, 19, 30, 28, 17, 7, 13, 14, 6
- top-K snippets:
  1. Hi James! I just started playing chess to get better at strategy. I'm loving it! Have you ever tried it out?
  2. Hi James! I just started playing chess to get better at strategy. I'm loving it! Have you ever tried it out?
  3. Hey John, hope you're doing well. Yesterday, we started on a road trip. It was fun spending time with the family and my dogs. Exploring new 
  4. Hey, James! Good to hear from you. I have some awesome news - I joined a programming group online last Friday and it's been incredible! It's
  5. Hey John! Guess what? Me and my family are currently on the road trip! We`ve already visited my friends Josh and Mark and had such a great t
  6. Hey James! Hope you're doing great. I've some amazing news - I held a gaming tourney with my buddies last night. We played Fortnite and a fe
  7. Hey James, good catching up! Been a while huh? I made a huge call - recently left my IT job after 3 years. It was tough but I wanted somethi
  8. Hey John! Guess what? Me and my family are currently on the road trip! We`ve already visited my friends Josh and Mark and had such a great t
  9. Hey James! Hope you're doing great. I've some amazing news - I held a gaming tourney with my buddies last night. We played Fortnite and a fe
  10. Hey John! What's up? Anything fun going on?

### [temporal] dataset_6.json — `When did John first organize a charity tournament with his friends?`

- expected conv indices: 9
- top-10 ranked conv indices: 13, 25, 1, 15, 7, 16, 0, 19, 29, 8
- top-K snippets:
  1. Hey John, how's it going? A lot has happened for me lately, some good and some not so great. I`m lucky to have at least two people who alway
  2. Hey James! Busy few weeks for sure, but I'm pushing through. Got an email about a volunteer gig at a game dev non-profit. It's something I'v
  3. Hey John, how's it going? A lot has happened for me lately, some good and some not so great. I`m lucky to have at least two people who alway
  4. Hey John, something awesome happened since we talked. I made a game avatar and joined a new platform. It's so fun exploring and chatting wit
  5. Hey John! Long time no talk - hope you're doing well. Guess what? Last week I actually won an online gaming tournament! It was such an excit
  6. Hey John, how's it going? A lot has happened for me lately, some good and some not so great. I`m lucky to have at least two people who alway
  7. Hey John! What's up? Anything fun going on?
  8. Hi James! I just started playing chess to get better at strategy. I'm loving it! Have you ever tried it out?
  9. Hey! Glad to finally talk to you. I want to ask you, what motivates you?
  10. Hey, James! Good to hear from you. I have some awesome news - I joined a programming group online last Friday and it's been incredible! It's

### [temporal] dataset_6.json — `When will John start his new job?`

- expected conv indices: 12
- top-10 ranked conv indices: 29, 17, 30, 19, 26, 27, 1, 24, 6, 13
- top-K snippets:
  1. Hey John, hope you're doing well. Yesterday, we started on a road trip. It was fun spending time with the family and my dogs. Exploring new 
  2. Hey James, good catching up! Been a while huh? I made a huge call - recently left my IT job after 3 years. It was tough but I wanted somethi
  3. Hey John! Guess what? Me and my family are currently on the road trip! We`ve already visited my friends Josh and Mark and had such a great t
  4. Hey, James! Good to hear from you. I have some awesome news - I joined a programming group online last Friday and it's been incredible! It's
  5. Hey James! How's it going? I had a blast last week when my programmer friends and I organized an online comp. It was awesome to see everyone
  6. Hey, James! Good to hear from you. I have some awesome news - I joined a programming group online last Friday and it's been incredible! It's
  7. Hey John, long time no talk! So much has happened!
  8. Hey John, long time no talk! So much has happened!
  9. Hey John, something awesome happened since we talked. I made a game avatar and joined a new platform. It's so fun exploring and chatting wit
  10. Hey James, been a few days since we chatted. Lots of stuff goin' on in my life!

### [temporal] dataset_6.json — `When did James depart for his trip to Canada?`

- expected conv indices: 15
- top-10 ranked conv indices: 16, 19, 29, 17, 13, 30, 6, 14, 7, 5
- top-K snippets:
  1. Hi James! I just started playing chess to get better at strategy. I'm loving it! Have you ever tried it out?
  2. Hey, James! Good to hear from you. I have some awesome news - I joined a programming group online last Friday and it's been incredible! It's
  3. Hey John, hope you're doing well. Yesterday, we started on a road trip. It was fun spending time with the family and my dogs. Exploring new 
  4. Hi James! I just started playing chess to get better at strategy. I'm loving it! Have you ever tried it out?
  5. Hey James, good catching up! Been a while huh? I made a huge call - recently left my IT job after 3 years. It was tough but I wanted somethi
  6. Hey John, hope you're doing well. Yesterday, we started on a road trip. It was fun spending time with the family and my dogs. Exploring new 
  7. Hey John, how's it going? A lot has happened for me lately, some good and some not so great. I`m lucky to have at least two people who alway
  8. Hey John! Guess what? Me and my family are currently on the road trip! We`ve already visited my friends Josh and Mark and had such a great t
  9. Hey John! Guess what? Me and my family are currently on the road trip! We`ve already visited my friends Josh and Mark and had such a great t
  10. Hey James! How's it going?

### [temporal] dataset_6.json — `How many days did James plan to spend on his trip in Canada?`

- expected conv indices: 15
- top-10 ranked conv indices: 16, 13, 19, 29, 7, 30, 17, 6, 14, 10
- top-K snippets:
  1. Hi James! I just started playing chess to get better at strategy. I'm loving it! Have you ever tried it out?
  2. Hey John, how's it going? A lot has happened for me lately, some good and some not so great. I`m lucky to have at least two people who alway
  3. Hey, James! Good to hear from you. I have some awesome news - I joined a programming group online last Friday and it's been incredible! It's
  4. Hey John, hope you're doing well. Yesterday, we started on a road trip. It was fun spending time with the family and my dogs. Exploring new 
  5. Hey John, hope you're doing well. Yesterday, we started on a road trip. It was fun spending time with the family and my dogs. Exploring new 
  6. Hey John! What's up? Anything fun going on?
  7. Hey John! Guess what? Me and my family are currently on the road trip! We`ve already visited my friends Josh and Mark and had such a great t
  8. Hey John! Guess what? Me and my family are currently on the road trip! We`ve already visited my friends Josh and Mark and had such a great t
  9. Hi James! I just started playing chess to get better at strategy. I'm loving it! Have you ever tried it out?
  10. Hi James! I just started playing chess to get better at strategy. I'm loving it! Have you ever tried it out?

### [temporal] dataset_6.json — `Where was James at on July 12, 2022?`

- expected conv indices: 15
- top-10 ranked conv indices: 29, 16, 19, 14, 30, 13, 17, 6, 10, 7
- top-K snippets:
  1. Hey John, hope you're doing well. Yesterday, we started on a road trip. It was fun spending time with the family and my dogs. Exploring new 
  2. Hi James! I just started playing chess to get better at strategy. I'm loving it! Have you ever tried it out?
  3. Hey, James! Good to hear from you. I have some awesome news - I joined a programming group online last Friday and it's been incredible! It's
  4. Hi James! I just started playing chess to get better at strategy. I'm loving it! Have you ever tried it out?
  5. Hey John, since our last chat, something awesome happened. Last Friday, I started introducing Max, Daisy and the new pup Ned. It was hard at
  6. Hey John! Guess what? Me and my family are currently on the road trip! We`ve already visited my friends Josh and Mark and had such a great t
  7. Hey John, how's it going? A lot has happened for me lately, some good and some not so great. I`m lucky to have at least two people who alway
  8. Hey James, good catching up! Been a while huh? I made a huge call - recently left my IT job after 3 years. It was tough but I wanted somethi
  9. Hey John! Guess what? Me and my family are currently on the road trip! We`ve already visited my friends Josh and Mark and had such a great t
  10. Hi James! I just started playing chess to get better at strategy. I'm loving it! Have you ever tried it out?

### [temporal] dataset_6.json — `When did James meet Samantha?`

- expected conv indices: 18
- top-10 ranked conv indices: 16, 12, 19, 29, 30, 24, 17, 22, 7, 23
- top-K snippets:
  1. Hi James! I just started playing chess to get better at strategy. I'm loving it! Have you ever tried it out?
  2. Hey James, long time no talk! A lot has happened during this time. Let me fill you in.
  3. Hey, James! Good to hear from you. I have some awesome news - I joined a programming group online last Friday and it's been incredible! It's
  4. Hey John, hope you're doing well. Yesterday, we started on a road trip. It was fun spending time with the family and my dogs. Exploring new 
  5. Hey John! Guess what? Me and my family are currently on the road trip! We`ve already visited my friends Josh and Mark and had such a great t
  6. Hi James! I just started playing chess to get better at strategy. I'm loving it! Have you ever tried it out?
  7. Hi James! I just started playing chess to get better at strategy. I'm loving it! Have you ever tried it out?
  8. Hey John! Guess what? Me and my family are currently on the road trip! We`ve already visited my friends Josh and Mark and had such a great t
  9. Hey John! Guess what? Me and my family are currently on the road trip! We`ve already visited my friends Josh and Mark and had such a great t
  10. Hey James, been a few days since we chatted. Lots of stuff goin' on in my life!

### [temporal] dataset_6.json — `When did John and James meet at McGee's bar?`

- expected conv indices: 20
- top-10 ranked conv indices: 16, 19, 30, 29, 13, 17, 23, 7, 12, 24
- top-K snippets:
  1. Hi James! I just started playing chess to get better at strategy. I'm loving it! Have you ever tried it out?
  2. Hey, James! Good to hear from you. I have some awesome news - I joined a programming group online last Friday and it's been incredible! It's
  3. Hey John! Guess what? Me and my family are currently on the road trip! We`ve already visited my friends Josh and Mark and had such a great t
  4. Hey John, hope you're doing well. Yesterday, we started on a road trip. It was fun spending time with the family and my dogs. Exploring new 
  5. Hey, James! Good to hear from you. I have some awesome news - I joined a programming group online last Friday and it's been incredible! It's
  6. Hey John, how's it going? A lot has happened for me lately, some good and some not so great. I`m lucky to have at least two people who alway
  7. Hey John, hope you're doing well. Yesterday, we started on a road trip. It was fun spending time with the family and my dogs. Exploring new 
  8. Hi James! I just started playing chess to get better at strategy. I'm loving it! Have you ever tried it out?
  9. Hey John! Guess what? Me and my family are currently on the road trip! We`ve already visited my friends Josh and Mark and had such a great t
  10. Hey James, good catching up! Been a while huh? I made a huge call - recently left my IT job after 3 years. It was tough but I wanted somethi

### [temporal] dataset_6.json — `When did James ask Samantha to be his girlfriend?`

- expected conv indices: 22
- top-10 ranked conv indices: 16, 29, 19, 12, 30, 6, 14, 7, 17, 24
- top-K snippets:
  1. Hi James! I just started playing chess to get better at strategy. I'm loving it! Have you ever tried it out?
  2. Hey John, hope you're doing well. Yesterday, we started on a road trip. It was fun spending time with the family and my dogs. Exploring new 
  3. Hey, James! Good to hear from you. I have some awesome news - I joined a programming group online last Friday and it's been incredible! It's
  4. Hey James, long time no talk! A lot has happened during this time. Let me fill you in.
  5. Hey John! Guess what? Me and my family are currently on the road trip! We`ve already visited my friends Josh and Mark and had such a great t
  6. Hey John! Guess what? Me and my family are currently on the road trip! We`ve already visited my friends Josh and Mark and had such a great t
  7. Hi James! I just started playing chess to get better at strategy. I'm loving it! Have you ever tried it out?
  8. Hey John! Guess what? Me and my family are currently on the road trip! We`ve already visited my friends Josh and Mark and had such a great t
  9. Hi James! I just started playing chess to get better at strategy. I'm loving it! Have you ever tried it out?
  10. Hey James! How's it going?

### [temporal] dataset_6.json — `When did James start taking cooking classes?`

- expected conv indices: 22
- top-10 ranked conv indices: 19, 20, 29, 2, 16, 13, 30, 14, 3, 8
- top-K snippets:
  1. Hey, James! Good to hear from you. I have some awesome news - I joined a programming group online last Friday and it's been incredible! It's
  2. Hey John! Look how cute it is. My dog came to me today while I was playing on the console. What is new?
  3. Hey John, hope you're doing well. Yesterday, we started on a road trip. It was fun spending time with the family and my dogs. Exploring new 
  4. Hey James, long time no see! I had a big win in my game last week - finally advanced to the next level! It was a huge confidence booster and
  5. Hey James, long time no see! I had a big win in my game last week - finally advanced to the next level! It was a huge confidence booster and
  6. Hi James! I just started playing chess to get better at strategy. I'm loving it! Have you ever tried it out?
  7. Hey John! Look how cute it is. My dog came to me today while I was playing on the console. What is new?
  8. Hi James! I just started playing chess to get better at strategy. I'm loving it! Have you ever tried it out?
  9. Hey John, how's it going? A lot has happened for me lately, some good and some not so great. I`m lucky to have at least two people who alway
  10. Hey, James! Good to hear from you. I have some awesome news - I joined a programming group online last Friday and it's been incredible! It's

### [temporal] dataset_6.json — `When did John start working on his 2D Adventure mobile game?`

- expected conv indices: 24
- top-10 ranked conv indices: 30, 29, 27, 8, 19, 7, 26, 9, 0, 21
- top-K snippets:
  1. Hey John! Guess what? Me and my family are currently on the road trip! We`ve already visited my friends Josh and Mark and had such a great t
  2. Hey John! Guess what? Me and my family are currently on the road trip! We`ve already visited my friends Josh and Mark and had such a great t
  3. Hey John, hope you're doing well. Yesterday, we started on a road trip. It was fun spending time with the family and my dogs. Exploring new 
  4. Hey John! Guess what? Me and my family are currently on the road trip! We`ve already visited my friends Josh and Mark and had such a great t
  5. Hey John, hope you're doing well. Yesterday, we started on a road trip. It was fun spending time with the family and my dogs. Exploring new 
  6. Hey John, long time no talk! So much has happened!
  7. Hey James! How've you been? Had an eventful week since our last chat.
  8. Hey John, hope you're doing well. Yesterday, we started on a road trip. It was fun spending time with the family and my dogs. Exploring new 
  9. Hey, James! Good to hear from you. I have some awesome news - I joined a programming group online last Friday and it's been incredible! It's

### [temporal] dataset_6.json — `When did John and his programming friends host an online programming competition?`

- expected conv indices: 26
- top-10 ranked conv indices: 16, 25, 15, 30, 0, 19, 13, 27, 9, 7
- top-K snippets:
  1. Hi James! I just started playing chess to get better at strategy. I'm loving it! Have you ever tried it out?
  2. Hey James! Busy few weeks for sure, but I'm pushing through. Got an email about a volunteer gig at a game dev non-profit. It's something I'v
  3. Hey John! Long time no talk - hope you're doing well. Guess what? Last week I actually won an online gaming tournament! It was such an excit
  4. Hey John! Guess what? Me and my family are currently on the road trip! We`ve already visited my friends Josh and Mark and had such a great t
  5. Hey! Glad to finally talk to you. I want to ask you, what motivates you?
  6. Hey, James! Good to hear from you. I have some awesome news - I joined a programming group online last Friday and it's been incredible! It's
  7. Hey John, how's it going? A lot has happened for me lately, some good and some not so great. I`m lucky to have at least two people who alway
  8. Hey John, long time no talk! So much has happened!
  9. Hey John, long time no talk! So much has happened!
  10. Hey John! Been a while since we chatted. Sorry 'bout not getting back sooner. How's it going? Any new games you're into?

### [temporal] dataset_6.json — `When did James' mother and her friend visit him?`

- expected conv indices: 27
- top-10 ranked conv indices: 16, 29, 19, 30, 17, 14, 13, 24, 3, 22
- top-K snippets:
  1. Hi James! I just started playing chess to get better at strategy. I'm loving it! Have you ever tried it out?
  2. Hi James! I just started playing chess to get better at strategy. I'm loving it! Have you ever tried it out?
  3. Hi James! I just started playing chess to get better at strategy. I'm loving it! Have you ever tried it out?
  4. Hey John, hope you're doing well. Yesterday, we started on a road trip. It was fun spending time with the family and my dogs. Exploring new 
  5. Hey, James! Good to hear from you. I have some awesome news - I joined a programming group online last Friday and it's been incredible! It's
  6. Hey John! Guess what? Me and my family are currently on the road trip! We`ve already visited my friends Josh and Mark and had such a great t
  7. Hey James, good catching up! Been a while huh? I made a huge call - recently left my IT job after 3 years. It was tough but I wanted somethi
  8. Hey John! Guess what? Me and my family are currently on the road trip! We`ve already visited my friends Josh and Mark and had such a great t
  9. Hey John! Guess what? Me and my family are currently on the road trip! We`ve already visited my friends Josh and Mark and had such a great t
  10. Hey John! Guess what? Me and my family are currently on the road trip! We`ve already visited my friends Josh and Mark and had such a great t

### [temporal] dataset_6.json — `When did James try Cyberpunk 2077 game?`

- expected conv indices: 27
- top-10 ranked conv indices: 22, 30, 29, 7, 9, 0, 23, 16, 25, 24
- top-K snippets:
  1. Hey John, it's been a few days since we talked. So much has gone on, both good and bad. Yesterday, when we were at the theater, Samantha lov
  2. Hey John! Guess what? Me and my family are currently on the road trip! We`ve already visited my friends Josh and Mark and had such a great t
  3. Hey John! Guess what? Me and my family are currently on the road trip! We`ve already visited my friends Josh and Mark and had such a great t
  4. Hey John, hope you're doing well. Yesterday, we started on a road trip. It was fun spending time with the family and my dogs. Exploring new 
  5. Hey John! What's up? Anything fun going on?
  6. Hey John! What's up? Anything fun going on?
  7. Hey John! Guess what? Me and my family are currently on the road trip! We`ve already visited my friends Josh and Mark and had such a great t
  8. Hey John, hope you're doing well. Yesterday, we started on a road trip. It was fun spending time with the family and my dogs. Exploring new 

### [temporal] dataset_6.json — `When did John and his gaming friends organize the charity tournament?`

- expected conv indices: 28
- top-10 ranked conv indices: 15, 16, 13, 0, 25, 18, 9, 19, 8, 27
- top-K snippets:
  1. Hey John! Long time no talk - hope you're doing well. Guess what? Last week I actually won an online gaming tournament! It was such an excit
  2. Hi James! I just started playing chess to get better at strategy. I'm loving it! Have you ever tried it out?
  3. Hey John, how's it going? A lot has happened for me lately, some good and some not so great. I`m lucky to have at least two people who alway
  4. Hey! Glad to finally talk to you. I want to ask you, what motivates you?
  5. Hey James! Busy few weeks for sure, but I'm pushing through. Got an email about a volunteer gig at a game dev non-profit. It's something I'v
  6. Hey James, been a few days. The convo got me thinking about my passions and goals. Thanks for encouraging me to try new game genres.
  7. Hey John! Been a while since we chatted. Sorry 'bout not getting back sooner. How's it going? Any new games you're into?
  8. Hey John, how's it going? A lot has happened for me lately, some good and some not so great. I`m lucky to have at least two people who alway
  9. Hey, James! Good to hear from you. I have some awesome news - I joined a programming group online last Friday and it's been incredible! It's
  10. Hey James! How've you been? Had an eventful week since our last chat.

### [temporal] dataset_6.json — `How long did James and Samantha date for before deciding to move in together?`

- expected conv indices: 18, 28
- top-10 ranked conv indices: 12, 16, 1, 24, 7, 30, 4, 29, 22, 19
- top-K snippets:
  1. Hey James, long time no talk! A lot has happened during this time. Let me fill you in.
  2. Hi James! I just started playing chess to get better at strategy. I'm loving it! Have you ever tried it out?
  3. Hey John, something awesome happened since we talked. I made a game avatar and joined a new platform. It's so fun exploring and chatting wit
  4. Hey James, been a few days since we chatted. Lots of stuff goin' on in my life!
  5. Hey John! What's up? Anything fun going on?
  6. Hi James! I just started playing chess to get better at strategy. I'm loving it! Have you ever tried it out?
  7. Hey John! Guess what? Me and my family are currently on the road trip! We`ve already visited my friends Josh and Mark and had such a great t
  8. Hey John! Long time no chat - I adopted a pup from a shelter in Stamford last week and my days have been so much happier with him in the fam
  9. Hey John, hope you're doing well. Yesterday, we started on a road trip. It was fun spending time with the family and my dogs. Exploring new 
  10. Hey John, it's been a few days since we talked. So much has gone on, both good and bad. Yesterday, when we were at the theater, Samantha lov

### [temporal] dataset_7.json — `What kind of project was Jolene working on in the beginning of January 2023?`

- expected conv indices: 0
- top-10 ranked conv indices: 24, 15, 13, 2, 12, 3, 22, 23, 21, 9
- top-K snippets:
  1. Woohoo! I signed up for a meditation course at a retreat near a lake. Can't wait to share this experience with my partner and learn some new
  2. Hey Jolene! Great news - I just started a project for a cleanup in our community and have been trying to raise funds for it. It's been amazi
  3. Hey Jolene! How's it going? We haven't talked in a while. I've been busy getting ready for a yoga retreat with some buddies. A chance to han
  4. Hi Deb! How're you? I've been busy. My engineering professor gave us a huge robotics project. It's tough but fun, it's making me get creativ
  5. Hey Deborah! Long time no talk - I had lots of stuff going on. Remember the tough engineering project? I finally wrapped that up last month.
  6. Hey Deborah! Good to hear from you. How've you been? I've been on an emotional rollercoaster lately, but I'm coping.
  7. Hey Deborah, how's it going? Guess what? Yesterday my partner and I got back from an awesome trip to Rio de Janeiro- we checked out some coo
  8. Hey Jolene, just catching up. I went to a cool event last week with the aim to support each other - pretty inspiring. Have you been connecti
  9. Hey Jolene, since we talked I've been thinking about my mom's influence. Remembering those we love is important.
  10. Hey Jolene, it's been a while. Hope you're doing okay with all your exams and deadlines. I know it's difficult for you right now.

### [temporal] dataset_7.json — `When did Deborah's father pass away?`

- expected conv indices: 1
- top-10 ranked conv indices: 

### [temporal] dataset_7.json — `When was Deborah's parents' wedding?`

- expected conv indices: 1
- top-10 ranked conv indices: 

### [temporal] dataset_7.json — `When did Deborah receive an appreciation letter from her community?`

- expected conv indices: 1
- top-10 ranked conv indices: 

### [temporal] dataset_7.json — `When did Jolene buy her pet Seraphim?`

- expected conv indices: 1
- top-10 ranked conv indices: 

### [temporal] dataset_7.json — `Which book did Jolene read in January 2023?`

- expected conv indices: 3
- top-10 ranked conv indices: 1, 0, 2
- top-K snippets:
  1. Hey Jolene, sorry to tell you this but my dad passed away two days ago. It's been really tough on us all - his sudden death left us all kind
  2. Hey Jolene, sorry to tell you this but my dad passed away two days ago. It's been really tough on us all - his sudden death left us all kind
  3. Hey Jolene, sorry to tell you this but my dad passed away two days ago. It's been really tough on us all - his sudden death left us all kind
  4. Hey Jolene, sorry to tell you this but my dad passed away two days ago. It's been really tough on us all - his sudden death left us all kind
  5. Hey Jolene, sorry to tell you this but my dad passed away two days ago. It's been really tough on us all - his sudden death left us all kind
  6. Hey Jolene, nice to meet you! How's your week going? Anything fun happened?
  7. Hey Jolene, nice to meet you! How's your week going? Anything fun happened?
  8. Hey Jolene, sorry to tell you this but my dad passed away two days ago. It's been really tough on us all - his sudden death left us all kind
  9. Hey Jolene, sorry to tell you this but my dad passed away two days ago. It's been really tough on us all - his sudden death left us all kind

### [temporal] dataset_7.json — `When did Deborah go for her first morning jog in a nearby park?`

- expected conv indices: 6
- top-10 ranked conv indices: 0, 7, 1, 10, 5, 8, 3, 9, 2, 4
- top-K snippets:
  1. Hey Jolene, nice to meet you! How's your week going? Anything fun happened?
  2. Hey Jolene, Anna got me a vegan stir-fry the other day - tofu and veg with ginger and soy sauce. It was really tasty! Food is such a wonderf
  3. Hey Jolene, sorry to tell you this but my dad passed away two days ago. It's been really tough on us all - his sudden death left us all kind
  4. Hey Jolene, Anna got me a vegan stir-fry the other day - tofu and veg with ginger and soy sauce. It was really tasty! Food is such a wonderf
  5. Hey Jolene, Anna got me a vegan stir-fry the other day - tofu and veg with ginger and soy sauce. It was really tasty! Food is such a wonderf
  6. Hey Deb, long time no talk. A lot's happened! On Friday I had a breakthrough with my engineering project. Finally found a solution to a prob
  7. Hey Jolene, Anna got me a vegan stir-fry the other day - tofu and veg with ginger and soy sauce. It was really tasty! Food is such a wonderf
  8. Hey Jolene, Anna got me a vegan stir-fry the other day - tofu and veg with ginger and soy sauce. It was really tasty! Food is such a wonderf
  9. Hey Jolene, sorry to tell you this but my dad passed away two days ago. It's been really tough on us all - his sudden death left us all kind

### [temporal] dataset_7.json — `When did Deborah start the yoga class in the neighborhood?`

- expected conv indices: 8
- top-10 ranked conv indices: 14, 7, 11, 0, 13, 10, 5, 9, 3, 6
- top-K snippets:
  1. Hey Jolene! I started a running group with Anna - it's awesome connecting with people who care about fitness!
  2. Hey Jolene, Anna got me a vegan stir-fry the other day - tofu and veg with ginger and soy sauce. It was really tasty! Food is such a wonderf
  3. Hey Jolene, Anna got me a vegan stir-fry the other day - tofu and veg with ginger and soy sauce. It was really tasty! Food is such a wonderf
  4. Hey Jolene! Great to see you! Had a blast biking nearby with my neighbor last week - was so freeing and beautiful. Checked out an art show w
  5. Hey Jolene, nice to meet you! How's your week going? Anything fun happened?
  6. Hey Jolene! How's it going? We haven't talked in a while. I've been busy getting ready for a yoga retreat with some buddies. A chance to han
  7. Hey Deb, long time no talk. A lot's happened! On Friday I had a breakthrough with my engineering project. Finally found a solution to a prob

### [temporal] dataset_7.json — `When did Deborah go to an art show with Anna?`

- expected conv indices: 11
- top-10 ranked conv indices: 6, 5, 3, 10, 14, 16, 0, 1, 17, 12
- top-K snippets:
  1. Hi Deborah, it's been a while! Since we last talked, so much has happened. Balancing engineering school with my partner's video games is qui
  2. Hi Deborah, it's been a while! Since we last talked, so much has happened. Balancing engineering school with my partner's video games is qui
  3. Hey Deborah, totally buzzing! Had a great night out last night - dinner, and drinks with my friends. So glad I got to let my hair down. You?
  4. Hey Deborah! Good to hear from you. How've you been? I've been on an emotional rollercoaster lately, but I'm coping.
  5. Hey Deb, long time no talk. A lot's happened! On Friday I had a breakthrough with my engineering project. Finally found a solution to a prob
  6. Hey Jolene! I started a running group with Anna - it's awesome connecting with people who care about fitness!
  7. Hi Deborah, it's been a while! Since we last talked, so much has happened. Balancing engineering school with my partner's video games is qui
  8. Since we last spoke, I made a meditation guide for my yoga retreat. How about you?
  9. Hey Jolene, nice to meet you! How's your week going? Anything fun happened?
  10. Hey Deb, long time no talk. A lot's happened! On Friday I had a breakthrough with my engineering project. Finally found a solution to a prob

### [temporal] dataset_7.json — `How long did Jolene work on the robotics project given to her by her Professor?`

- expected conv indices: 2, 11, 12
- top-10 ranked conv indices: 14, 0, 13, 20, 22, 8, 17, 18, 15, 19
- top-K snippets:
  1. Hey Jolene! I started a running group with Anna - it's awesome connecting with people who care about fitness!
  2. Hey Jolene, nice to meet you! How's your week going? Anything fun happened?
  3. Hey Jolene! How's it going? We haven't talked in a while. I've been busy getting ready for a yoga retreat with some buddies. A chance to han
  4. Hey Jolene! I started a running group with Anna - it's awesome connecting with people who care about fitness!
  5. Hey Jolene! Good to hear from you! A lot's happened since we talked - last week I got to go to this yoga retreat near my mom's place. It was
  6. Hey Jolene! How's it going? We haven't talked in a while. I've been busy getting ready for a yoga retreat with some buddies. A chance to han
  7. Hey Deborah, how's it going? Guess what? Yesterday my partner and I got back from an awesome trip to Rio de Janeiro- we checked out some coo
  8. Hi Jolene! We haven't corresponded for a long time!
  9. Hey Jolene! How's it going? We haven't talked in a while. I've been busy getting ready for a yoga retreat with some buddies. A chance to han
  10. Been thinking a lot about my plans lately, especially after checking in with my bf. It's been up and down!  Some days it feels like I'm tryi

### [temporal] dataset_7.json — `When did Jolene do yoga at Talkeetna?`

- expected conv indices: 12
- top-10 ranked conv indices: 19, 23, 7, 25, 13, 24, 5, 11, 20, 14
- top-K snippets:
  1.  Long time no talk! We were given a new game for the console last week, it is Battlefield 1. What's been up with you?
  2.  Long time no talk! We were given a new game for the console last week, it is Battlefield 1. What's been up with you?
  3.  Long time no talk! We were given a new game for the console last week, it is Battlefield 1. What's been up with you?
  4.  Long time no talk! We were given a new game for the console last week, it is Battlefield 1. What's been up with you?
  5.  Long time no talk! We were given a new game for the console last week, it is Battlefield 1. What's been up with you?
  6.  Long time no talk! We were given a new game for the console last week, it is Battlefield 1. What's been up with you?
  7. Hey Jolene, just catching up. I went to a cool event last week with the aim to support each other - pretty inspiring. Have you been connecti
  8. Hey Jolene, Anna got me a vegan stir-fry the other day - tofu and veg with ginger and soy sauce. It was really tasty! Food is such a wonderf
  9.  Long time no talk! We were given a new game for the console last week, it is Battlefield 1. What's been up with you?

### [temporal] dataset_7.json — `Which year did Jolene start practicing yoga?`

- expected conv indices: 12
- top-10 ranked conv indices: 19, 14, 7, 26, 20, 23, 18, 1, 0, 8
- top-K snippets:
  1.  Long time no talk! We were given a new game for the console last week, it is Battlefield 1. What's been up with you?
  2.  Long time no talk! We were given a new game for the console last week, it is Battlefield 1. What's been up with you?
  3.  Long time no talk! We were given a new game for the console last week, it is Battlefield 1. What's been up with you?
  4.  Long time no talk! We were given a new game for the console last week, it is Battlefield 1. What's been up with you?
  5.  Long time no talk! We were given a new game for the console last week, it is Battlefield 1. What's been up with you?
  6.  Long time no talk! We were given a new game for the console last week, it is Battlefield 1. What's been up with you?
  7. Hey Jolene! I started a running group with Anna - it's awesome connecting with people who care about fitness!
  8. Hey Jolene, Anna got me a vegan stir-fry the other day - tofu and veg with ginger and soy sauce. It was really tasty! Food is such a wonderf
  9. Hey Deb! So sorry for the late reply, been super busy. Last weekend my partner and I traveled to a meditation retreat for a few weeks in Phu
  10.  Long time no talk! We were given a new game for the console last week, it is Battlefield 1. What's been up with you?

### [temporal] dataset_7.json — `When did Jolene adopt her snake Susie?`

- expected conv indices: 15, 27
- top-10 ranked conv indices: 14, 13, 25, 11, 4, 21, 0, 24, 18, 10
- top-K snippets:
  1. Hey Jolene! I started a running group with Anna - it's awesome connecting with people who care about fitness!
  2. Hey Jolene! I started a running group with Anna - it's awesome connecting with people who care about fitness!
  3. Hey Jolene! How's it going? We haven't talked in a while. I've been busy getting ready for a yoga retreat with some buddies. A chance to han
  4. Hey Jolene, had a tough week. Storm forced us to cancel our yoga getaway.
  5. Hey Jolene, had a tough week. Storm forced us to cancel our yoga getaway.
  6. Hey Jolene! Great to see you! Had a blast biking nearby with my neighbor last week - was so freeing and beautiful. Checked out an art show w
  7. Hey Deborah! Been a few days since we last talked so I wanted to fill you in on something cool. Last Wednesday I did a mini retreat to asses
  8. Hey Jolene, since we talked I've been thinking about my mom's influence. Remembering those we love is important.
  9. Hey Jolene, nice to meet you! How's your week going? Anything fun happened?

### [temporal] dataset_7.json — `Which pet did Jolene adopt first - Susie or Seraphim?`

- expected conv indices: 1, 15
- top-10 ranked conv indices: 11, 14, 0, 13, 3, 25, 6, 24, 18, 16
- top-K snippets:
  1. Hey Jolene! Great to see you! Had a blast biking nearby with my neighbor last week - was so freeing and beautiful. Checked out an art show w
  2. Hey Jolene! I started a running group with Anna - it's awesome connecting with people who care about fitness!
  3. Hey Jolene, nice to meet you! How's your week going? Anything fun happened?
  4. Hey Jolene! How's it going? We haven't talked in a while. I've been busy getting ready for a yoga retreat with some buddies. A chance to han
  5. Hey Jolene! Great to see you! Had a blast biking nearby with my neighbor last week - was so freeing and beautiful. Checked out an art show w
  6. Hey Jolene! I started a running group with Anna - it's awesome connecting with people who care about fitness!
  7. Hey Deborah! Good to hear from you. How've you been? I've been on an emotional rollercoaster lately, but I'm coping.
  8. Hey Jolene! Great to see you! Had a blast biking nearby with my neighbor last week - was so freeing and beautiful. Checked out an art show w
  9. Hey Jolene, had a tough week. Storm forced us to cancel our yoga getaway.

### [temporal] dataset_7.json — `Which pet did Jolene adopt more recently - Susie or Seraphim?`

- expected conv indices: 1, 15
- top-10 ranked conv indices: 14, 0, 13, 3, 25, 11, 4, 8, 22, 21
- top-K snippets:
  1. Hey Jolene! I started a running group with Anna - it's awesome connecting with people who care about fitness!
  2. Hey Jolene, nice to meet you! How's your week going? Anything fun happened?
  3. Hey Jolene! How's it going? We haven't talked in a while. I've been busy getting ready for a yoga retreat with some buddies. A chance to han
  4. Hey Deborah! Good to hear from you. How've you been? I've been on an emotional rollercoaster lately, but I'm coping.
  5. Hey Jolene, had a tough week. Storm forced us to cancel our yoga getaway.
  6. Hey Jolene! Great to see you! Had a blast biking nearby with my neighbor last week - was so freeing and beautiful. Checked out an art show w
  7. Hey Jolene! Great to see you! Had a blast biking nearby with my neighbor last week - was so freeing and beautiful. Checked out an art show w
  8. Hey Jolene! I started a running group with Anna - it's awesome connecting with people who care about fitness!
  9. Hey Jolene! I started a running group with Anna - it's awesome connecting with people who care about fitness!
  10. Hey Deborah! Been a few days since we last talked so I wanted to fill you in on something cool. Last Wednesday I did a mini retreat to asses

### [temporal] dataset_7.json — `When did Deborah lead a meditation session during the sunset?`

- expected conv indices: 17
- top-10 ranked conv indices: 24, 23, 10, 11, 19, 16, 13, 14, 1, 7
- top-K snippets:
  1. Woohoo! I signed up for a meditation course at a retreat near a lake. Can't wait to share this experience with my partner and learn some new
  2. Woohoo! I signed up for a meditation course at a retreat near a lake. Can't wait to share this experience with my partner and learn some new
  3. Hey Jolene, just catching up. I went to a cool event last week with the aim to support each other - pretty inspiring. Have you been connecti
  4. Hey Deb, long time no talk. A lot's happened! On Friday I had a breakthrough with my engineering project. Finally found a solution to a prob
  5. Hey Jolene! Great to see you! Had a blast biking nearby with my neighbor last week - was so freeing and beautiful. Checked out an art show w

### [temporal] dataset_7.json — `When did Jolene gift her partner a new console?`

- expected conv indices: 18
- top-10 ranked conv indices: 6, 12, 11, 1, 28, 23, 2, 27, 24, 20
- top-K snippets:
  1. Hi Deborah, it's been a while! Since we last talked, so much has happened. Balancing engineering school with my partner's video games is qui
  2. Hey Deborah! Long time no talk - I had lots of stuff going on. Remember the tough engineering project? I finally wrapped that up last month.
  3. Hey Jolene! Great to see you! Had a blast biking nearby with my neighbor last week - was so freeing and beautiful. Checked out an art show w
  4. Hey Jolene, sorry to tell you this but my dad passed away two days ago. It's been really tough on us all - his sudden death left us all kind
  5. Hey Jolene, I'm so excited to tell you! Yesterday, me and my neighbor ran a free gardening class for the community, it was awesome! People o
  6. Hey Jolene, just catching up. I went to a cool event last week with the aim to support each other - pretty inspiring. Have you been connecti
  7. Hi Deb! How're you? I've been busy. My engineering professor gave us a huge robotics project. It's tough but fun, it's making me get creativ
  8. Hey Jolene, just catching up. I went to a cool event last week with the aim to support each other - pretty inspiring. Have you been connecti
  9. Since speaking last, I reconnected with my mom's old friends. Their stories made me tear up and reminded me how lucky I am to have had her.

### [temporal] dataset_7.json — `When did Deborah go to a yoga retreat near her mom's place?`

- expected conv indices: 20
- top-10 ranked conv indices: 19, 23, 24, 25, 14, 1, 16, 5, 13, 11
- top-K snippets:
  1.  Long time no talk! We were given a new game for the console last week, it is Battlefield 1. What's been up with you?
  2.  Long time no talk! We were given a new game for the console last week, it is Battlefield 1. What's been up with you?
  3.  Long time no talk! We were given a new game for the console last week, it is Battlefield 1. What's been up with you?
  4.  Long time no talk! We were given a new game for the console last week, it is Battlefield 1. What's been up with you?
  5. Hey Jolene, just catching up. I went to a cool event last week with the aim to support each other - pretty inspiring. Have you been connecti
  6.  Long time no talk! We were given a new game for the console last week, it is Battlefield 1. What's been up with you?
  7. Woohoo! I signed up for a meditation course at a retreat near a lake. Can't wait to share this experience with my partner and learn some new
  8.  Long time no talk! We were given a new game for the console last week, it is Battlefield 1. What's been up with you?

### [temporal] dataset_7.json — `Which country was Jolene located in during the last week of August 2023?`

- expected conv indices: 22
- top-10 ranked conv indices: 10, 27, 9, 28, 5, 1, 15, 26, 20, 17
- top-K snippets:
  1. Hey Deb, long time no talk. A lot's happened! On Friday I had a breakthrough with my engineering project. Finally found a solution to a prob
  2. Since speaking last, I reconnected with my mom's old friends. Their stories made me tear up and reminded me how lucky I am to have had her.
  3. Hey Jolene, it's been a while. Hope you're doing okay with all your exams and deadlines. I know it's difficult for you right now.
  4. Hey Deb, long time no talk. A lot's happened! On Friday I had a breakthrough with my engineering project. Finally found a solution to a prob
  5. Since speaking last, I reconnected with my mom's old friends. Their stories made me tear up and reminded me how lucky I am to have had her.
  6. Hey Jolene, I'm so excited to tell you! Yesterday, me and my neighbor ran a free gardening class for the community, it was awesome! People o
  7. Hey Deb, long time no talk. A lot's happened! On Friday I had a breakthrough with my engineering project. Finally found a solution to a prob
  8. Hey Deborah, totally buzzing! Had a great night out last night - dinner, and drinks with my friends. So glad I got to let my hair down. You?

### [temporal] dataset_7.json — `When did Jolene and her partner return home from Rio de Janeiro?`

- expected conv indices: 22
- top-10 ranked conv indices: 2, 6, 16, 9, 26, 23, 27, 15, 5, 11
- top-K snippets:
  1. Hi Deb! How're you? I've been busy. My engineering professor gave us a huge robotics project. It's tough but fun, it's making me get creativ
  2. Hi Deborah, it's been a while! Since we last talked, so much has happened. Balancing engineering school with my partner's video games is qui
  3. Since we last spoke, I made a meditation guide for my yoga retreat. How about you?
  4. Hi Deborah, it's been a while! Since we last talked, so much has happened. Balancing engineering school with my partner's video games is qui
  5. Hey Jolene, it's been a while. Hope you're doing okay with all your exams and deadlines. I know it's difficult for you right now.
  6. Hey Deb! So sorry for the late reply, been super busy. Last weekend my partner and I traveled to a meditation retreat for a few weeks in Phu
  7. Hey Jolene, just catching up. I went to a cool event last week with the aim to support each other - pretty inspiring. Have you been connecti
  8. Since speaking last, I reconnected with my mom's old friends. Their stories made me tear up and reminded me how lucky I am to have had her.
  9. Hey Jolene! Great news - I just started a project for a cleanup in our community and have been trying to raise funds for it. It's been amazi

### [temporal] dataset_7.json — `When did Deborah visit Brazil?`

- expected conv indices: 22
- top-10 ranked conv indices: 26, 15, 16, 27, 28, 5, 8, 10, 14, 0
- top-K snippets:
  1. Hey Deb! So sorry for the late reply, been super busy. Last weekend my partner and I traveled to a meditation retreat for a few weeks in Phu
  2. Hey Jolene! Great news - I just started a project for a cleanup in our community and have been trying to raise funds for it. It's been amazi
  3. Since we last spoke, I made a meditation guide for my yoga retreat. How about you?
  4. Hey Deb! So sorry for the late reply, been super busy. Last weekend my partner and I traveled to a meditation retreat for a few weeks in Phu
  5. Since speaking last, I reconnected with my mom's old friends. Their stories made me tear up and reminded me how lucky I am to have had her.
  6. Hey Jolene, I'm so excited to tell you! Yesterday, me and my neighbor ran a free gardening class for the community, it was awesome! People o
  7. Hey Deborah, totally buzzing! Had a great night out last night - dinner, and drinks with my friends. So glad I got to let my hair down. You?

### [temporal] dataset_7.json — `When did Deborah go to a community meetup?`

- expected conv indices: 23
- top-10 ranked conv indices: 10, 26, 6, 11, 1, 21, 14, 20, 5, 12
- top-K snippets:
  1. Hey Deb, long time no talk. A lot's happened! On Friday I had a breakthrough with my engineering project. Finally found a solution to a prob
  2. Hey Deb! So sorry for the late reply, been super busy. Last weekend my partner and I traveled to a meditation retreat for a few weeks in Phu
  3. Hi Deborah, it's been a while! Since we last talked, so much has happened. Balancing engineering school with my partner's video games is qui
  4. Hey Jolene! Great to see you! Had a blast biking nearby with my neighbor last week - was so freeing and beautiful. Checked out an art show w
  5. Hey Jolene, sorry to tell you this but my dad passed away two days ago. It's been really tough on us all - his sudden death left us all kind
  6. Hey Jolene, since we talked I've been thinking about my mom's influence. Remembering those we love is important.
  7. Hi Deborah, it's been a while! Since we last talked, so much has happened. Balancing engineering school with my partner's video games is qui
  8. Hey Jolene! I started a running group with Anna - it's awesome connecting with people who care about fitness!
  9. Hey Jolene! Good to hear from you! A lot's happened since we talked - last week I got to go to this yoga retreat near my mom's place. It was
  10. Hey Jolene, sorry to tell you this but my dad passed away two days ago. It's been really tough on us all - his sudden death left us all kind

### [temporal] dataset_7.json — `When did Jolene and her partner try scuba diving lessons?`

- expected conv indices: 28
- top-10 ranked conv indices: 20, 1, 11, 24, 16, 26, 10, 14, 2, 25
- top-K snippets:
  1. Hey Jolene! Good to hear from you! A lot's happened since we talked - last week I got to go to this yoga retreat near my mom's place. It was
  2. Hey Jolene, sorry to tell you this but my dad passed away two days ago. It's been really tough on us all - his sudden death left us all kind
  3. Hey Jolene! Great to see you! Had a blast biking nearby with my neighbor last week - was so freeing and beautiful. Checked out an art show w
  4. Woohoo! I signed up for a meditation course at a retreat near a lake. Can't wait to share this experience with my partner and learn some new
  5. Since we last spoke, I made a meditation guide for my yoga retreat. How about you?
  6. Hey Deb! So sorry for the late reply, been super busy. Last weekend my partner and I traveled to a meditation retreat for a few weeks in Phu

### [temporal] dataset_7.json — `Where did Jolene and her partner spend most of September 2023?`

- expected conv indices: 1
- top-10 ranked conv indices: 10, 16, 9, 27, 15, 26, 5, 2, 11, 24
- top-K snippets:
  1. Hey Deb, long time no talk. A lot's happened! On Friday I had a breakthrough with my engineering project. Finally found a solution to a prob
  2. Since we last spoke, I made a meditation guide for my yoga retreat. How about you?
  3. Hey Jolene, it's been a while. Hope you're doing okay with all your exams and deadlines. I know it's difficult for you right now.
  4. Since speaking last, I reconnected with my mom's old friends. Their stories made me tear up and reminded me how lucky I am to have had her.
  5. Hey Deb, long time no talk. A lot's happened! On Friday I had a breakthrough with my engineering project. Finally found a solution to a prob
  6. Hey Jolene! Great news - I just started a project for a cleanup in our community and have been trying to raise funds for it. It's been amazi
  7. Hey Deb! So sorry for the late reply, been super busy. Last weekend my partner and I traveled to a meditation retreat for a few weeks in Phu
  8. Hey Deborah, totally buzzing! Had a great night out last night - dinner, and drinks with my friends. So glad I got to let my hair down. You?

### [temporal] dataset_7.json — `When did the Deboran and Jolene agree to go surfing?`

- expected conv indices: 28
- top-10 ranked conv indices: 27, 26, 16, 2, 24, 23, 20, 10, 25, 14
- top-K snippets:
  1. Since speaking last, I reconnected with my mom's old friends. Their stories made me tear up and reminded me how lucky I am to have had her.
  2. Hey Deb! So sorry for the late reply, been super busy. Last weekend my partner and I traveled to a meditation retreat for a few weeks in Phu
  3. Since we last spoke, I made a meditation guide for my yoga retreat. How about you?

### [temporal] dataset_8.json — `What significant event happened in Sam's life towards the end of summer 2023?`

- expected conv indices: 4
- top-10 ranked conv indices: 19, 18, 9, 2, 23, 22, 12, 16, 5, 20
- top-K snippets:
  1. Hey Sam, what's up? Long time no see, huh? Lots has happened.
  2. Hey Sam, hope you're doing good. Wanted to share some amazing news - my partner is pregnant! We're so excited! It's been a while since we ha
  3. Hey Sam! Long time no talk! Hope all is good. What have I been doing these past few weeks?
  4. Hey Sam! Long time no talk! How're you doing? Life's been quite the rollercoaster lately. I had a health scare last week – a sudden heart pa
  5. Hey Sam, hope you're doing good. Something funny happened last night.
  6. Hey Sam, guess what? My partner and I told our extended fam about our marriage yesterday – it was so special! We've been totally overwhelmed
  7. Hey Sam, how's it going? Been a while since we talked. Hope all is good.
  8. Hey Ev! Long time no chat. How's it going? Hope all is well.
  9. Hey Sam, long time no talk! Hope you're doing great. I just got back from a rad vacay with my new SO in Canada. Tried some awesome activitie
  10. Hey Evan! Long time no see, how's it going?

### [temporal] dataset_8.json — `Which year did Evan start taking care of his health seriously?`

- expected conv indices: 4
- top-10 ranked conv indices: 3, 7, 10, 8, 13, 9, 21, 2, 15, 14
- top-K snippets:
  1. Hey Evan, I need to talk to you. My friends were mocking my weight last Friday and it hurt. That made me realize I need to make changes.
  2. Hey Evan, some big news: I'm on a diet and living healthier! Been tough, but I'm determined.
  3. Hey Evan, long time no see! I've started eating healthier - what's new with you? Picked up any new hobbies?
  4. Hey Evan! Exciting news: I started a new diet and exercise routine last Monday and it's made a huge difference. I feel great! What about you
  5. Hey Evan! I've been missing our chats. I had quite the health scare last weekend - ended up in the ER with a severe stomachache. Turns out, 
  6. Hey Evan, I need to talk to you. My friends were mocking my weight last Friday and it hurt. That made me realize I need to make changes.
  7. Hey Sam! Long time no talk! Hope all is good. What have I been doing these past few weeks?
  8. Hey Evan! I’m really getting into this healthier lifestyle—just took my friends on an epic hiking trip last Friday!
  9. Hey Sam! Long time no talk! How're you doing? Life's been quite the rollercoaster lately. I had a health scare last week – a sudden heart pa
  10. Hey Evan! Hope you're doing good. Got some good news to share - I'm a Weight Watchers coach in my group now! It's a pretty big accomplishmen

### [temporal] dataset_8.json — `When Evan get back from a vacation with his SO?`

- expected conv indices: 5
- top-10 ranked conv indices: 20, 22, 0, 18, 23, 12, 19, 11, 10, 8
- top-K snippets:
  1. Hey Evan! Long time no see, how's it going?
  2. Hey Sam, guess what? My partner and I told our extended fam about our marriage yesterday – it was so special! We've been totally overwhelmed
  3. Hey Evan, good to see you! What's new since we last met? Anything cool happening?
  4. Hey Sam, hope you're doing good. Wanted to share some amazing news - my partner is pregnant! We're so excited! It's been a while since we ha
  5. Hey Sam, hope you're doing good. Something funny happened last night.
  6. Hey Sam, how's it going? Been a while since we talked. Hope all is good.
  7. Hey Evan, good to see you! What's new since we last met? Anything cool happening?
  8. Hey Sam, how's it going? Been a while since we talked. Hope all is good.
  9. Hey Sam, guess what? My partner and I told our extended fam about our marriage yesterday – it was so special! We've been totally overwhelmed
  10. Hey Sam, guess what? My partner and I told our extended fam about our marriage yesterday – it was so special! We've been totally overwhelmed

### [temporal] dataset_8.json — `When was Sam in the ER?`

- expected conv indices: 13
- top-10 ranked conv indices: 16, 6, 2, 23, 19, 12, 24, 9, 1, 14
- top-K snippets:
  1. Hey Ev! Long time no chat. How's it going? Hope all is well.
  2. Hey Sam, what's up? It's been a few days since we talked. How have you been? Life's been tough lately - my son had a soccer accident last Sa
  3. Hey Sam! Long time no talk! How're you doing? Life's been quite the rollercoaster lately. I had a health scare last week – a sudden heart pa
  4. Hey Sam, hope you're doing good. Something funny happened last night.
  5. Hey Sam, what's up? It's been a few days since we talked. How have you been? Life's been tough lately - my son had a soccer accident last Sa
  6. Hey Sam, what's up? Long time no see, huh? Lots has happened.
  7. Hey Sam, how's it going? Been a while since we talked. Hope all is good.
  8. Hey Evan, been a few days since we last chatted. Hope you're doing OK. A lot's happened since then. Got issues with my health, it's been rou
  9. Hey Sam! Long time no talk! Hope all is good. What have I been doing these past few weeks?
  10. Hey Sam, good to hear from you! Since we last talked, lots has been happening! Last weekend, I took my family on a road trip to Jasper. It w

### [temporal] dataset_8.json — `When did Evan's son fall off his bike?`

- expected conv indices: 19
- top-10 ranked conv indices: 

### [temporal] dataset_8.json — `When did Evan announce his marriage to his extended family?`

- expected conv indices: 22
- top-10 ranked conv indices: 

### [temporal] dataset_8.json — `When did Evan finish the painting that's hanging in the exhibit?`

- expected conv indices: 19
- top-10 ranked conv indices: 0, 1, 2
- top-K snippets:
  1. Hey Evan, good to see you! What's new since we last met? Anything cool happening?
  2. Hey Sam, good to hear from you! Since we last talked, lots has been happening! Last weekend, I took my family on a road trip to Jasper. It w
  3. Hey Evan, good to see you! What's new since we last met? Anything cool happening?
  4. Hey Sam, good to hear from you! Since we last talked, lots has been happening! Last weekend, I took my family on a road trip to Jasper. It w
  5. Hey Sam, good to hear from you! Since we last talked, lots has been happening! Last weekend, I took my family on a road trip to Jasper. It w
  6. Hey Sam, good to hear from you! Since we last talked, lots has been happening! Last weekend, I took my family on a road trip to Jasper. It w
  7. Hey Evan, good to see you! What's new since we last met? Anything cool happening?
  8. Hey Sam, good to hear from you! Since we last talked, lots has been happening! Last weekend, I took my family on a road trip to Jasper. It w

### [temporal] dataset_8.json — `When will Evan and his partner have their honeymoon in Canada?`

- expected conv indices: 22
- top-10 ranked conv indices: 1, 0, 2
- top-K snippets:
  1. Hey Sam, good to hear from you! Since we last talked, lots has been happening! Last weekend, I took my family on a road trip to Jasper. It w
  2. Hey Evan, good to see you! What's new since we last met? Anything cool happening?
  3. Hey Evan, good to see you! What's new since we last met? Anything cool happening?
  4. Hey Sam, good to hear from you! Since we last talked, lots has been happening! Last weekend, I took my family on a road trip to Jasper. It w
  5. Hey Sam, good to hear from you! Since we last talked, lots has been happening! Last weekend, I took my family on a road trip to Jasper. It w
  6. Hey Sam! Long time no talk! How're you doing? Life's been quite the rollercoaster lately. I had a health scare last week – a sudden heart pa
  7. Hey Sam, good to hear from you! Since we last talked, lots has been happening! Last weekend, I took my family on a road trip to Jasper. It w

### [temporal] dataset_8.json — `When did Evan have a drunken night with his friends?`

- expected conv indices: 23
- top-10 ranked conv indices: 0, 2, 1
- top-K snippets:
  1. Hey Evan, good to see you! What's new since we last met? Anything cool happening?
  2. Hey Evan, good to see you! What's new since we last met? Anything cool happening?
  3. Hey Sam! Long time no talk! How're you doing? Life's been quite the rollercoaster lately. I had a health scare last week – a sudden heart pa
  4. Hey Sam, good to hear from you! Since we last talked, lots has been happening! Last weekend, I took my family on a road trip to Jasper. It w
  5. Hey Evan, good to see you! What's new since we last met? Anything cool happening?
  6. Hey Sam, good to hear from you! Since we last talked, lots has been happening! Last weekend, I took my family on a road trip to Jasper. It w
  7. Hey Sam! Long time no talk! How're you doing? Life's been quite the rollercoaster lately. I had a health scare last week – a sudden heart pa

### [temporal] dataset_9.json — `When did Calvin's place get flooded in Tokyo?`

- expected conv indices: 5
- top-10 ranked conv indices: 13, 2, 0, 6, 15, 1, 17, 29, 14, 19
- top-K snippets:
  1. Hey Cal, how's it going? Something cool happened since last we talked - I got to go to a car workshop in San Francisco! So cool to dive into
  2. Hey Dave! Long time no see. I just went to an awesome music thingy in Tokyo - so cool!
  3. Hey Dave! Nice to meet you! How's it going since we talked?
  4. Hey Dave! Been ages since we chatted. So much has gone down. Touring with Frank Ocean last week was wild. Tokyo was unreal -- the crowd was 
  5. Hey Cal, how's it going? Something cool happened since last we talked - I got to go to a car workshop in San Francisco! So cool to dive into
  6. Hey Calvin! Long time no chat! How was the end of your tour? I bet it was amazing!
  7. Hey Dave, been a few days, so I wanted to let you in on some cool news.  I just got a new car and it's amazing! Finally owning a luxury car 
  8. Hey Dave! Long time no see. I just went to an awesome music thingy in Tokyo - so cool!
  9. Hey Dave! Sorry it took me so long to get back to you. Crazy times since we talked! My album finally dropped on the 11th and it was a wild f
  10. Hey Calvin, long time no talk! A lot has happened. I've taken up photography and it's been great - been taking pics of the scenery around he

### [temporal] dataset_9.json — `Which city was Calvin at on October 3, 2023?`

- expected conv indices: 20
- top-10 ranked conv indices: 17, 15, 7, 14, 19, 12, 1, 6, 13, 4
- top-K snippets:
  1. Hey Dave! Sorry it took me so long to get back to you. Crazy times since we talked! My album finally dropped on the 11th and it was a wild f
  2. Hey Calvin! Long time no chat! How was the end of your tour? I bet it was amazing!
  3. Hey Dave! Met with the creative team for my album yesterday. It was a long session, but awesome to see everything coming together. 
  4. Hey Calvin! Haven't talked in a while! Last Friday I had a card-night with my friends, it was so much fun. We laughed and had a great time! 
  5. Hey Calvin, good to catch up again! Had a tough time with my car project. Worked on the engine of the vintage Mustang, thought I'd fixed it,
  6. Hey Calvin, been ages! Guess what? I got picked for a car mod workshop. Gonna get better at it and learn something new! Look at the cars I'm
  7. Hey Calvin! Haven't talked in a while! Last Friday I had a card-night with my friends, it was so much fun. We laughed and had a great time! 
  8. Hey Dave, been a few days, so I wanted to let you in on some cool news.  I just got a new car and it's amazing! Finally owning a luxury car 
  9. Hey Dave! Sorry it took me so long to get back to you. Crazy times since we talked! My album finally dropped on the 11th and it was a wild f
  10. Hey Dave! Sorry it took me so long to get back to you. Crazy times since we talked! My album finally dropped on the 11th and it was a wild f

### [temporal] dataset_9.json — `When did Calvin met with local artists in Boston?`

- expected conv indices: 20
- top-10 ranked conv indices: 28, 6, 29, 18, 25, 13, 2, 1, 17, 24
- top-K snippets:
  1. Hey Dave, I invited my old high school buddy to see me perform in Boston! It was insane. It got me thinking about how far I've come and remi
  2. Hey Dave! Been ages since we chatted. So much has gone down. Touring with Frank Ocean last week was wild. Tokyo was unreal -- the crowd was 
  3. Hey Calvin, long time no talk! A lot has happened. I've taken up photography and it's been great - been taking pics of the scenery around he
  4. Hey Calvin! Long time no talk! Got some cool news to share - last night was a blast! My band and I were jamming and the music just kept flow
  5. Hey Dave! Long time no talk! I had a great time yesterday, and visited some sights in Boston with a high school friend. It was really fun an
  6. Hey Cal, how's it going? Something cool happened since last we talked - I got to go to a car workshop in San Francisco! So cool to dive into
  7. Hey Dave! Long time no see. I just went to an awesome music thingy in Tokyo - so cool!
  8. Hey Dave, been a few days, so I wanted to let you in on some cool news.  I just got a new car and it's amazing! Finally owning a luxury car 
  9. Hey Dave! Sorry it took me so long to get back to you. Crazy times since we talked! My album finally dropped on the 11th and it was a wild f
  10. Hey Calvin, how's the tour with Frank Ocean? I was pondering our chat the other day about fame and its impact on relationships. It must be a

### [temporal] dataset_9.json — `What was Dave doing in the first weekend of October 2023?`

- expected conv indices: 21
- top-10 ranked conv indices: 17, 29, 5, 14, 7, 19, 3, 16, 2, 22
- top-K snippets:
  1. Hey Dave! Sorry it took me so long to get back to you. Crazy times since we talked! My album finally dropped on the 11th and it was a wild f
  2. Hey Calvin, long time no talk! A lot has happened. I've taken up photography and it's been great - been taking pics of the scenery around he
  3. Hey Dave! Long time no chat! Lots has gone down since we last caught up.
  4. Hey Calvin! Haven't talked in a while! Last Friday I had a card-night with my friends, it was so much fun. We laughed and had a great time! 
  5. Hey Dave! Met with the creative team for my album yesterday. It was a long session, but awesome to see everything coming together. 
  6. Hey Calvin, long time no talk! A lot has happened. I've taken up photography and it's been great - been taking pics of the scenery around he
  7. Hey Dave! Long time no chat! Lots has gone down since we last caught up.
  8. Hey Dave! Sorry it took me so long to get back to you. Crazy times since we talked! My album finally dropped on the 11th and it was a wild f
  9. Hey Calvin, good to catch up again! Had a tough time with my car project. Worked on the engine of the vintage Mustang, thought I'd fixed it,
  10. Hey Calvin! Haven't talked in a while! Last Friday I had a card-night with my friends, it was so much fun. We laughed and had a great time! 

### [temporal] dataset_9.json — `When did Calvin buy his second Ferrari?`

- expected conv indices: 22
- top-10 ranked conv indices: 1, 21, 24, 3, 16, 0, 14, 25, 12, 19
- top-K snippets:
  1. Hey Dave, been a few days, so I wanted to let you in on some cool news.  I just got a new car and it's amazing! Finally owning a luxury car 
  2. Hey Dave, been a few days, so I wanted to let you in on some cool news.  I just got a new car and it's amazing! Finally owning a luxury car 
  3. Hey Calvin! What’s up? Last Friday I went to the car show. I saw some awesome cars and got to mess with car mods! There were so many cool ma
  4. Hey Calvin, how's the tour with Frank Ocean? I was pondering our chat the other day about fame and its impact on relationships. It must be a
  5. Hey Dave, been a few days, so I wanted to let you in on some cool news.  I just got a new car and it's amazing! Finally owning a luxury car 
  6. Hey Calvin, long time no see! A lot's been happening since we last talked. Guess what? I finally opened my own car maintenance shop! It's so
  7. Hey Calvin! Been a while, what's up? I'm tied up with car stuff lately, yesterday I came back from San Francsico with some great insights an
  8. Hey Dave! Nice to meet you! How's it going since we talked?
  9. Hey Calvin! Haven't talked in a while! Last Friday I had a card-night with my friends, it was so much fun. We laughed and had a great time! 
  10. Hey Dave! Long time no talk! I had a great time yesterday, and visited some sights in Boston with a high school friend. It was really fun an

### [temporal] dataset_9.json — `When did Calvin plan on travelling to Tokyo the second time?`

- expected conv indices: 23
- top-10 ranked conv indices: 13, 0, 2, 17, 5, 7, 6, 15, 19, 14
- top-K snippets:
  1. Hey Cal, how's it going? Something cool happened since last we talked - I got to go to a car workshop in San Francisco! So cool to dive into
  2. Hey Dave! Nice to meet you! How's it going since we talked?
  3. Hey Dave! Long time no see. I just went to an awesome music thingy in Tokyo - so cool!
  4. Hey Dave! Sorry it took me so long to get back to you. Crazy times since we talked! My album finally dropped on the 11th and it was a wild f
  5. Hey Dave! Long time no chat! Lots has gone down since we last caught up.
  6. Hey Dave! Met with the creative team for my album yesterday. It was a long session, but awesome to see everything coming together. 
  7. Hey Dave! Been ages since we chatted. So much has gone down. Touring with Frank Ocean last week was wild. Tokyo was unreal -- the crowd was 
  8. Hey Dave! Been ages since we chatted. So much has gone down. Touring with Frank Ocean last week was wild. Tokyo was unreal -- the crowd was 
  9. Hey Calvin! Long time no chat! How was the end of your tour? I bet it was amazing!
  10. Hey Calvin, good to catch up again! Had a tough time with my car project. Worked on the engine of the vintage Mustang, thought I'd fixed it,

### [temporal] dataset_9.json — `Which hobby did Dave pick up in October 2023?`

- expected conv indices: 26
- top-10 ranked conv indices: 11, 12, 27, 3, 14, 21, 5, 25, 17, 4
- top-K snippets:
  1. Hey Dave, long time no see! I just took my Ferrari for a service and it was so stressful. I'm kinda attached to it. Can you relate? What kin
  2. Hey Calvin, been ages! Guess what? I got picked for a car mod workshop. Gonna get better at it and learn something new! Look at the cars I'm
  3. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  4. Hey Dave, long time no see! I just took my Ferrari for a service and it was so stressful. I'm kinda attached to it. Can you relate? What kin
  5. Hey Calvin, long time no see! A lot's been happening since we last talked. Guess what? I finally opened my own car maintenance shop! It's so
  6. Hey Calvin, long time no see! A lot's been happening since we last talked. Guess what? I finally opened my own car maintenance shop! It's so
  7. Hey Calvin! Haven't talked in a while! Last Friday I had a card-night with my friends, it was so much fun. We laughed and had a great time! 
  8. Hey Calvin! What’s up? Last Friday I went to the car show. I saw some awesome cars and got to mess with car mods! There were so many cool ma
  9. Hey Dave! Long time no chat! Lots has gone down since we last caught up.
  10. Hey Calvin, long time no see! A lot's been happening since we last talked. Guess what? I finally opened my own car maintenance shop! It's so

### [temporal] dataset_9.json — `When did Dave take a photo of a Boston clock tower?`

- expected conv indices: 26
- top-10 ranked conv indices: 29, 2, 27, 11, 3, 5, 25, 21, 8, 14
- top-K snippets:
  1. Hey Calvin, long time no talk! A lot has happened. I've taken up photography and it's been great - been taking pics of the scenery around he
  2. Hey Calvin, long time no talk! A lot has happened. I've taken up photography and it's been great - been taking pics of the scenery around he
  3. Hey Calvin, long time no talk! A lot has happened. I've taken up photography and it's been great - been taking pics of the scenery around he
  4. Hey Dave! Long time no see. I just went to an awesome music thingy in Tokyo - so cool!
  5. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  6. Hey Dave, long time no see! I just took my Ferrari for a service and it was so stressful. I'm kinda attached to it. Can you relate? What kin
  7. Hey Calvin, long time no talk! A lot has happened. I've taken up photography and it's been great - been taking pics of the scenery around he
  8. Hey Calvin, long time no see! A lot's been happening since we last talked. Guess what? I finally opened my own car maintenance shop! It's so
  9. Hey Dave! Long time no chat! Lots has gone down since we last caught up.
  10. Hey Dave! Long time no chat! Lots has gone down since we last caught up.

### [temporal] dataset_9.json — `Where was Calvin located in the last week of October 2023?`

- expected conv indices: 27
- top-10 ranked conv indices: 17, 15, 14, 19, 7, 13, 12, 1, 21, 4
- top-K snippets:
  1. Hey Dave! Sorry it took me so long to get back to you. Crazy times since we talked! My album finally dropped on the 11th and it was a wild f
  2. Hey Calvin! Long time no chat! How was the end of your tour? I bet it was amazing!
  3. Hey Calvin! Haven't talked in a while! Last Friday I had a card-night with my friends, it was so much fun. We laughed and had a great time! 
  4. Hey Calvin, good to catch up again! Had a tough time with my car project. Worked on the engine of the vintage Mustang, thought I'd fixed it,
  5. Hey Dave! Met with the creative team for my album yesterday. It was a long session, but awesome to see everything coming together. 
  6. Hey Cal, how's it going? Something cool happened since last we talked - I got to go to a car workshop in San Francisco! So cool to dive into
  7. Hey Calvin, been ages! Guess what? I got picked for a car mod workshop. Gonna get better at it and learn something new! Look at the cars I'm
  8. Hey Dave! Met with the creative team for my album yesterday. It was a long session, but awesome to see everything coming together. 
  9. Hey Calvin! Long time no chat! How was the end of your tour? I bet it was amazing!
  10. Hey Dave! Sorry it took me so long to get back to you. Crazy times since we talked! My album finally dropped on the 11th and it was a wild f

### [temporal] dataset_9.json — `When did Dave buy a vintage camera?`

- expected conv indices: 29
- top-10 ranked conv indices: 

### [temporal] dataset_9.json — `When did Calvin attend a gala in Boston?`

- expected conv indices: 29
- top-10 ranked conv indices: 

### [abstention] dataset_0.json — `What did Caroline realize after her charity race?`

- expected conv indices: 1
- top-10 ranked conv indices: 7, 18, 16, 11, 6, 9, 17, 14, 10, 15
- top-K snippets:
  1. Hey Mel, what's up? Been a busy week since we talked.
  2. Woohoo Melanie! I passed the adoption agency interviews last Friday! I'm so excited and thankful. This is a big move towards my goal of havi
  3. Woohoo Melanie! I passed the adoption agency interviews last Friday! I'm so excited and thankful. This is a big move towards my goal of havi
  4. Hey Mel, what's up? Long time no see! I just contacted my mentor for adoption advice. I'm ready to be a mom and share my love and family. It
  5. Woohoo Melanie! I passed the adoption agency interviews last Friday! I'm so excited and thankful. This is a big move towards my goal of havi
  6. Hey Mel! How're ya doin'? Recently, I had a not-so-great experience on a hike. I ran into a group of religious conservatives who said someth
  7. Hey Mel, great to chat with you again! So much has happened since we last spoke - I went to an LGBTQ conference two days ago and it was real
  8. Hey Melanie! Just wanted to say hi!
  9. Woohoo Melanie! I passed the adoption agency interviews last Friday! I'm so excited and thankful. This is a big move towards my goal of havi
  10. Hey Mel, great to chat with you again! So much has happened since we last spoke - I went to an LGBTQ conference two days ago and it was real

### [abstention] dataset_0.json — `What are Melanie's plans for the summer with respect to adoption?`

- expected conv indices: 1
- top-10 ranked conv indices: 18, 9, 11, 6, 14, 16, 17, 7, 13, 0
- top-K snippets:
  1. Woohoo Melanie! I passed the adoption agency interviews last Friday! I'm so excited and thankful. This is a big move towards my goal of havi
  2. Woohoo Melanie! I passed the adoption agency interviews last Friday! I'm so excited and thankful. This is a big move towards my goal of havi
  3. Woohoo Melanie! I passed the adoption agency interviews last Friday! I'm so excited and thankful. This is a big move towards my goal of havi
  4. Hey Melanie! Just wanted to say hi!
  5. Woohoo Melanie! I passed the adoption agency interviews last Friday! I'm so excited and thankful. This is a big move towards my goal of havi
  6. Hey Mel! How're ya doin'? Recently, I had a not-so-great experience on a hike. I ran into a group of religious conservatives who said someth
  7. Hey Mel! How're ya doin'? Recently, I had a not-so-great experience on a hike. I ran into a group of religious conservatives who said someth
  8. Hey Mel, great to chat with you again! So much has happened since we last spoke - I went to an LGBTQ conference two days ago and it was real
  9. Woohoo Melanie! I passed the adoption agency interviews last Friday! I'm so excited and thankful. This is a big move towards my goal of havi
  10. Hey Melanie, great to hear from you. What's been up since we talked?

### [abstention] dataset_0.json — `What type of individuals does the adoption agency Melanie is considering support?`

- expected conv indices: 1
- top-10 ranked conv indices: 18, 7, 17, 9, 6, 16, 14, 8, 13, 0
- top-K snippets:
  1. Woohoo Melanie! I passed the adoption agency interviews last Friday! I'm so excited and thankful. This is a big move towards my goal of havi
  2. Woohoo Melanie! I passed the adoption agency interviews last Friday! I'm so excited and thankful. This is a big move towards my goal of havi
  3. Woohoo Melanie! I passed the adoption agency interviews last Friday! I'm so excited and thankful. This is a big move towards my goal of havi
  4. Woohoo Melanie! I passed the adoption agency interviews last Friday! I'm so excited and thankful. This is a big move towards my goal of havi
  5. Woohoo Melanie! I passed the adoption agency interviews last Friday! I'm so excited and thankful. This is a big move towards my goal of havi
  6. Woohoo Melanie! I passed the adoption agency interviews last Friday! I'm so excited and thankful. This is a big move towards my goal of havi
  7. Woohoo Melanie! I passed the adoption agency interviews last Friday! I'm so excited and thankful. This is a big move towards my goal of havi
  8. Hey Mel, what's up? Been a busy week since we talked.
  9. Hey Caroline, that roadtrip this past weekend was insane! We were all freaked when my son got into an accident. We were so lucky he was okay
  10. Hey Mel, what's up? Been a busy week since we talked.

### [abstention] dataset_0.json — `Why did Melanie choose the adoption agency?`

- expected conv indices: 1
- top-10 ranked conv indices: 18, 7, 9, 17, 6, 16, 10, 14, 11, 15
- top-K snippets:
  1. Woohoo Melanie! I passed the adoption agency interviews last Friday! I'm so excited and thankful. This is a big move towards my goal of havi
  2. Woohoo Melanie! I passed the adoption agency interviews last Friday! I'm so excited and thankful. This is a big move towards my goal of havi
  3. Woohoo Melanie! I passed the adoption agency interviews last Friday! I'm so excited and thankful. This is a big move towards my goal of havi
  4. Hey Mel, what's up? Been a busy week since we talked.
  5. Hey Melanie! Just wanted to say hi!
  6. Woohoo Melanie! I passed the adoption agency interviews last Friday! I'm so excited and thankful. This is a big move towards my goal of havi
  7. Woohoo Melanie! I passed the adoption agency interviews last Friday! I'm so excited and thankful. This is a big move towards my goal of havi
  8. Woohoo Melanie! I passed the adoption agency interviews last Friday! I'm so excited and thankful. This is a big move towards my goal of havi
  9. Woohoo Melanie! I passed the adoption agency interviews last Friday! I'm so excited and thankful. This is a big move towards my goal of havi
  10. Hey Caroline, that roadtrip this past weekend was insane! We were all freaked when my son got into an accident. We were so lucky he was okay

### [abstention] dataset_0.json — `What is Melanie excited about in her adoption process?`

- expected conv indices: 1
- top-10 ranked conv indices: 18, 9, 16, 7, 17, 11, 6, 14, 13, 8
- top-K snippets:
  1. Woohoo Melanie! I passed the adoption agency interviews last Friday! I'm so excited and thankful. This is a big move towards my goal of havi
  2. Woohoo Melanie! I passed the adoption agency interviews last Friday! I'm so excited and thankful. This is a big move towards my goal of havi
  3. Hey Melanie! Just wanted to say hi!
  4. Woohoo Melanie! I passed the adoption agency interviews last Friday! I'm so excited and thankful. This is a big move towards my goal of havi
  5. Woohoo Melanie! I passed the adoption agency interviews last Friday! I'm so excited and thankful. This is a big move towards my goal of havi
  6. Hey Mel, what's up? Long time no see! I just contacted my mentor for adoption advice. I'm ready to be a mom and share my love and family. It
  7. Hey Mel, what's up? Long time no see! I just contacted my mentor for adoption advice. I'm ready to be a mom and share my love and family. It
  8. Woohoo Melanie! I passed the adoption agency interviews last Friday! I'm so excited and thankful. This is a big move towards my goal of havi
  9. Hey Mel, what's up? Been a busy week since we talked.
  10. Hey Caroline, that roadtrip this past weekend was insane! We were all freaked when my son got into an accident. We were so lucky he was okay

### [abstention] dataset_0.json — `What does Melanie's necklace symbolize?`

- expected conv indices: 3
- top-10 ranked conv indices: 16, 17, 9, 7, 18, 6, 14, 15, 11, 13
- top-K snippets:
  1. Hey Mel, what's up? Long time no see! I just contacted my mentor for adoption advice. I'm ready to be a mom and share my love and family. It
  2. Hey Caroline, that roadtrip this past weekend was insane! We were all freaked when my son got into an accident. We were so lucky he was okay
  3. Hey Melanie! Just wanted to say hi!
  4. Hey Mel, what's up? Been a busy week since we talked.
  5. Woohoo Melanie! I passed the adoption agency interviews last Friday! I'm so excited and thankful. This is a big move towards my goal of havi
  6. Hey Mel, what's up? Been a busy week since we talked.
  7. Hey Melanie! Just wanted to say hi!
  8. Woohoo Melanie! I passed the adoption agency interviews last Friday! I'm so excited and thankful. This is a big move towards my goal of havi
  9. Hey Mel, what's up? Long time no see! I just contacted my mentor for adoption advice. I'm ready to be a mom and share my love and family. It
  10. Woohoo Melanie! I passed the adoption agency interviews last Friday! I'm so excited and thankful. This is a big move towards my goal of havi

### [abstention] dataset_0.json — `What country is Melanie's grandma from?`

- expected conv indices: 3
- top-10 ranked conv indices: 9, 14, 17, 6, 18, 16, 7, 11, 15, 5
- top-K snippets:
  1. Hey Melanie! Just wanted to say hi!
  2. Hey Melanie, great to hear from you. What's been up since we talked?
  3. Hey Caroline, that roadtrip this past weekend was insane! We were all freaked when my son got into an accident. We were so lucky he was okay
  4. Hey Caroline, that roadtrip this past weekend was insane! We were all freaked when my son got into an accident. We were so lucky he was okay
  5. Hey Mel, great to chat with you again! So much has happened since we last spoke - I went to an LGBTQ conference two days ago and it was real
  6. Woohoo Melanie! I passed the adoption agency interviews last Friday! I'm so excited and thankful. This is a big move towards my goal of havi
  7. Hey Mel, what's up? Long time no see! I just contacted my mentor for adoption advice. I'm ready to be a mom and share my love and family. It
  8. Woohoo Melanie! I passed the adoption agency interviews last Friday! I'm so excited and thankful. This is a big move towards my goal of havi
  9. Woohoo Melanie! I passed the adoption agency interviews last Friday! I'm so excited and thankful. This is a big move towards my goal of havi
  10. Hey Mel, what's up? Been a busy week since we talked.

### [abstention] dataset_0.json — `What was grandpa's gift to Caroline?`

- expected conv indices: 3
- top-10 ranked conv indices: 7, 9, 16, 18, 17, 11, 6, 14, 13
- top-K snippets:
  1. Hey Mel, what's up? Been a busy week since we talked.
  2. Hey Mel, what's up? Been a busy week since we talked.
  3. Hey Melanie! Just wanted to say hi!
  4. Hey Mel, what's up? Long time no see! I just contacted my mentor for adoption advice. I'm ready to be a mom and share my love and family. It
  5. Woohoo Melanie! I passed the adoption agency interviews last Friday! I'm so excited and thankful. This is a big move towards my goal of havi
  6. Hey Mel, what's up? Been a busy week since we talked.
  7. Hey Caroline, that roadtrip this past weekend was insane! We were all freaked when my son got into an accident. We were so lucky he was okay
  8. Hey Mel, what's up? Been a busy week since we talked.
  9. Hey Mel! How're ya doin'? Recently, I had a not-so-great experience on a hike. I ran into a group of religious conservatives who said someth
  10. Woohoo Melanie! I passed the adoption agency interviews last Friday! I'm so excited and thankful. This is a big move towards my goal of havi

### [abstention] dataset_0.json — `What did Caroline and her family do while camping?`

- expected conv indices: 3
- top-10 ranked conv indices: 17, 7, 11, 16, 9, 18, 6, 13, 8, 14
- top-K snippets:
  1. Hey Caroline, that roadtrip this past weekend was insane! We were all freaked when my son got into an accident. We were so lucky he was okay
  2. Hey Caroline, that roadtrip this past weekend was insane! We were all freaked when my son got into an accident. We were so lucky he was okay
  3. Hey Mel, what's up? Been a busy week since we talked.
  4. Hey Caroline, that roadtrip this past weekend was insane! We were all freaked when my son got into an accident. We were so lucky he was okay
  5. Hey Caroline, that roadtrip this past weekend was insane! We were all freaked when my son got into an accident. We were so lucky he was okay
  6. Hey Caroline, that roadtrip this past weekend was insane! We were all freaked when my son got into an accident. We were so lucky he was okay
  7. Hey Mel! How're ya doin'? Recently, I had a not-so-great experience on a hike. I ran into a group of religious conservatives who said someth
  8. Hey Mel, what's up? Long time no see! I just contacted my mentor for adoption advice. I'm ready to be a mom and share my love and family. It
  9. Hey Caroline, that roadtrip this past weekend was insane! We were all freaked when my son got into an accident. We were so lucky he was okay
  10. Hey Melanie! Just wanted to say hi!

### [abstention] dataset_0.json — `What kind of counseling and mental health services is Melanie interested in pursuing?`

- expected conv indices: 3
- top-10 ranked conv indices: 6, 18, 14, 7, 17, 16, 9, 10, 11, 15
- top-K snippets:
  1. Hey Mel, great to chat with you again! So much has happened since we last spoke - I went to an LGBTQ conference two days ago and it was real
  2. Hey Mel, great to chat with you again! So much has happened since we last spoke - I went to an LGBTQ conference two days ago and it was real
  3. Woohoo Melanie! I passed the adoption agency interviews last Friday! I'm so excited and thankful. This is a big move towards my goal of havi
  4. Woohoo Melanie! I passed the adoption agency interviews last Friday! I'm so excited and thankful. This is a big move towards my goal of havi
  5. Woohoo Melanie! I passed the adoption agency interviews last Friday! I'm so excited and thankful. This is a big move towards my goal of havi
  6. Hey Melanie, great to hear from you. What's been up since we talked?
  7. Woohoo Melanie! I passed the adoption agency interviews last Friday! I'm so excited and thankful. This is a big move towards my goal of havi
  8. Hey Mel, great to chat with you again! So much has happened since we last spoke - I went to an LGBTQ conference two days ago and it was real
  9. Hey Mel, what's up? Been a busy week since we talked.
  10. Hey Caroline, that roadtrip this past weekend was insane! We were all freaked when my son got into an accident. We were so lucky he was okay

### [abstention] dataset_0.json — `What kind of counseling workshop did Melanie attend recently?`

- expected conv indices: 3
- top-10 ranked conv indices: 7, 6, 18, 16, 9, 14, 17, 15, 11
- top-K snippets:
  1. Hey Mel, what's up? Been a busy week since we talked.
  2. Hey Mel, great to chat with you again! So much has happened since we last spoke - I went to an LGBTQ conference two days ago and it was real
  3. Woohoo Melanie! I passed the adoption agency interviews last Friday! I'm so excited and thankful. This is a big move towards my goal of havi
  4. Woohoo Melanie! I passed the adoption agency interviews last Friday! I'm so excited and thankful. This is a big move towards my goal of havi
  5. Hey Mel, great to chat with you again! So much has happened since we last spoke - I went to an LGBTQ conference two days ago and it was real
  6. Hey Mel, what's up? Long time no see! I just contacted my mentor for adoption advice. I'm ready to be a mom and share my love and family. It
  7. Woohoo Melanie! I passed the adoption agency interviews last Friday! I'm so excited and thankful. This is a big move towards my goal of havi
  8. Woohoo Melanie! I passed the adoption agency interviews last Friday! I'm so excited and thankful. This is a big move towards my goal of havi
  9. Hey Melanie! Just wanted to say hi!
  10. Hey Mel, what's up? Been a busy week since we talked.

### [abstention] dataset_0.json — `What motivated Melanie to pursue counseling?`

- expected conv indices: 3
- top-10 ranked conv indices: 18, 7, 6, 17, 9, 16, 10, 14, 0, 15
- top-K snippets:
  1. Woohoo Melanie! I passed the adoption agency interviews last Friday! I'm so excited and thankful. This is a big move towards my goal of havi
  2. Hey Mel, what's up? Been a busy week since we talked.
  3. Hey Mel, great to chat with you again! So much has happened since we last spoke - I went to an LGBTQ conference two days ago and it was real
  4. Woohoo Melanie! I passed the adoption agency interviews last Friday! I'm so excited and thankful. This is a big move towards my goal of havi
  5. Hey Mel, great to chat with you again! So much has happened since we last spoke - I went to an LGBTQ conference two days ago and it was real
  6. Hey Caroline, that roadtrip this past weekend was insane! We were all freaked when my son got into an accident. We were so lucky he was okay
  7. Woohoo Melanie! I passed the adoption agency interviews last Friday! I'm so excited and thankful. This is a big move towards my goal of havi
  8. Woohoo Melanie! I passed the adoption agency interviews last Friday! I'm so excited and thankful. This is a big move towards my goal of havi
  9. Hey Mel, great to chat with you again! So much has happened since we last spoke - I went to an LGBTQ conference two days ago and it was real
  10. Hey Melanie! Just wanted to say hi!

### [abstention] dataset_0.json — `What kind of place does Melanie want to create for people?`

- expected conv indices: 3
- top-10 ranked conv indices: 18, 16, 7, 11, 10, 9, 17, 6, 14, 13
- top-K snippets:
  1. Woohoo Melanie! I passed the adoption agency interviews last Friday! I'm so excited and thankful. This is a big move towards my goal of havi
  2. Hey Mel, what's up? Long time no see! I just contacted my mentor for adoption advice. I'm ready to be a mom and share my love and family. It
  3. Hey Mel, what's up? Been a busy week since we talked.
  4. Hey Mel! How're ya doin'? Recently, I had a not-so-great experience on a hike. I ran into a group of religious conservatives who said someth
  5. Hey Caroline! Last night was amazing! We celebrated my daughter's birthday with a concert surrounded by music, joy and the warm summer breez
  6. Woohoo Melanie! I passed the adoption agency interviews last Friday! I'm so excited and thankful. This is a big move towards my goal of havi
  7. Hey Melanie! Just wanted to say hi!
  8. Woohoo Melanie! I passed the adoption agency interviews last Friday! I'm so excited and thankful. This is a big move towards my goal of havi
  9. Hey Caroline, that roadtrip this past weekend was insane! We were all freaked when my son got into an accident. We were so lucky he was okay
  10. Hey Mel, great to chat with you again! So much has happened since we last spoke - I went to an LGBTQ conference two days ago and it was real

### [abstention] dataset_0.json — `Did Caroline make the black and white bowl in the photo?`

- expected conv indices: 4
- top-10 ranked conv indices: 7, 16, 11, 17, 9, 6, 18, 15, 14, 8
- top-K snippets:
  1. Hey Mel, what's up? Been a busy week since we talked.
  2. Hey Mel, what's up? Been a busy week since we talked.
  3. Hey Mel, what's up? Been a busy week since we talked.
  4. Hey Mel, what's up? Long time no see! I just contacted my mentor for adoption advice. I'm ready to be a mom and share my love and family. It
  5. Hey Mel! How're ya doin'? Recently, I had a not-so-great experience on a hike. I ran into a group of religious conservatives who said someth
  6. Hey Caroline, that roadtrip this past weekend was insane! We were all freaked when my son got into an accident. We were so lucky he was okay
  7. Hey Mel, what's up? Been a busy week since we talked.
  8. Hey Mel, what's up? Been a busy week since we talked.
  9. Hey Mel! How're ya doin'? Recently, I had a not-so-great experience on a hike. I ran into a group of religious conservatives who said someth
  10. Hey Mel, what's up? Long time no see! I just contacted my mentor for adoption advice. I'm ready to be a mom and share my love and family. It

### [abstention] dataset_0.json — `What inspired Melanie's painting for the art show?`

- expected conv indices: 8
- top-10 ranked conv indices: 16, 7, 9, 14, 18, 17, 13, 6, 11, 15
- top-K snippets:
  1. Hey Mel, what's up? Long time no see! I just contacted my mentor for adoption advice. I'm ready to be a mom and share my love and family. It
  2. Hey Mel, what's up? Been a busy week since we talked.
  3. Hey Mel, what's up? Long time no see! I just contacted my mentor for adoption advice. I'm ready to be a mom and share my love and family. It
  4. Hey Mel, what's up? Been a busy week since we talked.
  5. Hey Melanie! Just wanted to say hi!
  6. Hey Mel, what's up? Been a busy week since we talked.
  7. Hey Mel, what's up? Been a busy week since we talked.
  8. Hey Mel, what's up? Long time no see! I just contacted my mentor for adoption advice. I'm ready to be a mom and share my love and family. It
  9. Hey Melanie, great to hear from you. What's been up since we talked?
  10. Hey Mel, what's up? Long time no see! I just contacted my mentor for adoption advice. I'm ready to be a mom and share my love and family. It

### [abstention] dataset_0.json — `What inspired Caroline's sculpture for the art show?`

- expected conv indices: 8
- top-10 ranked conv indices: 16, 7, 18, 11, 14, 17, 6, 9, 1, 5
- top-K snippets:
  1. Hey Mel, what's up? Long time no see! I just contacted my mentor for adoption advice. I'm ready to be a mom and share my love and family. It
  2. Hey Mel, what's up? Been a busy week since we talked.
  3. Hey Mel, what's up? Been a busy week since we talked.
  4. Hey Mel, what's up? Been a busy week since we talked.
  5. Hey Mel, what's up? Long time no see! I just contacted my mentor for adoption advice. I'm ready to be a mom and share my love and family. It
  6. Woohoo Melanie! I passed the adoption agency interviews last Friday! I'm so excited and thankful. This is a big move towards my goal of havi
  7. Hey Mel! How're ya doin'? Recently, I had a not-so-great experience on a hike. I ran into a group of religious conservatives who said someth
  8. Hey Mel! How're ya doin'? Recently, I had a not-so-great experience on a hike. I ran into a group of religious conservatives who said someth
  9. Hey Melanie, great to hear from you. What's been up since we talked?
  10. Woohoo Melanie! I passed the adoption agency interviews last Friday! I'm so excited and thankful. This is a big move towards my goal of havi

### [abstention] dataset_0.json — `Is Oscar Melanie's pet?`

- expected conv indices: 12
- top-10 ranked conv indices: 6, 0, 9, 14, 17, 18, 8, 13, 16, 7
- top-K snippets:
  1. Hey Mel, great to chat with you again! So much has happened since we last spoke - I went to an LGBTQ conference two days ago and it was real
  2. Hey Mel! Good to see you! How have you been?
  3. Hey Mel, great to chat with you again! So much has happened since we last spoke - I went to an LGBTQ conference two days ago and it was real
  4. Hey Mel, great to chat with you again! So much has happened since we last spoke - I went to an LGBTQ conference two days ago and it was real
  5. Hey Melanie! Just wanted to say hi!
  6. Hey Melanie, great to hear from you. What's been up since we talked?
  7. Hey Mel, great to chat with you again! So much has happened since we last spoke - I went to an LGBTQ conference two days ago and it was real
  8. Hey Caroline, that roadtrip this past weekend was insane! We were all freaked when my son got into an accident. We were so lucky he was okay
  9. Woohoo Melanie! I passed the adoption agency interviews last Friday! I'm so excited and thankful. This is a big move towards my goal of havi

### [abstention] dataset_0.json — `Where did Oscar hide his bone once?`

- expected conv indices: 12
- top-10 ranked conv indices: 6, 13, 1, 17, 3, 16, 4, 15, 9, 18
- top-K snippets:
  1. Hey Mel, great to chat with you again! So much has happened since we last spoke - I went to an LGBTQ conference two days ago and it was real
  2. Hey, Mel! How's it going? There's something I want to tell you. I went hiking last week and got into a bad spot with some people. It really 
  3. Hey Caroline, since we last chatted, I've had a lot of things happening to me. I ran a charity race for mental health last Saturday – it was
  4. Hey, Mel! How's it going? There's something I want to tell you. I went hiking last week and got into a bad spot with some people. It really 
  5. Hey Caroline, that roadtrip this past weekend was insane! We were all freaked when my son got into an accident. We were so lucky he was okay
  6. Hey Mel, great to chat with you again! So much has happened since we last spoke - I went to an LGBTQ conference two days ago and it was real

### [abstention] dataset_0.json — `What activity did Melanie used to do with her dad?`

- expected conv indices: 12
- top-10 ranked conv indices: 9, 7, 14, 17, 6, 16, 18, 11, 15, 13
- top-K snippets:
  1. Hey Melanie! Just wanted to say hi!
  2. Hey Mel, what's up? Been a busy week since we talked.
  3. Hey Melanie, great to hear from you. What's been up since we talked?
  4. Hey Caroline, that roadtrip this past weekend was insane! We were all freaked when my son got into an accident. We were so lucky he was okay
  5. Hey Caroline, that roadtrip this past weekend was insane! We were all freaked when my son got into an accident. We were so lucky he was okay
  6. Hey Mel, great to chat with you again! So much has happened since we last spoke - I went to an LGBTQ conference two days ago and it was real
  7. Hey Mel, what's up? Long time no see! I just contacted my mentor for adoption advice. I'm ready to be a mom and share my love and family. It
  8. Hey Melanie! Just wanted to say hi!
  9. Hey Mel, great to chat with you again! So much has happened since we last spoke - I went to an LGBTQ conference two days ago and it was real
  10. Woohoo Melanie! I passed the adoption agency interviews last Friday! I'm so excited and thankful. This is a big move towards my goal of havi

### [abstention] dataset_0.json — `What did Melanie find in her neighborhood during her walk?`

- expected conv indices: 13
- top-10 ranked conv indices: 9, 15, 7, 6, 17, 16, 18, 14, 5, 1
- top-K snippets:
  1. Hey Melanie! Just wanted to say hi!
  2. Hey Mel, long time no chat! I had a wicked day out with the gang last weekend - we went biking and saw some pretty cool stuff. It was so ref
  3. Hey Mel, what's up? Been a busy week since we talked.
  4. Hey Mel, great to chat with you again! So much has happened since we last spoke - I went to an LGBTQ conference two days ago and it was real
  5. Hey Caroline, that roadtrip this past weekend was insane! We were all freaked when my son got into an accident. We were so lucky he was okay
  6. Hey Mel, what's up? Long time no see! I just contacted my mentor for adoption advice. I'm ready to be a mom and share my love and family. It
  7. Woohoo Melanie! I passed the adoption agency interviews last Friday! I'm so excited and thankful. This is a big move towards my goal of havi
  8. Hey Melanie, great to hear from you. What's been up since we talked?
  9. Hey Mel, great to chat with you again! So much has happened since we last spoke - I went to an LGBTQ conference two days ago and it was real
  10. Hey Melanie! Just wanted to say hi!

### [abstention] dataset_1.json — `What is Jon's favorite style of painting?`

- expected conv indices: 0
- top-10 ranked conv indices: 15, 16, 18, 17, 12, 10, 13, 9, 11, 14
- top-K snippets:
  1. Hey Jon, what's been up? Some pretty cool stuff happened since we talked. I have acquired some new unique pieces for my store.
  2. Hey Jon! Long time no chat! How's the dance studio? Last week was wild, I got noticed by fashion editors and it's been amazing but kinda sca
  3. Hey Gina! We haven't talked in a few days. Been rehearsing hard and working on business plans. It's been stressful, but dancing has kept me 
  4. Hey Jon! Long time no talk! Last week, I built a new website for customers to make orders. It's been a wild ride but I'm loving it. What's u
  5. Hey Gina, thanks for being there for me and believing in me. It means a lot.
  6. Hi! Since we last spoke I am still working on the dance studio and things are looking up!
  7. Hey Gina! We haven't talked in a few days. Been rehearsing hard and working on business plans. It's been stressful, but dancing has kept me 
  8. Hey Gina, thanks for being there for me and believing in me. It means a lot.
  9. Hey Jon! Long time no talk! Last week, I built a new website for customers to make orders. It's been a wild ride but I'm loving it. What's u
  10. Gina, you won't believe it - I got mentored by this amazing business dude yesterday! It was really inspiring and now I'm even more pumped to

### [abstention] dataset_1.json — `What was Jon's favorite dancing memory?`

- expected conv indices: 0
- top-10 ranked conv indices: 13, 14, 16, 10, 17, 18, 11, 12, 15, 3
- top-K snippets:
  1. Gina, you won't believe it - I got mentored by this amazing business dude yesterday! It was really inspiring and now I'm even more pumped to
  2. Hey Gina, hope you're doing great! Still working on my biz. Took a short trip last week to Rome to clear my mind a little.
  3. Gina, you won't believe it - I got mentored by this amazing business dude yesterday! It was really inspiring and now I'm even more pumped to
  4. Hey Jon! Long time no chat! How's the dance studio? Last week was wild, I got noticed by fashion editors and it's been amazing but kinda sca
  5. Gina, you won't believe it - I got mentored by this amazing business dude yesterday! It was really inspiring and now I'm even more pumped to
  6. Hey Jon! Long time no chat! How's the dance studio? Last week was wild, I got noticed by fashion editors and it's been amazing but kinda sca
  7. Hi! Since we last spoke I am still working on the dance studio and things are looking up!
  8. Hey Jon! Long time no talk! Last week, I built a new website for customers to make orders. It's been a wild ride but I'm loving it. What's u
  9. Hey Jon! Long time no chat! How's the dance studio? Last week was wild, I got noticed by fashion editors and it's been amazing but kinda sca
  10. Hi! Since we last spoke I am still working on the dance studio and things are looking up!

### [abstention] dataset_1.json — `What kind of dance piece did Jon's team perform to win first place?`

- expected conv indices: 0
- top-10 ranked conv indices: 13, 16, 10, 17, 14, 3, 12, 18, 11, 15
- top-K snippets:
  1. Gina, you won't believe it - I got mentored by this amazing business dude yesterday! It was really inspiring and now I'm even more pumped to
  2. Hey Jon! Long time no chat! How's the dance studio? Last week was wild, I got noticed by fashion editors and it's been amazing but kinda sca
  3. Hey Jon! Long time no chat! How's the dance studio? Last week was wild, I got noticed by fashion editors and it's been amazing but kinda sca
  4. Gina, you won't believe it - I got mentored by this amazing business dude yesterday! It was really inspiring and now I'm even more pumped to
  5. Hi! Since we last spoke I am still working on the dance studio and things are looking up!
  6. Hey Jon! Long time no talk! Last week, I built a new website for customers to make orders. It's been a wild ride but I'm loving it. What's u
  7. Gina, you won't believe it - I got mentored by this amazing business dude yesterday! It was really inspiring and now I'm even more pumped to
  8. Hey Gina, hope you're doing great! Still working on my biz. Took a short trip last week to Rome to clear my mind a little.
  9. Hey Jon! Long time no talk! Last week, I built a new website for customers to make orders. It's been a wild ride but I'm loving it. What's u
  10. Hey Gina! What's up? How's the store going? I gotta tell you about this thing with my biz.

### [abstention] dataset_1.json — `What did Jon find for his clothing store on 1 February, 2023?`

- expected conv indices: 2
- top-10 ranked conv indices: 15, 17, 16, 14, 18, 13, 1, 12, 11, 9
- top-K snippets:
  1. Hey Jon, what's been up? Some pretty cool stuff happened since we talked. I have acquired some new unique pieces for my store.
  2. Hey Jon! Long time no talk! Last week, I built a new website for customers to make orders. It's been a wild ride but I'm loving it. What's u
  3. Hey Jon! Long time no talk! Last week, I built a new website for customers to make orders. It's been a wild ride but I'm loving it. What's u
  4. Hey Jon! Long time no talk! Last week, I built a new website for customers to make orders. It's been a wild ride but I'm loving it. What's u
  5. Hey Jon! Long time no talk! Last week, I built a new website for customers to make orders. It's been a wild ride but I'm loving it. What's u
  6. Hey Jon, what's been up? Some pretty cool stuff happened since we talked. I have acquired some new unique pieces for my store.
  7. Hey Jon! Long time no talk! Last week, I built a new website for customers to make orders. It's been a wild ride but I'm loving it. What's u
  8. Hey Jon! Long time no talk! Last week, I built a new website for customers to make orders. It's been a wild ride but I'm loving it. What's u
  9. Hey Jon! Long time no chat! How's the dance studio? Last week was wild, I got noticed by fashion editors and it's been amazing but kinda sca
  10. Hey Gina, hope you're doing great! Still working on my biz. Took a short trip last week to Rome to clear my mind a little.

### [abstention] dataset_1.json — `What did Jon design for his store?`

- expected conv indices: 2
- top-10 ranked conv indices: 17, 15, 18, 16, 10, 11, 3, 12, 0, 8
- top-K snippets:
  1. Hey Jon! Long time no talk! Last week, I built a new website for customers to make orders. It's been a wild ride but I'm loving it. What's u
  2. Hey Jon! Long time no talk! Last week, I built a new website for customers to make orders. It's been a wild ride but I'm loving it. What's u
  3. Hey Jon, what's been up? Some pretty cool stuff happened since we talked. I have acquired some new unique pieces for my store.
  4. Hey Jon! Long time no talk! Last week, I built a new website for customers to make orders. It's been a wild ride but I'm loving it. What's u
  5. Hey Jon! Long time no talk! Last week, I built a new website for customers to make orders. It's been a wild ride but I'm loving it. What's u
  6. Hey Jon! Long time no talk! Last week, I built a new website for customers to make orders. It's been a wild ride but I'm loving it. What's u
  7. Hey Gina! We haven't talked in a few days. Been rehearsing hard and working on business plans. It's been stressful, but dancing has kept me 
  8. Hey Jon! Long time no talk! Last week, I built a new website for customers to make orders. It's been a wild ride but I'm loving it. What's u
  9. Hey Jon! Long time no chat! How's the dance studio? Last week was wild, I got noticed by fashion editors and it's been amazing but kinda sca
  10. Hey Jon! Long time no talk! Last week, I built a new website for customers to make orders. It's been a wild ride but I'm loving it. What's u

### [abstention] dataset_1.json — `What did Jon want his customers to feel in her store?`

- expected conv indices: 2
- top-10 ranked conv indices: 15, 17, 16, 1, 11, 0, 5, 18, 10, 3
- top-K snippets:
  1. Hey Jon, what's been up? Some pretty cool stuff happened since we talked. I have acquired some new unique pieces for my store.
  2. Hey Jon! Long time no talk! Last week, I built a new website for customers to make orders. It's been a wild ride but I'm loving it. What's u
  3. Hey Jon! Long time no talk! Last week, I built a new website for customers to make orders. It's been a wild ride but I'm loving it. What's u
  4. Hey Jon! Long time no talk! Last week, I built a new website for customers to make orders. It's been a wild ride but I'm loving it. What's u
  5. Hey Jon! Long time no chat! How's the dance studio? Last week was wild, I got noticed by fashion editors and it's been amazing but kinda sca
  6. Hey Jon! Long time no see! Things have been hectic lately. I just launched an ad campaign for my clothing store in hopes of growing the busi
  7. Hey Jon! Long time no chat! How's the dance studio? Last week was wild, I got noticed by fashion editors and it's been amazing but kinda sca
  8. Hey Jon! Long time no talk! Last week, I built a new website for customers to make orders. It's been a wild ride but I'm loving it. What's u
  9. Hey Jon! Long time no talk! A lot's happened - I just got accepted for a fashion internship!
  10. Hey Jon! Good to see you. What's up? Anything new?

### [abstention] dataset_1.json — `What does Jon's tattoo symbolize?`

- expected conv indices: 4
- top-10 ranked conv indices: 13, 17, 0, 11, 16, 10, 3, 1, 6, 14
- top-K snippets:
  1. Gina, you won't believe it - I got mentored by this amazing business dude yesterday! It was really inspiring and now I'm even more pumped to
  2. Hey Jon! Long time no talk! Last week, I built a new website for customers to make orders. It's been a wild ride but I'm loving it. What's u
  3. Hey Jon! Good to see you. What's up? Anything new?
  4. Hey Jon! Long time no talk! A lot's happened - I just got accepted for a fashion internship!
  5. Hey Jon! Long time no chat! How's the dance studio? Last week was wild, I got noticed by fashion editors and it's been amazing but kinda sca
  6. Hi! Since we last spoke I am still working on the dance studio and things are looking up!
  7. Hey Jon! Long time no talk! Last week, I built a new website for customers to make orders. It's been a wild ride but I'm loving it. What's u
  8. Hey Jon! Long time no talk! Last week, I built a new website for customers to make orders. It's been a wild ride but I'm loving it. What's u
  9. Gina, you won't believe it - I got mentored by this amazing business dude yesterday! It was really inspiring and now I'm even more pumped to
  10. Hey Jon! Good to see you. What's up? Anything new?

### [abstention] dataset_1.json — `Why did Gina shut down her bank account?`

- expected conv indices: 7
- top-10 ranked conv indices: 10, 16, 17, 15, 5, 8, 4, 12, 1, 0
- top-K snippets:
  1. Hi! Since we last spoke I am still working on the dance studio and things are looking up!
  2. Hey Jon! Long time no chat! How's the dance studio? Last week was wild, I got noticed by fashion editors and it's been amazing but kinda sca
  3. Hey Jon! Long time no talk! Last week, I built a new website for customers to make orders. It's been a wild ride but I'm loving it. What's u
  4. Hi! Since we last spoke I am still working on the dance studio and things are looking up!
  5. Hey Jon, what's been up? Some pretty cool stuff happened since we talked. I have acquired some new unique pieces for my store.
  6. Hi Gina! Been hectic for me lately. Started hitting the gym last week to stay on track with the venture. Gotta figure out how to balance it 
  7. Hey Gina! I'm turning my loves of dance into a business. I'm sunk tons of time into the studio lately, and look at my students - they're alr
  8. Hey Gina! I'm turning my loves of dance into a business. I'm sunk tons of time into the studio lately, and look at my students - they're alr
  9. Hey Jon! Long time no talk! Last week, I built a new website for customers to make orders. It's been a wild ride but I'm loving it. What's u

### [abstention] dataset_1.json — `Why did Jon combine his clothing business with dance?`

- expected conv indices: 7
- top-10 ranked conv indices: 17, 3, 10, 13, 8, 16, 18, 14, 2, 0
- top-K snippets:
  1. Hey Jon! Long time no talk! Last week, I built a new website for customers to make orders. It's been a wild ride but I'm loving it. What's u
  2. Hey Gina! What's up? How's the store going? I gotta tell you about this thing with my biz.
  3. Hey Jon! Long time no talk! Last week, I built a new website for customers to make orders. It's been a wild ride but I'm loving it. What's u
  4. Hey Jon! Long time no talk! Last week, I built a new website for customers to make orders. It's been a wild ride but I'm loving it. What's u
  5. Hi! Since we last spoke I am still working on the dance studio and things are looking up!
  6. Hi! Since we last spoke I am still working on the dance studio and things are looking up!
  7. Hey Jon! Long time no talk! Last week, I built a new website for customers to make orders. It's been a wild ride but I'm loving it. What's u
  8. Hi! Since we last spoke I am still working on the dance studio and things are looking up!
  9. Hey Jon! Long time no talk! Last week, I built a new website for customers to make orders. It's been a wild ride but I'm loving it. What's u

### [abstention] dataset_1.json — `Where is Gina's HR internship?`

- expected conv indices: 11
- top-10 ranked conv indices: 10, 12, 17, 16, 13, 2, 1, 9, 4, 14
- top-K snippets:
  1. Hi! Since we last spoke I am still working on the dance studio and things are looking up!
  2. Hey Gina, thanks for being there for me and believing in me. It means a lot.
  3. Hey Gina, thanks for being there for me and believing in me. It means a lot.
  4. Hey Gina, thanks for being there for me and believing in me. It means a lot.
  5. Hey Jon! Long time no talk! Last week, I built a new website for customers to make orders. It's been a wild ride but I'm loving it. What's u
  6. Hey Jon! Long time no chat! How's the dance studio? Last week was wild, I got noticed by fashion editors and it's been amazing but kinda sca
  7. Hey Jon! Long time no talk! Last week, I built a new website for customers to make orders. It's been a wild ride but I'm loving it. What's u
  8. Gina, you won't believe it - I got mentored by this amazing business dude yesterday! It was really inspiring and now I'm even more pumped to
  9. Hi! Since we last spoke I am still working on the dance studio and things are looking up!
  10. Hey Gina, hope you're doing ok! Still following my passion for dance. It's been bumpy, but I'm determined to make it work. I'm still searchi

### [abstention] dataset_1.json — `What book is Gina currently reading?`

- expected conv indices: 11
- top-10 ranked conv indices: 16, 10, 17, 3, 18, 9, 6, 1, 13, 2
- top-K snippets:
  1. Hey Jon! Long time no chat! How's the dance studio? Last week was wild, I got noticed by fashion editors and it's been amazing but kinda sca
  2. Hi! Since we last spoke I am still working on the dance studio and things are looking up!
  3. Hey Jon! Long time no talk! Last week, I built a new website for customers to make orders. It's been a wild ride but I'm loving it. What's u
  4. Hi! Since we last spoke I am still working on the dance studio and things are looking up!
  5. Hey Jon! Long time no talk! Last week, I built a new website for customers to make orders. It's been a wild ride but I'm loving it. What's u

### [abstention] dataset_1.json — `What did Jon take a trip to Barcelona for?`

- expected conv indices: 14
- top-10 ranked conv indices: 17, 5, 16, 11, 10, 0, 13, 6, 4, 2
- top-K snippets:
  1. Hey Jon! Long time no talk! Last week, I built a new website for customers to make orders. It's been a wild ride but I'm loving it. What's u
  2. Hi Gina! Been hectic for me lately. Started hitting the gym last week to stay on track with the venture. Gotta figure out how to balance it 
  3. Hey Jon! Long time no talk! Last week, I built a new website for customers to make orders. It's been a wild ride but I'm loving it. What's u
  4. Hey Jon! Long time no chat! How's the dance studio? Last week was wild, I got noticed by fashion editors and it's been amazing but kinda sca
  5. Hey Jon! Long time no talk! Last week, I built a new website for customers to make orders. It's been a wild ride but I'm loving it. What's u

### [abstention] dataset_1.json — `What did Jon make a limited edition line of?`

- expected conv indices: 15
- top-10 ranked conv indices: 17, 16, 10, 11, 8, 0, 3, 13, 4, 18
- top-K snippets:
  1. Hey Jon! Long time no talk! Last week, I built a new website for customers to make orders. It's been a wild ride but I'm loving it. What's u
  2. Hey Jon! Long time no talk! Last week, I built a new website for customers to make orders. It's been a wild ride but I'm loving it. What's u
  3. Hey Jon! Long time no chat! How's the dance studio? Last week was wild, I got noticed by fashion editors and it's been amazing but kinda sca
  4. Hey Jon! Long time no talk! Last week, I built a new website for customers to make orders. It's been a wild ride but I'm loving it. What's u
  5. Hey Jon! Long time no chat! How's the dance studio? Last week was wild, I got noticed by fashion editors and it's been amazing but kinda sca
  6. Hi! Since we last spoke I am still working on the dance studio and things are looking up!

### [abstention] dataset_2.json — `What did Maria donate to a luxury store in December 2023?`

- expected conv indices: 1
- top-10 ranked conv indices: 15, 3, 26, 19, 28, 6, 4, 0, 20, 12
- top-K snippets:
  1. Hey Maria, I've been busy doing the petition I started - it's tricky but it's been cool getting back in touch with my buddies and gaining su
  2. Hey John, great news - I'm now friends with one of my fellow volunteers! We both love helping others. How have you been since we last chatte
  3. Hey Maria, hope you're doing OK. I had to share something cool with you - I asked family and friends to join the virtual support group I am 
  4. Hey John, long time no talk! A lot has happened since then. I've been struggling, but I'm focusing on the positive and relying on my friends
  5. Hey John, what's been going on? I just wanted to check in. Last week was wild - I volunteered at the homeless shelter and they gave me a med
  6. Hey John, how's it going? Just wanted to give you the heads up on what's been happening lately- I took a creative writing class recently, an
  7. Hey Maria, hope you're doing OK. I had to share something cool with you - I asked family and friends to join the virtual support group I am 
  8. Hey Maria, since we last spoke I went to that community mtg. It was really interesting hearing everyone's worries and how it affects our are
  9. Hey John! Long time no see! What's up?
  10. Hey John, long time no see! Sorry I didn't get back to you sooner... So much has happened! Check out these kids I met at the shelter!

### [abstention] dataset_2.json — `Who inspired John to start volunteering?`

- expected conv indices: 4
- top-10 ranked conv indices: 20, 5, 6, 27, 11, 25, 31, 26, 8, 14
- top-K snippets:
  1. Hey John, long time no see! Sorry I didn't get back to you sooner... So much has happened! Check out these kids I met at the shelter!
  2. Hey John! Long time no talk. I just wanted to let you know I challenged myself last Friday and did a charity event. It was great! I truly fe
  3. Hey John, how's it going? Just wanted to give you the heads up on what's been happening lately- I took a creative writing class recently, an
  4. Hey Maria, great chatting with you again! Crazy thing happened since we last talked. I lost my job at the mechanical engineering company. Th
  5. Hey Maria, hope you're doing okay. Since we chatted last, I've been blogging about politics and the government. It's been a really satisfyin
  6. Hey John, I'm doing ok - hope you are too. Some interesting stuff has been going on; last week I dropped off that stuff I baked at the homel
  7. Hey Maria! Guess what? I'm now part of the fire-fighting brigade. I'm super excited to be involved and help out my community!
  8. Hey Maria, hope you're doing OK. I had to share something cool with you - I asked family and friends to join the virtual support group I am 
  9. Hey John, how's it going? Just wanted to give you the heads up on what's been happening lately- I took a creative writing class recently, an
  10. Hey John, long time no see! I've been taking a poetry class lately to help me put my feelings into words. It's been a rough ride, but it's b

### [abstention] dataset_2.json — `Why did Maria start blogging about politics and policies?`

- expected conv indices: 11
- top-10 ranked conv indices: 

### [abstention] dataset_2.json — `What was the focus of John's recent travel and photography blog?`

- expected conv indices: 11
- top-10 ranked conv indices: 

### [abstention] dataset_2.json — `How often does Maria work out with her family?`

- expected conv indices: 12
- top-10 ranked conv indices: 

### [abstention] dataset_2.json — `How has John's artistic skills improved since starting boot camps with his family?`

- expected conv indices: 12
- top-10 ranked conv indices: 0, 1, 2, 3
- top-K snippets:
  1. Hey John! Long time no see! What's up?
  2. Hey John! Long time no see! What's up?
  3. Hey John, been a few days since we chatted. In the meantime, I donated my old car to a homeless shelter I volunteer at yesterday. How's the 
  4. Hey John! Long time no see! What's up?
  5. Hey John! Long time no see! What's up?
  6. Hey John, been a few days since we chatted. In the meantime, I donated my old car to a homeless shelter I volunteer at yesterday. How's the 
  7. Hey John! Long time no see! What's up?
  8. Hey John, been a few days since we chatted. In the meantime, I donated my old car to a homeless shelter I volunteer at yesterday. How's the 
  9. Hey John, been a few days since we chatted. In the meantime, I donated my old car to a homeless shelter I volunteer at yesterday. How's the 
  10. Hey Maria, great to chat again! I joined a service-focused online group last week and it's been an emotional ride. Everyone there is incredi

### [abstention] dataset_2.json — `What kind of food did Maria have on her dinner spread with her father?`

- expected conv indices: 12
- top-10 ranked conv indices: 2, 0, 1, 3
- top-K snippets:
  1. Hey Maria, great to chat again! I joined a service-focused online group last week and it's been an emotional ride. Everyone there is incredi
  2. Hey Maria, great to chat again! I joined a service-focused online group last week and it's been an emotional ride. Everyone there is incredi
  3. Hey John! Long time no see! What's up?
  4. Hey John, been a few days since we chatted. In the meantime, I donated my old car to a homeless shelter I volunteer at yesterday. How's the 
  5. Hey John! Long time no see! What's up?
  6. Hey John! Long time no see! What's up?
  7. Hey John, been a few days since we chatted. In the meantime, I donated my old car to a homeless shelter I volunteer at yesterday. How's the 
  8. Hey Maria, great to chat again! I joined a service-focused online group last week and it's been an emotional ride. Everyone there is incredi
  9. Hey Maria, great to chat again! I joined a service-focused online group last week and it's been an emotional ride. Everyone there is incredi
  10. Hey John, been a few days since we chatted. In the meantime, I donated my old car to a homeless shelter I volunteer at yesterday. How's the 

### [abstention] dataset_2.json — `What did John do to feel closer to a community and his faith?`

- expected conv indices: 13
- top-10 ranked conv indices: 0, 3, 1, 2
- top-K snippets:
  1. Hey John! Long time no see! What's up?
  2. Hey John, great news - I'm now friends with one of my fellow volunteers! We both love helping others. How have you been since we last chatte
  3. Hey John, been a few days since we chatted. In the meantime, I donated my old car to a homeless shelter I volunteer at yesterday. How's the 
  4. Hey John! Long time no see! What's up?
  5. Hey John, been a few days since we chatted. In the meantime, I donated my old car to a homeless shelter I volunteer at yesterday. How's the 
  6. Hey John! Long time no see! What's up?
  7. Hey John! Long time no see! What's up?
  8. Hey John, been a few days since we chatted. In the meantime, I donated my old car to a homeless shelter I volunteer at yesterday. How's the 
  9. Hey John, been a few days since we chatted. In the meantime, I donated my old car to a homeless shelter I volunteer at yesterday. How's the 
  10. Hey John, great news - I'm now friends with one of my fellow volunteers! We both love helping others. How have you been since we last chatte

### [abstention] dataset_2.json — `Why did John join a nearby church recently?`

- expected conv indices: 13
- top-10 ranked conv indices: 0, 4, 5, 1, 3, 2, 6
- top-K snippets:
  1. Hey John! Long time no see! What's up?
  2. Hey Maria, since we last spoke I went to that community mtg. It was really interesting hearing everyone's worries and how it affects our are
  3. Hey John! Long time no talk. I just wanted to let you know I challenged myself last Friday and did a charity event. It was great! I truly fe
  4. Hey John! Long time no see! What's up?
  5. Hey John! Long time no see! What's up?
  6. Hey John! Long time no see! What's up?
  7. Hey John! Long time no see! What's up?
  8. Hey John! Long time no see! What's up?
  9. Hey John, been a few days since we chatted. In the meantime, I donated my old car to a homeless shelter I volunteer at yesterday. How's the 
  10. Hey John, great news - I'm now friends with one of my fellow volunteers! We both love helping others. How have you been since we last chatte

### [abstention] dataset_2.json — `How long was Max a part of Maria's family?`

- expected conv indices: 16
- top-10 ranked conv indices: 6, 4, 0, 2, 5, 1, 3
- top-K snippets:
  1. Hey John, how's it going? Just wanted to give you the heads up on what's been happening lately- I took a creative writing class recently, an
  2. Hey Maria, since we last spoke I went to that community mtg. It was really interesting hearing everyone's worries and how it affects our are
  3. Hey Maria, since we last spoke I went to that community mtg. It was really interesting hearing everyone's worries and how it affects our are
  4. Hey Maria, since we last spoke I went to that community mtg. It was really interesting hearing everyone's worries and how it affects our are
  5. Hey John! Long time no see! What's up?
  6. Hey John! Long time no see! What's up?
  7. Hey Maria, great to chat again! I joined a service-focused online group last week and it's been an emotional ride. Everyone there is incredi
  8. Hey Maria, since we last spoke I went to that community mtg. It was really interesting hearing everyone's worries and how it affects our are
  9. Hey Maria, since we last spoke I went to that community mtg. It was really interesting hearing everyone's worries and how it affects our are
  10. Hey Maria, since we last spoke I went to that community mtg. It was really interesting hearing everyone's worries and how it affects our are

### [abstention] dataset_2.json — `How does Maria plan to honor the memories of her beloved pet?`

- expected conv indices: 16
- top-10 ranked conv indices: 4, 1, 5, 0, 2, 3
- top-K snippets:
  1. Hey Maria, since we last spoke I went to that community mtg. It was really interesting hearing everyone's worries and how it affects our are
  2. Hey John, been a few days since we chatted. In the meantime, I donated my old car to a homeless shelter I volunteer at yesterday. How's the 
  3. Hey John! Long time no talk. I just wanted to let you know I challenged myself last Friday and did a charity event. It was great! I truly fe
  4. Hey John, been a few days since we chatted. In the meantime, I donated my old car to a homeless shelter I volunteer at yesterday. How's the 
  5. Hey John! Long time no see! What's up?
  6. Hey John! Long time no see! What's up?
  7. Hey Maria, since we last spoke I went to that community mtg. It was really interesting hearing everyone's worries and how it affects our are
  8. Hey Maria, since we last spoke I went to that community mtg. It was really interesting hearing everyone's worries and how it affects our are

### [abstention] dataset_2.json — `What important values does Maria want to teach her kids through adopting a rescue dog?`

- expected conv indices: 16
- top-10 ranked conv indices: 5, 4, 6, 8, 3, 7, 1, 2
- top-K snippets:
  1. Hey John! Long time no talk. I just wanted to let you know I challenged myself last Friday and did a charity event. It was great! I truly fe
  2. Hey Maria, since we last spoke I went to that community mtg. It was really interesting hearing everyone's worries and how it affects our are
  3. Hey John! Long time no talk. I just wanted to let you know I challenged myself last Friday and did a charity event. It was great! I truly fe
  4. Hey John! Long time no talk. I just wanted to let you know I challenged myself last Friday and did a charity event. It was great! I truly fe
  5. Hey John, how's it going? Just wanted to give you the heads up on what's been happening lately- I took a creative writing class recently, an
  6. Hey Maria, since we last spoke I went to that community mtg. It was really interesting hearing everyone's worries and how it affects our are

### [abstention] dataset_2.json — `What did Maria say it was like being at the desert in Oregon?`

- expected conv indices: 17
- top-10 ranked conv indices: 1, 8, 4, 5, 0, 7, 2, 6, 3
- top-K snippets:
  1. Hey John, been a few days since we chatted. In the meantime, I donated my old car to a homeless shelter I volunteer at yesterday. How's the 
  2. Hey John, long time no see! I've been taking a poetry class lately to help me put my feelings into words. It's been a rough ride, but it's b
  3. Hey John, long time no see! I've been taking a poetry class lately to help me put my feelings into words. It's been a rough ride, but it's b
  4. Hey Maria, since we last spoke I went to that community mtg. It was really interesting hearing everyone's worries and how it affects our are
  5. Hey John, been a few days since we chatted. In the meantime, I donated my old car to a homeless shelter I volunteer at yesterday. How's the 
  6. Hey John! Long time no talk. I just wanted to let you know I challenged myself last Friday and did a charity event. It was great! I truly fe
  7. Hey John! Long time no see! What's up?
  8. Hey John! Long time no see! What's up?
  9. Hey Maria, since we last spoke I went to that community mtg. It was really interesting hearing everyone's worries and how it affects our are
  10. Hey John, been a few days since we chatted. In the meantime, I donated my old car to a homeless shelter I volunteer at yesterday. How's the 

### [abstention] dataset_2.json — `What does John say she feels when doing upside-down yoga poses?`

- expected conv indices: 17
- top-10 ranked conv indices: 4, 7, 6, 8, 5, 3, 0, 2, 1
- top-K snippets:
  1. Hey Maria, since we last spoke I went to that community mtg. It was really interesting hearing everyone's worries and how it affects our are
  2. Hey John, I haven't talked to you in a while. Last week, my grandma passed away and it's been really hard. I'm trying to stay positive, but 
  3. Hey John, how's it going? Just wanted to give you the heads up on what's been happening lately- I took a creative writing class recently, an
  4. Hey John, long time no see! I've been taking a poetry class lately to help me put my feelings into words. It's been a rough ride, but it's b
  5. Hey John! Long time no talk. I just wanted to let you know I challenged myself last Friday and did a charity event. It was great! I truly fe
  6. Hey Maria, since we last spoke I went to that community mtg. It was really interesting hearing everyone's worries and how it affects our are
  7. Hey John, great news - I'm now friends with one of my fellow volunteers! We both love helping others. How have you been since we last chatte
  8. Hey John, long time no see! I've been taking a poetry class lately to help me put my feelings into words. It's been a rough ride, but it's b
  9. Hey John, I haven't talked to you in a while. Last week, my grandma passed away and it's been really hard. I'm trying to stay positive, but 

### [abstention] dataset_2.json — `What did Maria recently get promoted to?`

- expected conv indices: 18
- top-10 ranked conv indices: 4, 8, 7, 0, 5, 2, 6, 3, 1
- top-K snippets:
  1. Hey Maria, since we last spoke I went to that community mtg. It was really interesting hearing everyone's worries and how it affects our are
  2. Hey Maria, since we last spoke I went to that community mtg. It was really interesting hearing everyone's worries and how it affects our are
  3. Hey John, long time no see! I've been taking a poetry class lately to help me put my feelings into words. It's been a rough ride, but it's b
  4. Hey John, I haven't talked to you in a while. Last week, my grandma passed away and it's been really hard. I'm trying to stay positive, but 
  5. Hey John! Long time no see! What's up?
  6. Hey John, I haven't talked to you in a while. Last week, my grandma passed away and it's been really hard. I'm trying to stay positive, but 
  7. Hey John, I haven't talked to you in a while. Last week, my grandma passed away and it's been really hard. I'm trying to stay positive, but 
  8. Hey John! Long time no see! What's up?
  9. Hey Maria, since we last spoke I went to that community mtg. It was really interesting hearing everyone's worries and how it affects our are
  10. Hey John! Long time no see! What's up?

### [abstention] dataset_2.json — `What was one of the biggest challenges Maria faced in her journey to becoming assistant manager?`

- expected conv indices: 18
- top-10 ranked conv indices: 1, 4, 7, 8, 3, 5, 2, 6
- top-K snippets:
  1. Hey John, been a few days since we chatted. In the meantime, I donated my old car to a homeless shelter I volunteer at yesterday. How's the 
  2. Hey Maria, since we last spoke I went to that community mtg. It was really interesting hearing everyone's worries and how it affects our are
  3. Hey John, I haven't talked to you in a while. Last week, my grandma passed away and it's been really hard. I'm trying to stay positive, but 
  4. Hey John, long time no see! I've been taking a poetry class lately to help me put my feelings into words. It's been a rough ride, but it's b
  5. Hey John, great news - I'm now friends with one of my fellow volunteers! We both love helping others. How have you been since we last chatte
  6. Hey John! Long time no talk. I just wanted to let you know I challenged myself last Friday and did a charity event. It was great! I truly fe
  7. Hey John! Long time no talk. I just wanted to let you know I challenged myself last Friday and did a charity event. It was great! I truly fe
  8. Hey Maria, great to chat again! I joined a service-focused online group last week and it's been an emotional ride. Everyone there is incredi
  9. Hey Maria, since we last spoke I went to that community mtg. It was really interesting hearing everyone's worries and how it affects our are

### [abstention] dataset_2.json — `Why did John need to help his cousin find a new place to live?`

- expected conv indices: 20
- top-10 ranked conv indices: 1, 0, 11, 5, 6, 10, 8, 7, 3, 9
- top-K snippets:
  1. Hey John, been a few days since we chatted. In the meantime, I donated my old car to a homeless shelter I volunteer at yesterday. How's the 
  2. Hey John! Long time no see! What's up?
  3. Hey Maria, hope you're doing okay. Since we chatted last, I've been blogging about politics and the government. It's been a really satisfyin
  4. Hey John! Long time no talk. I just wanted to let you know I challenged myself last Friday and did a charity event. It was great! I truly fe
  5. Hey John, how's it going? Just wanted to give you the heads up on what's been happening lately- I took a creative writing class recently, an
  6. Hey John! Long time no see! What's up?
  7. Hey Maria, haven't talked for a few days. Had a wild week, my car broke down last Fri on my way to work. Trying to get it fixed but it's tou
  8. Hey John, how's it going? Just wanted to give you the heads up on what's been happening lately- I took a creative writing class recently, an
  9. Hey John, long time no see! I've been taking a poetry class lately to help me put my feelings into words. It's been a rough ride, but it's b
  10. Hey John, how's it going? Just wanted to give you the heads up on what's been happening lately- I took a creative writing class recently, an

### [abstention] dataset_2.json — `What event did Maria participate in to show support for veterans' rights?`

- expected conv indices: 20
- top-10 ranked conv indices: 1, 3, 5, 10, 4, 2, 11, 8, 7, 6
- top-K snippets:
  1. Hey John, been a few days since we chatted. In the meantime, I donated my old car to a homeless shelter I volunteer at yesterday. How's the 
  2. Hey John, great news - I'm now friends with one of my fellow volunteers! We both love helping others. How have you been since we last chatte
  3. Hey John! Long time no talk. I just wanted to let you know I challenged myself last Friday and did a charity event. It was great! I truly fe
  4. Hey John! Long time no talk. I just wanted to let you know I challenged myself last Friday and did a charity event. It was great! I truly fe
  5. Hey John, been a few days since we chatted. In the meantime, I donated my old car to a homeless shelter I volunteer at yesterday. How's the 
  6. Hey Maria, haven't talked for a few days. Had a wild week, my car broke down last Fri on my way to work. Trying to get it fixed but it's tou
  7. Hey Maria, since we last spoke I went to that community mtg. It was really interesting hearing everyone's worries and how it affects our are
  8. Hey Maria, haven't talked for a few days. Had a wild week, my car broke down last Fri on my way to work. Trying to get it fixed but it's tou
  9. Hey Maria, great to chat again! I joined a service-focused online group last week and it's been an emotional ride. Everyone there is incredi
  10. Hey Maria, hope you're doing okay. Since we chatted last, I've been blogging about politics and the government. It's been a really satisfyin

### [abstention] dataset_2.json — `How did the drought impact the homes in John's old area?`

- expected conv indices: 22
- top-10 ranked conv indices: 0, 6, 1, 7, 9, 3, 8, 10, 12, 5
- top-K snippets:
  1. Hey John! Long time no see! What's up?
  2. Hey John! Long time no see! What's up?
  3. Hey John, how's it going? Just wanted to give you the heads up on what's been happening lately- I took a creative writing class recently, an
  4. Hey John, been a few days since we chatted. In the meantime, I donated my old car to a homeless shelter I volunteer at yesterday. How's the 
  5. Hey John! Long time no see! What's up?
  6. Hey John, I haven't talked to you in a while. Last week, my grandma passed away and it's been really hard. I'm trying to stay positive, but 
  7. Hey Maria, I'm so excited to tell you I started a weekend yoga class with a colleague - it's awesome! I feel great, both mentally and physic
  8. Hey John, been a few days since we chatted. In the meantime, I donated my old car to a homeless shelter I volunteer at yesterday. How's the 
  9. Hey John, been a few days since we chatted. In the meantime, I donated my old car to a homeless shelter I volunteer at yesterday. How's the 

### [abstention] dataset_2.json — `What does John criticize about the veteran's hospital visit?`

- expected conv indices: 23
- top-10 ranked conv indices: 1, 5, 14, 13, 6, 11, 4, 2, 0, 10
- top-K snippets:
  1. Hey John, been a few days since we chatted. In the meantime, I donated my old car to a homeless shelter I volunteer at yesterday. How's the 
  2. Hey John! Long time no talk. I just wanted to let you know I challenged myself last Friday and did a charity event. It was great! I truly fe
  3. Hey Maria, how's it going? Been real busy tackling a project to support military veterans. Trying to get a petition going, it's pretty rewar
  4. Hey Maria, great to chat again! A lot has happened since we last spoke. Last week, I decided to run for office again - even though I haven't
  5. Hey John, how's it going? Just wanted to give you the heads up on what's been happening lately- I took a creative writing class recently, an
  6. Hey Maria, great to chat again! A lot has happened since we last spoke. Last week, I decided to run for office again - even though I haven't
  7. Hey John, how's it going? Just wanted to give you the heads up on what's been happening lately- I took a creative writing class recently, an
  8. Hey Maria, hope you're doing okay. Since we chatted last, I've been blogging about politics and the government. It's been a really satisfyin
  9. Hey Maria, since we last spoke I went to that community mtg. It was really interesting hearing everyone's worries and how it affects our are
  10. Hey Maria, great to chat again! I joined a service-focused online group last week and it's been an emotional ride. Everyone there is incredi

### [abstention] dataset_2.json — `What did John take away from visiting the orphanage?`

- expected conv indices: 23
- top-10 ranked conv indices: 0, 8, 14, 5, 13, 6, 3, 10, 7, 1
- top-K snippets:
  1. Hey John! Long time no see! What's up?
  2. Hey John! Long time no see! What's up?
  3. Hey John, long time no see! I've been taking a poetry class lately to help me put my feelings into words. It's been a rough ride, but it's b
  4. Hey Maria, how's it going? Been real busy tackling a project to support military veterans. Trying to get a petition going, it's pretty rewar
  5. Hey Maria, how's it going? Been real busy tackling a project to support military veterans. Trying to get a petition going, it's pretty rewar
  6. Hey John! Long time no talk. I just wanted to let you know I challenged myself last Friday and did a charity event. It was great! I truly fe
  7. Hey Maria, great to chat again! A lot has happened since we last spoke. Last week, I decided to run for office again - even though I haven't
  8. Hey John, how's it going? Just wanted to give you the heads up on what's been happening lately- I took a creative writing class recently, an
  9. Hey Maria, great to chat again! A lot has happened since we last spoke. Last week, I decided to run for office again - even though I haven't

### [abstention] dataset_2.json — `Why did Maria feel inspired to join the military after the visit to the hospital?`

- expected conv indices: 23
- top-10 ranked conv indices: 4, 13, 6, 16, 17, 15, 10, 14, 5, 1
- top-K snippets:
  1. Hey Maria, since we last spoke I went to that community mtg. It was really interesting hearing everyone's worries and how it affects our are
  2. Hey Maria, since we last spoke I went to that community mtg. It was really interesting hearing everyone's worries and how it affects our are
  3. Hey Maria, since we last spoke I went to that community mtg. It was really interesting hearing everyone's worries and how it affects our are
  4. Hey Maria, since we last spoke I went to that community mtg. It was really interesting hearing everyone's worries and how it affects our are
  5. Hey Maria, great to chat again! A lot has happened since we last spoke. Last week, I decided to run for office again - even though I haven't
  6. Hey John, how's it going? Just wanted to give you the heads up on what's been happening lately- I took a creative writing class recently, an
  7. Hey Maria, long time no talk! Life's been pretty wild lately. The toughest thing to deal with is that we had to say goodbye to Max. He was s
  8. Hey Maria, long time no talk! Life's been pretty wild lately. The toughest thing to deal with is that we had to say goodbye to Max. He was s
  9. Hey John, how're you doing? I'm sorry about Max. Losing a pet is tough. Some friends from church and I went camping last weekend - it was a 

### [abstention] dataset_2.json — `How did Maria describe her kids' reaction at the military memorial?`

- expected conv indices: 26
- top-10 ranked conv indices: 18, 6, 4, 10, 0, 16, 5, 1, 12, 8
- top-K snippets:
  1. Hey John, been good since we talked? I got some great news to share - I joined a gym last week! It's been super positive - I'm sticking to m
  2. Hey John, been good since we talked? I got some great news to share - I joined a gym last week! It's been super positive - I'm sticking to m
  3. Hey John, been good since we talked? I got some great news to share - I joined a gym last week! It's been super positive - I'm sticking to m
  4. Hey John, been good since we talked? I got some great news to share - I joined a gym last week! It's been super positive - I'm sticking to m
  5. Hey John, how's it going? Just wanted to give you the heads up on what's been happening lately- I took a creative writing class recently, an
  6. Hey Maria, since we last spoke I went to that community mtg. It was really interesting hearing everyone's worries and how it affects our are
  7. Hey John, been good since we talked? I got some great news to share - I joined a gym last week! It's been super positive - I'm sticking to m
  8. Hey John, been good since we talked? I got some great news to share - I joined a gym last week! It's been super positive - I'm sticking to m
  9. Hey John, been good since we talked? I got some great news to share - I joined a gym last week! It's been super positive - I'm sticking to m
  10. Hey Maria, haven't talked for a few days. Had a wild week, my car broke down last Fri on my way to work. Trying to get it fixed but it's tou

### [abstention] dataset_2.json — `Why does Maria think it's important for younger generations to visit art galleries?`

- expected conv indices: 26
- top-10 ranked conv indices: 10, 20, 7, 19, 17, 18, 1, 9, 21, 16
- top-K snippets:
  1. Hey Maria, haven't talked for a few days. Had a wild week, my car broke down last Fri on my way to work. Trying to get it fixed but it's tou
  2. Hey John, long time no see! Sorry I didn't get back to you sooner... So much has happened! Check out these kids I met at the shelter!
  3. Hey John, I haven't talked to you in a while. Last week, my grandma passed away and it's been really hard. I'm trying to stay positive, but 
  4. Hey John, I haven't talked to you in a while. Last week, my grandma passed away and it's been really hard. I'm trying to stay positive, but 
  5. Hey John, I haven't talked to you in a while. Last week, my grandma passed away and it's been really hard. I'm trying to stay positive, but 
  6. Hey John, long time no see! Sorry I didn't get back to you sooner... So much has happened! Check out these kids I met at the shelter!
  7. Hey Maria, haven't talked for a few days. Had a wild week, my car broke down last Fri on my way to work. Trying to get it fixed but it's tou
  8. Hey John, long time no talk! A lot has happened since then. I've been struggling, but I'm focusing on the positive and relying on my friends
  9. Hey John, long time no talk! A lot has happened since then. I've been struggling, but I'm focusing on the positive and relying on my friends
  10. Hey John, long time no see! Sorry I didn't get back to you sooner... So much has happened! Check out these kids I met at the shelter!

### [abstention] dataset_2.json — `What happened to Maria's job in August 2023?`

- expected conv indices: 27
- top-10 ranked conv indices: 15, 19, 9, 4, 0, 20, 18, 23, 17, 11
- top-K snippets:
  1. Hey Maria, I've been busy doing the petition I started - it's tricky but it's been cool getting back in touch with my buddies and gaining su
  2. Hey Maria, I've been busy doing the petition I started - it's tricky but it's been cool getting back in touch with my buddies and gaining su
  3. Hey John, long time no talk! A lot has happened since then. I've been struggling, but I'm focusing on the positive and relying on my friends
  4. Hey Maria, I'm so excited to tell you I started a weekend yoga class with a colleague - it's awesome! I feel great, both mentally and physic
  5. Hey John, long time no talk! A lot has happened since then. I've been struggling, but I'm focusing on the positive and relying on my friends
  6. Hey Maria, since we last spoke I went to that community mtg. It was really interesting hearing everyone's worries and how it affects our are
  7. Hey John! Long time no see! What's up?
  8. Hey John, long time no see! Sorry I didn't get back to you sooner... So much has happened! Check out these kids I met at the shelter!
  9. Hey John, been good since we talked? I got some great news to share - I joined a gym last week! It's been super positive - I'm sticking to m
  10. Hey Maria, I've been busy doing the petition I started - it's tricky but it's been cool getting back in touch with my buddies and gaining su

### [abstention] dataset_2.json — `What cause did the 5K charity run organized by Maria support?`

- expected conv indices: 28
- top-10 ranked conv indices: 15, 1, 18, 14, 13, 5, 3, 16, 23, 17
- top-K snippets:
  1. Hey Maria, I've been busy doing the petition I started - it's tricky but it's been cool getting back in touch with my buddies and gaining su
  2. Hey John, been a few days since we chatted. In the meantime, I donated my old car to a homeless shelter I volunteer at yesterday. How's the 
  3. Hey John, been good since we talked? I got some great news to share - I joined a gym last week! It's been super positive - I'm sticking to m
  4. Hey Maria, how's it going? Been real busy tackling a project to support military veterans. Trying to get a petition going, it's pretty rewar
  5. Hey Maria, great to chat again! A lot has happened since we last spoke. Last week, I decided to run for office again - even though I haven't
  6. Hey John! Long time no talk. I just wanted to let you know I challenged myself last Friday and did a charity event. It was great! I truly fe
  7. Hey John! Long time no talk. I just wanted to let you know I challenged myself last Friday and did a charity event. It was great! I truly fe
  8. Hey John, great news - I'm now friends with one of my fellow volunteers! We both love helping others. How have you been since we last chatte
  9. Hey Maria, long time no talk! Life's been pretty wild lately. The toughest thing to deal with is that we had to say goodbye to Max. He was s
  10. Hey John, been a few days since we chatted. In the meantime, I donated my old car to a homeless shelter I volunteer at yesterday. How's the 

### [abstention] dataset_2.json — `Who did John work with to raise awareness and funds for animal welfare?`

- expected conv indices: 28
- top-10 ranked conv indices: 5, 15, 19, 17, 1, 7, 24, 22, 9, 18
- top-K snippets:
  1. Hey John! Long time no talk. I just wanted to let you know I challenged myself last Friday and did a charity event. It was great! I truly fe
  2. Hey Maria, I've been busy doing the petition I started - it's tricky but it's been cool getting back in touch with my buddies and gaining su
  3. Hey John, long time no talk! A lot has happened since then. I've been struggling, but I'm focusing on the positive and relying on my friends
  4. Hey John, how're you doing? I'm sorry about Max. Losing a pet is tough. Some friends from church and I went camping last weekend - it was a 
  5. Hey John, long time no talk! A lot has happened since then. I've been struggling, but I'm focusing on the positive and relying on my friends
  6. Hey John, been a few days since we chatted. In the meantime, I donated my old car to a homeless shelter I volunteer at yesterday. How's the 
  7. Hey John, I haven't talked to you in a while. Last week, my grandma passed away and it's been really hard. I'm trying to stay positive, but 
  8. Hey John, I haven't talked to you in a while. Last week, my grandma passed away and it's been really hard. I'm trying to stay positive, but 
  9. Hi Maria! It's so good to talk again. A lot has changed since last time. I'm really enjoying my new job. My team has been super encouraging 
  10. Maria, since we talked, it's been tough. My old area was hit by a nasty flood last week. The infrastructure wasn't great so lots of homes we

### [abstention] dataset_2.json — `What recognition did John receive at the homeless shelter in August 2023?`

- expected conv indices: 28
- top-10 ranked conv indices: 7, 15, 22, 0, 5, 20, 19, 1, 17, 9
- top-K snippets:
  1. Hey John, I haven't talked to you in a while. Last week, my grandma passed away and it's been really hard. I'm trying to stay positive, but 
  2. Hey Maria, I've been busy doing the petition I started - it's tricky but it's been cool getting back in touch with my buddies and gaining su
  3. Maria, since we talked, it's been tough. My old area was hit by a nasty flood last week. The infrastructure wasn't great so lots of homes we
  4. Hey John! Long time no see! What's up?
  5. Hey John! Long time no talk. I just wanted to let you know I challenged myself last Friday and did a charity event. It was great! I truly fe
  6. Hey John, long time no see! Sorry I didn't get back to you sooner... So much has happened! Check out these kids I met at the shelter!
  7. Hey Maria, I've been busy doing the petition I started - it's tricky but it's been cool getting back in touch with my buddies and gaining su
  8. Hey John, long time no talk! A lot has happened since then. I've been struggling, but I'm focusing on the positive and relying on my friends
  9. Hey John, been a few days since we chatted. In the meantime, I donated my old car to a homeless shelter I volunteer at yesterday. How's the 
  10. Hey John! Long time no see! What's up?

### [abstention] dataset_2.json — `What is the name of John's puppy he got two weeks before August 11, 2023?`

- expected conv indices: 29
- top-10 ranked conv indices: 18, 6, 9, 12, 4, 19, 0, 22, 20, 1
- top-K snippets:
  1. Hey John, been good since we talked? I got some great news to share - I joined a gym last week! It's been super positive - I'm sticking to m
  2. Hey John, how's it going? Just wanted to give you the heads up on what's been happening lately- I took a creative writing class recently, an
  3. Hey Maria, I'm so excited to tell you I started a weekend yoga class with a colleague - it's awesome! I feel great, both mentally and physic
  4. Hey Maria! Long time no see! Tons has gone down since then!
  5. Hey Maria, since we last spoke I went to that community mtg. It was really interesting hearing everyone's worries and how it affects our are
  6. Hey John, been good since we talked? I got some great news to share - I joined a gym last week! It's been super positive - I'm sticking to m
  7. Hey John, been good since we talked? I got some great news to share - I joined a gym last week! It's been super positive - I'm sticking to m
  8. Hey John, long time no talk! A lot has happened since then. I've been struggling, but I'm focusing on the positive and relying on my friends
  9. Hey Maria! Long time no see! Tons has gone down since then!
  10. Hey John! Long time no see! What's up?

### [abstention] dataset_2.json — `How does Maria describe the camping trip with Max?`

- expected conv indices: 29
- top-10 ranked conv indices: 0, 4, 12, 21, 5, 1, 8, 22, 24, 13
- top-K snippets:
  1. Hey John! Long time no see! What's up?
  2. Hey Maria, since we last spoke I went to that community mtg. It was really interesting hearing everyone's worries and how it affects our are
  3. Hey Maria! Long time no see! Tons has gone down since then!
  4. Since the last chat, I've been thinking about how education and infrastructure shape communities. It's so sad how they can stunt growth in n
  5. Hey John! Long time no talk. I just wanted to let you know I challenged myself last Friday and did a charity event. It was great! I truly fe
  6. Hey John, been a few days since we chatted. In the meantime, I donated my old car to a homeless shelter I volunteer at yesterday. How's the 
  7. Hey John, long time no see! I've been taking a poetry class lately to help me put my feelings into words. It's been a rough ride, but it's b
  8. Maria, since we talked, it's been tough. My old area was hit by a nasty flood last week. The infrastructure wasn't great so lots of homes we
  9. Hi Maria! It's so good to talk again. A lot has changed since last time. I'm really enjoying my new job. My team has been super encouraging 

### [abstention] dataset_2.json — `What is the name of Maria's second kitten?`

- expected conv indices: 30
- top-10 ranked conv indices: 19, 20, 18, 1, 4, 7, 3, 6, 17, 0
- top-K snippets:
  1. Hey John, long time no talk! A lot has happened since then. I've been struggling, but I'm focusing on the positive and relying on my friends
  2. Hey John, long time no see! Sorry I didn't get back to you sooner... So much has happened! Check out these kids I met at the shelter!
  3. Hey John, long time no talk! A lot has happened since then. I've been struggling, but I'm focusing on the positive and relying on my friends
  4. Hey John, been good since we talked? I got some great news to share - I joined a gym last week! It's been super positive - I'm sticking to m
  5. Hey John, been a few days since we chatted. In the meantime, I donated my old car to a homeless shelter I volunteer at yesterday. How's the 
  6. Hey Maria, since we last spoke I went to that community mtg. It was really interesting hearing everyone's worries and how it affects our are
  7. Hey John, I haven't talked to you in a while. Last week, my grandma passed away and it's been really hard. I'm trying to stay positive, but 

### [abstention] dataset_2.json — `How is John's new puppy adjusting to its new home?`

- expected conv indices: 30
- top-10 ranked conv indices: 7, 18, 12, 17, 0, 8, 1, 9, 14, 6
- top-K snippets:
  1. Hey John, I haven't talked to you in a while. Last week, my grandma passed away and it's been really hard. I'm trying to stay positive, but 
  2. Hey John, been good since we talked? I got some great news to share - I joined a gym last week! It's been super positive - I'm sticking to m
  3. Hey Maria! Long time no see! Tons has gone down since then!
  4. Hey John, how're you doing? I'm sorry about Max. Losing a pet is tough. Some friends from church and I went camping last weekend - it was a 
  5. Hey John! Long time no see! What's up?
  6. Hey John, long time no see! I've been taking a poetry class lately to help me put my feelings into words. It's been a rough ride, but it's b
  7. Hey John, been a few days since we chatted. In the meantime, I donated my old car to a homeless shelter I volunteer at yesterday. How's the 
  8. Hey Maria, I'm so excited to tell you I started a weekend yoga class with a colleague - it's awesome! I feel great, both mentally and physic
  9. Hey John! Long time no see! What's up?
  10. Hey Maria, how's it going? Been real busy tackling a project to support military veterans. Trying to get a petition going, it's pretty rewar

### [abstention] dataset_3.json — `What color did Joanna choose for her hair?`

- expected conv indices: 6
- top-10 ranked conv indices: 27, 28, 26
- top-K snippets:
  1. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  2. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  3. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  4. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  5. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  6. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  7. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  8. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  9. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  10. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!

### [abstention] dataset_3.json — `What is Joanna's favorite movie trilogy?`

- expected conv indices: 8
- top-10 ranked conv indices: 28, 27, 26
- top-K snippets:
  1. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  2. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  3. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  4. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  5. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  6. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  7. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  8. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  9. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  10. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w

### [abstention] dataset_3.json — `What is Joanna's favorite book series about?`

- expected conv indices: 8
- top-10 ranked conv indices: 27, 28, 26, 1, 2, 0
- top-K snippets:
  1. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  2. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  3. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  4. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  5. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  6. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  7. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  8. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  9. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  10. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 

### [abstention] dataset_3.json — `What kind of lighting does Joanna's gaming room have?`

- expected conv indices: 9
- top-10 ranked conv indices: 27, 26, 28, 0, 2, 1, 3
- top-K snippets:
  1. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  2. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  3. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  4. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  5. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  6. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  7. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  8. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  9. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  10. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 

### [abstention] dataset_3.json — `What game was the second tournament that Joanna won based on?`

- expected conv indices: 9
- top-10 ranked conv indices: 27, 28, 26, 1, 0, 2
- top-K snippets:
  1. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  2. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  3. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  4. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  5. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  6. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  7. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  8. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  9. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  10. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!

### [abstention] dataset_3.json — `What is Nate's third screenplay about?`

- expected conv indices: 11
- top-10 ranked conv indices: 28, 27, 26, 1, 5, 4, 3, 2
- top-K snippets:
  1. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  2. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  3. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  4. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  5. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  6. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  7. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  8. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  9. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  10. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w

### [abstention] dataset_3.json — `What was Nate's audition for?`

- expected conv indices: 5
- top-10 ranked conv indices: 28, 27, 26, 13, 9, 15, 12, 14, 1, 3
- top-K snippets:
  1. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  2. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  3. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  4. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  5. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  6. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  7. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  8. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  9. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  10. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 

### [abstention] dataset_3.json — `What are the main ingredients of the ice cream recipe shared by Joanna?`

- expected conv indices: 7
- top-10 ranked conv indices: 27, 28, 26, 12, 3, 2, 8, 0, 18, 9
- top-K snippets:
  1. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  2. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  3. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  4. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  5. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  6. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  7. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  8. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  9. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  10. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 

### [abstention] dataset_3.json — `What is Nate's project called in the writers group?`

- expected conv indices: 8
- top-10 ranked conv indices: 27, 28, 26, 20, 11, 4, 7, 19, 14, 9
- top-K snippets:
  1. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  2. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  3. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  4. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  5. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  6. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  7. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  8. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  9. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  10. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!

### [abstention] dataset_3.json — `What filling did Nate use in the cake he made recently in May 2022?`

- expected conv indices: 9
- top-10 ranked conv indices: 28, 26, 27, 18, 3, 14, 7, 1, 10, 5
- top-K snippets:
  1. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  2. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  3. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  4. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  5. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  6. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  7. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  8. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  9. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  10. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 

### [abstention] dataset_3.json — `Who did Joanna plan to invite to her gaming party in June 2022?`

- expected conv indices: 13
- top-10 ranked conv indices: 27, 28, 26, 0, 1, 14, 19, 9, 5, 20
- top-K snippets:
  1. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  2. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  3. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  4. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  5. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  6. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  7. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  8. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  9. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  10. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 

### [abstention] dataset_3.json — `What supervillain is Joanna a fan of?`

- expected conv indices: 14
- top-10 ranked conv indices: 27, 28, 26, 16, 1, 6, 12, 13, 23, 18
- top-K snippets:
  1. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  2. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  3. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  4. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  5. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  6. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  7. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  8. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  9. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  10. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!

### [abstention] dataset_3.json — `Which superhero toy figure does Joanna share a photo of?`

- expected conv indices: 14
- top-10 ranked conv indices: 16, 13, 3, 1, 26, 22, 20, 15, 27, 18
- top-K snippets:
  1. Hey Joanna, check this out! I won my fourth video game tournament on Friday! It was awesome competing and showing off my skills - and the vi
  2. Nate, after finishing my screenplay I got a rejection letter from a major company. It really bummed me out.
  3. Hey Joanna! Sorry I haven't been around. I made my friend some ice cream and they loved it!
  4. Hey Nate! Haven't talked in a few days. Crazy things happened to me!
  5. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  6. Hey Joanna, it's been a couple days since we last talked. Something exciting happened last Friday. I went to a game convention and met new p
  7. Hey Nate, long time no see! My laptop crashed last week and I lost all my work - super frustrating! As a writer, my laptop is like half of m
  8. Hey Nate, long time no see! How have you been? I just got done submitting my recent screenplay to a film contest just to see how others migh
  9. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 

### [abstention] dataset_3.json — `What did Joanna make and share with her vegan diet group?`

- expected conv indices: 15
- top-10 ranked conv indices: 26, 0, 2, 9, 27, 13, 20, 14, 16, 3
- top-K snippets:
  1. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  2. Hey Joanna! Long time no see! What's up? Anything fun going on?
  3. Hey Nate, long time no see! The screenplay I sent in to the film festival has been on my mind all day everyday. I keep bouncing between craz
  4. Hey Nate, how's it going? I took your reccomendation and watched "The Lord of the Rings" Trilogy last night! It was awesome!
  5. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  6. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  7. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  8. Hey Nate, long time no see! The screenplay I sent in to the film festival has been on my mind all day everyday. I keep bouncing between craz
  9. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 

### [abstention] dataset_3.json — `How many people attended the gaming party hosted by Joanna in June 2022?`

- expected conv indices: 15
- top-10 ranked conv indices: 27, 26, 1, 14, 2, 13, 22, 5, 0, 21
- top-K snippets:
  1. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  2. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  3. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  4. Hey Nate! Haven't talked in a few days. Crazy things happened to me!
  5. Hey Nate! Yesterday was crazy cool - I wrote a few bits for a screenplay that appeared on the big screen yesterday! It was nerve-wracking bu
  6. Hey Nate, long time no see! The screenplay I sent in to the film festival has been on my mind all day everyday. I keep bouncing between craz
  7. Hey Nate! Yesterday was crazy cool - I wrote a few bits for a screenplay that appeared on the big screen yesterday! It was nerve-wracking bu
  8. Hey Nate! Yesterday was crazy cool - I wrote a few bits for a screenplay that appeared on the big screen yesterday! It was nerve-wracking bu
  9. Hey Nate! Yesterday was crazy cool - I wrote a few bits for a screenplay that appeared on the big screen yesterday! It was nerve-wracking bu

### [abstention] dataset_3.json — `What did Joanna discover at the museum in Woodhaven?`

- expected conv indices: 16
- top-10 ranked conv indices: 26, 23, 27, 25, 8, 12, 20, 18, 0, 15
- top-K snippets:
  1. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  2. Hey Joanna, what's been up? Haven't seen you since we last talked.
  3. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  4. Wow, Nate, I'm on fire! I just set up meetings with movie producers — my dreams are comin' true!
  5. Hey Nate! Long time no talk! I wanted to tell ya I just joined a writers group. It's unbelievable--such inspirational people who really get 
  6. Hey Jo! Been ages since we last talked. Here's something cool that happened the other day - I took Max for a walk and ran into this super ni
  7. Hey Nate, long time no see! My laptop crashed last week and I lost all my work - super frustrating! As a writer, my laptop is like half of m
  8. Woah Joanna, I won an international tournament yesterday! It was wild. Gaming has brought me so much success and now I'm able to make a livi
  9. Hey Joanna! Long time no see! What's up? Anything fun going on?

### [abstention] dataset_3.json — `What specific themes are explored in Nate's new book?`

- expected conv indices: 16
- top-10 ranked conv indices: 26, 24, 14, 10, 17, 27, 6, 28, 5, 2
- top-K snippets:
  1. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  2. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  3. Hey Joanna, what's been up since we last chatted? How's it going?
  4. Hey Nate! Yesterday was crazy cool - I wrote a few bits for a screenplay that appeared on the big screen yesterday! It was nerve-wracking bu
  5. Hey Nate! Great to hear from you! Quite a week since we last talked - something awesome happened to me!
  6. Hey Joanna, what's been up since we last chatted? How's it going?
  7. Hey Nate, long time no talk! I've been busy with writing projects and really going all out with it. It's been the best thing ever - a mix of

### [abstention] dataset_3.json — `What kind of impact does Joanna hope to have with her painting?`

- expected conv indices: 17
- top-10 ranked conv indices: 24, 13, 6, 11, 27, 9, 28, 26, 20, 18
- top-K snippets:
  1. Hey Joanna, what's been up since we last chatted? How's it going?
  2. Nate, after finishing my screenplay I got a rejection letter from a major company. It really bummed me out.
  3. Hey Jo, guess what I did? Dyed my hair last week - come see!
  4. Hey Joanna! How've you been? Been a busy week since we talked.
  5. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  6. Hey Nate, how's it going? I took your reccomendation and watched "The Lord of the Rings" Trilogy last night! It was awesome!
  7. Hey Joanna, what's been up since we last chatted? How's it going?
  8. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  9. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w

### [abstention] dataset_3.json — `What did Nate share with his writers group in August 2022?`

- expected conv indices: 18
- top-10 ranked conv indices: 26, 14, 20, 1, 7, 27, 11, 5, 10, 2
- top-K snippets:
  1. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  2. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  3. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  4. Hey Nate! Yesterday was crazy cool - I wrote a few bits for a screenplay that appeared on the big screen yesterday! It was nerve-wracking bu
  5. Hey Nate! Yesterday was crazy cool - I wrote a few bits for a screenplay that appeared on the big screen yesterday! It was nerve-wracking bu
  6. Hey Nate, long time no see! My laptop crashed last week and I lost all my work - super frustrating! As a writer, my laptop is like half of m
  7. Hey Nate! Haven't talked in a few days. Crazy things happened to me!
  8. Hey Joanna! Haven't talked with you in a while - how's it going?
  9. Hey Nate, long time no see! My laptop crashed last week and I lost all my work - super frustrating! As a writer, my laptop is like half of m
  10. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w

### [abstention] dataset_3.json — `How did Nate celebrate after sharing his book with a writers group?`

- expected conv indices: 18
- top-10 ranked conv indices: 26, 27, 14, 28, 1, 7, 5, 20, 11, 2
- top-K snippets:
  1. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  2. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  3. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  4. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  5. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  6. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  7. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  8. Hey Nate! Yesterday was crazy cool - I wrote a few bits for a screenplay that appeared on the big screen yesterday! It was nerve-wracking bu
  9. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  10. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!

### [abstention] dataset_3.json — `How did Joanna celebrate winning the international tournament?`

- expected conv indices: 18
- top-10 ranked conv indices: 14, 15, 13, 27, 0, 1, 5, 26, 23, 6
- top-K snippets:
  1. Hey Nate! Yesterday was crazy cool - I wrote a few bits for a screenplay that appeared on the big screen yesterday! It was nerve-wracking bu
  2. Hey Nate, long time no see! How have you been? I just got done submitting my recent screenplay to a film contest just to see how others migh
  3. Nate, after finishing my screenplay I got a rejection letter from a major company. It really bummed me out.
  4. Nate, after finishing my screenplay I got a rejection letter from a major company. It really bummed me out.
  5. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  6. Hey Nate, long time no see! How have you been? I just got done submitting my recent screenplay to a film contest just to see how others migh
  7. Hey Nate, long time no see! How have you been? I just got done submitting my recent screenplay to a film contest just to see how others migh
  8. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  9. Nate, after finishing my screenplay I got a rejection letter from a major company. It really bummed me out.
  10. Hey Nate! Yesterday was crazy cool - I wrote a few bits for a screenplay that appeared on the big screen yesterday! It was nerve-wracking bu

### [abstention] dataset_3.json — `What substitution does Nate suggest for sugar in dairy-free baking?`

- expected conv indices: 19
- top-10 ranked conv indices: 26, 15, 24, 5, 4, 27, 10, 0, 25, 2
- top-K snippets:
  1. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  2. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  3. Hey Nate, long time no see! How have you been? I just got done submitting my recent screenplay to a film contest just to see how others migh
  4. Hey Joanna, what's been up since we last chatted? How's it going?
  5. Hey Joanna! Long time no talk, how's it going? Crazy stuff's been happening since we last chatted.
  6. Hey Nate, it's been a minute! I wrapped up my second script, and the feels have been wild. Sometimes I'm so relieved, but other times I just
  7. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  8. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w

### [abstention] dataset_3.json — `What type of show did Joanna host where she taught vegan ice cream recipes?`

- expected conv indices: 20
- top-10 ranked conv indices: 26, 1, 14, 13, 2, 27, 5, 21, 15, 18
- top-K snippets:
  1. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  2. Hey Nate! Haven't talked in a few days. Crazy things happened to me!
  3. Hey Nate! Yesterday was crazy cool - I wrote a few bits for a screenplay that appeared on the big screen yesterday! It was nerve-wracking bu
  4. Nate, after finishing my screenplay I got a rejection letter from a major company. It really bummed me out.
  5. Hey Nate, long time no see! The screenplay I sent in to the film festival has been on my mind all day everyday. I keep bouncing between craz
  6. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  7. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  8. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  9. Nate, after finishing my screenplay I got a rejection letter from a major company. It really bummed me out.
  10. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 

### [abstention] dataset_3.json — `What is Joanna's favorite dish from the cooking show she hosted?`

- expected conv indices: 20
- top-10 ranked conv indices: 26, 2, 1, 18, 27, 0, 14, 10, 5, 23
- top-K snippets:
  1. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  2. Hey Nate, long time no see! The screenplay I sent in to the film festival has been on my mind all day everyday. I keep bouncing between craz
  3. Hey Nate! Haven't talked in a few days. Crazy things happened to me!
  4. Hey Nate, long time no see! The screenplay I sent in to the film festival has been on my mind all day everyday. I keep bouncing between craz
  5. Hey Nate, long time no see! The screenplay I sent in to the film festival has been on my mind all day everyday. I keep bouncing between craz
  6. Woah Joanna, I won an international tournament yesterday! It was wild. Gaming has brought me so much success and now I'm able to make a livi
  7. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  8. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  9. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  10. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 

### [abstention] dataset_3.json — `What movie did Joanna recently watch and enjoy on October 6, 2022?`

- expected conv indices: 21
- top-10 ranked conv indices: 27, 18, 26, 15, 1, 14, 10, 24, 22, 6
- top-K snippets:
  1. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  2. Woah Joanna, I won an international tournament yesterday! It was wild. Gaming has brought me so much success and now I'm able to make a livi
  3. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  4. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  5. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  6. Hey Nate, long time no see! How have you been? I just got done submitting my recent screenplay to a film contest just to see how others migh
  7. Hey Nate! Haven't talked in a few days. Crazy things happened to me!
  8. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  9. Hey Nate! Yesterday was crazy cool - I wrote a few bits for a screenplay that appeared on the big screen yesterday! It was nerve-wracking bu
  10. Hey Nate, long time no see! How have you been? I just got done submitting my recent screenplay to a film contest just to see how others migh

### [abstention] dataset_3.json — `What game has Joanna been playing nonstop with a futuristic setting and gameplay on October 9, 2022?`

- expected conv indices: 22
- top-10 ranked conv indices: 26, 14, 1, 0, 9, 8, 23, 3, 17, 5
- top-K snippets:
  1. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  2. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  3. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  4. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  5. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  6. Hey Nate! Yesterday was crazy cool - I wrote a few bits for a screenplay that appeared on the big screen yesterday! It was nerve-wracking bu
  7. Hey Nate! Haven't talked in a few days. Crazy things happened to me!
  8. Hey Joanna! Long time no see! What's up? Anything fun going on?
  9. Hey Nate, how's it going? I took your reccomendation and watched "The Lord of the Rings" Trilogy last night! It was awesome!
  10. Hey Nate! Yesterday was crazy cool - I wrote a few bits for a screenplay that appeared on the big screen yesterday! It was nerve-wracking bu

### [abstention] dataset_3.json — `How did Nate describe the classic movie he watched?`

- expected conv indices: 22
- top-10 ranked conv indices: 26, 27, 18, 3, 1, 12, 15, 2, 19, 23
- top-K snippets:
  1. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  2. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  3. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  4. Woah Joanna, I won an international tournament yesterday! It was wild. Gaming has brought me so much success and now I'm able to make a livi
  5. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  6. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  7. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  8. Hey Joanna! Sorry I haven't been around. I made my friend some ice cream and they loved it!
  9. Hey Nate! Haven't talked in a few days. Crazy things happened to me!
  10. Hey Jo! Been ages since we last talked. Here's something cool that happened the other day - I took Max for a walk and ran into this super ni

### [abstention] dataset_3.json — `What does Nate recommend to make a living room comfy like his?`

- expected conv indices: 22
- top-10 ranked conv indices: 27, 0, 26, 10, 8, 4, 28, 20, 9, 7
- top-K snippets:
  1. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  2. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  3. Hey Joanna! Long time no see! What's up? Anything fun going on?
  4. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  5. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  6. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  7. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  8. Hey Nate! Great to hear from you! Quite a week since we last talked - something awesome happened to me!
  9. Hey Nate! Long time no talk! I wanted to tell ya I just joined a writers group. It's unbelievable--such inspirational people who really get 
  10. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w

### [abstention] dataset_3.json — `What helps Joanna stay distracted and brings her sadness?`

- expected conv indices: 23
- top-10 ranked conv indices: 22, 24, 26, 4, 10, 3, 27, 19, 6, 28
- top-K snippets:
  1. Hey Joanna, it's been a couple days since we last talked. Something exciting happened last Friday. I went to a game convention and met new p
  2. Hey Joanna, what's been up since we last chatted? How's it going?
  3. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  4. Hey Nate, it's been a minute! I wrapped up my second script, and the feels have been wild. Sometimes I'm so relieved, but other times I just
  5. Hey Nate! Great to hear from you! Quite a week since we last talked - something awesome happened to me!
  6. Hey Joanna, it's been a couple days since we last talked. Something exciting happened last Friday. I went to a game convention and met new p
  7. Hey Nate, it's been a minute! I wrapped up my second script, and the feels have been wild. Sometimes I'm so relieved, but other times I just
  8. Hey Joanna, what's been up since we last chatted? How's it going?
  9. Hey Joanna, what's been up since we last chatted? How's it going?

### [abstention] dataset_3.json — `What does Nate do while he writes?`

- expected conv indices: 23
- top-10 ranked conv indices: 26, 27, 5, 24, 9, 14, 7, 12, 4, 6
- top-K snippets:
  1. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  2. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  3. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  4. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  5. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  6. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  7. Hey Joanna! Long time no talk, how's it going? Crazy stuff's been happening since we last chatted.
  8. Hey Joanna, what's been up since we last chatted? How's it going?
  9. Hey Nate, how's it going? I took your reccomendation and watched "The Lord of the Rings" Trilogy last night! It was awesome!

### [abstention] dataset_3.json — `What does Nate do after receiving a rejection from a production company?`

- expected conv indices: 23
- top-10 ranked conv indices: 26, 2, 22, 5, 14, 12, 4, 27, 28, 7
- top-K snippets:
  1. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  2. Hey Nate, long time no see! The screenplay I sent in to the film festival has been on my mind all day everyday. I keep bouncing between craz
  3. Hey Joanna, it's been a couple days since we last talked. Something exciting happened last Friday. I went to a game convention and met new p
  4. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  5. Hey Joanna, it's been a couple days since we last talked. Something exciting happened last Friday. I went to a game convention and met new p
  6. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  7. Hey Joanna! Long time no talk, how's it going? Crazy stuff's been happening since we last chatted.
  8. Hey Nate! Yesterday was crazy cool - I wrote a few bits for a screenplay that appeared on the big screen yesterday! It was nerve-wracking bu
  9. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  10. Hey Jo! Been ages since we last talked. Here's something cool that happened the other day - I took Max for a walk and ran into this super ni

### [abstention] dataset_3.json — `What does Joanna rely on for cheer and joy?`

- expected conv indices: 23
- top-10 ranked conv indices: 24, 26, 28, 13, 22, 10, 6, 20, 3, 27
- top-K snippets:
  1. Hey Joanna, what's been up since we last chatted? How's it going?
  2. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  3. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  4. Nate, after finishing my screenplay I got a rejection letter from a major company. It really bummed me out.
  5. Hey Joanna, it's been a couple days since we last talked. Something exciting happened last Friday. I went to a game convention and met new p
  6. Hey Nate! Great to hear from you! Quite a week since we last talked - something awesome happened to me!
  7. Hey Joanna, it's been a couple days since we last talked. Something exciting happened last Friday. I went to a game convention and met new p
  8. Hey Jo, guess what I did? Dyed my hair last week - come see!

### [abstention] dataset_3.json — `What does Nate use to remember his dog from Michigan?`

- expected conv indices: 23
- top-10 ranked conv indices: 26, 15, 7, 27, 11, 28, 21, 19, 16, 3
- top-K snippets:
  1. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  2. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  3. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  4. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  5. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  6. Hey Nate, long time no see! How have you been? I just got done submitting my recent screenplay to a film contest just to see how others migh
  7. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  8. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  9. Hey Joanna! Haven't talked with you in a while - how's it going?

### [abstention] dataset_3.json — `What did Nate find in old notebooks last week that prompted him to reflect on her progress as a writer?`

- expected conv indices: 25
- top-10 ranked conv indices: 26, 27, 10, 28, 21, 24, 22, 2, 1, 3
- top-K snippets:
  1. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  2. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  3. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  4. Hey Nate! Great to hear from you! Quite a week since we last talked - something awesome happened to me!
  5. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  6. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  7. Hey Joanna, what a wild week! My game tournament got pushed back, so I tried out some cooking. Look at this homemade coconut ice cream! The 
  8. Nate, can you believe it? I'm finally filming my own movie from the road-trip script!
  9. Hey Nate, hi! Yesterday, I tried my newest dairy-free recipe and it was a winner with my family! Mixing and matching flavors is fun and I'm 

### [abstention] dataset_3.json — `What did Nate take a picture of near Fort Wayne last summer?`

- expected conv indices: 27
- top-10 ranked conv indices: 23, 26, 9, 7, 13, 1, 11, 28, 12, 15
- top-K snippets:
  1. Hey Joanna, what's been up? Haven't seen you since we last talked.
  2. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  3. Hey Joanna! Hope you’re doing alright. Crazy thing happened - I was in the final of a big Valorant tournament last Saturday, and I won! It w
  4. Hey Nate, how's it going? I took your reccomendation and watched "The Lord of the Rings" Trilogy last night! It was awesome!

### [abstention] dataset_4.json — `What cult did Tim join recently?`

- expected conv indices: 1
- top-10 ranked conv indices: 0, 22, 7, 5, 10, 28, 24, 13, 26, 20
- top-K snippets:
  1. Hey Tim, nice to meet you! What's up? Anything new happening?
  2. Hey Tim, great to see you! Any new success stories?
  3. Hey Tim! Long time no talk. Hope you're doing great. Crazy things have been going on in my life. Just the other day, I found a new gym to st
  4. Hey Tim, sorry I missed you. Been a crazy few days. Took a trip to a new place - it's been amazing. Love the energy there.
  5. Hey Tim, been a while! How ya been?
  6. Hey John! How's it going? Hope all is good.
  7. Hey John, been a while since we chatted. How's it going?
  8. Hey Tim! Long time no talk - a lot has been going on since then!
  9. Hi John, how's it going? Interesting things have happened since we last talked - I joined a group of globetrotters who are into the same stu
  10. Hey John! Haven't talked in a few days, wanted to let you know I joined a travel club! Always been interested in different cultures and coun

### [abstention] dataset_4.json — `What new activity has John started learning in August 2023?`

- expected conv indices: 7
- top-10 ranked conv indices: 25, 20, 23, 26, 16, 10, 27, 14, 0, 8
- top-K snippets:
  1. Hey Tim! Great to hear from you. My week's been busy - I started doing seminars, helping people with their sports and marketing. It's been a
  2. Hey John! Haven't talked in a few days, wanted to let you know I joined a travel club! Always been interested in different cultures and coun
  3. Hey John, catch up time! What've you been up to? Any good b-ball games lately?
  4. Hi John, how's it going? Interesting things have happened since we last talked - I joined a group of globetrotters who are into the same stu
  5. Hey Tim! Great to chat again. So much has happened!
  6. Hey Tim, been a while! How ya been?
  7. Hey John! Haven't talked in a few days, wanted to let you know I joined a travel club! Always been interested in different cultures and coun
  8. Hey John, long time no talk. On Friday, I got great news - I'm finally in the study abroad program I applied for! Next month, I'm off to Ire
  9. Hey John! Haven't talked to you in a bit but wanted to let you know I read this awesome book about castles in the UK. It was so interesting 
  10. Hey Tim! Great to hear from you. My week's been busy - I started doing seminars, helping people with their sports and marketing. It's been a

### [abstention] dataset_4.json — `What is Tim excited to see at Disneyland?`

- expected conv indices: 9
- top-10 ranked conv indices: 14, 27, 22, 21, 7, 0, 28, 8, 26, 5
- top-K snippets:
  1. Hey John! Haven't talked to you in a bit but wanted to let you know I read this awesome book about castles in the UK. It was so interesting 
  2. Hey John, long time no talk. On Friday, I got great news - I'm finally in the study abroad program I applied for! Next month, I'm off to Ire
  3. Hey Tim, great to see you! Any new success stories?
  4. Hey John! Long time no see! I just got back from the coolest Harry Potter party. Met lots of awesome people who were into the same stuff as 
  5. Hey Tim! Long time no talk. Hope you're doing great. Crazy things have been going on in my life. Just the other day, I found a new gym to st
  6. Hey Tim, nice to meet you! What's up? Anything new happening?
  7. Hey John! How's it going? Hope all is good.
  8. Hey John, this week's been really busy for me. Assignments and exams are overwhelming. I'm not giving up though! I'm trying to find a way to
  9. Hi John, how's it going? Interesting things have happened since we last talked - I joined a group of globetrotters who are into the same stu
  10. Hey Tim, sorry I missed you. Been a crazy few days. Took a trip to a new place - it's been amazing. Love the energy there.

### [abstention] dataset_4.json — `What movie did Tim just finish watching on 8th December, 2023?`

- expected conv indices: 21
- top-10 ranked conv indices: 7, 25, 13, 28, 16, 27, 0, 10, 26, 11
- top-K snippets:
  1. Hey Tim! Long time no talk. Hope you're doing great. Crazy things have been going on in my life. Just the other day, I found a new gym to st
  2. Hey Tim! Long time no talk. Hope you're doing great. Crazy things have been going on in my life. Just the other day, I found a new gym to st
  3. Hey Tim! Great to hear from you. My week's been busy - I started doing seminars, helping people with their sports and marketing. It's been a
  4. Hey Tim! Long time no talk - a lot has been going on since then!
  5. Hey John! How's it going? Hope all is good.
  6. Hey Tim! Great to chat again. So much has happened!
  7. Hey John, long time no talk. On Friday, I got great news - I'm finally in the study abroad program I applied for! Next month, I'm off to Ire
  8. Hey Tim! Great to hear from you. My week's been busy - I started doing seminars, helping people with their sports and marketing. It's been a
  9. Hey Tim, nice to meet you! What's up? Anything new happening?
  10. Hey Tim! Long time no talk. Hope you're doing great. Crazy things have been going on in my life. Just the other day, I found a new gym to st

### [abstention] dataset_5.json — `What are some of the personalities of Andrew's four fur babies?`

- expected conv indices: 12
- top-10 ranked conv indices: 27, 14, 1, 20, 25, 5, 23, 19, 13, 15
- top-K snippets:
  1. Hey Andrew! Long time no talk! Last Friday I took my fur kids to the pet salon - they were so psyched and their tails were wagging like craz
  2. Hey Andrew, since we last spoke I got another tattoo of my four dogs on my arm! They really mean a lot to me so I thought it'd be nice to ha
  3. Hey Andrew! Long time no talk! Last Friday I took my fur kids to the pet salon - they were so psyched and their tails were wagging like craz
  4. Hey Andrew, I got a surprise for you! We adopted another puppy called Pixie. She's SO cute! Isn't she just the cutest?
  5. Hi Audrey! Been a while since I hear from you. How's it been?
  6. Hey Andrew, since we last spoke I got another tattoo of my four dogs on my arm! They really mean a lot to me so I thought it'd be nice to ha
  7. Hey Andrew, I wanted to let you know about something going on with my dogs. I noticed they weren't acting normally, so I made an appointment
  8. Hi Audrey! I had a great hike last weekend with some friends and my girlfriend at the spot we found recently. Nature was so peaceful – it wa
  9. Hey Andrew, hope you're doing ok. I recently had a good week - I went to a pet store last Monday to buy toys for my dogs and it was great se
  10. Hey wassup? Got some great news - the gf and I are hitting the beach next month with Toby!

### [abstention] dataset_6.json — `How did James relax in his free time on 9 July, 2022?`

- expected conv indices: 15
- top-10 ranked conv indices: 29, 27, 26, 0, 9, 18, 6, 14, 2, 24
- top-K snippets:
  1. Hey John, hope you're doing well. Yesterday, we started on a road trip. It was fun spending time with the family and my dogs. Exploring new 
  2. Hey John, long time no talk! So much has happened!
  3. Hey James! How's it going? I had a blast last week when my programmer friends and I organized an online comp. It was awesome to see everyone
  4. Hey! Glad to finally talk to you. I want to ask you, what motivates you?
  5. Hey John! Been a while since we chatted. Sorry 'bout not getting back sooner. How's it going? Any new games you're into?
  6. Hey James, been a few days. The convo got me thinking about my passions and goals. Thanks for encouraging me to try new game genres.
  7. Hey James! How's it going?
  8. Hey John, since our last chat, something awesome happened. Last Friday, I started introducing Max, Daisy and the new pup Ned. It was hard at
  9. Hey James, long time no see! I had a big win in my game last week - finally advanced to the next level! It was a huge confidence booster and
  10. Hey James, been a few days since we chatted. Lots of stuff goin' on in my life!

### [abstention] dataset_6.json — `What new hobby did John become interested in on 9 July, 2022?`

- expected conv indices: 15
- top-10 ranked conv indices: 1, 24, 27, 7, 22, 29, 19, 8, 21, 3
- top-K snippets:
  1. Hey John, something awesome happened since we talked. I made a game avatar and joined a new platform. It's so fun exploring and chatting wit
  2. Hey James, been a few days since we chatted. Lots of stuff goin' on in my life!
  3. Hey John, something awesome happened since we talked. I made a game avatar and joined a new platform. It's so fun exploring and chatting wit
  4. Hey John, long time no talk! So much has happened!
  5. Hey John! What's up? Anything fun going on?
  6. Hey John, something awesome happened since we talked. I made a game avatar and joined a new platform. It's so fun exploring and chatting wit
  7. Hey John, it's been a few days since we talked. So much has gone on, both good and bad. Yesterday, when we were at the theater, Samantha lov
  8. Hey John, hope you're doing well. Yesterday, we started on a road trip. It was fun spending time with the family and my dogs. Exploring new 
  9. Hey, James! Good to hear from you. I have some awesome news - I joined a programming group online last Friday and it's been incredible! It's
  10. Hey James! How've you been? Had an eventful week since our last chat.

### [abstention] dataset_7.json — `For how long has Jolene had Lucifer as a pet?`

- expected conv indices: 13
- top-10 ranked conv indices: 27, 1, 21, 15, 14, 8, 0, 12, 3, 6
- top-K snippets:
  1. Since speaking last, I reconnected with my mom's old friends. Their stories made me tear up and reminded me how lucky I am to have had her.
  2. Hey Jolene, sorry to tell you this but my dad passed away two days ago. It's been really tough on us all - his sudden death left us all kind
  3. Hey Jolene, since we talked I've been thinking about my mom's influence. Remembering those we love is important.
  4. Hey Jolene! Great news - I just started a project for a cleanup in our community and have been trying to raise funds for it. It's been amazi
  5. Hey Jolene, since we talked I've been thinking about my mom's influence. Remembering those we love is important.
  6. Hey Jolene, sorry to tell you this but my dad passed away two days ago. It's been really tough on us all - his sudden death left us all kind
  7. Hey Jolene! I started a running group with Anna - it's awesome connecting with people who care about fitness!
  8. Hey Jolene, since we talked I've been thinking about my mom's influence. Remembering those we love is important.
  9. Hi Jolene! We haven't corresponded for a long time!
  10. Hey Jolene, since we talked I've been thinking about my mom's influence. Remembering those we love is important.

### [abstention] dataset_7.json — `What kind of event did Deborah present at recently?`

- expected conv indices: 20
- top-10 ranked conv indices: 28, 4, 12, 5, 22, 3, 13, 11, 15, 6
- top-K snippets:
  1. Hey Jolene, I'm so excited to tell you! Yesterday, me and my neighbor ran a free gardening class for the community, it was awesome! People o
  2. Hey Deborah! Been a few days since we last talked so I wanted to fill you in on something cool. Last Wednesday I did a mini retreat to asses
  3. Hey Deborah! Long time no talk - I had lots of stuff going on. Remember the tough engineering project? I finally wrapped that up last month.
  4. Hey Deborah, totally buzzing! Had a great night out last night - dinner, and drinks with my friends. So glad I got to let my hair down. You?
  5. Hey Deborah, how's it going? Guess what? Yesterday my partner and I got back from an awesome trip to Rio de Janeiro- we checked out some coo
  6. Hey Deborah! Good to hear from you. How've you been? I've been on an emotional rollercoaster lately, but I'm coping.
  7. Hey Jolene! How's it going? We haven't talked in a while. I've been busy getting ready for a yoga retreat with some buddies. A chance to han
  8. Hey Jolene! Great to see you! Had a blast biking nearby with my neighbor last week - was so freeing and beautiful. Checked out an art show w
  9. Hey Deborah! Been a few days since we last talked so I wanted to fill you in on something cool. Last Wednesday I did a mini retreat to asses
  10. Hey Jolene! Great news - I just started a project for a cleanup in our community and have been trying to raise funds for it. It's been amazi

### [abstention] dataset_7.json — `Where did Jolene get married?`

- expected conv indices: 27
- top-10 ranked conv indices: 6, 3, 12, 1, 19, 0, 21, 13, 22, 8
- top-K snippets:
  1. Hi Deborah, it's been a while! Since we last talked, so much has happened. Balancing engineering school with my partner's video games is qui
  2. Hey Deborah! Good to hear from you. How've you been? I've been on an emotional rollercoaster lately, but I'm coping.
  3. Hey Deborah! Long time no talk - I had lots of stuff going on. Remember the tough engineering project? I finally wrapped that up last month.
  4. Hey Jolene, sorry to tell you this but my dad passed away two days ago. It's been really tough on us all - his sudden death left us all kind
  5.  Long time no talk! We were given a new game for the console last week, it is Battlefield 1. What's been up with you?
  6. Hey Jolene, nice to meet you! How's your week going? Anything fun happened?
  7. Hey Jolene, since we talked I've been thinking about my mom's influence. Remembering those we love is important.
  8. Hey Jolene! How's it going? We haven't talked in a while. I've been busy getting ready for a yoga retreat with some buddies. A chance to han
  9. Hey Deborah, how's it going? Guess what? Yesterday my partner and I got back from an awesome trip to Rio de Janeiro- we checked out some coo
  10. Hi Jolene! We haven't corresponded for a long time!

### [abstention] dataset_8.json — `What electronics issue has been frustrating Evan lately?`

- expected conv indices: 10
- top-10 ranked conv indices: 17, 0, 23, 20, 13, 15, 19, 12, 24, 8
- top-K snippets:
  1. Hey Sam, good to hear from you. I've hit a bit of a snag - my new Prius, the one I just bought, broke down. It's a bit of a stressor since I
  2. Hey Evan, good to see you! What's new since we last met? Anything cool happening?
  3. Hey Sam, hope you're doing good. Something funny happened last night.
  4. Hey Evan! Long time no see, how's it going?
  5. Hey Evan! I've been missing our chats. I had quite the health scare last weekend - ended up in the ER with a severe stomachache. Turns out, 
  6. Hey Evan! Hope you're doing good. Got some good news to share - I'm a Weight Watchers coach in my group now! It's a pretty big accomplishmen
  7. Hey Sam, what's up? Long time no see, huh? Lots has happened.
  8. Hey Sam, how's it going? Been a while since we talked. Hope all is good.
  9. Hey Evan, been a few days since we last chatted. Hope you're doing OK. A lot's happened since then. Got issues with my health, it's been rou
  10. Hey Evan! Exciting news: I started a new diet and exercise routine last Monday and it's made a huge difference. I feel great! What about you

### [abstention] dataset_8.json — `What activity did Evan quit one year ago?`

- expected conv indices: 11
- top-10 ranked conv indices: 10, 8, 7, 21, 0, 20, 14, 15, 3, 19
- top-K snippets:
  1. Hey Evan, long time no see! I've started eating healthier - what's new with you? Picked up any new hobbies?
  2. Hey Evan! Exciting news: I started a new diet and exercise routine last Monday and it's made a huge difference. I feel great! What about you
  3. Hey Evan, some big news: I'm on a diet and living healthier! Been tough, but I'm determined.
  4. Hey Evan! I’m really getting into this healthier lifestyle—just took my friends on an epic hiking trip last Friday!
  5. Hey Evan, good to see you! What's new since we last met? Anything cool happening?
  6. Hey Evan! Long time no see, how's it going?
  7. Morning, Evan. I've been trying to keep up with my new health routine, but it's tough. My family's really pushing for it, and I feel so pres
  8. Hey Evan! Hope you're doing good. Got some good news to share - I'm a Weight Watchers coach in my group now! It's a pretty big accomplishmen
  9. Hey Evan, I need to talk to you. My friends were mocking my weight last Friday and it hurt. That made me realize I need to make changes.
  10. Hey Evan, I need to talk to you. My friends were mocking my weight last Friday and it hurt. That made me realize I need to make changes.

### [abstention] dataset_8.json — `What dance activity did Evan and his partner try in a recent weekend?`

- expected conv indices: 23
- top-10 ranked conv indices: 7, 0, 8, 21, 10, 3, 9, 20, 14, 24
- top-K snippets:
  1. Hey Evan, some big news: I'm on a diet and living healthier! Been tough, but I'm determined.
  2. Hey Evan, good to see you! What's new since we last met? Anything cool happening?
  3. Hey Evan! Exciting news: I started a new diet and exercise routine last Monday and it's made a huge difference. I feel great! What about you
  4. Hey Evan! I’m really getting into this healthier lifestyle—just took my friends on an epic hiking trip last Friday!
  5. Hey Evan! Exciting news: I started a new diet and exercise routine last Monday and it's made a huge difference. I feel great! What about you
  6. Hey Evan, long time no see! I've started eating healthier - what's new with you? Picked up any new hobbies?
  7. Hey Evan, good to see you! What's new since we last met? Anything cool happening?
  8. Hey Evan, I need to talk to you. My friends were mocking my weight last Friday and it hurt. That made me realize I need to make changes.
  9. Hey Sam! Long time no talk! Hope all is good. What have I been doing these past few weeks?
  10. Hey Evan, long time no see! I've started eating healthier - what's new with you? Picked up any new hobbies?

### [abstention] dataset_8.json — `What activity hindered Evan's stress and flexibility?`

- expected conv indices: 23
- top-10 ranked conv indices: 8, 10, 16, 3, 17, 24, 13, 14, 15, 12
- top-K snippets:
  1. Hey Evan! Exciting news: I started a new diet and exercise routine last Monday and it's made a huge difference. I feel great! What about you
  2. Hey Evan, long time no see! I've started eating healthier - what's new with you? Picked up any new hobbies?
  3. Hey Evan, long time no see! I've started eating healthier - what's new with you? Picked up any new hobbies?
  4. Hey Ev! Long time no chat. How's it going? Hope all is well.
  5. Hey Evan, I need to talk to you. My friends were mocking my weight last Friday and it hurt. That made me realize I need to make changes.
  6. Hey Sam, good to hear from you. I've hit a bit of a snag - my new Prius, the one I just bought, broke down. It's a bit of a stressor since I
  7. Hey Evan, been a few days since we last chatted. Hope you're doing OK. A lot's happened since then. Got issues with my health, it's been rou
  8. Hey Evan! I've been missing our chats. I had quite the health scare last weekend - ended up in the ER with a severe stomachache. Turns out, 
  9. Morning, Evan. I've been trying to keep up with my new health routine, but it's tough. My family's really pushing for it, and I feel so pres
  10. Hey Evan! Hope you're doing good. Got some good news to share - I'm a Weight Watchers coach in my group now! It's a pretty big accomplishmen

### [abstention] dataset_9.json — `What did Calvin open in May 2023?`

- expected conv indices: 5
- top-10 ranked conv indices: 17, 3, 14, 15, 29, 13, 27, 1, 19, 4
- top-K snippets:
  1. Hey Dave! Sorry it took me so long to get back to you. Crazy times since we talked! My album finally dropped on the 11th and it was a wild f
  2. Hey Calvin, long time no see! A lot's been happening since we last talked. Guess what? I finally opened my own car maintenance shop! It's so
  3. Hey Calvin! Haven't talked in a while! Last Friday I had a card-night with my friends, it was so much fun. We laughed and had a great time! 
  4. Hey Calvin! Haven't talked in a while! Last Friday I had a card-night with my friends, it was so much fun. We laughed and had a great time! 
  5. Hey Calvin! Long time no chat! How was the end of your tour? I bet it was amazing!
  6. Hey Calvin, long time no talk! A lot has happened. I've taken up photography and it's been great - been taking pics of the scenery around he
  7. Hey Cal, how's it going? Something cool happened since last we talked - I got to go to a car workshop in San Francisco! So cool to dive into
  8. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  9. Hey Cal, how's it going? Something cool happened since last we talked - I got to go to a car workshop in San Francisco! So cool to dive into
  10. Hey Dave! Sorry it took me so long to get back to you. Crazy times since we talked! My album finally dropped on the 11th and it was a wild f

### [abstention] dataset_9.json — `Which horror movie did Dave mention as one of his favorites?`

- expected conv indices: 18
- top-10 ranked conv indices: 1, 3, 14, 27, 23, 24, 17, 5, 15, 29
- top-K snippets:
  1. Hey Dave, been a few days, so I wanted to let you in on some cool news.  I just got a new car and it's amazing! Finally owning a luxury car 
  2. Hey Calvin, long time no see! A lot's been happening since we last talked. Guess what? I finally opened my own car maintenance shop! It's so
  3. Hey Calvin! Haven't talked in a while! Last Friday I had a card-night with my friends, it was so much fun. We laughed and had a great time! 
  4. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  5. Hey Dave! Been a few days since we talked, but lots happened. Started touring with Frank Ocean and it's been amazing - so much energy from t
  6. Hey Calvin, how's the tour with Frank Ocean? I was pondering our chat the other day about fame and its impact on relationships. It must be a
  7. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  8. Hey Dave! Sorry it took me so long to get back to you. Crazy times since we talked! My album finally dropped on the 11th and it was a wild f
  9. Hey Dave! Long time no chat! Lots has gone down since we last caught up.
  10. Hey Calvin! Long time no chat! How was the end of your tour? I bet it was amazing!

### [abstention] dataset_9.json — `What does Dave find satisfying about destroying old cars?`

- expected conv indices: 20
- top-10 ranked conv indices: 21, 3, 16, 11, 12, 22, 25, 18, 13, 19
- top-K snippets:
  1. Hey Calvin! What’s up? Last Friday I went to the car show. I saw some awesome cars and got to mess with car mods! There were so many cool ma
  2. Hey Calvin, long time no see! A lot's been happening since we last talked. Guess what? I finally opened my own car maintenance shop! It's so
  3. Hey Calvin! Been a while, what's up? I'm tied up with car stuff lately, yesterday I came back from San Francsico with some great insights an
  4. Hey Calvin, long time no see! A lot's been happening since we last talked. Guess what? I finally opened my own car maintenance shop! It's so
  5. Hey Dave, long time no see! I just took my Ferrari for a service and it was so stressful. I'm kinda attached to it. Can you relate? What kin
  6. Hey Calvin, been ages! Guess what? I got picked for a car mod workshop. Gonna get better at it and learn something new! Look at the cars I'm
  7. Hey Cal, miss ya! Crazy rollercoaster week. A competing car maintenance shop snagged a deal we were trying to secure for months and it made 
  8. Hey Dave! Long time no talk! I had a great time yesterday, and visited some sights in Boston with a high school friend. It was really fun an
  9. Hey Calvin! What’s up? Last Friday I went to the car show. I saw some awesome cars and got to mess with car mods! There were so many cool ma
  10. Hey Calvin! Long time no talk! Got some cool news to share - last night was a blast! My band and I were jamming and the music just kept flow

### [abstention] dataset_9.json — `What did Calvin recently get that is a "masterpiece on canvas"?`

- expected conv indices: 22
- top-10 ranked conv indices: 24, 19, 27, 12, 28, 3, 15, 23, 1, 10
- top-K snippets:
  1. Hey Calvin, how's the tour with Frank Ocean? I was pondering our chat the other day about fame and its impact on relationships. It must be a
  2. Hey Calvin, how's the tour with Frank Ocean? I was pondering our chat the other day about fame and its impact on relationships. It must be a
  3. Hey Calvin, good to catch up again! Had a tough time with my car project. Worked on the engine of the vintage Mustang, thought I'd fixed it,
  4. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  5. Hey Dave! It's been a while! Crazy stuff has been happening. Last week I threw a small party at my Japanese house for my new album. It was a
  6. Hey Calvin, been ages! Guess what? I got picked for a car mod workshop. Gonna get better at it and learn something new! Look at the cars I'm
  7. Hey Dave, I invited my old high school buddy to see me perform in Boston! It was insane. It got me thinking about how far I've come and remi
  8. Hey Calvin, long time no see! A lot's been happening since we last talked. Guess what? I finally opened my own car maintenance shop! It's so
  9. Hey Calvin! Long time no chat! How was the end of your tour? I bet it was amazing!
  10. Hey Dave! Been a few days since we talked, but lots happened. Started touring with Frank Ocean and it's been amazing - so much energy from t

### [abstention] dataset_9.json — `What new item did Calvin buy recently?`

- expected conv indices: 29
- top-10 ranked conv indices: 1, 2, 3, 16, 24, 14, 17, 4, 27, 5
- top-K snippets:
  1. Hey Dave, been a few days, so I wanted to let you in on some cool news.  I just got a new car and it's amazing! Finally owning a luxury car 
  2. Hey Dave, been a few days, so I wanted to let you in on some cool news.  I just got a new car and it's amazing! Finally owning a luxury car 
  3. Hey Dave! Long time no see. I just went to an awesome music thingy in Tokyo - so cool!
  4. Hey Calvin, long time no see! A lot's been happening since we last talked. Guess what? I finally opened my own car maintenance shop! It's so
  5. Hey Calvin! Been a while, what's up? I'm tied up with car stuff lately, yesterday I came back from San Francsico with some great insights an
  6. Hey Dave, been a few days, so I wanted to let you in on some cool news.  I just got a new car and it's amazing! Finally owning a luxury car 
  7. Hey Calvin, how's the tour with Frank Ocean? I was pondering our chat the other day about fame and its impact on relationships. It must be a
  8. Hey Calvin! Haven't talked in a while! Last Friday I had a card-night with my friends, it was so much fun. We laughed and had a great time! 
  9. Hey Dave! Sorry it took me so long to get back to you. Crazy times since we talked! My album finally dropped on the 11th and it was a wild f
  10. Hey Calvin! Long time no talk. How's it going? Crazy news - I'm teaming up with a local garage. Take a look at what we working on together!

